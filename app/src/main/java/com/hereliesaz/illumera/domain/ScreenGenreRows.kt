package com.hereliesaz.illumera.domain

import android.content.Context
import java.util.Locale
import com.hereliesaz.illumera.data.model.stremio.CatalogManifest

/**
 * Movies and Series are genre-first browsing screens, not a unified All-items row.
 * These lists match the standard Stremio Cinemeta movie/series genre dropdowns.
 * Enabled addons can contribute additional real genres.
 */
object ScreenGenreRows {
    private const val PREFS = "illumera_screen_genres"

    private val movieDefaults = listOf(
        "Action", "Adventure", "Animation", "Biography", "Comedy", "Crime",
        "Documentary", "Drama", "Family", "Fantasy", "History", "Horror",
        "Mystery", "Romance", "Sci-Fi", "Sport", "Thriller", "War", "Western"
    )
    private val seriesExtra = listOf("Game-Show", "Reality-TV", "Talk-Show")

    // Stremio addons often overload the manifest's "genre" options with
    // discover filters: spoken language, country, 4K/1080p, year, recently
    // released, or popularity. Those are NOT genres and must never become
    // poster rows. Keep official Stremio genres plus recognized cinematic
    // genres, rather than treating every arbitrary option as a genre.
    private val additionalRealGenres = listOf(
        "Anime", "Children", "Cult", "Experimental", "Film-Noir",
        "Independent", "Kids", "Martial Arts", "Music", "Musical",
        "News", "Noir", "Psychological", "Short", "Slice of Life",
        "Superhero", "Suspense", "True Crime", "Soap", "TV Movie", "Action & Adventure"
    )
    private val recognizedGenreLabels =
        (movieDefaults + seriesExtra + additionalRealGenres)
            .associateBy { it.lowercase(Locale.ROOT) }

    private val nonGenreLabels = setOf(
        "all", "all genres", "none", "trending", "popular", "most popular",
        "new releases", "recently added", "recently released", "latest",
        "top rated", "top 10", "most watched", "most viewed", "featured",
        "uk", "usa", "u.s.", "u.s.a.", "korea", "south korea", "north korea",
        "english dubbed", "subbed", "dubbed", "original language",
        "hd", "uhd", "sd", "hdr", "hdr10", "dolby vision",
        "bluray", "blu-ray", "web-dl", "web rip", "webrip", "cam",
        "all languages", "all countries", "any language", "any country"
    )

    private val languageAndCountryLabels: Set<String> by lazy {
        val languages = Locale.getISOLanguages().map { language ->
            Locale.forLanguageTag(language).getDisplayLanguage(Locale.ENGLISH)
                .lowercase(Locale.ROOT)
        }
        val countries = Locale.getISOCountries().map { region ->
            Locale.Builder().setRegion(region).build()
                .getDisplayCountry(Locale.ENGLISH).lowercase(Locale.ROOT)
        }
        (languages + countries).toSet()
    }

    private fun isActualGenre(genre: String): Boolean {
        val name = genre.trim().lowercase(Locale.ROOT)
        if (name.isEmpty()) return false
        // Recognized genres always win before the generic filter rules.
        if (name in recognizedGenreLabels) return true
        if (name in nonGenreLabels || name in languageAndCountryLabels) return false
        if (name.matches(Regex("(?:19|20)[0-9]{2}(?:s)?"))) return false
        if (name.matches(Regex("[0-9]{3,4}p|[248]k(?:\\s*(?:uhd|hdr))?"))) return false
        if (name.matches(Regex("(?:last|past|next|this)\\s+(?:[0-9]+\\s+)?(?:days?|weeks?|months?|years?)"))) return false
        if (name.matches(Regex("[0-9]+\\s*(?:days?|weeks?|months?|years?)"))) return false
        if (name.startsWith("recently ") || name.startsWith("released ") ||
            name.startsWith("added ") || name.startsWith("top ") ||
            name.startsWith("most ")) return false
        // An addon is allowed to introduce a real genre not in Cinemeta's list.
        return true
    }


    fun canonicalGenre(value: String): String = when (value.trim().lowercase()) {
        "science fiction", "science-fiction", "scifi", "sci fi" -> "Sci-Fi"
        "game show", "game show tv" -> "Game-Show"
        "reality tv", "reality television" -> "Reality-TV"
        "talk show" -> "Talk-Show"
        "film noir", "film_noir" -> "Film-Noir"
        "martial-arts", "martialarts" -> "Martial Arts"
        "true-crime", "truecrime" -> "True Crime"
        "slice-of-life", "sliceoflife" -> "Slice of Life"
        "sports" -> "Sport"
        "children's", "childrens" -> "Children"
        "action and adventure", "action-adventure", "action & adventure" -> "Action & Adventure"
        "tv-movie", "tv film", "television movie" -> "TV Movie"
        "soap opera", "soap-opera" -> "Soap"
        else -> value.trim()
    }

    /** Return screen-specific defaults plus non-year genre options from enabled addon manifests. */
    fun availableGenres(type: String, catalogs: List<CatalogManifest>): List<String> {
        if (type !in setOf("movie", "series")) return emptyList()
        val defaults = if (type == "series") movieDefaults + seriesExtra else movieDefaults
        val extra = catalogs.asSequence()
            .filter { it.type == type && it.id != "year" }
            .flatMap { catalog ->
                (catalog.genres.orEmpty() +
                    catalog.extra.orEmpty().filter { it.name == "genre" }
                        .flatMap { it.options.orEmpty() }).asSequence()
            }
            .map(::canonicalGenre)
            .filter(::isActualGenre)
            .toList()
        return (defaults + extra).distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
    }

    private fun preferenceKey(profileId: Int, screen: String) =
        "${profileId}_${screen}_disabled"

    fun disabledGenres(context: Context, profileId: Int, screen: String): Set<String> {
        if (screen !in setOf("movies", "series")) return emptySet()
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(preferenceKey(profileId, screen), emptySet())
            ?.map(::canonicalGenre)?.toSet().orEmpty()
    }

    fun enabledGenres(
        context: Context, profileId: Int, screen: String, available: List<String>
    ): List<String> {
        val disabled = disabledGenres(context, profileId, screen)
            .map { canonicalGenre(it).lowercase() }.toSet()
        return available.filter { canonicalGenre(it).lowercase() !in disabled }
    }

    fun setDisabledGenres(
        context: Context, profileId: Int, screen: String, disabled: Set<String>
    ) {
        if (screen !in setOf("movies", "series")) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(preferenceKey(profileId, screen), disabled.toSet()).apply()
    }
}
