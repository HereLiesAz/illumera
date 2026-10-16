package com.hereliesaz.illumera.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ToggleCsvKeyTest {
    @Test
    fun addsAndRemovesKeys() {
        assertEquals("4k,1080p,720p", toggleCsvKey("4k,1080p", "720p"))
        assertEquals("4k,720p", toggleCsvKey("4k, 1080p ,720p", "1080p"))
        assertEquals("sd", toggleCsvKey("", "sd"))
    }
}
