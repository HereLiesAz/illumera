package com.hereliesaz.illumera.data.web

import com.hereliesaz.illumera.data.trailer.YouTubeExtractor
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPageVideoResolverTest {
    private val page = "https://site.example/watch/42"

    @Test fun aMetaTagThatNamesTheVideoIsFound() {
        val html = """<head><meta property="og:video" content="https://cdn.example/v/42.mp4"></head>"""
        assertEquals(listOf("https://cdn.example/v/42.mp4"), findVideoCandidates(html, page))
    }

    @Test fun aRelativeSourceInsideAVideoTagResolvesAgainstThePage() {
        val html = """<video controls><source src="/media/42.mp4" type="video/mp4"></video>"""
        assertEquals(listOf("https://site.example/media/42.mp4"), findVideoCandidates(html, page))
    }

    @Test fun anEscapedAddressInScriptJsonIsUnescaped() {
        val html = """<script type="application/ld+json">{"contentUrl":"https:\/\/cdn.example\/v\/1080p.mp4?t=1&k=2"}</script>"""
        assertEquals(listOf("https://cdn.example/v/1080p.mp4?t=1&k=2"), findVideoCandidates(html, page))
    }

    @Test fun aProtocolRelativeHlsAddressIsFound() {
        val html = """player.setup({"hls":"//cdn.example/stream/master.m3u8"})"""
        assertEquals(listOf("https://cdn.example/stream/master.m3u8"), findVideoCandidates(html, page))
    }

    @Test fun hlsBeatsMp4AndTheBiggerQualityBeatsTheSmaller() {
        assertEquals(
            "https://cdn.example/a/index.m3u8",
            pickBestVideo(listOf("https://cdn.example/a/720.mp4", "https://cdn.example/a/index.m3u8"))
        )
        assertEquals(
            "https://cdn.example/a/1080p.mp4",
            pickBestVideo(listOf("https://cdn.example/a/480p.mp4", "https://cdn.example/a/1080p.mp4", "https://cdn.example/a/720p.mp4"))
        )
    }

    @Test fun previewsThumbnailsAndAdvertsAreNotTheVideo() {
        assertEquals(
            "https://cdn.example/full/42.mp4",
            pickBestVideo(listOf("https://cdn.example/preview/42.mp4", "https://cdn.example/full/42.mp4", "https://cdn.example/ads/x.mp4"))
        )
        assertNull(pickBestVideo(listOf("https://cdn.example/preview/42.mp4", "https://cdn.example/thumb/1.mp4")))
        assertNull(pickBestVideo(emptyList()))
    }

    @Test fun anEmbeddedPlayerFrameIsListedButNotAds() {
        val html = """<iframe src="/embed/42"></iframe><iframe src="https://ads.example/ads/frame"></iframe>"""
        assertEquals(listOf("https://site.example/embed/42"), findEmbedFrames(html, page))
    }

    @Test fun youTubeAddressesAreRecognised() {
        assertTrue(isYouTubeAddress("https://www.youtube.com/watch?v=abc"))
        assertTrue(isYouTubeAddress("https://youtu.be/abc"))
        assertTrue(isYouTubeAddress("https://www.youtube-nocookie.com/embed/abc"))
        assertFalse(isYouTubeAddress("https://notyoutube.example/watch"))
    }

    private val resolver = WebPageVideoResolver(OkHttpClient(), mockk<YouTubeExtractor>(relaxed = true))

    @Test fun thePageIsFetchedAndTheVideoComesBackWithTheRefererItNeeds() = runBlocking {
        val result = resolver.resolveFrom(page, emptyMap(), 1) { url, _ ->
            if (url == page) """<video><source src="https://cdn.example/v/42.mp4"></video>""" else null
        }
        assertEquals("https://cdn.example/v/42.mp4", result?.url)
        assertEquals(page, result?.headers?.get("Referer"))
        assertEquals("https://site.example", result?.headers?.get("Origin"))
    }

    @Test fun aPageWithoutAVideoFollowsItsPlayerFrameOnce() = runBlocking {
        val asked = mutableListOf<Pair<String, Map<String, String>>>()
        val result = resolver.resolveFrom(page, emptyMap(), 1) { url, headers ->
            asked += url to headers
            when (url) {
                page -> """<iframe src="/embed/42"></iframe>"""
                "https://site.example/embed/42" -> """{"file":"https://cdn.example/stream/master.m3u8"}"""
                else -> null
            }
        }
        assertEquals("https://cdn.example/stream/master.m3u8", result?.url)
        // The frame was fetched as if from the page, and the video is asked for with the frame as Referer.
        assertEquals(page, asked[1].second["Referer"])
        assertEquals("https://site.example/embed/42", result?.headers?.get("Referer"))
    }

    @Test fun aFrameInsideAFrameIsNotFollowed() = runBlocking {
        val result = resolver.resolveFrom(page, emptyMap(), 1) { url, _ ->
            when (url) {
                page -> """<iframe src="/a"></iframe>"""
                "https://site.example/a" -> """<iframe src="/b"></iframe>"""
                "https://site.example/b" -> """{"file":"https://cdn.example/v.mp4"}"""
                else -> null
            }
        }
        assertNull(result)
    }

    @Test fun anUnreachablePageResolvesToNothing() = runBlocking {
        assertNull(resolver.resolveFrom(page, emptyMap(), 1) { _, _ -> null })
    }
}
