package com.hereliesaz.illumera.ui.home

import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.data.model.stremio.Stream
import com.hereliesaz.illumera.data.model.stremio.StreamBehaviorHints
import com.hereliesaz.illumera.data.model.stremio.StreamProxyHeaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPreviewTest {
    @Test
    fun `an addon's own non-IMDb title is a web video candidate`() {
        assertTrue(isWebVideoCandidate(MetaItem(id = "yt:abc", addonBaseUrl = "https://a.example")))
        assertFalse(isWebVideoCandidate(MetaItem(id = "tt123", addonBaseUrl = "https://a.example")))
        assertFalse(isWebVideoCandidate(MetaItem(id = "yt:abc")))
    }

    @Test
    fun `a YouTube id is a YouTube source`() {
        assertEquals(
            WebPreviewSource.YouTube("dQw4w9WgXcQ"),
            webPreviewSource(listOf(Stream(ytId = "dQw4w9WgXcQ")))
        )
    }

    @Test
    fun `a youtube address in url is a YouTube source`() {
        assertEquals(
            WebPreviewSource.YouTube("https://www.youtube.com/watch?v=x"),
            webPreviewSource(listOf(Stream(url = "https://www.youtube.com/watch?v=x")))
        )
    }

    @Test
    fun `a plain web address plays as it is`() {
        assertEquals(
            WebPreviewSource.Direct("https://cdn.example/v.mp4"),
            webPreviewSource(listOf(Stream(url = "https://cdn.example/v.mp4")))
        )
    }

    @Test
    fun `torrents and header-gated or not-web-ready streams are skipped`() {
        val skipped = listOf(
            Stream(infoHash = "abc", url = "https://x.example/v.mp4"),
            Stream(url = "https://x.example/h.mp4", behaviorHints = StreamBehaviorHints(
                proxyHeaders = StreamProxyHeaders(request = mapOf("Referer" to "https://x.example")))),
            Stream(url = "https://x.example/n.mkv", behaviorHints = StreamBehaviorHints(notWebReady = true))
        )
        assertNull(webPreviewSource(skipped))
        assertEquals(
            WebPreviewSource.Direct("https://x.example/ok.mp4"),
            webPreviewSource(skipped + Stream(url = "https://x.example/ok.mp4"))
        )
    }

    @Test
    fun `the preview starts halfway, once the length is known`() {
        assertEquals(30_000L, previewMidpointMs(60_000L))
        assertNull(previewMidpointMs(0L))
        assertNull(previewMidpointMs(-1L))
    }
}
