package com.hereliesaz.illumera.data.stream

import com.hereliesaz.illumera.data.model.StreamQuality
import com.hereliesaz.illumera.data.model.stremio.Stream
import com.hereliesaz.illumera.data.model.stremio.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamParserTest {

    @Test
    fun extractSizeBytesSupportsBinaryUnitsAndDecimals() {
        assertEquals(1_024L, StreamParser.extractSizeBytes("1 KB"))
        assertEquals(1_572_864L, StreamParser.extractSizeBytes("1.5 MB"))
        assertEquals(2_147_483_648L, StreamParser.extractSizeBytes("2GB"))
        assertEquals(1_099_511_627_776L, StreamParser.extractSizeBytes("1 TB"))
    }

    @Test
    fun extractSizeBytesRejectsMissingInvalidAndZeroValues() {
        assertNull(StreamParser.extractSizeBytes(null))
        assertNull(StreamParser.extractSizeBytes(""))
        assertNull(StreamParser.extractSizeBytes("unknown size"))
        assertNull(StreamParser.extractSizeBytes("0 GB"))
    }

    @Test
    fun extractSeedsSupportsCommonAddonFormats() {
        assertEquals(1234, StreamParser.extractSeeds("👤 1,234"))
        assertEquals(98, StreamParser.extractSeeds("Seeds: 98"))
        assertEquals(77, StreamParser.extractSeeds("peers 77"))
        assertEquals(1234, StreamParser.extractSeeds("S 1.234"))
    }

    @Test
    fun extractSeedsDoesNotMistakeSeasonEpisodeMarkerForSeedCount() {
        assertNull(StreamParser.extractSeeds("Show.Name.S02E05.1080p"))
    }

    @Test
    fun extractFormatsRecognizesVideoAudioAnd3dAliases() {
        val formats = StreamParser.extractFormats(
            "DoVi HDR10+ DTS-HD MA Atmos EAC-3 H.265 AV1 Half-SBS"
        )

        assertEquals(setOf("dv", "hdr", "dts", "dolby", "hevc", "av1", "3d"), formats)
    }

    @Test
    fun extractFormatsReturnsEmptyForBlankInput() {
        assertTrue(StreamParser.extractFormats(null).isEmpty())
        assertTrue(StreamParser.extractFormats("  ").isEmpty())
    }

    @Test
    fun parsePrefersBehaviorHintVideoSizeOverTextSize() {
        val parsed = StreamParser.parse(
            Stream(
                title = "1080p 4 GB seeds: 12",
                behaviorHints = StreamBehaviorHints(videoSize = 123_456L)
            )
        )

        assertEquals(123_456L, parsed.sizeBytes)
        assertEquals(12, parsed.seeds)
    }

    @Test
    fun parseChecksQualityFieldsInImplementationPrecedenceOrder() {
        val parsed = StreamParser.parse(
            Stream(
                name = "720p",
                title = "1080p",
                behaviorHints = StreamBehaviorHints(filename = "Movie.2160p.mkv")
            )
        )

        assertEquals(StreamQuality.UHD_4K, parsed.quality)
    }

    @Test
    fun combinedTextIncludesAllAvailableMetadata() {
        val text = StreamParser.combinedText(
            Stream(
                name = "Addon",
                title = "Title",
                description = "Description",
                behaviorHints = StreamBehaviorHints(filename = "file.mkv")
            )
        )

        assertEquals("Addon Title Description file.mkv", text)
    }

    @Test
    fun parseReturnsUnknownAndNullsWhenMetadataIsEmpty() {
        val parsed = StreamParser.parse(Stream())

        assertEquals(StreamQuality.UNKNOWN, parsed.quality)
        assertNull(parsed.sizeBytes)
        assertNull(parsed.seeds)
        assertTrue(parsed.formats.isEmpty())
    }
}
