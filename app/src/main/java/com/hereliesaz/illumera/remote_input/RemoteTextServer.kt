package com.hereliesaz.illumera.remote_input

import android.net.Uri
import android.os.Handler
import android.os.Looper
import fi.iki.elonen.NanoHTTPD
import java.util.UUID

/**
 * Pairing-token-protected, single-value phone -> TV transfer.
 *
 * The QR points at a tiny page hosted by the TV. An optional HTTPS helper link
 * can open the provider's API-key page; the resulting text is pasted back into
 * this local page and delivered directly to illumera.
 */
class RemoteTextServer(
    hostname: String,
    port: Int,
    pairingToken: String,
    private val title: String,
    private val prompt: String,
    private val fieldLabel: String,
    private val helperUrl: String? = null,
    private val helperLabel: String? = null,
    private val secret: Boolean = true,
    private val onTextReceived: (String) -> Unit
) : PairedLanServer(hostname, port, pairingToken) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val csrfToken = UUID.randomUUID().toString()

    companion object {
        private const val MAX_VALUE_LENGTH = 8192
    }

    // Address filtering, /ping, pin + expiry checks and no-store headers are
    // handled by PairedLanServer before this is called.
    override fun serveAuthorized(session: IHTTPSession): Response {
        return when {
            session.method == Method.GET && session.uri == "/" -> serveForm()
            session.method == Method.POST && session.uri == "/submit" -> handleSubmission(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveForm(): Response {
        val safeHelperUrl = helperUrl?.takeIf { url ->
            val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
            scheme == "http" || scheme == "https"
        }
        val helperHtml = if (safeHelperUrl != null) {
            val href = escapeHtml(safeHelperUrl)
            val label = escapeHtml(helperLabel ?: "Open provider page")
            """<a class="helper" href="$href" target="_blank" rel="noopener noreferrer">$label</a>"""
        } else {
            ""
        }
        val inputType = if (secret) "password" else "text"

        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>${escapeHtml(title)}</title>
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background:#121212; color:#fff; min-height:100vh; display:flex; align-items:center; justify-content:center; padding:20px; }
                    .card { width:100%; max-width:430px; background:#1e1e1e; border-radius:16px; padding:30px 24px; text-align:center; }
                    h1 { font-size:1.45rem; margin-bottom:10px; }
                    p { color:#aaa; line-height:1.45; margin-bottom:20px; }
                    .helper, button { display:block; width:100%; border:0; border-radius:24px; padding:14px 20px; font-size:1rem; font-weight:600; background:#fff; color:#000; text-decoration:none; cursor:pointer; }
                    .helper { margin-bottom:18px; }
                    input { width:100%; padding:15px; margin-bottom:14px; border-radius:10px; border:2px solid #333; background:#111; color:#fff; font-size:16px; outline:none; }
                    input:focus { border-color:#777; }
                    .note { font-size:12px; color:#777; margin-top:16px; margin-bottom:0; }
                    .success { display:none; color:#10b981; }
                </style>
            </head>
            <body>
                <div class="card">
                    <div id="form-wrap">
                        <h1>${escapeHtml(title)}</h1>
                        <p>${escapeHtml(prompt)}</p>
                        $helperHtml
                        <form id="textForm">
                            <input type="hidden" name="csrf_token" value="$csrfToken">
                            <input type="$inputType" name="value" id="value" placeholder="${escapeHtml(fieldLabel)}" autocomplete="off" autocapitalize="off" spellcheck="false" required>
                            <button type="submit" id="send">Send to TV</button>
                        </form>
                        <p class="note">This transfer goes directly to the TV over your local network and only works from the same network. The connection is not encrypted, so use a network you trust. This link works once and expires after a few minutes.</p>
                    </div>
                    <div class="success" id="success">
                        <h1>Sent to TV</h1>
                        <p>The TV received it. You can close this page.</p>
                    </div>
                </div>
                <script>
                    document.getElementById('textForm').addEventListener('submit', async function (e) {
                        e.preventDefault();
                        const button = document.getElementById('send');
                        button.disabled = true;
                        button.textContent = 'Sending...';
                        try {
                            const body = new FormData(document.getElementById('textForm'));
                            const response = await fetch('/submit' + window.location.search, { method: 'POST', body });
                            if (!response.ok) throw new Error('Rejected');
                            document.getElementById('form-wrap').style.display = 'none';
                            document.getElementById('success').style.display = 'block';
                        } catch (_) {
                            button.disabled = false;
                            button.textContent = 'Send to TV';
                            alert('Could not send to the TV. Check that both devices are on the same network.');
                        }
                    });
                </script>
                ${DisconnectBanner.htmlSnippet}
            </body>
            </html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun handleSubmission(session: IHTTPSession): Response {
        return try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            if (!PairingSession.constantTimeEquals(session.parms["csrf_token"], csrfToken)) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Invalid request")
            }
            val value = session.parms["value"]?.trim()
            if (value.isNullOrBlank()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Value required")
            }
            if (value.length > MAX_VALUE_LENGTH) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Value too long")
            }
            // Single-use: burn the pairing token once an API key has been accepted.
            pairingSession.invalidate()
            mainHandler.post { onTextReceived(value) }
            newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
        } catch (e: Exception) {
            com.hereliesaz.illumera.crash.AppErrors.w("RemoteTextServer", "Error handling submission", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error processing request")
        }
    }

    private fun escapeHtml(value: String): String = buildString(value.length) {
        value.forEach { char ->
            append(
                when (char) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&#39;"
                    else -> char
                }
            )
        }
    }
}
