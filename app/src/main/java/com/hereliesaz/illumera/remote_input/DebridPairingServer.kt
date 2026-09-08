package com.hereliesaz.illumera.remote_input

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.JsonObject
import com.hereliesaz.illumera.data.model.debrid.DebridProvider
import fi.iki.elonen.NanoHTTPD
import java.util.UUID

/**
 * Local, session-scoped handoff page used by TV devices to receive a debrid API key
 * from a phone without forcing the user to type a long credential with a remote.
 */
class DebridPairingServer(
    port: Int,
    private val pairingToken: String,
    private val provider: DebridProvider,
    private val onApiKeyReceived: (String) -> Unit
) : NanoHTTPD(port) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val csrfToken = UUID.randomUUID().toString()

    companion object {
        private const val TAG = "DebridPairingServer"
        private const val MAX_FIELD_LENGTH = 4096
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.uri == "/ping") return DisconnectBanner.pingResponse()
        if (session.parms["pin"] != pairingToken) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }

        return when {
            session.method == Method.GET && session.uri == "/" -> servePairingForm()
            session.method == Method.POST && session.uri == "/connect" -> handleConnect(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun servePairingForm(): Response {
        val providerName = htmlEscape(provider.displayName)
        val apiKeyUrl = htmlEscape(provider.apiKeyUrl)
        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Connect $providerName</title>
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        background: #121212;
                        color: #fff;
                        min-height: 100vh;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        padding: 20px;
                    }
                    .card {
                        width: 100%;
                        max-width: 430px;
                        background: #1e1e1e;
                        border-radius: 18px;
                        padding: 28px 22px;
                        box-shadow: 0 8px 30px rgba(0,0,0,.35);
                    }
                    h1 { font-size: 1.5rem; margin-bottom: 8px; text-align: center; }
                    p { color: #aaa; line-height: 1.45; text-align: center; margin-bottom: 18px; }
                    a.button, button {
                        width: 100%;
                        display: block;
                        padding: 14px 16px;
                        border-radius: 24px;
                        border: 0;
                        text-align: center;
                        text-decoration: none;
                        font-size: 1rem;
                        font-weight: 650;
                        cursor: pointer;
                    }
                    a.button { background: #fff; color: #000; margin-bottom: 18px; }
                    button { background: #fff; color: #000; margin-top: 8px; }
                    button:disabled { opacity: .55; cursor: wait; }
                    input {
                        width: 100%;
                        padding: 15px;
                        border: 2px solid #333;
                        border-radius: 12px;
                        background: #111;
                        color: #fff;
                        font-size: 16px;
                        margin-bottom: 10px;
                    }
                    input:focus { border-color: #777; outline: none; }
                    .step { color: #ddd; font-size: 14px; margin: 14px 0 8px; }
                    .note { font-size: 12px; color: #777; margin-top: 18px; }
                    .error { display: none; color: #ef4444; margin: 10px 0; text-align: center; }
                    .success { display: none; text-align: center; color: #10b981; padding: 24px 0; }
                </style>
            </head>
            <body>
                <div class="card">
                    <div id="pair-form">
                        <h1>Connect $providerName</h1>
                        <p>Get your API key on this phone, then send it straight to Illumera on your TV.</p>
                        <div class="step">1. Open $providerName and copy your API key.</div>
                        <a class="button" href="$apiKeyUrl" target="_blank" rel="noopener noreferrer">Get API Key</a>
                        <div class="step">2. Paste the key below.</div>
                        <div id="error" class="error"></div>
                        <form id="apiForm">
                            <input type="hidden" name="csrf_token" value="$csrfToken">
                            <input type="password" name="api_key" id="apiKey" placeholder="$providerName API key" autocomplete="off" required>
                            <button type="submit" id="sendButton">Connect TV</button>
                        </form>
                        <p class="note">This pairing page only exists on your local network for this session. The API key is sent directly to your TV and is never relayed through an Illumera server.</p>
                    </div>
                    <div id="success" class="success">
                        <h1>Key sent</h1>
                        <p>Return to your TV. Illumera is validating the account now.</p>
                    </div>
                </div>
                <script>
                    document.getElementById('apiForm').addEventListener('submit', async (event) => {
                        event.preventDefault();
                        const button = document.getElementById('sendButton');
                        const error = document.getElementById('error');
                        button.disabled = true;
                        button.textContent = 'Sending...';
                        error.style.display = 'none';
                        try {
                            const form = new FormData(document.getElementById('apiForm'));
                            const response = await fetch('/connect' + window.location.search, { method: 'POST', body: form });
                            const result = await response.json();
                            if (!result.success) throw new Error(result.error || 'Could not send key');
                            document.getElementById('pair-form').style.display = 'none';
                            document.getElementById('success').style.display = 'block';
                        } catch (err) {
                            error.textContent = err.message || 'Connection failed';
                            error.style.display = 'block';
                            button.disabled = false;
                            button.textContent = 'Connect TV';
                        }
                    });
                </script>
                ${DisconnectBanner.htmlSnippet}
            </body>
            </html>
        """.trimIndent()

        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun handleConnect(session: IHTTPSession): Response {
        return try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)

            if (session.parms["csrf_token"] != csrfToken) {
                return jsonResponse(false, "Invalid request")
            }

            val apiKey = session.parms["api_key"]?.trim()
            if (apiKey.isNullOrBlank()) return jsonResponse(false, "API key is required")
            if (apiKey.length > MAX_FIELD_LENGTH) return jsonResponse(false, "API key is too long")

            mainHandler.post { onApiKeyReceived(apiKey) }
            jsonResponse(true, null)
        } catch (e: Exception) {
            if (com.hereliesaz.illumera.BuildConfig.DEBUG) Log.e(TAG, "Error handling debrid pairing", e)
            jsonResponse(false, "Server error")
        }
    }

    private fun jsonResponse(success: Boolean, error: String?): Response {
        val json = JsonObject().apply {
            addProperty("success", success)
            if (error != null) addProperty("error", error)
        }.toString()
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    private fun htmlEscape(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            append(
                when (ch) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&#39;"
                    else -> ch
                }
            )
        }
    }
}
