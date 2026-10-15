package com.hereliesaz.illumera.domain

import com.hereliesaz.illumera.data.model.stremio.CatalogExtra
import com.hereliesaz.illumera.data.model.stremio.CatalogManifest
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
        assertTrue(result.containsAll(listOf("Talk-Show", "Anime")))
        assertFalse(result.contains("English"))
        assertFalse(result.contains("4K"))
        assertFalse(result.contains("Korea"))
        assertFalse(result.contains("Last Month"))
    }

    @Test fun recognizedProviderGenresRemainAvailable() {
        val list = ScreenGenreRows.availableGenres(
            "series",
            listOf(CatalogManifest(
                type = "series", id = "top",
                genres = listOf("Soap", "TV Movie", "Action & Adventure", "Sci-Fi & Fantasy")
            ))
        )
        assertTrue(list.containsAll(
            listOf("Soap", "TV Movie", "Action & Adventure", "Sci-Fi & Fantasy")
        ))
    }

    @Test fun legacyDisabledGenreAliasesCanonicalize() {
        assertEquals("Film-Noir", ScreenGenreRows.canonicalGenre("Film Noir"))
        assertEquals("True Crime", ScreenGenreRows.canonicalGenre("True-Crime"))
        assertEquals("Martial Arts", ScreenGenreRows.canonicalGenre("Martial-Arts"))
        assertEquals("Slice of Life", ScreenGenreRows.canonicalGenre("Slice-of-Life"))
    }

    @Test fun sourceGenreNamesUseTheOfficialAlias() {
        assertEquals("Sci-Fi", ScreenGenreRows.canonicalGenre("Science Fiction"))
        assertEquals("Reality-TV", ScreenGenreRows.canonicalGenre("Reality TV"))
    }
}
