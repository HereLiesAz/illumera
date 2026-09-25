package com.hereliesaz.illumera.data.net

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Keeps addon, debrid and stream URLs off plain HTTP on the public internet.
 *
 * Those URLs often carry account tokens (debrid links, configured addon URLs), and TV
 * boxes sit on shared networks. Requests to public hosts are upgraded to HTTPS; hosts
 * on this device or the local network (TorrServer, LAN addons) stay as they are, since
 * the traffic never leaves the network and such servers rarely have certificates.
 *
 * A public server that only speaks HTTP now fails instead of silently exposing its URL.
 */
object CleartextGuard : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.scheme != "http" || isLocalHost(url.host)) return chain.proceed(request)
        val port = if (url.port == 80) 443 else url.port
        return chain.proceed(request.newBuilder().url(url.newBuilder().scheme("https").port(port).build()).build())
    }

    /** True for this device and private/link-local networks, where cleartext stays local. */
    fun isLocalHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
        if (':' in h) return h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")
        val octets = h.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4) return false
        val (a, b) = octets
        return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
    }
}

/** Applies [CleartextGuard] and refuses HTTPS→HTTP redirects, which would undo it. */
fun OkHttpClient.Builder.guardCleartext(): OkHttpClient.Builder =
    addInterceptor(CleartextGuard).followSslRedirects(false)
