package com.hereliesaz.illumera.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AboutContentTest {
    @Test
    fun `intro is the text between the title and the first image`() {
        val md = "# illumera\n\nBased on [Lumera](https://x).\n\nBrowse **content**.\n\n![b](a.png)\n\n## Screenshots\n"
        assertEquals("Based on Lumera.\n\nBrowse content.", AboutContent.introOf(md))
    }

    @Test
    fun `intro stops at the first heading when there is no image`() {
        assertEquals("Hello.", AboutContent.introOf("# T\n\nHello.\n\n## Next\nMore"))
    }

    @Test
    fun `plain text drops heading marks and link targets`() {
        val out = AboutContent.plainText("## Title\n\nSee [docs](http://x) and `code`.")
        assertEquals("Title\n\nSee docs and code.", out)
        assertFalse(out.contains("http"))
    }
}
