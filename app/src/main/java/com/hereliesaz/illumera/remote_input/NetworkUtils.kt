package com.hereliesaz.illumera.remote_input

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Utility to get the device's local IPv4 address for the web server.
 */
object NetworkUtils {

    /**
     * Returns the device's LAN IPv4 address, preferring a physical Wi-Fi or
     * Ethernet interface (`wlan*` / `eth*`) that is up, not virtual and not
     * point-to-point (so VPN tunnels and cellular links are skipped). If no such
     * interface has an address, falls back to the first non-loopback IPv4
     * address on any up interface. Returns null if nothing suitable is found.
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            val up = interfaces.filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }

            val preferred = up.filter { iface ->
                val name = iface.name.orEmpty()
                (name.startsWith("wlan") || name.startsWith("eth")) &&
                    !iface.isVirtual &&
                    runCatching { !iface.isPointToPoint }.getOrDefault(false)
            }

            for (networkInterface in preferred + up) {
                firstIpv4(networkInterface)?.let { return it }
            }
        } catch (e: Exception) {
            com.hereliesaz.illumera.crash.AppErrors.w("NetworkUtils", "Failed to get local IP", e)
        }
        return null
    }

    private fun firstIpv4(networkInterface: NetworkInterface): String? {
        for (address in networkInterface.inetAddresses) {
            if (address is Inet4Address && !address.isLoopbackAddress) {
                return address.hostAddress
            }
        }
        return null
    }
}
