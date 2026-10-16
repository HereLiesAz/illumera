package com.hereliesaz.illumera.data.debrid

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
}
