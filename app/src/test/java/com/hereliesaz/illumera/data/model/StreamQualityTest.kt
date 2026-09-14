package com.hereliesaz.illumera.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamQualityTest {

    @Test
    fun explicitResolutionWinsOverFuzzyOrCamLabels() {
        assertEquals(StreamQuality.FHD_1080P, StreamQuality.fromString("CAM UHD 1080p"))
        assertEquals(StreamQuality.HD_720P, StreamQuality.fromString("4K CAM 720p"))
        assertEquals(StreamQuality.SD_480P, StreamQuality.fromString("HD 480p"))
    }

    @Test
    fun parsesExplicitResolutionVariants() {
        assertEquals(StreamQuality.UHD_4K, StreamQuality.fromString("movie.2160p.web-dl"))
        assertEquals(StreamQuality.UHD_4K, StreamQuality.fromString("2160i"))
        assertEquals(StreamQuality.FHD_1080P, StreamQuality.fromString("1080"))
        assertEquals(StreamQuality.HD_720P, StreamQuality.fromString("720P"))
        assertEquals(StreamQuality.SD_480P, StreamQuality.fromString("480i"))
    }

    @Test
    fun parsesFuzzyQualityLabels() {
        assertEquals(StreamQuality.UHD_4K, StreamQuality.fromString("Ultra HD BluRay"))
        assertEquals(StreamQuality.UHD_4K, StreamQuality.fromString("UHD release"))
        assertEquals(StreamQuality.FHD_1080P, StreamQuality.fromString("FHD release"))
        assertEquals(StreamQuality.HD_720P, StreamQuality.fromString("HD release"))
        assertEquals(StreamQuality.SD_480P, StreamQuality.fromString("DVDRip"))
        assertEquals(StreamQuality.CAM, StreamQuality.fromString("HDTS"))
    }

    @Test
    fun tokenBoundariesAvoidResolutionFalsePositives() {
        assertEquals(StreamQuality.UNKNOWN, StreamQuality.fromString("x2160x"))
        assertEquals(StreamQuality.UNKNOWN, StreamQuality.fromString("10800p"))
        assertEquals(StreamQuality.UNKNOWN, StreamQuality.fromString("cambridge documentary"))
    }

    @Test
    fun keyConversionsRoundTripEveryQuality() {
        StreamQuality.entries.forEach { quality ->
            val key = StreamQuality.toKey(quality)
            assertEquals(quality, StreamQuality.fromKey(key))
        }
    }

    @Test
    fun unknownKeyReturnsNull() {
        assertNull(StreamQuality.fromKey("8k"))
        assertNull(StreamQuality.fromKey(""))
    }
}
