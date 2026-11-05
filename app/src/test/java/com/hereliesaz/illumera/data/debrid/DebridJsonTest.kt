package com.hereliesaz.illumera.data.debrid

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.hereliesaz.illumera.data.model.debrid.DebridItem
import com.hereliesaz.illumera.data.model.debrid.DebridProvider
import com.hereliesaz.illumera.data.model.debrid.DebridResult
import org.junit.Test

class DebridJsonTest {

    private fun obj(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test
    fun absentNullAndWrongTypedFieldsReadAsNull() {
        val o = obj("""{"n":null,"o":{},"a":[1]}""")
        listOf("missing", "n", "o", "a").forEach { key ->
            assertNull(key, o.str(key))
            assertNull(key, o.long(key))
            assertNull(key, o.int(key))
            assertNull(key, o.double(key))
            assertNull(key, o.bool(key))
        }
    }

    @Test
    fun primitivesAndNumericStringsAreRead() {
        val o = obj("""{"s":"x","l":12345678901,"d":0.5,"ds":"42","b":true,"bs":"false"}""")
        assertEquals("x", o.str("s"))
        assertEquals(12345678901L, o.long("l"))
        assertEquals(0.5, o.double("d")!!, 0.0)
        assertEquals(42, o.int("ds"))
        assertEquals(true, o.bool("b"))
        assertEquals(false, o.bool("bs"))
        assertNull(o.long("s"))
    }

    @Test
    fun fileNamesMatchRequiresEqualityOrSameEpisode() {
        assertTrue(fileNamesMatch("Show.Name.S01E02.1080p.mkv", "show name s01e02 1080p.mkv"))
        assertTrue(fileNamesMatch("Show.Name.S01E02.mkv", "Show.Name.S01E02.1080p.WEB.mkv"))
        assertFalse(fileNamesMatch("Show.Name.S01E02.mkv", "Show.Name.S01E03.mkv"))
        // Containment without an episode token (e.g. a season pack) is not a match.
        assertFalse(fileNamesMatch("Some.Movie.2020.mkv", "Some.Movie.2020.Collection.Extras"))
        assertFalse(fileNamesMatch(null, "Some.Movie.2020.mkv"))
    }

    private val pack = listOf(
        "Show.S01E01.1080p.mkv", "Show.S01E02.1080p.mkv", "Show.S01E03.1080p.mkv",
        "Show.S01E04.1080p.mkv", "Show.S01E05.1080p.mkv", "Show.S01E06.1080p.mkv",
        "Show.S01E07.1080p.mkv", "readme.nfo"
    )

    @Test
    fun aPackYieldsTheWantedEpisodeNotTheFirstVideo() {
        assertEquals(FilePick.Index(5), chooseWantedFile(pack, WantedFile(season = 1, episode = 6)))
        assertEquals(FilePick.Index(6), chooseWantedFile(pack, WantedFile("Show.S01E07.1080p.mkv", 1, 7)))
    }

    @Test
    fun aPackWithoutTheWantedEpisodeFailsInsteadOfGuessing() {
        assertEquals(FilePick.NoMatch, chooseWantedFile(pack, WantedFile(season = 2, episode = 1)))
    }

    @Test
    fun aSingleVideoOrNoPreferenceKeepsTheUsualChoice() {
        assertEquals(FilePick.NoPreference, chooseWantedFile(listOf("Movie.2020.mkv", "x.nfo"), WantedFile(season = 1, episode = 6)))
        assertEquals(FilePick.NoPreference, chooseWantedFile(pack, null))
        assertEquals(FilePick.NoPreference, chooseWantedFile(pack, WantedFile()))
    }

    @Test
    fun nxeeNamesAreRecognised() {
        assertEquals(FilePick.Index(1), chooseWantedFile(listOf("Show 1x05.mkv", "Show 1x06.mkv", "Show 1x07.mkv"), WantedFile(season = 1, episode = 6)))
    }

    @Test
    fun anItemIsNarrowedToTheWantedFileOrFails() {
        val item = DebridItem(
            id = "1", name = "Show.S01", provider = DebridProvider.ALL_DEBRID,
            directLinks = pack.indices.map { "link$it" }, linkNames = pack
        )
        val narrowed = (item.withWantedFile(WantedFile(season = 1, episode = 6)) as DebridResult.Success).value
        assertEquals(listOf("link5"), narrowed.directLinks)
        val missing = item.withWantedFile(WantedFile(season = 3, episode = 1)) as DebridResult.Failure
        assertEquals(NO_WANTED_FILE, missing.message)
        // Names not listed (e.g. Real-Debrid): left for the provider.
        val unnamed = item.copy(linkNames = emptyList())
        assertEquals(unnamed, (unnamed.withWantedFile(WantedFile(season = 1, episode = 6)) as DebridResult.Success).value)
    }
}
