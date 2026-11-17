package com.hereliesaz.illumera.ui.player.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorTextTest {
    @Test fun anUnreadableContainerSaysSoInPlainWords() {
        val text = playbackErrorText("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED", null, null)
        assertTrue(text.startsWith("This source isn't a video file the player can read"))
        assertTrue(text.contains("web page"))
        // The code stays for bug reports.
        assertTrue(text.contains("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED"))
    }

    @Test fun otherErrorsKeepTheirCodeAndDetail() {
        assertEquals("ERROR_CODE_IO_BAD_HTTP_STATUS (HTTP 403)", playbackErrorText("ERROR_CODE_IO_BAD_HTTP_STATUS", 403, null))
        assertEquals("ERROR_CODE_FAILED_RUNTIME_CHECK: boom", playbackErrorText("ERROR_CODE_FAILED_RUNTIME_CHECK", null, "boom"))
        assertEquals("ERROR_CODE_TIMEOUT", playbackErrorText("ERROR_CODE_TIMEOUT", null, null))
    }

    @Test fun onlyTheUnreadableSourceMessageOffersThePage() {
        assertTrue(isUnreadableSourceMessage(playbackErrorText("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED", null, null)))
        assertFalse(isUnreadableSourceMessage("ERROR_CODE_TIMEOUT"))
        assertFalse(isUnreadableSourceMessage(null))
    }
}
