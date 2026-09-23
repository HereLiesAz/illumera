package com.hereliesaz.illumera.remote_input

import com.hereliesaz.illumera.domain.HubShape
import fi.iki.elonen.NanoHTTPD

/**
 * Singleton manager for the Hub bulk image upload server.
 * Manages the HTTP server lifecycle for the web portal
 * where users can upload images for multiple hub items at once.
 */
object HubServerManager {

    private var server: NanoHTTPD? = null

    /**
     * Start the Bulk Hub upload server.
     *
     * @param items List of items to manage
     * @param shape The shape constraint for all items
     * @param port Port to listen on
     * @param onImageReceived Callback when an image is uploaded for a specific ID
     * @return The URL string for QR code generation, or null on failure
     */
    private const val PORT_START = 8085
    private const val PORT_END = 8095

    @Synchronized
    fun startBulkServer(
        items: List<com.hereliesaz.illumera.data.model.HubRowItemEntity>,
        shape: HubShape,
        onImageReceived: (String, ByteArray) -> Unit,
        onImageDeleted: ((String) -> Unit)? = null
    ): String? {
        stopServer()

        val ip = NetworkUtils.getLocalIpAddress() ?: return null
        val pairingToken = java.util.UUID.randomUUID().toString()

        for (port in PORT_START..PORT_END) {
            try {
                val bulkServer = HubBulkUploadServer(
                    port = port,
                    pairingToken = pairingToken,
                    items = items,
                    shape = shape,
                    onImageReceived = onImageReceived,
                    onImageDeleted = onImageDeleted
                )
                bulkServer.start()
                server = bulkServer
                return "http://$ip:$port/?pin=$pairingToken"
            } catch (e: java.net.BindException) {
                continue // Port in use, try next
            } catch (e: Exception) {
                com.hereliesaz.illumera.crash.AppErrors.w("HubServerManager", "Server start failed", e)
                continue
            }
        }
        return null
    }

    /**
     * Stop the Hub upload server.
     */
    @Synchronized
    fun stopServer() {
        server?.stop()
        server = null
    }

}
