package com.hereliesaz.illumera.domain

import com.hereliesaz.illumera.data.model.stremio.MetaItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingOriginalsTest {
    private val keys = setOf(
        StreamingOriginals.key("Stranger Things", "2016"),
        StreamingOriginals.key("The Crown", "2016")
    )

    private fun item(name: String, releaseInfo: String?) =
        MetaItem(id = "tt1", type = "series", name = name, releaseInfo = releaseInfo)

    @Test fun matchesByTitleIgnoringCaseAndPunctuation() {
        assertTrue(StreamingOriginals.matches(item("STRANGER THINGS!", "2016–2025"), keys))
    }

    @Test fun matchesWithinOneYear() {
        assertTrue(StreamingOriginals.matches(item("The Crown", "2017"), keys))
    }

    @Test fun rejectsDifferentYearOrTitle() {
        assertFalse(StreamingOriginals.matches(item("The Crown", "2005"), keys))
        assertFalse(StreamingOriginals.matches(item("Other Show", "2016"), keys))
    }

    @Test fun missingYearMatchesOnTitleAlone() {
        assertTrue(StreamingOriginals.matches(item("The Crown", null), keys))
    }
}
