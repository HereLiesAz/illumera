package com.hereliesaz.illumera.data.stream

import com.hereliesaz.illumera.data.model.ProfileEntity
import com.hereliesaz.illumera.data.model.StreamQuality
import com.hereliesaz.illumera.data.model.stremio.Stream
import com.hereliesaz.illumera.data.model.stremio.StreamBehaviorHints
import com.hereliesaz.illumera.data.model.stremio.StreamSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSortingServiceTest {
    private val service = StreamSortingService()

    private fun filter(
        streams: List<Stream>,
        profile: ProfileEntity
    ): List<Stream> = service.sortAndFilter(
        streams = streams,
        enabledQualities = StreamQuality.entries.toSet(),
        excludePhrases = emptyList(),
        addonSortOrders = emptyMap(),
        profile = profile
    )

    @Test
    fun skipSeedless_removesOnlyExplicitZeroSeedSources() {
        val streams = listOf(
            Stream(title = "1080p seeds: 0", url = "zero"),
            Stream(title = "1080p seeds: 8", url = "seeded"),
            Stream(title = "1080p", url = "unknown"),
            Stream(title = "1080p peers: 0", url = "peer-only")
        )

        val result = filter(
            streams,
            ProfileEntity(name = "Test")
        )

        assertEquals(setOf("seeded", "unknown", "peer-only"), result.mapNotNull { it.url }.toSet())
    }

    @Test
    fun skipSeedless_canBeDisabled() {
        val streams = listOf(
            Stream(title = "1080p seeds: 0", url = "zero"),
            Stream(title = "1080p seeds: 8", url = "seeded")
        )

        val result = filter(
            streams,
            ProfileEntity(name = "Test", sourceSkipSeedless = false)
        )

        assertEquals(setOf("zero", "seeded"), result.mapNotNull { it.url }.toSet())
    }

    @Test
    fun forcedPrimaryAudio_acceptsAuthoritativeLanguageAndRejectsMismatch() {
        val streams = listOf(
            Stream(
                title = "1080p release",
                url = "english",
                behaviorHints = StreamBehaviorHints(filename = "Movie.English.1080p.mkv")
            ),
            Stream(
                title = "1080p release",
                url = "spanish",
                behaviorHints = StreamBehaviorHints(filename = "Movie.Spanish.1080p.mkv")
            )
        )

        val result = filter(
            streams,
            ProfileEntity(
                name = "Test",
                preferredAudioLanguage = "en",
                sourceAudioLanguageRequirement = "primary"
            )
        )

        assertEquals(listOf("english"), result.mapNotNull { it.url })
    }

    @Test
    fun forcedAudio_doesNotTreatContentTitleAsAudioMetadataWithoutCue() {
        val streams = listOf(
            Stream(title = "English Drama 1080p", url = "title-only"),
            Stream(title = "1080p English Audio", url = "audio-tagged")
        )

        val result = filter(
            streams,
            ProfileEntity(
                name = "Test",
                preferredAudioLanguage = "en",
                sourceAudioLanguageRequirement = "primary"
            )
        )

        assertEquals(listOf("audio-tagged"), result.mapNotNull { it.url })
    }

    @Test
    fun forcedPrimaryOrSecondaryAudio_acceptsEitherConfiguredLanguage() {
        val streams = listOf(
            Stream(name = "English 1080p", url = "english"),
            Stream(name = "Spanish 1080p", url = "spanish"),
            Stream(name = "French 1080p", url = "french")
        )

        val result = filter(
            streams,
            ProfileEntity(
                name = "Test",
                preferredAudioLanguage = "en",
                preferredAudioLanguageSecondary = "es",
                sourceAudioLanguageRequirement = "primary_or_secondary"
            )
        )

        assertEquals(setOf("english", "spanish"), result.mapNotNull { it.url }.toSet())
    }

    @Test
    fun forcedSubtitle_acceptsAdvertisedSubtitleLanguage() {
        val streams = listOf(
            Stream(
                title = "1080p release",
                url = "spanish-subs",
                subtitles = listOf(StreamSubtitle(lang = "es", url = "https://example.invalid/es.srt"))
            ),
            Stream(
                title = "1080p release",
                url = "english-subs",
                subtitles = listOf(StreamSubtitle(lang = "en", url = "https://example.invalid/en.srt"))
            )
        )

        val result = filter(
            streams,
            ProfileEntity(
                name = "Test",
                preferredSubtitleLanguage = "es",
                sourceSubtitleLanguageRequirement = "primary"
            )
        )

        assertEquals(listOf("spanish-subs"), result.mapNotNull { it.url })
    }

    @Test
    fun forcedSubtitle_acceptsCaptionLanguageCueInSourceText() {
        val result = filter(
            listOf(Stream(title = "1080p Spanish Subs", url = "caption-tagged")),
            ProfileEntity(
                name = "Test",
                preferredSubtitleLanguage = "es",
                sourceSubtitleLanguageRequirement = "primary"
            )
        )

        assertTrue(result.any { it.url == "caption-tagged" })
    }

    @Test
    fun forcedAudio_doesNotTreatBareTwoLetterWordsAsLanguageMetadata() {
        val result = filter(
            listOf(
                Stream(name = "No Way Home 1080p", url = "title-word"),
                Stream(name = "Norwegian 1080p", url = "norwegian")
            ),
            ProfileEntity(
                name = "Test",
                preferredAudioLanguage = "no",
                sourceAudioLanguageRequirement = "primary"
            )
        )

        assertEquals(listOf("norwegian"), result.mapNotNull { it.url })
    }

    @Test
    fun forcedSubtitle_acceptsSubbedCue() {
        val result = filter(
            listOf(Stream(title = "1080p English subbed", url = "subbed")),
            ProfileEntity(
                name = "Test",
                preferredSubtitleLanguage = "en",
                sourceSubtitleLanguageRequirement = "primary"
            )
        )

        assertEquals(listOf("subbed"), result.mapNotNull { it.url })
    }

    @Test
    fun forcedSubtitle_normalizesIso3AndAddonLanguageCodes() {
        val streams = listOf(
            Stream(
                title = "1080p release",
                url = "english",
                subtitles = listOf(StreamSubtitle(lang = "eng", url = "https://example.invalid/en.srt"))
            ),
            Stream(
                title = "1080p release",
                url = "brazilian",
                subtitles = listOf(StreamSubtitle(lang = "pob", url = "https://example.invalid/ptbr.srt"))
            )
        )

        val english = filter(
            streams,
            ProfileEntity(
                name = "Test",
                preferredSubtitleLanguage = "en",
                sourceSubtitleLanguageRequirement = "primary"
            )
        )
        val brazilian = filter(
            streams,
            ProfileEntity(
                name = "Test",
                preferredSubtitleLanguage = "pt-BR",
                sourceSubtitleLanguageRequirement = "primary"
            )
        )

        assertEquals(listOf("english"), english.mapNotNull { it.url })
        assertEquals(listOf("brazilian"), brazilian.mapNotNull { it.url })
    }
    @Test
    fun secondarySeedSortBreaksPrimaryQualityTies() {
        val streams = listOf(
            Stream(title = "1080p 8 GB seeds: 3", url = "large-low-seeds"),
            Stream(title = "1080p 2 GB seeds: 20", url = "small-high-seeds")
        )

        val result = service.sortAndFilter(
            streams = streams,
            enabledQualities = StreamQuality.entries.toSet(),
            excludePhrases = emptyList(),
            addonSortOrders = emptyMap(),
            sortBy = "quality",
            profile = ProfileEntity(
                name = "Test",
                sourceSortSecondary = "seeds",
                sourceSkipSeedless = false
            )
        )

        assertEquals(listOf("small-high-seeds", "large-low-seeds"), result.mapNotNull { it.url })
    }

    @Test
    fun duplicateSecondarySortFallsBackToLegacyTieBreak() {
        val streams = listOf(
            Stream(title = "1080p 2 GB seeds: 5", url = "small"),
            Stream(title = "1080p 8 GB seeds: 5", url = "large")
        )

        val result = service.sortAndFilter(
            streams = streams,
            enabledQualities = StreamQuality.entries.toSet(),
            excludePhrases = emptyList(),
            addonSortOrders = emptyMap(),
            sortBy = "size",
            profile = ProfileEntity(
                name = "Test",
                sourceSortSecondary = "size",
                sourceSkipSeedless = false
            )
        )

        assertEquals(listOf("large", "small"), result.mapNotNull { it.url })
    }

    @Test
    fun seedSortingRanksUnknownAboveExplicitZeroWithoutInventingASeedCount() {
        val streams = listOf(
            Stream(title = "1080p seeds: 12", url = "seeded"),
            Stream(title = "1080p", url = "unknown"),
            Stream(title = "1080p seeds: 0", url = "zero")
        )

        val result = service.sortAndFilter(
            streams = streams,
            enabledQualities = StreamQuality.entries.toSet(),
            excludePhrases = emptyList(),
            addonSortOrders = emptyMap(),
            sortBy = "seeds",
            profile = ProfileEntity(name = "Test", sourceSkipSeedless = false)
        )

        assertEquals(listOf("seeded", "unknown", "zero"), result.mapNotNull { it.url })
    }

}
