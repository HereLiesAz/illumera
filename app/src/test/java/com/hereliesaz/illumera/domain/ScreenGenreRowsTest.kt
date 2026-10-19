package com.hereliesaz.illumera.domain

import com.hereliesaz.illumera.data.model.stremio.CatalogExtra
import com.hereliesaz.illumera.data.model.stremio.CatalogManifest
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenGenreRowsTest {
    @Test fun moviesDefaultToEveryOfficialStremioGenre() {
        val genres = ScreenGenreRows.availableGenres("movie", emptyList())
        assertEquals(19, genres.size)
        assertTrue("Sci-Fi" in genres)
        assertTrue("Biography" in genres)
        assertTrue("Sport" in genres)
        assertFalse("Reality-TV" in genres)
        assertFalse("All Movies" in genres)
    }

    @Test fun seriesIncludeTheThreeAdditionalOfficialGenres() {
        val genres = ScreenGenreRows.availableGenres("series", emptyList())
        assertEquals(22, genres.size)
        assertTrue(listOf("Reality-TV", "Talk-Show", "Game-Show").all { it in genres })
    }

    @Test fun addonGenresAreIncludedWithoutYearsOrDuplicateSynonyms() {
        val catalogs = listOf(
            CatalogManifest(
                type = "movie", id = "top",
                genres = listOf("Science Fiction", "Action", "Noir"),
                extra = listOf(CatalogExtra(name = "genre", options = listOf("Sci-Fi", "Film-Noir")))
            ),
            CatalogManifest(
                type = "movie", id = "year",
                genres = listOf("2026", "2025"),
                extra = listOf(CatalogExtra(name = "genre", options = listOf("2024")))
            ),
            CatalogManifest(type = "series", id = "top", genres = listOf("Animation"))
        )
        val genres = ScreenGenreRows.availableGenres("movie", catalogs)
        assertEquals(1, genres.count { it == "Sci-Fi" })
        assertTrue("Noir" in genres)
        assertTrue("Film-Noir" in genres)
        assertFalse("2024" in genres)
        assertFalse("2025" in genres)
        assertFalse("2026" in genres)
    }

    @Test fun addonDiscoverFiltersMustNotCreateFakeGenreRows() {
        val manifest = CatalogManifest(
            type = "movie", id = "top",
            genres = listOf(
                "English", "French", "Hindi", "Japanese", "United States",
                "India", "UK", "4K", "1080p", "720p", "HDR", "Dolby Vision",
                "2026", "2020s", "Last 7 Days", "Last Month", "This Week",
                "Recently Added", "New Releases", "Trending", "Popular",
                "Animation", "Horror", "Film Noir", "True Crime"
            ),
            extra = listOf(CatalogExtra(
                name = "genre", options = listOf(
                    "English", "USA", "Germany", "BluRay", "2160p", "2025",
                    "Past Year", "30 days", "Action", "Science Fiction"
                )
            ))
        )
        val result = ScreenGenreRows.availableGenres("movie", listOf(manifest))
        assertTrue(result.containsAll(listOf("Action", "Animation", "Horror", "Sci-Fi", "Film-Noir", "True Crime")))
        assertEquals(1, result.count { it == "Sci-Fi" })
        for (notGenre in listOf(
            "English", "French", "Hindi", "Japanese", "United States",
            "India", "UK", "4K", "1080p", "720p", "HDR", "Dolby Vision",
            "2026", "2020s", "Last 7 Days", "Last Month", "This Week",
            "Recently Added", "New Releases", "Trending", "Popular",
            "USA", "Germany", "BluRay", "2160p", "2025", "Past Year", "30 days"
        )) {
            assertFalse("Non-genre filter created a poster row: $notGenre", result.contains(notGenre))
        }
    }

    @Test fun seriesNonGenreFiltersAreNotAddedEither() {
        val manifest = CatalogManifest(
            type = "series", id = "top",
            genres = listOf("English", "4K", "Last Month", "Korea", "Talk Show", "Anime")
        )
        val result = ScreenGenreRows.availableGenres("series", listOf(manifest))
        assertTrue(result.contains("Talk-Show"))
        assertFalse(result.contains("Anime"))
        assertFalse(result.contains("English"))
        assertFalse(result.contains("4K"))
        assertFalse(result.contains("Korea"))
        assertFalse(result.contains("Last Month"))
    }

    @Test fun recognizedProviderGenresRemainAvailable() {
        val list = ScreenGenreRows.availableGenres(
            "movie",
            listOf(CatalogManifest(
                type = "movie", id = "top",
                genres = listOf("Soap", "TV Movie", "Action & Adventure", "Sci-Fi & Fantasy")
            ))
        )
        assertTrue(list.containsAll(
            listOf("Soap", "TV Movie", "Action & Adventure", "Sci-Fi & Fantasy")
        ))
    }

    @Test fun seriesGenresAreTheStandardListOnly() {
        val manifest = CatalogManifest(
            type = "series", id = "top",
            genres = listOf("All Time", "Anime", "Anti Hero", "Soap")
        )
        val result = ScreenGenreRows.availableGenres("series", listOf(manifest))
        assertTrue(result.containsAll(listOf("Drama", "Reality-TV", "Talk-Show", "Game-Show")))
        for (addonLabel in listOf("All Time", "Anime", "Anti Hero", "Soap")) {
            assertFalse(addonLabel, result.contains(addonLabel))
        }
    }

    @Test fun legacyDisabledGenreAliasesCanonicalize() {
        assertEquals("Film-Noir", ScreenGenreRows.canonicalGenre("Film Noir"))
        assertEquals("True Crime", ScreenGenreRows.canonicalGenre("True-Crime"))
        assertEquals("Martial Arts", ScreenGenreRows.canonicalGenre("Martial-Arts"))
        assertEquals("Slice of Life", ScreenGenreRows.canonicalGenre("Slice-of-Life"))
    }

    @Test fun localizedLanguageNamesNeverCreateGenreRows() {
        val manifest = CatalogManifest(
            type = "movie", id = "top",
            genres = listOf(
                "Español", "Português", "Deutsch", "Français",
                "日本語", "Espana", "España", "Horror", "Anime"
            ),
            extra = listOf(CatalogExtra(
                name = "genre", options = listOf("Italiano", "Русский", "Drama")
            ))
        )
        val rows = ScreenGenreRows.availableGenres("movie", listOf(manifest))
        assertTrue(rows.containsAll(listOf("Horror", "Anime", "Drama")))
        for (language in listOf(
            "Español", "Português", "Deutsch", "Français", "日本語", "Italiano", "Русский"
        )) assertFalse("Unexpected language row: $language", language in rows)
    }

    @Test fun combinedGenreMetadataMatchesBothConstituentRows() {
        assertTrue(ScreenGenreRows.matchesRequestedGenre("Action & Adventure", "Action"))
        assertTrue(ScreenGenreRows.matchesRequestedGenre("Action & Adventure", "Adventure"))
        assertTrue(ScreenGenreRows.matchesRequestedGenre("Sci-Fi & Fantasy", "Fantasy"))
        assertTrue(ScreenGenreRows.matchesRequestedGenre("Sci-Fi & Fantasy", "Sci-Fi"))
        assertFalse(ScreenGenreRows.matchesRequestedGenre("Sci-Fi & Fantasy", "Comedy"))
    }

    @Test fun sourceGenreNamesUseTheOfficialAlias() {
        assertEquals("Sci-Fi", ScreenGenreRows.canonicalGenre("Science Fiction"))
        assertEquals("Reality-TV", ScreenGenreRows.canonicalGenre("Reality TV"))
    }
    @Test fun genreTitleOrdersAreDeterministicAndPreserveCatalogOrderByDefault() {
        val catalog = listOf(
            MetaItem(id = "first", name = "Zebra", releaseInfo = "1991", imdbRating = "6.0"),
            MetaItem(id = "second", name = "Albatross", releaseInfo = "2024", imdbRating = "9.0"),
            MetaItem(id = "third", name = "Crane", releaseInfo = "2005")
        )
        assertEquals(listOf("first", "second", "third"),
            ScreenGenreRows.sortTitles(catalog, ScreenGenreRows.TitleOrder.CATALOG).map { it.id })
        assertEquals(listOf("second", "first", "third"),
            ScreenGenreRows.sortTitles(catalog, ScreenGenreRows.TitleOrder.RATING).map { it.id })
        assertEquals(listOf("second", "third", "first"),
            ScreenGenreRows.sortTitles(catalog, ScreenGenreRows.TitleOrder.NEWEST).map { it.id })
        assertEquals(listOf("first", "third", "second"),
            ScreenGenreRows.sortTitles(catalog, ScreenGenreRows.TitleOrder.OLDEST).map { it.id })
        assertEquals(listOf("second", "third", "first"),
            ScreenGenreRows.sortTitles(catalog, ScreenGenreRows.TitleOrder.TITLE_ASC).map { it.id })
    }

    @Test fun genreAffinityCreditsViewingAndBookmarkingWithoutDoubleCountingOtherTitles() {
        val items = listOf(
            MetaItem(id = "viewed", name = "Viewed"),
            MetaItem(id = "liked", name = "Liked"),
            MetaItem(id = "both", name = "Both"),
            MetaItem(id = "unknown", name = "Unwatched")
        )
        assertEquals(10, ScreenGenreRows.affinityScore(
            items,
            watchedIds = setOf("viewed", "both", "elsewhere"),
            likedIds = setOf("liked", "both")
        ))
        assertEquals(0, ScreenGenreRows.affinityScore(items, emptySet(), emptySet()))
    }

}
