package com.hereliesaz.illumera.domain

import android.content.Context
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

    fun canonicalGenre(value: String): String = when (value.trim().lowercase()) {
        "science fiction", "science-fiction", "scifi", "sci fi" -> "Sci-Fi"
        "game show", "game show tv" -> "Game-Show"
        "reality tv", "reality television" -> "Reality-TV"
        "talk show" -> "Talk-Show"
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
            .filter { genre ->
                genre.isNotBlank() && !genre.matches(Regex("\\d{4}")) &&
                    genre.lowercase() !in setOf("all", "all genres", "none")
            }.toList()
        return (defaults + extra).distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
    }

    private fun preferenceKey(profileId: Int, screen: String) =
        "${profileId}_${screen}_disabled"

    fun disabledGenres(context: Context, profileId: Int, screen: String): Set<String> {
        if (screen !in setOf("movies", "series")) return emptySet()
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(preferenceKey(profileId, screen), emptySet())
            ?.toSet().orEmpty()
    }

    fun enabledGenres(
        context: Context, profileId: Int, screen: String, available: List<String>
    ): List<String> {
        val disabled = disabledGenres(context, profileId, screen).map { it.lowercase() }.toSet()
        return available.filter { it.lowercase() !in disabled }
    }

    fun setDisabledGenres(
        context: Context, profileId: Int, screen: String, disabled: Set<String>
    ) {
        if (screen !in setOf("movies", "series")) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(preferenceKey(profileId, screen), disabled.toSet()).apply()
    }
}
