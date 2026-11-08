package com.hereliesaz.illumera.domain

import com.hereliesaz.illumera.data.model.stremio.MetaItem
import java.util.Locale

/** The streaming services the Streaming Catalogs addon lists, by catalog id. */
object StreamingServices {
    val names: Map<String, String> = linkedMapOf(
        "nfx" to "Netflix", "hbm" to "HBO Max", "dnp" to "Disney+", "amp" to "Prime Video",
        "atp" to "Apple TV+", "pmp" to "Paramount+", "hlu" to "Hulu", "pcp" to "Peacock"
    )

    private fun normalized(title: String) = title.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private val normalizedNames: Set<String> = names.values.mapTo(mutableSetOf(), ::normalized)

    /**
     * True when a Home row [title] names one of the services ("Netflix", "Prime  Video",
     * "Disney+"). Home rows merge a service's movie and series catalogs under one title and
     * carry no catalog id, so the title is what identifies them.
     */
    fun isServiceRowTitle(title: String): Boolean = normalized(title) in normalizedNames
}

/**
 * Which titles are a streaming service's own productions, judged from TMDB's network/company
 * discover results. Titles are matched by name and release year, since the addon catalogs and
 * TMDB share no id.
 */
object StreamingOriginals {
    /** TMDB TV networks, keyed by the Streaming Catalogs addon's catalog id. */
    val tvNetworks: Map<String, Int> = mapOf(
        "nfx" to 213, "hbm" to 3186, "dnp" to 2739, "amp" to 1024,
        "atp" to 2552, "pmp" to 4330, "hlu" to 453, "pcp" to 3353
    )

    /** TMDB production companies for movie originals; services without a clear one are absent. */
    val movieCompanies: Map<String, Int> = mapOf(
        "nfx" to 178464, "amp" to 20580, "atp" to 151347
    )

    private val yearRegex = Regex("(?:19|20)[0-9]{2}")

    private fun titleKey(name: String) =
        name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun yearOf(text: String?): Int? =
        text?.let { yearRegex.find(it)?.value?.toIntOrNull() }

    fun key(name: String, releaseInfo: String?): String =
        "${titleKey(name)}|${yearOf(releaseInfo) ?: ""}"

    /** True when [item]'s title (and year, within one) is among the [keys] built with [key]. */
    fun matches(item: MetaItem, keys: Set<String>): Boolean {
        val title = titleKey(item.name)
        if (title.isEmpty()) return false
        val year = yearOf(item.releaseInfo)
        if (year == null || "$title|" in keys) {
            return keys.any { it.startsWith("$title|") }
        }
        return (year - 1..year + 1).any { "$title|$it" in keys }
    }
}
