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

    @Test fun sourceGenreNamesUseTheOfficialAlias() {
        assertEquals("Sci-Fi", ScreenGenreRows.canonicalGenre("Science Fiction"))
        assertEquals("Reality-TV", ScreenGenreRows.canonicalGenre("Reality TV"))
    }
}
