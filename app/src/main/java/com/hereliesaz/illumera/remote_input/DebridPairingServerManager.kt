package com.hereliesaz.illumera.remote_input

import com.hereliesaz.illumera.data.model.debrid.DebridProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.BindException
import java.util.UUID

class DebridPairingServerManager {

    private var server: DebridPairingServer? = null

    companion object {
        private const val PORT_START = 8080
        private const val PORT_END = 8090
    }

    suspend fun startServer(
        provider: DebridProvider,
        onApiKeyReceived: (String) -> Unit
    ): ServerInfo? = withContext(Dispatchers.IO) {
        stopServer()
        val ip = NetworkUtils.getLocalIpAddress() ?: return@withContext null
        val pairingToken = UUID.randomUUID().toString()

        for (port in PORT_START..PORT_END) {
            try {
                val pairingServer = DebridPairingServer(
                    port = port,
                    pairingToken = pairingToken,
                    provider = provider,
                    onApiKeyReceived = onApiKeyReceived
                )
                pairingServer.start()
                server = pairingServer
                return@withContext ServerInfo(ip, port, pairingToken)
            } catch (_: BindException) {
                continue
            } catch (_: Exception) {
                continue
            }
        }

        null
    }

    fun stopServer() {
        server?.stop()
        server = null
    }
}
