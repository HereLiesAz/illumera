package com.hereliesaz.illumera.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppErrorsTest {
    @Test
    fun urlsKeepOnlySchemeAndHost() {
        assertEquals(
            "Stream error: https://real-debrid.com/<redacted> failed",
            redact("Stream error: https://real-debrid.com/d/ABCDEF123TOKEN/movie.mkv?x=1 failed")
        )
        assertEquals(
            "http://host.example/<redacted>",
            redact("http://user:secret@host.example/path")
        )
    }

    @Test
    fun magnetLinksAreDropped() {
        assertEquals("open magnet:<redacted>", redact("open magnet:?xt=urn:btih:abc&dn=Movie"))
    }

    @Test
    fun causeChainMessagesAreRedactedAndStacksKept() {
        val inner = IllegalStateException("GET https://api.example/v1/key123/stream")
        val outer = RuntimeException("wrapped", inner)
        val copy = redacted(outer)
        assertEquals("java.lang.RuntimeException: wrapped", copy.toString())
        assertEquals("java.lang.IllegalStateException: GET https://api.example/<redacted>", copy.cause.toString())
        assertFalse(copy.cause.toString().contains("key123"))
        assertEquals(inner.stackTrace.toList(), copy.cause!!.stackTrace.toList())
    }
}
