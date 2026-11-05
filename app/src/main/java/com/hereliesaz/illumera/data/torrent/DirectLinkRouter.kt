package com.hereliesaz.illumera.data.torrent

import android.content.Context
import com.hereliesaz.illumera.data.debrid.DebridManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The direct-link half of "Require VPN for torrents": while it is on and no VPN is up, a direct
 * http(s) stream is fetched through the connected debrid service instead of from its host.
 * If debrid can't take the link (unsupported host, no service, timeout), it plays directly.
 *
 * [route] blocks; call it only off the main thread (ExoPlayer's loader thread).
 */
@Singleton
class DirectLinkRouter @Inject constructor(
    private val guard: TorrentVpnGuard,
    private val debrid: DebridManager
) {
    // Unrestricted links last hours; one lookup per link per process is enough.
    private val routed = ConcurrentHashMap<String, String>()

    fun route(url: String): String {
        if (!guard.required.value || guard.isVpnActive()) return url
        routed[url]?.let { return it }
        val resolved = runBlocking { withTimeoutOrNull(TIMEOUT_MS) { debrid.unrestrictLink(url) } }
        return (resolved ?: url).also { routed[url] = it }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Access {
        fun directLinkRouter(): DirectLinkRouter
    }

    companion object {
        private const val TIMEOUT_MS = 15_000L

        fun from(context: Context): DirectLinkRouter? = runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, Access::class.java).directLinkRouter()
        }.getOrNull()
    }
}
