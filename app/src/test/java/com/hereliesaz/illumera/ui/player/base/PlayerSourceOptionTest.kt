package com.hereliesaz.illumera.ui.player.base

import com.hereliesaz.illumera.data.model.stremio.Stream
import com.hereliesaz.illumera.data.model.stremio.StreamBehaviorHints
import com.hereliesaz.illumera.data.model.stremio.StreamProxyHeaders
import com.hereliesaz.illumera.data.model.stremio.StreamSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerSourceOptionTest {

    @Test
    fun sourceUiProjectionPreservesOriginalAddonStreamContract() {
        val original = Stream(
            name = "Addon source",
            title = "1080p",
            description = "seeds: 7",
            infoHash = "deadbeef",
            fileIdx = 4,
            sources = listOf("tracker:https://tracker.example/announce"),
            subtitles = listOf(
                StreamSubtitle(id = "embedded", lang = "en", url = "/subs/en.srt")
            ),
            behaviorHints = StreamBehaviorHints(
                countryWhitelist = listOf("US"),
                notWebReady = true,
                bingeGroup = "same-release",
                proxyHeaders = StreamProxyHeaders(
                    request = mapOf("Cookie" to "session=abc"),
                    response = mapOf("X-Test" to "ok")
                ),
                videoHash = "video-hash",
                videoSize = 42L,
                filename = "movie.mkv"
            ),
            addonTransportUrl = "https://addon.example",
            addonDisplayName = "Addon"
        )

        val projected = PlayerSourceOption(
            id = "selection-id",
            url = "magnet:?xt=urn:btih:deadbeef",
            label = "1080p",
            addonStream = original
        ).toSourceUiStream()

        // The UI selection id is app metadata; every addon field remains untouched.
        assertEquals("selection-id", projected.sourceSelectionId)
        assertEquals(original.copy(sourceSelectionId = "selection-id"), projected)
        assertNull(projected.url)
        assertEquals("deadbeef", projected.infoHash)
        assertEquals(4, projected.fileIdx)
        assertEquals(original.sources, projected.sources)
        assertEquals(original.subtitles, projected.subtitles)
        assertEquals("session=abc", projected.behaviorHints?.proxyHeaders?.request?.get("Cookie"))
        assertEquals("ok", projected.behaviorHints?.proxyHeaders?.response?.get("X-Test"))
        assertEquals("video-hash", projected.behaviorHints?.videoHash)
        assertEquals(42L, projected.behaviorHints?.videoSize)
        assertEquals("movie.mkv", projected.behaviorHints?.filename)
    }
}
