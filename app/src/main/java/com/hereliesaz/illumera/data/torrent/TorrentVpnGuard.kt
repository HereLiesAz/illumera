package com.hereliesaz.illumera.data.torrent

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Decides whether a torrent may run, and tells the controller when that stops being true. */
interface TorrentGate {
    /** Why a torrent must not run right now, or null when it may. */
    fun blockReason(): String?

    /**
     * Calls [onBlocked] (on the main thread) if the torrent must stop while running.
     * Returns the function that ends the watch.
     */
    fun watch(onBlocked: (reason: String) -> Unit): () -> Unit

    companion object {
        val Open = object : TorrentGate {
            /**
     * "Test VPN": reports whether this app's traffic rides a VPN, and the public exit
     * address the internet sees (ipinfo.io). Never throws; a failed lookup is reported inline.
     */
    suspend fun test(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val vpn = if (isVpnActive()) "VPN active" else "No VPN"
        val exit = runCatching {
            val conn = java.net.URL(IP_LOOKUP_URL).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            try {
                val json = org.json.JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val place = listOf(json.optString("city"), json.optString("country"))
                    .filter { it.isNotBlank() }.joinToString(", ")
                json.optString("ip").ifBlank { "?" } + if (place.isNotEmpty()) " ($place)" else ""
            } finally { conn.disconnect() }
        }.getOrElse { "exit IP unknown" }
        "$vpn · $exit"
    }

    override fun blockReason(): String? = null
            override fun watch(onBlocked: (reason: String) -> Unit): () -> Unit = {}
        }
    }
}

/**
 * "Only stream torrents over a VPN". Off until the viewer turns it on; once on it stays on
 * (app-wide, not per profile, so switching profiles can't bypass it) until they turn it off.
 *
 * Active means this app's default network is a VPN, so a VPN that excludes illumera does not
 * count. Direct links and debrid streams never touch peers and are not gated.
 */
@Singleton
class TorrentVpnGuard @Inject constructor(
    @ApplicationContext private val context: Context
) : TorrentGate {
    private val prefs = context.getSharedPreferences("illumera_network", Context.MODE_PRIVATE)
    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _required = MutableStateFlow(prefs.getBoolean(KEY_REQUIRED, false))
    val required: StateFlow<Boolean> = _required.asStateFlow()

    fun setRequired(value: Boolean) {
        _required.value = value
        prefs.edit().putBoolean(KEY_REQUIRED, value).apply()
    }

    fun isVpnActive(): Boolean = runCatching {
        val network = connectivity.activeNetwork ?: return false
        connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)

    /**
     * "Test VPN": reports whether this app's traffic rides a VPN, and the public exit
     * address the internet sees (ipinfo.io). Never throws; a failed lookup is reported inline.
     */
    suspend fun test(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val vpn = if (isVpnActive()) "VPN active" else "No VPN"
        val exit = runCatching {
            val conn = java.net.URL(IP_LOOKUP_URL).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            try {
                val json = org.json.JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val place = listOf(json.optString("city"), json.optString("country"))
                    .filter { it.isNotBlank() }.joinToString(", ")
                json.optString("ip").ifBlank { "?" } + if (place.isNotEmpty()) " ($place)" else ""
            } finally { conn.disconnect() }
        }.getOrElse { "exit IP unknown" }
        "$vpn · $exit"
    }

    override fun blockReason(): String? =
        if (_required.value && !isVpnActive()) BLOCKED_MESSAGE else null

    override fun watch(onBlocked: (reason: String) -> Unit): () -> Unit {
        if (!_required.value) return {}
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun check(vpn: Boolean) {
                if (!vpn && _required.value) main.post { onBlocked(DROPPED_MESSAGE) }
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                check(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            override fun onLost(network: Network) = check(false)
        }
        val registered = runCatching { connectivity.registerDefaultNetworkCallback(callback) }.isSuccess
        if (!registered) {
            // Can't watch: the next start still checks, so fail closed rather than stream unwatched.
            main.post { onBlocked(DROPPED_MESSAGE) }
            return {}
        }
        return { runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }

    companion object {
        private const val IP_LOOKUP_URL = "https://ipinfo.io/json"
        private const val KEY_REQUIRED = "torrent_requires_vpn"
        const val BLOCKED_MESSAGE = "Torrent blocked: connect to a VPN first"
        const val DROPPED_MESSAGE = "Torrent stopped: VPN connection lost"
    }
}
