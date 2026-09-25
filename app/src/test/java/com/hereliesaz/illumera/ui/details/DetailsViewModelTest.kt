package com.hereliesaz.illumera.ui.details

import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.ProfileEntity
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.data.model.stremio.Stream
import com.hereliesaz.illumera.data.model.stremio.StreamBehaviorHints
import com.hereliesaz.illumera.data.player.EpisodeBrowseStore
import com.hereliesaz.illumera.data.player.PlaybackTrackSelectionStore
import com.hereliesaz.illumera.data.player.SourceSelectionStore
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.repository.AddonRepository
import com.hereliesaz.illumera.data.repository.SubtitleRepository
import com.hereliesaz.illumera.data.stream.StreamSortingService
import com.hereliesaz.illumera.data.tmdb.TmdbMetadataService
import com.hereliesaz.illumera.data.tmdb.TmdbService
import com.hereliesaz.illumera.data.trakt.TraktSyncManager
import com.hereliesaz.illumera.data.wutch.WutchManager
import com.hereliesaz.illumera.domain.AddonSubtitle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetailsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var dao: AddonDao
    private lateinit var sourceSelectionStore: SourceSelectionStore
    private lateinit var repository: AddonRepository
    private lateinit var subtitleRepository: SubtitleRepository
    private lateinit var profileConfigurationManager: ProfileConfigurationManager

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        dao = mockk(relaxed = true)
        sourceSelectionStore = mockk(relaxed = true)
        repository = mockk()
        subtitleRepository = mockk()
        profileConfigurationManager = mockk()

        every { profileConfigurationManager.getLastActiveProfileId() } returns 1
        coEvery { dao.getProfileById(1) } returns ProfileEntity(
            id = 1,
            name = "Test",
            sourceSortingEnabled = false
        )
        every { sourceSelectionStore.isSourceListDisabled(any()) } returns false
        every { sourceSelectionStore.getExcludedSources(any()) } returns emptySet()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun sameMediaIdReloadsWhenAddonOriginChanges() = runTest(dispatcher) {
        val first = MetaItem(id = "tt1", type = "movie", name = "First Provider")
        val second = MetaItem(id = "tt1", type = "movie", name = "Second Provider")

        coEvery {
            repository.resolveMetaDetails("movie", "tt1", "https://one.example")
        } returns first
        coEvery {
            repository.resolveMetaDetails("movie", "tt1", "https://two.example")
        } returns second
        coEvery { repository.getStreams(any(), any()) } returns emptyList()
        coEvery { subtitleRepository.getSubtitles(any(), any()) } returns emptyList()

        val viewModel = newViewModel()
        viewModel.loadDetails("movie", "tt1", "https://one.example")
        advanceUntilIdle()

        assertEquals("First Provider", viewModel.state.value.meta?.name)
        assertEquals("movie:tt1:https://one.example", viewModel.state.value.contentKey)

        viewModel.loadDetails("movie", "tt1", "https://two.example")
        advanceUntilIdle()

        assertEquals("Second Provider", viewModel.state.value.meta?.name)
        assertEquals("movie:tt1:https://two.example", viewModel.state.value.contentKey)
        coVerify(exactly = 1) {
            repository.resolveMetaDetails("movie", "tt1", "https://one.example")
        }
        coVerify(exactly = 1) {
            repository.resolveMetaDetails("movie", "tt1", "https://two.example")
        }
    }

    @Test
    fun manualSourceSelectionUsesExactStreamRequestIdBeforeEmittingPlayback() = runTest(dispatcher) {
        val streamRequestId = "kitsu:show:episode-17"
        val playbackId = "kitsu:show:1:17"
        val stream = Stream(
            url = "https://cdn.example/video.mkv",
            behaviorHints = StreamBehaviorHints(
                videoHash = "video-hash",
                videoSize = 1234L,
                filename = "episode.mkv"
            )
        )
        val generic = AddonSubtitle(
            id = "generic",
            url = "https://subs.example/generic.srt",
            lang = "en",
            addonName = "Subs"
        )
        val specific = AddonSubtitle(
            id = "specific",
            url = "https://subs.example/specific.srt",
            lang = "en",
            addonName = "Subs"
        )

        coEvery { repository.getStreams("series", streamRequestId) } returns listOf(stream)
        coEvery { subtitleRepository.getSubtitles("series", streamRequestId) } returns listOf(generic)
        coEvery {
            subtitleRepository.getSubtitlesForStream(
                type = "series",
                playbackId = streamRequestId,
                stream = stream,
                fallback = listOf(generic)
            )
        } returns listOf(generic, specific)

        val viewModel = newViewModel()
        viewModel.loadStreams(
            type = "series",
            id = streamRequestId,
            displayTitle = "Episode 17",
            sourceSelectionId = playbackId,
            forceSourcePicker = true
        )
        advanceUntilIdle()

        assertEquals(streamRequestId, viewModel.state.value.activeStreamRequestId)
        assertEquals("series", viewModel.state.value.activeStreamMediaType)
        assertFalse(viewModel.state.value.isLoadingStreams)

        viewModel.selectStreamForPlayback(stream)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            subtitleRepository.getSubtitlesForStream(
                type = "series",
                playbackId = streamRequestId,
                stream = stream,
                fallback = listOf(generic)
            )
        }
        assertSame(stream, viewModel.state.value.autoPlayStream)
        assertEquals(listOf(generic, specific), viewModel.state.value.addonSubtitles)
        assertFalse(viewModel.state.value.isLoadingStreams)
    }

    private fun newViewModel() = DetailsViewModel(
        dao = dao,
        sourceSelectionStore = sourceSelectionStore,
        episodeBrowseStore = mockk<EpisodeBrowseStore>(relaxed = true),
        playbackTrackSelectionStore = mockk<PlaybackTrackSelectionStore>(relaxed = true),
        repository = repository,
        subtitleRepository = subtitleRepository,
        profileConfigurationManager = profileConfigurationManager,
        streamSortingService = StreamSortingService(),
        tmdbService = mockk<TmdbService>(relaxed = true),
        tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true),
        traktSyncManager = mockk<TraktSyncManager>(relaxed = true),
        wutchManager = mockk<WutchManager>(relaxed = true)
    )
}
