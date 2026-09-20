package com.hereliesaz.illumera.data.repository

import com.google.gson.JsonParser
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.AddonEntity
import com.hereliesaz.illumera.data.model.stremio.Manifest
import com.hereliesaz.illumera.data.model.stremio.Stream
import com.hereliesaz.illumera.data.model.stremio.StreamBehaviorHints
import com.hereliesaz.illumera.data.model.stremio.StreamSubtitle
import com.hereliesaz.illumera.data.model.stremio.SubtitleResponse
import com.hereliesaz.illumera.data.remote.StremioApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleRepositoryTest {

    @Test
    fun seriesRequestPreservesEpisodeIdAndEncodesVideoMetadata() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        val addon = addon(url = "https://subs.example", nickname = "My Subs")
        every { dao.getAllAddons() } returns flowOf(listOf(addon))
        coEvery { api.getManifest("https://subs.example/manifest.json") } returns Manifest(
            resources = listOf(JsonParser.parseString("\"subtitles\""))
        )
        val expectedUrl = "https://subs.example/subtitles/series/tt123:2:4/videoHash=abc%20123&videoSize=456&filename=My%20File.mkv.json"
        coEvery { api.getSubtitles(expectedUrl) } returns SubtitleResponse(
            listOf(StreamSubtitle(id = "s1", lang = "EN_us", url = "/captions/en.srt"))
        )

        val result = SubtitleRepository(api, dao).getSubtitles(
            type = " SERIES ",
            playbackId = "tt123:2:4",
            videoHash = " abc 123 ",
            videoSize = 456,
            filename = " My File.mkv "
        )

        assertEquals(1, result.size)
        assertEquals("s1", result.single().id)
        assertEquals("en-us", result.single().lang)
        assertEquals("My Subs", result.single().addonName)
        assertEquals("https://subs.example/captions/en.srt", result.single().url)
        coVerify(exactly = 1) { api.getSubtitles(expectedUrl) }
    }

    @Test
    fun selectedStreamHintsTriggerSourceAwareSubtitleQueryAndMergeFallback() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        every { dao.getAllAddons() } returns flowOf(listOf(addon()))
        coEvery { api.getManifest(any()) } returns Manifest(
            resources = listOf(JsonParser.parseString("\"subtitles\""))
        )
        val expectedUrl = "https://addon.example/subtitles/movie/tt123/videoHash=hash123&videoSize=9876&filename=Movie%20File.mkv.json"
        coEvery { api.getSubtitles(expectedUrl) } returns SubtitleResponse(
            listOf(StreamSubtitle(id = "specific", lang = "en", url = "https://cdn.example/specific.srt"))
        )
        val fallback = listOf(
            com.hereliesaz.illumera.domain.AddonSubtitle(
                id = "generic",
                url = "https://cdn.example/generic.srt",
                lang = "en",
                addonName = "Addon"
            )
        )

        val result = SubtitleRepository(api, dao).getSubtitlesForStream(
            type = "movie",
            playbackId = "tt123",
            stream = Stream(
                behaviorHints = StreamBehaviorHints(
                    videoHash = " hash123 ",
                    videoSize = 9876L,
                    filename = " Movie File.mkv "
                )
            ),
            fallback = fallback
        )

        assertEquals(
            setOf("https://cdn.example/generic.srt", "https://cdn.example/specific.srt"),
            result.map { it.url }.toSet()
        )
        coVerify(exactly = 1) { api.getSubtitles(expectedUrl) }
    }

    @Test
    fun sourceAwareDuplicateWinsOverGenericSubtitleIdentity() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        every { dao.getAllAddons() } returns flowOf(listOf(addon()))
        coEvery { api.getManifest(any()) } returns Manifest(
            resources = listOf(JsonParser.parseString("\"subtitles\""))
        )
        val expectedUrl = "https://addon.example/subtitles/movie/tt123/videoHash=hash123.json"
        coEvery { api.getSubtitles(expectedUrl) } returns SubtitleResponse(
            listOf(StreamSubtitle(id = "specific-id", lang = "en", url = "https://cdn.example/same.srt"))
        )
        val fallback = listOf(
            com.hereliesaz.illumera.domain.AddonSubtitle(
                id = "generic-id",
                url = "https://cdn.example/same.srt",
                lang = "en",
                addonName = "Addon"
            )
        )

        val result = SubtitleRepository(api, dao).getSubtitlesForStream(
            type = "movie",
            playbackId = "tt123",
            stream = Stream(behaviorHints = StreamBehaviorHints(videoHash = "hash123")),
            fallback = fallback
        )

        assertEquals(1, result.size)
        assertEquals("specific-id", result.single().id)
    }

    @Test
    fun liveSourceResolutionFetchesFreshGenericAndSourceAwareVariants() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        every { dao.getAllAddons() } returns flowOf(listOf(addon()))
        coEvery { api.getManifest(any()) } returns Manifest(
            resources = listOf(JsonParser.parseString("\"subtitles\""))
        )
        val genericUrl = "https://addon.example/subtitles/movie/tt123.json"
        val sourceUrl = "https://addon.example/subtitles/movie/tt123/videoHash=hash123.json"
        coEvery { api.getSubtitles(genericUrl) } returns SubtitleResponse(
            listOf(StreamSubtitle(id = "generic", lang = "en", url = "https://cdn.example/generic.srt"))
        )
        coEvery { api.getSubtitles(sourceUrl) } returns SubtitleResponse(
            listOf(StreamSubtitle(id = "specific", lang = "en", url = "https://cdn.example/specific.srt"))
        )

        val result = SubtitleRepository(api, dao).getSubtitlesForStream(
            type = "movie",
            playbackId = "tt123",
            stream = Stream(behaviorHints = StreamBehaviorHints(videoHash = "hash123"))
        )

        assertEquals(
            setOf("https://cdn.example/generic.srt", "https://cdn.example/specific.srt"),
            result.map { it.url }.toSet()
        )
        coVerify(exactly = 1) { api.getSubtitles(genericUrl) }
        coVerify(exactly = 1) { api.getSubtitles(sourceUrl) }
    }

    @Test
    fun liveSourceWithoutHintsFetchesFreshGenericSubtitles() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        every { dao.getAllAddons() } returns flowOf(listOf(addon()))
        coEvery { api.getManifest(any()) } returns Manifest(
            resources = listOf(JsonParser.parseString("\"subtitles\""))
        )
        val genericUrl = "https://addon.example/subtitles/movie/tt123.json"
        coEvery { api.getSubtitles(genericUrl) } returns SubtitleResponse(
            listOf(StreamSubtitle(id = "generic", lang = "en", url = "https://cdn.example/generic.srt"))
        )

        val result = SubtitleRepository(api, dao).getSubtitlesForStream(
            type = "movie",
            playbackId = "tt123",
            stream = Stream(title = "1080p")
        )

        assertEquals(listOf("https://cdn.example/generic.srt"), result.map { it.url })
        coVerify(exactly = 1) { api.getSubtitles(genericUrl) }
    }

    @Test
    fun sourceAwareSubtitleCancellationPropagatesToCaller() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        every { dao.getAllAddons() } returns flowOf(listOf(addon()))
        coEvery { api.getManifest(any()) } returns Manifest(
            resources = listOf(JsonParser.parseString("\"subtitles\""))
        )
        coEvery { api.getSubtitles(any()) } throws CancellationException("cancel source selection")

        try {
            SubtitleRepository(api, dao).getSubtitlesForStream(
                type = "movie",
                playbackId = "tt123",
                stream = Stream(behaviorHints = StreamBehaviorHints(videoHash = "hash123")),
                fallback = emptyList()
            )
            throw AssertionError("Expected CancellationException")
        } catch (_: CancellationException) {
            // Cancellation must escape so the caller cannot commit stale playback state.
        }
    }

    @Test
    fun selectedStreamWithoutSubtitleHintsReusesFallbackWithoutNetworkQuery() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        val fallback = listOf(
            com.hereliesaz.illumera.domain.AddonSubtitle(
                id = "generic",
                url = "https://cdn.example/generic.srt",
                lang = "en",
                addonName = "Addon"
            )
        )

        val result = SubtitleRepository(api, dao).getSubtitlesForStream(
            type = "movie",
            playbackId = "tt123",
            stream = Stream(title = "1080p"),
            fallback = fallback
        )

        assertEquals(fallback, result)
        coVerify(exactly = 0) { api.getManifest(any()) }
        coVerify(exactly = 0) { api.getSubtitles(any()) }
    }

    @Test
    fun manifestRulesSkipUnsupportedTypeAndIdPrefix() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        val addon = addon()
        every { dao.getAllAddons() } returns flowOf(listOf(addon))
        coEvery { api.getManifest(any()) } returns Manifest(
            resources = listOf(
                JsonParser.parseString(
                    """{"name":"subtitles","types":["series"],"idPrefixes":["tt"]}"""
                )
            )
        )
        val repository = SubtitleRepository(api, dao)

        assertTrue(repository.getSubtitles("movie", "tt123").isEmpty())
        assertTrue(repository.getSubtitles("series", "kitsu:123:1:1").isEmpty())

        coVerify(exactly = 0) { api.getSubtitles(any()) }
    }

    @Test
    fun manifestFailureFallsBackToQueryingAddon() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        val addon = addon()
        every { dao.getAllAddons() } returns flowOf(listOf(addon))
        coEvery { api.getManifest(any()) } throws IllegalStateException("manifest unavailable")
        coEvery { api.getSubtitles("https://addon.example/subtitles/movie/tt123.json") } returns SubtitleResponse(
            listOf(StreamSubtitle(lang = "fr", url = "https://cdn.example/fr.vtt"))
        )

        val result = SubtitleRepository(api, dao).getSubtitles("movie", "tt123")

        assertEquals(1, result.size)
        assertEquals("fr", result.single().lang)
        assertEquals("https://cdn.example/fr.vtt", result.single().url)
    }

    @Test
    fun disabledAddonsAreIgnoredAndEmptyAddonListReturnsImmediately() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        every { dao.getAllAddons() } returns flowOf(listOf(addon(enabled = false)))

        assertTrue(SubtitleRepository(api, dao).getSubtitles("movie", "tt123").isEmpty())

        coVerify(exactly = 0) { api.getManifest(any()) }
        coVerify(exactly = 0) { api.getSubtitles(any()) }
    }

    @Test
    fun invalidSchemesAndDuplicateSubtitleRowsAreRemoved() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        every { dao.getAllAddons() } returns flowOf(listOf(addon()))
        coEvery { api.getManifest(any()) } returns Manifest(
            resources = listOf(JsonParser.parseString("\"subtitle\""))
        )
        coEvery { api.getSubtitles(any()) } returns SubtitleResponse(
            listOf(
                StreamSubtitle(id = "one", lang = "EN", url = "https://cdn.example/a.srt"),
                StreamSubtitle(id = "two", lang = "en", url = "https://cdn.example/a.srt"),
                StreamSubtitle(id = "bad", lang = "en", url = "ftp://cdn.example/a.srt"),
                StreamSubtitle(id = "relative", lang = "es", url = "relative/es.srt")
            )
        )

        val result = SubtitleRepository(api, dao).getSubtitles("movie", "tt123")

        assertEquals(2, result.size)
        assertEquals(setOf("https://cdn.example/a.srt", "https://addon.example/relative/es.srt"), result.map { it.url }.toSet())
    }

    @Test
    fun manifestCapabilityIsCachedAcrossRequests() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        every { dao.getAllAddons() } returns flowOf(listOf(addon()))
        coEvery { api.getManifest(any()) } returns Manifest(
            resources = listOf(JsonParser.parseString("\"subtitles\""))
        )
        coEvery { api.getSubtitles(any()) } returns SubtitleResponse()
        val repository = SubtitleRepository(api, dao)

        repository.getSubtitles("movie", "tt1")
        repository.getSubtitles("movie", "tt2")

        coVerify(exactly = 1) { api.getManifest("https://addon.example/manifest.json") }
        coVerify(exactly = 2) { api.getSubtitles(any()) }
    }

    private fun addon(
        url: String = "https://addon.example",
        nickname: String? = null,
        enabled: Boolean = true
    ) = AddonEntity(
        transportUrl = url,
        id = "addon",
        name = "Addon",
        version = "1.0.0",
        description = null,
        iconUrl = null,
        isEnabled = enabled,
        nickname = nickname
    )
}
