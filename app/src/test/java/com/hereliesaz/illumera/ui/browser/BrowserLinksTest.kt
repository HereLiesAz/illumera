package com.hereliesaz.illumera.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserLinksTest {
    @Test
    fun `stremio links become https manifest installs`() {
        assertEquals(
            LinkAction.InstallAddon("https://a.example/cfg/manifest.json"),
            classifyLink("stremio://a.example/cfg/manifest.json")
        )
        assertEquals(
            LinkAction.InstallAddon("https://a.example/manifest.json"),
            classifyLink("stremio://a.example")
        )
    }

    @Test
    fun `a manifest over https is caught, a normal page loads`() {
        assertEquals(
            LinkAction.InstallAddon("https://a.example/x/manifest.json?k=1"),
            classifyLink("https://a.example/x/manifest.json?k=1")
        )
        assertEquals(LinkAction.Load, classifyLink("https://a.example/configure"))
    }

    @Test
    fun `other schemes are blocked`() {
        assertEquals(LinkAction.Block, classifyLink("intent://scan/#Intent;end"))
        assertEquals(LinkAction.Block, classifyLink("market://details?id=x"))
        assertEquals(LinkAction.Block, classifyLink("file:///sdcard/x"))
        assertEquals(LinkAction.Load, classifyLink("about:blank"))
    }

    @Test
    fun `only http and https open in the browser`() {
        assertTrue(canOpenInBrowser("https://x.example"))
        assertTrue(canOpenInBrowser("http://x.example"))
        assertFalse(canOpenInBrowser("stremio://x.example"))
        assertFalse(canOpenInBrowser("javascript:alert(1)"))
    }

    @Test
    fun `host and cursor step helpers`() {
        assertEquals("real-debrid.com", displayHost("https://www.real-debrid.com/apitoken?x=1"))
        assertTrue(cursorStep(0) < cursorStep(5))
        assertTrue(cursorStep(5) < cursorStep(20))
    }
}
