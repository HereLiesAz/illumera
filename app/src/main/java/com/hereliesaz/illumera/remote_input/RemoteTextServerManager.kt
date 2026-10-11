package com.hereliesaz.illumera.remote_input

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.BindException
import java.util.UUID

class RemoteTextServerManager {
    private var server: RemoteTextServer? = null

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

    suspend fun startServer(
        title: String,
        prompt: String,
        fieldLabel: String,
        helperUrl: String? = null,
        helperLabel: String? = null,
        secret: Boolean = true,
        onTextReceived: (String) -> Unit
    ): ServerInfo? = withContext(Dispatchers.IO) {
        val myGeneration = synchronized(lock) {
            stopLocked()
            ++generation
        }
        val ip = NetworkUtils.getLocalIpAddress() ?: return@withContext null
        val pairingToken = UUID.randomUUID().toString()

        for (port in PORT_START..PORT_END) {
            try {
                val candidate = RemoteTextServer(
                    hostname = ip, // bind only to the LAN address shown in the QR
                    port = port,
                    pairingToken = pairingToken,
                    title = title,
                    prompt = prompt,
                    fieldLabel = fieldLabel,
                    helperUrl = helperUrl,
                    helperLabel = helperLabel,
                    secret = secret,
                    onTextReceived = onTextReceived
                )
                candidate.start()
                val accepted = synchronized(lock) {
                    if (generation == myGeneration) {
                        server = candidate
                        true
                    } else false
                }
                if (!accepted) {
                    // Stopped (or superseded) while we were binding.
                    candidate.stop()
                    return@withContext null
                }
                return@withContext ServerInfo(ip, port, pairingToken)
            } catch (_: BindException) {
                continue
            } catch (e: Exception) {
                com.hereliesaz.illumera.crash.AppErrors.w("RemoteTextServerManager", "Port binding failed", e)
            }
        }
        null
    }

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
