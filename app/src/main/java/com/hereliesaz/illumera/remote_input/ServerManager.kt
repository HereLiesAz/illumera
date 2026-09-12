package com.hereliesaz.illumera.remote_input

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.BindException

/**
 * Holds information about the running server.
 */
data class ServerInfo(
    val ip: String,
    val port: Int,
    // Random per-session pairing token. Only a client that scans/sees the URL
    // this token is embedded in (i.e. has visual access to this TV) can reach
    // the server's content — a CSRF token alone doesn't prove that, since it's
    // served to anyone who requests the page.
    val pairingToken: String
) {
    val url: String get() = "http://$ip:$port/?pin=$pairingToken"
}

/**
 * Manages the LinkServer lifecycle with port hunting.
 * Tries ports 8080-8090 until one is available.
 */
class ServerManager {

    private var server: LinkServer? = null

    companion object {
        private const val PORT_START = 8080
        private const val PORT_END = 8090
    }

    /**
     * Attempts to start the server on an available port.
     * Returns ServerInfo on success, null on failure.
     */
    suspend fun startServer(
        helperUrl: String? = null,
        helperLabel: String? = null,
        onLinkReceived: (String) -> Unit
    ): ServerInfo? = withContext(Dispatchers.IO) {
        // First, get the local IP
        val ip = NetworkUtils.getLocalIpAddress()
        if (ip == null) {
            return@withContext null
        }

        val pairingToken = java.util.UUID.randomUUID().toString()

        // Try ports in range
        for (port in PORT_START..PORT_END) {
            try {
                val linkServer = LinkServer(
                    port = port,
                    pairingToken = pairingToken,
                    helperUrl = helperUrl,
                    helperLabel = helperLabel,
                    onLinkReceived = onLinkReceived
                )
                linkServer.start()
                server = linkServer
                return@withContext ServerInfo(ip, port, pairingToken)
            } catch (e: BindException) {
                // Port in use, try next
                continue
            } catch (e: Exception) {
                if (com.hereliesaz.illumera.BuildConfig.DEBUG) android.util.Log.w("ServerManager", "Port binding failed", e)
                continue
            }
        }

        // All ports failed
        null
    }

    /**
     * Stops the running server if any.
     */
    fun stopServer() {
        server?.stop()
        server = null
    }
}
