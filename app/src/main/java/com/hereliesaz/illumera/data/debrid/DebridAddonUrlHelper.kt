package com.hereliesaz.illumera.data.debrid

import com.hereliesaz.illumera.data.model.debrid.DebridProvider
import java.net.URI

/**
 * Auto-fills a connected debrid provider's API key into the install URL of addons whose
 * config-URL convention illumera knows, so the user doesn't have to look up and paste the
 * same key into every debrid-aware addon they install.
 *
 * There is no general client-side way to do this: most Stremio addons (Torrentio included)
 * don't declare their config fields in the manifest at all — they hand the user a fully custom
 * "configure" webpage that builds the final install URL however it wants, which only that
 * page's own JS can reproduce. This only covers addons explicitly listed here, verified against
 * their actual source (see [torrentioConfigKey] / TheBeastLT/torrentio-scraper's
 * addon/moch/moch.js and addon/lib/configuration.js).
 */
object DebridAddonUrlHelper {

    private const val MANIFEST_SUFFIX = "manifest.json"

    /** Torrentio's own moch config keys — used to detect a URL that's already configured. */
    private val TORRENTIO_DEBRID_KEYS = setOf(
        "realdebrid", "alldebrid", "premiumize", "debridlink", "easydebrid", "offcloud", "torbox", "putio"
    )

    private fun torrentioConfigKey(provider: DebridProvider): String? = when (provider) {
        DebridProvider.REAL_DEBRID -> "realdebrid"
        DebridProvider.ALL_DEBRID -> "alldebrid"
        DebridProvider.PREMIUMIZE -> "premiumize"
        DebridProvider.DEBRID_LINK -> "debridlink"
        DebridProvider.EASY_DEBRID -> "easydebrid"
        DebridProvider.OFFCLOUD -> "offcloud"
        DebridProvider.TORBOX -> "torbox"
    }

    /**
     * Returns [url] with the connected provider's API key inserted, if [url] points at a known
     * debrid-configurable addon and doesn't already have a debrid key configured. Otherwise
     * returns [url] unchanged.
     */
    fun withDebridKeyIfKnown(url: String, provider: DebridProvider?, apiKey: String?): String {
        if (provider == null || apiKey.isNullOrBlank()) return url
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val host = uri.host?.lowercase() ?: return url
        return when (host) {
            "torrentio.strem.fun" -> withTorrentioKey(uri, provider, apiKey) ?: url
            else -> url
        }
    }

    private fun withTorrentioKey(uri: URI, provider: DebridProvider, apiKey: String): String? {
        val configKey = torrentioConfigKey(provider) ?: return null
        val segments = uri.path.trim('/').split("/").filter { it.isNotEmpty() }
        if (segments.isEmpty() || segments.last() != MANIFEST_SUFFIX) return null

        val configSegments = segments.dropLast(1)
        val existingConfig = configSegments.lastOrNull()
        val configParts = existingConfig?.split("|")?.toMutableList() ?: mutableListOf()
        val alreadyConfigured = configParts.any { it.substringBefore("=").lowercase() in TORRENTIO_DEBRID_KEYS }
        if (alreadyConfigured) return null

        configParts.add("$configKey=$apiKey")
        val newConfigSegment = configParts.joinToString("|")
        val newSegments = if (existingConfig != null) {
            configSegments.dropLast(1) + newConfigSegment
        } else {
            configSegments + newConfigSegment
        }
        return "${uri.scheme}://${uri.authority}/${(newSegments + MANIFEST_SUFFIX).joinToString("/")}"
    }
}
