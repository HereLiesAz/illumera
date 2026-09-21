package com.hereliesaz.illumera.ui.player.base

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExoPlayerHttpFallbackTest {

    @Test
    fun rangeNotSatisfiableKeepsDedicatedRestartPath() {
        assertFalse(shouldAdvanceSourceForHttpStatus(416))
    }

    @Test
    fun rejectedOrUnavailableHttpSourceAdvancesToNextCandidate() {
        listOf(401, 403, 404, 410, 429, 500, 502, 503).forEach { status ->
            assertTrue("Expected HTTP $status to advance source", shouldAdvanceSourceForHttpStatus(status))
        }
    }
}
