package com.hereliesaz.illumera.data.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseDatesTest {
    private val today = LocalDate.of(2026, 4, 6)

    @Test
    fun isoTimestampComparesByDateNotString() {
        // "2026-04-06T00:00:00Z" > "2026-04-06" as strings, but it aired today.
        assertTrue(isReleasedBy("2026-04-06T00:00:00.000Z", today))
        assertFalse(isReleasedBy("2026-04-07", today))
        assertTrue(isReleasedBy("2026-04-05", today))
    }

    @Test
    fun missingOrGarbageCountsAsReleased() {
        assertTrue(isReleasedBy(null, today))
        assertTrue(isReleasedBy("soon", today))
    }

    @Test
    fun normalizesToDateOnly() {
        assertEquals("2026-04-06", normalizeReleaseDate("2026-04-06T12:00:00Z"))
        assertNull(normalizeReleaseDate("2026"))
    }
}
