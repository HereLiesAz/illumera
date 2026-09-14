package com.hereliesaz.illumera.remote_input

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD
import java.util.UUID

/**
 * HTTP server for handling Stremio login from a mobile device.
 * Serves a login form and forwards credentials to the app.
 */
class IntegrationServer(
    port: Int,
    private val pairingToken: String,
    private val onCredentialsReceived: (email: String, password: String) -> Unit
) : NanoHTTPD(port) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val csrfToken = UUID.randomUUID().toString()

    companion object {
        private const val TAG = "IntegrationServer"
        private const val MAX_IMAGE_SIZE = 5 * 1024 * 1024 // 5 MB
        private const val MAX_FIELD_LENGTH = 1024
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.uri == "/ping") return DisconnectBanner.pingResponse()
        // Require the pairing token from the QR/link URL on every request — proves
        // the caller actually saw the URL shown on this TV, unlike the CSRF token
        // below (which only protects against a forged submission once you already
        // have the page — anyone on the LAN could otherwise just GET / for it).
        if (session.parms["pin"] != pairingToken) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
        return when {
            session.method == Method.GET && session.uri == "/" -> serveLoginForm()
            session.method == Method.POST && session.uri == "/login" -> handleLogin(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveLoginForm(): Response {
        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Connect Stremio</title>
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        background-color: #121212;
                        color: #ffffff;
                        min-height: 100vh;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        padding: 20px;
                    }
                    .container {
                        background-color: #1e1e1e;
                        border-radius: 16px;
                        padding: 32px 24px;
                        width: 100%;
                        max-width: 400px;
                        box-shadow: 0 4px 6px rgba(0,0,0,0.3);
                        text-align: center;
                    }
                    .logo {
                        text-align: center;
                        margin-bottom: 24px;
                    }
                    .logo span {
                        font-size: 48px;
                    }
                    h1 {
                        color: #fff;
                        font-size: 1.5rem;
                        font-weight: 600;
                        margin-bottom: 0.5rem;
                        text-align: center;
                    }
                    p {
                        color: #aaaaaa;
                        font-size: 14px;
                        text-align: center;
                        margin-bottom: 24px;
                    }
                    input {
                        width: 100%;
                        padding: 16px;
                        font-size: 16px;
                        border: 2px solid #333;
                        border-radius: 12px;
                        background: rgba(0, 0, 0, 0.3);
                        color: #fff;
                        outline: none;
                        transition: border-color 0.2s;
                        margin-bottom: 12px;
                    }
                    input:focus {
                        border-color: #555;
                    }
                    input::placeholder {
                        color: rgba(255, 255, 255, 0.4);
                    }
                    button {
                        width: 100%;
                        padding: 14px 24px;
                        font-size: 1rem;
                        font-weight: 600;
                        border: none;
                        border-radius: 24px;
                        background-color: #ffffff;
                        color: #000000;
                        cursor: pointer;
                        transition: transform 0.1s, opacity 0.2s;
                        margin-top: 8px;
                    }
                    button:active {
                        transform: scale(0.98);
                    }
                    button:disabled {
                        opacity: 0.6;
                        cursor: not-allowed;
                    }
                    .success {
                        text-align: center;
                        color: #10b981;
                        padding: 40px 0;
                    }
                    .success svg {
                        width: 64px;
                        height: 64px;
                        margin-bottom: 16px;
                    }
                    .error {
                        background: rgba(239, 68, 68, 0.15);
                        border-radius: 8px;
                        padding: 12px;
                        color: #ef4444;
                        font-size: 14px;
                        text-align: center;
                        margin-bottom: 16px;
                        display: none;
                    }
                    .hidden { display: none; }
                    .spinner {
                        width: 20px;
                        height: 20px;
                        border: 2px solid rgba(0, 0, 0, 0.3);
                        border-top-color: #000;
                        border-radius: 50%;
                        animation: spin 0.8s linear infinite;
                        display: inline-block;
                        margin-right: 8px;
                        vertical-align: middle;
                    }
                    @keyframes spin {
                        to { transform: rotate(360deg); }
                    }
                    .info {
                        background: rgba(255, 255, 255, 0.05);
                        border-radius: 8px;
                        padding: 12px;
                        margin-top: 20px;
                    }
                    .info p {
                        margin: 0;
                        font-size: 13px;
                        color: rgba(255, 255, 255, 0.5);
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div id="form-container">
                        <div class="logo"><span>📺</span></div>
                        <h1>Connect Stremio to illumera</h1>
                        <p>Enter your Stremio credentials to sync your addons</p>
                        
                        <div class="error" id="error-msg"></div>
                        
                        <form id="loginForm">
                            <input type="hidden" name="csrf_token" value="$csrfToken">
                            <input type="email" name="email" id="emailInput"
                                   placeholder="Stremio Email"
                                   autocomplete="email"
                                   required>
                            <input type="password" name="password" id="passwordInput"
                                   placeholder="Password"
                                   autocomplete="current-password"
                                   required>
                            <button type="submit" id="submitBtn">Connect Account</button>
                        </form>
                        
                        <div class="info">
                            <p>This page is unique to this session — only send your credentials here if you scanned this link's QR code or opened it from your TV. They are not stored on any server, but this local connection is not encrypted, so avoid using this on a network you don't trust.</p>
                        </div>
                    </div>
                    
                    <div class="success hidden" id="success-container">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/>
                            <polyline points="22 4 12 14.01 9 11.01"/>
                        </svg>
                        <h1>Credentials Sent</h1>
                        <p style="margin-top: 12px;">Check your TV screen to see whether the login succeeded.</p>
                    </div>
                </div>

                <script>
                    document.getElementById('loginForm').addEventListener('submit', async (e) => {
                        e.preventDefault();
                        const btn = document.getElementById('submitBtn');
                        const email = document.getElementById('emailInput').value;
                        const password = document.getElementById('passwordInput').value;
                        const errorEl = document.getElementById('error-msg');
                        
                        btn.disabled = true;
                        btn.innerHTML = '<span class="spinner"></span>Connecting...';
                        errorEl.style.display = 'none';
                        
                        try {
                            const formData = new FormData(document.getElementById('loginForm'));

                            const response = await fetch('/login' + window.location.search, {
                                method: 'POST',
                                body: formData
                            });
                            
                            const result = await response.json();
                            
                            if (result.success) {
                                document.getElementById('form-container').classList.add('hidden');
                                document.getElementById('success-container').classList.remove('hidden');
                            } else {
                                errorEl.textContent = result.error || 'Login failed';
                                errorEl.style.display = 'block';
                                btn.disabled = false;
                                btn.innerHTML = 'Connect Account';
                            }
                        } catch (err) {
                            errorEl.textContent = 'Network error. Please try again.';
                            errorEl.style.display = 'block';
                            btn.disabled = false;
                            btn.innerHTML = 'Connect Account';
                        }
                    });
                </script>
                ${DisconnectBanner.htmlSnippet}
            </body>
            </html>
        """.trimIndent()

        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun handleLogin(session: IHTTPSession): Response {
        try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)

            // Validate CSRF token
            val token = session.parms["csrf_token"]
            if (token != csrfToken) {
                return jsonResponse(false, "Invalid request")
            }

            val email = session.parms["email"]
            val password = session.parms["password"]

            if (email.isNullOrBlank() || password.isNullOrBlank()) {
                return jsonResponse(false, "Email and password are required")
            }
            if (email.length > MAX_FIELD_LENGTH || password.length > MAX_FIELD_LENGTH) {
                return jsonResponse(false, "Input too long")
            }

            // Forward credentials to the app on main thread
            mainHandler.post {
                onCredentialsReceived(email, password)
            }

            return jsonResponse(true, null)

        } catch (e: Exception) {
            if (com.hereliesaz.illumera.BuildConfig.DEBUG) Log.e(TAG, "Error handling login", e)
            return jsonResponse(false, "Server error")
        }
    }

    private fun jsonResponse(success: Boolean, error: String?): Response {
        val json = JsonObject().apply {
            addProperty("success", success)
            if (error != null) addProperty("error", error)
        }.toString()
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }
}
