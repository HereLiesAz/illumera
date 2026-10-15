package com.hereliesaz.illumera.domain

import android.content.Context
import java.util.Locale
import java.text.Normalizer
import com.hereliesaz.illumera.data.model.stremio.CatalogManifest
import com.hereliesaz.illumera.data.model.stremio.MetaItem

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
        "Action & Adventure", "Anime", "Children", "Cult", "Experimental", "Film-Noir",
        "Independent", "Kids", "Martial Arts", "Music", "Musical",
        "News", "Noir", "Psychological", "Short", "Slice of Life",
        "Soap", "TV Movie", "Sci-Fi & Fantasy",
        "Superhero", "Suspense", "True Crime"
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

    private fun nativeLabelKey(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)

    private val languageAndCountryLabels: Set<String> by lazy {
        // Providers frequently use native spellings, not English ones:
        // Español, Português, Deutsch, Français, 日本語, etc.
        val locales = Locale.getISOLanguages().map { Locale.forLanguageTag(it) }
        // Avoid formatting every country in every ISO language on the UI
        // thread; the most common addon-localized labels cover the real cases.
        val displayLocales = listOf(
            "en", "es", "pt", "de", "fr", "it", "ja",
            "zh", "ko", "ru", "ar", "tr", "hi"
        ).map(Locale::forLanguageTag)
        val languages = locales.flatMap { language ->
            listOf(
                language.getDisplayLanguage(Locale.ENGLISH),
                language.getDisplayLanguage(language)
            )
        }
        val countries = Locale.getISOCountries().flatMap { region ->
            val country = Locale.Builder().setRegion(region).build()
            // Include native/localized country names from supported display
            // languages, not only English labels.
            displayLocales.map { locale -> country.getDisplayCountry(locale) }
        }
        (languages + countries).map(::nativeLabelKey).toSet()
    }

    /**
     * An addon may report a combined genre even when the viewer is browsing a
     * constituent Stremio row. Use the same relationship for source selection
     * AND metadata filtering.
     */
    fun matchesRequestedGenre(actual: String, requested: String): Boolean {
        val value = canonicalGenre(actual)
        val target = canonicalGenre(requested)
        return value.equals(target, ignoreCase = true) ||
            (value.equals("Action & Adventure", ignoreCase = true) &&
                target in setOf("Action", "Adventure")) ||
            (value.equals("Sci-Fi & Fantasy", ignoreCase = true) &&
                target in setOf("Sci-Fi", "Fantasy"))
    }

    private fun isActualGenre(genre: String): Boolean {
        val name = genre.trim().lowercase(Locale.ROOT)
        if (name.isEmpty()) return false
        // Recognized genres always win before the generic filter rules.
        if (name in recognizedGenreLabels) return true
        if (name in nonGenreLabels || nativeLabelKey(name) in languageAndCountryLabels) return false
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
        "action and adventure", "action-adventure" -> "Action & Adventure"
        "science fiction & fantasy", "sci fi & fantasy" -> "Sci-Fi & Fantasy"
        "tv movies", "television movie" -> "TV Movie"
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

    enum class TitleOrder(val label: String) {
        CATALOG("Catalog order"),
        RATING("Highest rated"),
        NEWEST("Newest first"),
        OLDEST("Oldest first"),
        TITLE_ASC("Title A–Z"),
        TITLE_DESC("Title Z–A")
    }

    private fun titleOrderKey(profileId: Int, screen: String, genre: String) =
        "${profileId}_${screen}_${canonicalGenre(genre).lowercase(Locale.ROOT)}_title_order"

    fun titleOrder(context: Context, profileId: Int, screen: String, genre: String): TitleOrder {
        if (screen !in setOf("movies", "series")) return TitleOrder.CATALOG
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(titleOrderKey(profileId, screen, genre), null)
        return TitleOrder.entries.firstOrNull { it.name == stored } ?: TitleOrder.CATALOG
    }

    fun setTitleOrder(context: Context, profileId: Int, screen: String, genre: String, order: TitleOrder) {
        if (screen !in setOf("movies", "series")) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(titleOrderKey(profileId, screen, genre), order.name)
            .apply()
    }

    /** Sort only within a genre. Missing metadata is placed last; catalog order breaks ties. */
    fun sortTitles(items: List<MetaItem>, order: TitleOrder): List<MetaItem> {
        fun year(item: MetaItem): Int? =
            Regex("\\d{4}").find(item.releaseInfo.orEmpty())?.value?.toIntOrNull()

        return when (order) {
            TitleOrder.CATALOG -> items
            TitleOrder.RATING -> items.sortedWith(compareByDescending<MetaItem> {
                it.imdbRating?.toDoubleOrNull() ?: Double.NEGATIVE_INFINITY
            })
            TitleOrder.NEWEST -> items.sortedWith(compareByDescending<MetaItem> { year(it) ?: Int.MIN_VALUE })
            TitleOrder.OLDEST -> items.sortedWith(compareBy<MetaItem> { year(it) ?: Int.MAX_VALUE })
            TitleOrder.TITLE_ASC -> items.sortedBy { it.name.lowercase(Locale.ROOT) }
            TitleOrder.TITLE_DESC -> items.sortedByDescending { it.name.lowercase(Locale.ROOT) }
        }
    }

    /** Viewing and saving a title are independent preference signals. */
    fun affinityScore(items: List<MetaItem>, watchedIds: Set<String>, likedIds: Set<String>): Int =
        items.sumOf { item ->
            (if (item.id in watchedIds) 2 else 0) +
                (if (item.id in likedIds) 3 else 0)
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
            .putStringSet(preferenceKey(profileId, screen), disabled.map(::canonicalGenre).toSet())
            .apply()
    }
}
