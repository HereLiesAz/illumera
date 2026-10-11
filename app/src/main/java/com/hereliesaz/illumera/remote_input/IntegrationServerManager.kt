package com.hereliesaz.illumera.remote_input

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.BindException

/**
 * Manages the IntegrationServer lifecycle for Stremio login.
 */
class IntegrationServerManager {

    private var server: IntegrationServer? = null

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
        onCredentialsReceived: (email: String, password: String) -> Unit
    ): ServerInfo? = withContext(Dispatchers.IO) {
        val myGeneration = synchronized(lock) {
            stopLocked()
            ++generation
        }
        val ip = NetworkUtils.getLocalIpAddress()
        if (ip == null) {
            return@withContext null
        }

        val pairingToken = java.util.UUID.randomUUID().toString()

        for (port in PORT_START..PORT_END) {
            try {
                val integrationServer = IntegrationServer(
                    port = port,
                    pairingToken = pairingToken,
                    onCredentialsReceived = onCredentialsReceived
                )
                integrationServer.start()
                val accepted = synchronized(lock) {
                    if (generation == myGeneration) {
                        server = integrationServer
                        true
                    } else false
                }
                if (!accepted) {
                    // Stopped (or superseded) while we were binding.
                    integrationServer.stop()
                    return@withContext null
                }
                return@withContext ServerInfo(ip, port, pairingToken)
            } catch (e: BindException) {
                continue
            } catch (e: Exception) {
                com.hereliesaz.illumera.crash.AppErrors.w("IntegrationServerManager", "Port binding failed", e)
                continue
            }
        }

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
