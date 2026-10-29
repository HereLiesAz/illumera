package com.hereliesaz.illumera.data.debrid

import com.hereliesaz.illumera.data.model.debrid.DebridProvider
import java.net.URI

/**
 * Torrentio's configuration, as encoded in its install URL:
 * `https://torrentio.strem.fun/<key=value|key=value…>/manifest.json`.
 *
 * Keys and values mirror TheBeastLT/torrentio-scraper `addon/lib/configuration.js` and
 * `addon/moch/moch.js`. An empty selection means Torrentio's own default and is omitted.
 */
data class TorrentioConfig(
    val providers: Set<String> = emptySet(),
    val sort: String = SORT_OPTIONS.first().first,
    val excludedQualities: Set<String> = emptySet(),
    val languages: List<String> = emptyList(),
    val limit: Int? = null,
    val debridProvider: DebridProvider? = null,
    val debridKey: String = "",
    val noDownloadLinks: Boolean = false,
    val noDebridCatalog: Boolean = false
) {
    /** The `|`-joined config path segment; empty for an all-defaults configuration. */
    fun configSegment(): String = buildList {
        if (providers.isNotEmpty()) add("providers=" + PROVIDERS.map { it.first }.filter { it in providers }.joinToString(","))
        if (sort != SORT_OPTIONS.first().first) add("sort=$sort")
        if (languages.isNotEmpty()) add("language=" + languages.joinToString(","))
        if (excludedQualities.isNotEmpty()) add("qualityfilter=" + QUALITY_FILTERS.map { it.first }.filter { it in excludedQualities }.joinToString(","))
        limit?.let { add("limit=$it") }
        val key = debridKey.trim()
        // Torrentio has no Torrin-specific config token. Never embed a Torrin key
        // in an invented provider field or send it to an unrelated provider.
        if (debridProvider != null && debridProvider != DebridProvider.TORRIN && key.isNotEmpty()) {
            val options = listOfNotNull(
                "nodownloadlinks".takeIf { noDownloadLinks },
                "nocatalog".takeIf { noDebridCatalog }
            )
            if (options.isNotEmpty()) add("debridoptions=" + options.joinToString(","))
            add("${configKey(debridProvider)}=$key")
        }
    }.joinToString("|")

    fun manifestUrl(): String {
        val segment = configSegment()
        return if (segment.isEmpty()) "$BASE_URL/manifest.json" else "$BASE_URL/$segment/manifest.json"
    }

    companion object {
        const val BASE_URL = "https://torrentio.strem.fun"
        const val HOST = "torrentio.strem.fun"

        val PROVIDERS = listOf(
            "yts" to "YTS", "eztv" to "EZTV", "rarbg" to "RARBG", "1337x" to "1337x",
            "thepiratebay" to "The Pirate Bay", "kickasstorrents" to "KickassTorrents",
            "torrentgalaxy" to "TorrentGalaxy", "magnetdl" to "MagnetDL",
            "horriblesubs" to "HorribleSubs", "nyaasi" to "Nyaa", "tokyotosho" to "TokyoTosho",
            "anidex" to "AniDex", "rutor" to "Rutor", "rutracker" to "Rutracker",
            "comando" to "Comando", "bludv" to "BluDV", "torrent9" to "Torrent9",
            "ilcorsaronero" to "ilCorSaRoNeRo", "mejortorrent" to "MejorTorrent",
            "wolfmax4k" to "Wolfmax4k", "cinecalidad" to "Cinecalidad", "besttorrents" to "BestTorrents"
        )
        val SORT_OPTIONS = listOf(
            "quality" to "By quality", "qualitysize" to "Quality, then size",
            "seeders" to "By seeders", "size" to "By size"
        )
        val QUALITY_FILTERS = listOf(
            "brremux" to "BluRay REMUX", "hdrall" to "HDR", "dolbyvision" to "Dolby Vision",
            "dolbyvisionwithhdr" to "Dolby Vision + HDR", "threed" to "3D", "nonthreed" to "Non-3D",
            "4k" to "4K", "1080p" to "1080p", "720p" to "720p", "480p" to "480p",
            "other" to "Other (DVDRip/HDRip…)", "scr" to "Screener", "cam" to "Cam", "unknown" to "Unknown"
        )
        val LANGUAGES = listOf(
            "japanese", "russian", "italian", "portuguese", "spanish", "latino", "korean", "chinese",
            "taiwanese", "french", "german", "dutch", "hindi", "telugu", "tamil", "polish", "lithuanian",
            "latvian", "estonian", "czech", "slovakian", "slovenian", "hungarian", "romanian",
            "bulgarian", "serbian", "croatian", "ukrainian", "greek", "danish", "finnish", "swedish",
            "norwegian", "turkish", "arabic", "persian", "hebrew", "vietnamese", "indonesian",
            "malay", "thai"
        )
        val LIMITS = listOf(null, 1, 2, 3, 5, 10)

        fun configKey(provider: DebridProvider): String = when (provider) {
            DebridProvider.REAL_DEBRID -> "realdebrid"
            DebridProvider.ALL_DEBRID -> "alldebrid"
            DebridProvider.PREMIUMIZE -> "premiumize"
            DebridProvider.DEBRID_LINK -> "debridlink"
            DebridProvider.EASY_DEBRID -> "easydebrid"
            DebridProvider.OFFCLOUD -> "offcloud"
            DebridProvider.TORBOX -> "torbox"
            DebridProvider.TORRIN -> "torrin" // Not supported by Torrentio; excluded by callers.
        }

        fun isTorrentio(url: String): Boolean =
            runCatching { URI(url).host?.lowercase() == HOST }.getOrDefault(false)

        /** Reads an installed Torrentio URL back into a config; unknown keys are dropped. */
        fun parse(url: String): TorrentioConfig {
            val segments = runCatching { URI(url).path }.getOrNull().orEmpty()
                .trim('/').split('/').filter { it.isNotEmpty() && it != "manifest.json" }
            val pairs = segments.lastOrNull().orEmpty().split('|').mapNotNull { part ->
                val key = part.substringBefore('=', "").lowercase()
                if (key.isEmpty()) null else key to part.substringAfter('=')
            }.toMap()
            fun list(key: String) = pairs[key]?.split(',')?.filter { it.isNotBlank() }.orEmpty()
            // Torrin has no Torrentio config token; treat any torrin= segment as unknown.
            val debrid = DebridProvider.entries
                .filterNot { it == DebridProvider.TORRIN }
                .firstOrNull { configKey(it) in pairs }
            val options = list("debridoptions")
            return TorrentioConfig(
                providers = list("providers").toSet(),
                sort = pairs["sort"]?.takeIf { s -> SORT_OPTIONS.any { it.first == s } } ?: SORT_OPTIONS.first().first,
                excludedQualities = list("qualityfilter").toSet(),
                languages = list("language"),
                limit = pairs["limit"]?.toIntOrNull(),
                debridProvider = debrid,
                debridKey = debrid?.let { pairs[configKey(it)] }.orEmpty(),
                noDownloadLinks = "nodownloadlinks" in options,
                noDebridCatalog = "nocatalog" in options
            )
        }
    }
}
