package com.hereliesaz.illumera.ui.player.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveStreamDetectionTest {

    @Test
    fun fileExtensionDecidesStreamKind() {
        assertEquals(AdaptiveStreamKind.HLS, detectAdaptiveStreamKind("/live/master.m3u8", null))
        assertEquals(AdaptiveStreamKind.DASH, detectAdaptiveStreamKind("/vod/manifest.MPD", null))
        assertEquals(AdaptiveStreamKind.PROGRESSIVE, detectAdaptiveStreamKind("/dl/Movie.2020.mkv", null))
    }

    @Test
    fun hlsOrDashPathSegmentDoesNotOverrideProgressiveExtension() {
        assertEquals(AdaptiveStreamKind.PROGRESSIVE, detectAdaptiveStreamKind("/hls/Movie.mp4", null))
        assertEquals(AdaptiveStreamKind.PROGRESSIVE, detectAdaptiveStreamKind("/dash/Show.S01E01.mkv", null))
        assertEquals(AdaptiveStreamKind.PROGRESSIVE, detectAdaptiveStreamKind("/playlist/file.webm", null))
    }

    @Test
    fun pathSegmentIsWeakHintWithoutKnownExtension() {
        assertEquals(AdaptiveStreamKind.HLS, detectAdaptiveStreamKind("/hls/abc123", null))
        assertEquals(AdaptiveStreamKind.DASH, detectAdaptiveStreamKind("/dash/abc123", null))
        assertEquals(AdaptiveStreamKind.PROGRESSIVE, detectAdaptiveStreamKind("/hlsfoo/abc", null))
        assertEquals(AdaptiveStreamKind.PROGRESSIVE, detectAdaptiveStreamKind(null, null))
    }

    @Test
    fun explicitMimeTypeWins() {
        assertEquals(AdaptiveStreamKind.HLS, detectAdaptiveStreamKind("/x/video.mp4", "application/x-mpegURL"))
        assertEquals(AdaptiveStreamKind.DASH, detectAdaptiveStreamKind("/x", "application/dash+xml"))
    }

    @Test
    fun primarySubtagFallbackMatchesRegionalVariants() {
        assertTrue(languagePrimarySubtagsMatch("pt-BR", "pt"))
        assertTrue(languagePrimarySubtagsMatch("en", "eng"))
        assertFalse(languagePrimarySubtagsMatch("pt", "es"))
        assertFalse(languagePrimarySubtagsMatch(null, "en"))
    }
}
