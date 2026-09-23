package com.hereliesaz.illumera.remote_input

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Utility to get the device's local IPv4 address for the web server.
 */
object NetworkUtils {

    /**
     * Finds the first non-loopback IPv4 address on the device.
     * Returns null if no suitable address is found.
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (networkInterface in interfaces) {
                // Skip loopback and down interfaces
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                for (address in networkInterface.inetAddresses) {
                    // Only consider IPv4 addresses
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            com.hereliesaz.illumera.crash.AppErrors.w("NetworkUtils", "Failed to get local IP", e)
        }
        return null
    }
}
