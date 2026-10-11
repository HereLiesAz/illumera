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

    // Guards [server] and [generation]. startServer's bind loop runs on IO
    // without holding the lock; a stopServer() (or newer startServer()) that
    // lands meanwhile bumps [generation], so the late-bound server is stopped
    // immediately instead of leaking.
    private val lock = Any()
    private var generation = 0L

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
        val myGeneration = synchronized(lock) {
            stopLocked()
            ++generation
        }
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
                val accepted = synchronized(lock) {
                    if (generation == myGeneration) {
                        server = linkServer
                        true
                    } else false
                }
                if (!accepted) {
                    // Stopped (or superseded) while we were binding.
                    linkServer.stop()
                    return@withContext null
                }
                return@withContext ServerInfo(ip, port, pairingToken)
            } catch (e: BindException) {
                // Port in use, try next
                continue
            } catch (e: Exception) {
                com.hereliesaz.illumera.crash.AppErrors.w("ServerManager", "Port binding failed", e)
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
        synchronized(lock) {
            generation++
            stopLocked()
        }
    }

    private fun stopLocked() {
        server?.stop()
        server = null
    }
}
