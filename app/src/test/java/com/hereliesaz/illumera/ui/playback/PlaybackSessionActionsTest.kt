package com.hereliesaz.illumera.ui.playback

import androidx.lifecycle.SavedStateHandle
import com.hereliesaz.illumera.crash.AppErrors
import com.hereliesaz.illumera.data.debrid.DebridManager
import com.hereliesaz.illumera.data.model.ProfileEntity
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.data.model.stremio.MetaVideo
import com.hereliesaz.illumera.data.model.stremio.Stream
import com.hereliesaz.illumera.data.model.stremio.StreamBehaviorHints
import com.hereliesaz.illumera.data.player.PlaybackTrackSelectionStore
import com.hereliesaz.illumera.data.player.SourceSelectionStore
import com.hereliesaz.illumera.data.queue.QueueItem
import com.hereliesaz.illumera.data.queue.QueueManager
import com.hereliesaz.illumera.data.queue.QueuePreferences
import com.hereliesaz.illumera.data.queue.QueueState
import com.hereliesaz.illumera.data.repository.AddonRepository
import com.hereliesaz.illumera.data.repository.SubtitleRepository
import com.hereliesaz.illumera.data.stream.StreamSortingService
import com.hereliesaz.illumera.data.torrent.TorrentProgress
import com.hereliesaz.illumera.domain.episodePlaybackId
import com.hereliesaz.illumera.ui.details.DetailsViewModel
import com.hereliesaz.illumera.ui.player.PlaybackDurationStatus
import com.hereliesaz.illumera.ui.player.PlayerSessionResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSessionActionsTest {

    private val dispatcher = StandardTestDispatcher()

    private class FakeLauncher : TorrentServiceLauncher {
        val starts = mutableListOf<String>()
        var stops = 0
        override fun start(magnet: String, fileIdx: Int, fileName: String) { starts += magnet }
        override fun stop() { stops++ }
    }

    private class FakeCallbacks : TorrentCallbackRegistry {
        override fun install(
            onReady: (String) -> Unit,
            onError: (String) -> Unit,
            onProgress: (TorrentProgress?) -> Unit
        ) = Unit
        override fun clearIfOwned(onReady: (String) -> Unit) = Unit
    }

    private val launcher = FakeLauncher()
    private val addonRepository: AddonRepository = mockk(relaxed = true)
    private val subtitleRepository: SubtitleRepository = mockk()
    private val sourceSelectionStore: SourceSelectionStore = mockk(relaxed = true)
    private val trackStore: PlaybackTrackSelectionStore = mockk(relaxed = true)
    private val queueManager: QueueManager = mockk(relaxed = true)
    private val debridManager: DebridManager = mockk()

    private val watching = Stream(
        name = "Watching", url = "https://cdn/e1.mkv", addonTransportUrl = "https://addon1",
        behaviorHints = StreamBehaviorHints(bingeGroup = "g1")
    )
    private val nextEpisode = MetaVideo(title = "Two", season = 1, episode = 2)
    private val nextId = "tt1:1:2"
    private val nextTitle = "S1:E2 - Two"

    private val autoplayProfile = ProfileEntity(
        name = "p", autoplayNextEpisode = true, sourceSortingEnabled = false
    )
    private val manualProfile = ProfileEntity(
        name = "p", autoplayNextEpisode = false, autoSelectSource = false, sourceSortingEnabled = false
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(AppErrors)
        every { AppErrors.e(any(), any(), any()) } just runs
        coEvery { subtitleRepository.getSubtitles(any(), any(), any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { subtitleRepository.getSubtitlesForStream(any(), any(), any(), any()) } returns emptyList()
        every { sourceSelectionStore.findPreferredStream(any(), any()) } returns null
        coEvery { addonRepository.getStreams(any(), any(), any(), any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        unmockkObject(AppErrors)
        Dispatchers.resetMain()
    }

    private fun session(): PlaybackSessionViewModel =
        PlaybackSessionViewModel(
            SavedStateHandle(),
            TorrentStreamController(launcher, FakeCallbacks()),
            addonRepository,
            subtitleRepository,
            StreamSortingService(),
            sourceSelectionStore,
            trackStore,
            queueManager,
            debridManager,
            youTubeExtractor = mockk(relaxed = true)
        ).apply {
            playbackSeriesId = "tt1"
            selectedPlaybackId = "tt1:1:1"
            selectedPlaybackType = "series"
            selectedVideoUrl = watching.url!!
            currentStream = watching
            pendingSourceSelection = PendingSourceSelection("tt1:1:1", watching, listOf(watching))
        }

    private fun nextStreams(vararg streams: Stream) {
        coEvery { addonRepository.getStreams("series", nextId, any(), any()) } returns streams.toList()
    }

    private fun TestScope.events(vm: PlaybackSessionViewModel): List<PlaybackNav> {
        val events = mutableListOf<PlaybackNav>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navEvents.toList(events) }
        return events
    }

    // ---- Autoplay hand-off ----

    @Test
    fun `bingeGroup match is chosen before the remembered source`() = runTest(dispatcher) {
        val other = Stream(name = "Other", url = "https://cdn/other.mkv", addonTransportUrl = "https://addon1")
        val binge = Stream(
            name = "Binge", url = "https://cdn/e2.mkv", addonTransportUrl = "https://addon1",
            behaviorHints = StreamBehaviorHints(bingeGroup = "g1")
        )
        nextStreams(other, binge)
        every { sourceSelectionStore.findPreferredStream(nextId, any()) } returns other
        val vm = session()

        vm.autoplayNextEpisode(nextEpisode, null, autoplayProfile)
        runCurrent()

        assertEquals("https://cdn/e2.mkv", vm.selectedVideoUrl)
        assertEquals(nextId, vm.selectedPlaybackId)
        assertEquals(nextTitle, vm.selectedPlaybackTitle)
        assertEquals(binge, vm.currentStream)
        assertFalse(vm.isEpisodeSwitchLoading)
        assertNull(vm.pendingEpisodeSwitch)
    }

    @Test
    fun `remembered source is chosen when nothing shares the bingeGroup`() = runTest(dispatcher) {
        val first = Stream(name = "First", url = "https://cdn/first.mkv")
        val remembered = Stream(name = "Remembered", url = "https://cdn/remembered.mkv")
        nextStreams(first, remembered)
        every { sourceSelectionStore.findPreferredStream(nextId, any()) } returns remembered
        val vm = session()

        vm.autoplayNextEpisode(nextEpisode, null, autoplayProfile)
        runCurrent()

        assertEquals("https://cdn/remembered.mkv", vm.selectedVideoUrl)
    }

    @Test
    fun `remembered source is ignored when the profile does not remember sources`() = runTest(dispatcher) {
        val first = Stream(name = "First", url = "https://cdn/first.mkv")
        val remembered = Stream(name = "Remembered", url = "https://cdn/remembered.mkv")
        nextStreams(first, remembered)
        every { sourceSelectionStore.findPreferredStream(nextId, any()) } returns remembered
        val vm = session()

        vm.autoplayNextEpisode(nextEpisode, null, autoplayProfile.copy(rememberSourceSelection = false))
        runCurrent()

        assertEquals("https://cdn/first.mkv", vm.selectedVideoUrl)
    }

    @Test
    fun `first playable source is chosen with autoplay on`() = runTest(dispatcher) {
        val unplayable = Stream(name = "Nothing")
        val magnet = Stream(name = "Torrent", infoHash = "abc", fileIdx = 4)
        nextStreams(unplayable, magnet)
        val vm = session()

        vm.autoplayNextEpisode(nextEpisode, null, autoplayProfile)
        runCurrent()

        // A torrent: the url waits for the service, which was started for the new episode.
        assertEquals("", vm.selectedVideoUrl)
        assertEquals(nextId, vm.selectedPlaybackId)
        assertTrue(launcher.starts.single().startsWith("magnet:?xt=urn:btih:abc"))
        assertEquals(magnet, vm.currentStream)
    }

    @Test
    fun `first playable source is chosen with auto-select on`() = runTest(dispatcher) {
        nextStreams(Stream(name = "A", url = "https://cdn/a.mkv"))
        val vm = session()

        vm.autoplayNextEpisode(nextEpisode, null, manualProfile.copy(autoSelectSource = true))
        runCurrent()

        assertEquals("https://cdn/a.mkv", vm.selectedVideoUrl)
        assertEquals(0, launcher.starts.size)
    }

    @Test
    fun `without autoplay or auto-select the source list is shown`() = runTest(dispatcher) {
        val a = Stream(name = "A", url = "https://cdn/a.mkv")
        nextStreams(a)
        val vm = session()

        vm.autoplayNextEpisode(nextEpisode, null, manualProfile)
        // Loading placeholder straight away, the list once the sources arrive.
        assertNull(vm.pendingEpisodeSwitch!!.streams)
        runCurrent()

        assertEquals(listOf(a), vm.pendingEpisodeSwitch!!.streams)
        assertEquals("tt1:1:1", vm.selectedPlaybackId)
        assertFalse(vm.isEpisodeSwitchLoading)
    }

    @Test
    fun `no streams shows an empty source list`() = runTest(dispatcher) {
        nextStreams()
        val vm = session()

        vm.autoplayNextEpisode(nextEpisode, null, autoplayProfile)
        runCurrent()

        val pending = vm.pendingEpisodeSwitch!!
        assertEquals(emptyList<Stream>(), pending.streams)
        assertEquals(nextId, pending.playbackId)
        assertFalse(vm.isEpisodeSwitchLoading)
        assertNull(vm.playbackStatus)
        assertEquals("tt1:1:1", vm.selectedPlaybackId)
    }

    @Test
    fun `the finished episode is settled as completed`() = runTest(dispatcher) {
        nextStreams(Stream(name = "A", url = "https://cdn/a.mkv"))
        val vm = session()

        vm.autoplayNextEpisode(nextEpisode, null, autoplayProfile)

        verify { sourceSelectionStore.rememberSelection("tt1:1:1", watching) }
    }

    @Test
    fun `a hand-off stalled for 45s is reported and falls back to the source list`() = runTest(dispatcher) {
        coEvery { addonRepository.getStreams("series", nextId, any(), any()) } coAnswers { awaitCancellation() }
        val vm = session()

        vm.autoplayNextEpisode(nextEpisode, null, autoplayProfile)
        runCurrent()
        assertEquals("Next episode · finding sources for $nextTitle", vm.playbackStatus)

        advanceTimeBy(AUTOPLAY_STALL_MS - 1)
        runCurrent()
        assertNull(vm.pendingEpisodeSwitch)

        advanceTimeBy(2)
        runCurrent()
        verify {
            AppErrors.e("Autoplay", match { it.contains("didn't start within 45s") && it.contains("finding sources") }, null)
        }
        assertEquals(emptyList<Stream>(), vm.pendingEpisodeSwitch!!.streams)
        assertFalse(vm.isEpisodeSwitchLoading)
        assertNull(vm.playbackStatus)
        assertTrue(vm.episodeSwitchJob!!.isCancelled)
    }

    @Test
    fun `an opened episode that draws no frame in 60s falls back to the source list`() = runTest(dispatcher) {
        val a = Stream(name = "A", url = "https://cdn/a.mkv", addonDisplayName = "Addon")
        nextStreams(a)
        val vm = session()

        vm.autoplayNextEpisode(nextEpisode, null, autoplayProfile)
        runCurrent()
        assertEquals(nextId, vm.awaitingFirstFrameId)

        advanceTimeBy(AUTOPLAY_FIRST_FRAME_MS + 1)
        runCurrent()

        verify {
            AppErrors.e("Autoplay", match { it.contains("drew no frame in 60s") && it.contains("direct link from Addon") }, null)
        }
        assertNull(vm.awaitingFirstFrameId)
        assertEquals(listOf(a), vm.pendingEpisodeSwitch!!.streams)
    }

    @Test
    fun `the first frame clears the wait`() = runTest(dispatcher) {
        nextStreams(Stream(name = "A", url = "https://cdn/a.mkv"))
        val vm = session()

        vm.autoplayNextEpisode(nextEpisode, null, autoplayProfile)
        runCurrent()
        vm.onFirstFrame()
        advanceTimeBy(AUTOPLAY_FIRST_FRAME_MS + AUTOPLAY_STALL_MS)
        runCurrent()

        assertNull(vm.awaitingFirstFrameId)
        assertNull(vm.playbackStatus)
        assertNull(vm.pendingEpisodeSwitch)
        verify(exactly = 0) { AppErrors.e(any(), any(), any()) }
    }

    @Test
    fun `leaving the player stops the watchdogs`() = runTest(dispatcher) {
        nextStreams(Stream(name = "A", url = "https://cdn/a.mkv"))
        val vm = session()
        events(vm)

        vm.autoplayNextEpisode(nextEpisode, null, autoplayProfile)
        runCurrent()
        vm.end(PlayerSessionResult(1_000L, null, false, null, null, null), autoplayProfile)
        advanceTimeBy(AUTOPLAY_FIRST_FRAME_MS + AUTOPLAY_STALL_MS)
        runCurrent()

        assertNull(vm.pendingEpisodeSwitch)
        verify(exactly = 0) { AppErrors.e(any(), any(), any()) }
    }

    @Test
    fun `a new hand-off supersedes the old one's watchdog`() = runTest(dispatcher) {
        nextStreams(Stream(name = "A", url = "https://cdn/a.mkv"))
        val vm = session()

        vm.autoplayNextEpisode(nextEpisode, null, autoplayProfile)
        runCurrent()
        val generation = vm.episodeSwitchGeneration
        vm.selectEpisode(MetaVideo(title = "Five", season = 1, episode = 5), null, manualProfile)
        assertEquals(generation + 1, vm.episodeSwitchGeneration)
        advanceTimeBy(AUTOPLAY_FIRST_FRAME_MS + 1)
        runCurrent()

        verify(exactly = 0) { AppErrors.e(any(), any(), any()) }
    }

    // ---- Episode switch ----

    @Test
    fun `dismiss bumps the generation so a late result is ignored`() = runTest(dispatcher) {
        val late = CompletableDeferred<List<Stream>>()
        coEvery { addonRepository.getStreams("series", nextId, any(), any()) } coAnswers { late.await() }
        val vm = session()

        vm.selectEpisode(nextEpisode, null, manualProfile.copy(autoSelectSource = true))
        runCurrent()
        val generation = vm.episodeSwitchGeneration
        vm.dismissEpisodeSwitch()
        assertEquals(generation + 1, vm.episodeSwitchGeneration)

        late.complete(listOf(Stream(name = "A", url = "https://cdn/a.mkv")))
        runCurrent()

        assertEquals("tt1:1:1", vm.selectedPlaybackId)
        assertEquals("https://cdn/e1.mkv", vm.selectedVideoUrl)
        assertNull(vm.pendingEpisodeSwitch)
        assertFalse(vm.isEpisodeSwitchLoading)
    }

    @Test
    fun `picking from the source list opens that source for the pending episode`() = runTest(dispatcher) {
        val a = Stream(name = "A", url = "https://cdn/a.mkv")
        val b = Stream(name = "B", url = "https://cdn/b.mkv")
        nextStreams(a, b)
        val vm = session()
        vm.selectEpisode(nextEpisode, null, manualProfile)
        runCurrent()

        vm.pickEpisodeSwitchSource("https://cdn/b.mkv", manualProfile)
        runCurrent()

        assertEquals("https://cdn/b.mkv", vm.selectedVideoUrl)
        assertEquals(nextId, vm.selectedPlaybackId)
        assertNull(vm.pendingEpisodeSwitch)
        assertEquals(PendingSourceSelection(nextId, b, listOf(a, b)), vm.pendingSourceSelection)
    }

    // ---- Ranked fallback ----

    @Test
    fun `ranked fallback opens the next source`() = runTest(dispatcher) {
        val next = Stream(name = "Next", url = "https://cdn/next.mkv")
        val vm = session()
        vm.pendingSourceSelection = PendingSourceSelection("tt1:1:1", watching, listOf(watching, next))

        vm.onSuspectSource(PlaybackDurationStatus.IMPLAUSIBLY_SHORT, autoplayProfile)
        runCurrent()

        assertEquals("https://cdn/next.mkv", vm.selectedVideoUrl)
        assertEquals(next, vm.currentStream)
        assertEquals("Opening source 2 of 2", vm.playbackStatus)
    }

    @Test
    fun `exhausted ranked fallback returns from the player`() = runTest(dispatcher) {
        val vm = session()
        val events = events(vm)

        vm.onSuspectSource(PlaybackDurationStatus.SOURCE_ERROR, autoplayProfile)
        runCurrent()

        assertEquals(listOf<PlaybackNav>(PlaybackNav.ReturnFromPlayer()), events)
        assertNull(vm.pendingSourceSelection)
        assertNull(vm.playbackStatus)
    }

    @Test
    fun `a debrid download that finishes in time replaces the url`() = runTest(dispatcher) {
        coEvery { debridManager.awaitPlayableSource(any(), any(), 30, any()) } returns "https://debrid/ready.mkv"
        val vm = session()

        vm.onSuspectSource(PlaybackDurationStatus.DEBRID_DOWNLOADING, autoplayProfile.copy(sourceDebridMaxWaitSeconds = 30))
        runCurrent()

        assertEquals("https://debrid/ready.mkv", vm.selectedVideoUrl)
        assertEquals("https://debrid/ready.mkv", vm.currentStream?.url)
        assertEquals("Download finished · opening the video", vm.playbackStatus)
    }

    // ---- Session end ----

    @Test
    fun `end commits the source once playback got going`() = runTest(dispatcher) {
        val vm = session()
        val events = events(vm)

        vm.end(PlayerSessionResult(10_000L, 100_000L, false, watching.url, null, null), autoplayProfile)

        verify { sourceSelectionStore.rememberSelection("tt1:1:1", watching) }
        assertNull(vm.pendingSourceSelection)
        assertEquals("tt1:1:1", vm.detailsResumePlaybackHint)
        assertEquals(1, launcher.stops)
        assertEquals(listOf<PlaybackNav>(PlaybackNav.ReturnFromPlayer()), events)
    }

    @Test
    fun `end clears a remembered source that failed to start`() = runTest(dispatcher) {
        every { sourceSelectionStore.findPreferredStream("tt1:1:1", listOf(watching)) } returns watching
        val vm = session()

        vm.end(PlayerSessionResult(0L, null, false, watching.url, null, null), autoplayProfile)

        verify { sourceSelectionStore.clearSelection("tt1:1:1") }
        verify(exactly = 0) { sourceSelectionStore.rememberSelection(any(), any()) }
        assertNull(vm.detailsResumePlaybackHint)
    }

    @Test
    fun `end does not remember a source when the profile says not to`() = runTest(dispatcher) {
        val vm = session()

        vm.end(PlayerSessionResult(10_000L, null, false, watching.url, null, null), autoplayProfile.copy(rememberSourceSelection = false))

        verify(exactly = 0) { sourceSelectionStore.rememberSelection(any(), any()) }
    }

    @Test
    fun `a trailer ends back on Details`() = runTest(dispatcher) {
        val vm = session()
        val events = events(vm)
        vm.selectedPlaybackId = "trailer_abc"

        vm.end(PlayerSessionResult(3_000L, null, true, null, null, null), autoplayProfile)

        assertEquals(listOf<PlaybackNav>(PlaybackNav.ReturnFromPlayer(trailerEnded = true)), events)
    }

    @Test
    fun `queue advance opens the next item's Details`() = runTest(dispatcher) {
        every { queueManager.state } returns MutableStateFlow(QueueState(preferences = QueuePreferences(enabled = true)))
        every { queueManager.advanceAfterPlayback("tt1:1:1") } returns
            QueueItem(id = "tt2", type = "series", title = "Show Two", poster = "p.jpg", seriesId = "tt2", wholeShow = true)
        val vm = session()
        val events = events(vm)

        vm.end(PlayerSessionResult(100_000L, 100_000L, true, watching.url, null, null), autoplayProfile)
        runCurrent()

        assertEquals(
            listOf<PlaybackNav>(
                PlaybackNav.OpenDetails(
                    movieId = "tt2", movieType = "series", title = "Show Two", poster = "p.jpg",
                    queueAutoPlayId = "tt2", wholeShow = true
                )
            ),
            events
        )
        assertEquals("tt2", vm.selectedPlaybackId)
        assertTrue(vm.queueStartPending)
        assertTrue(vm.queueWholeShowActive)
        coVerify { queueManager.ensureSuggestions() }
    }

    @Test
    fun `queue with nothing next ends queue playback`() = runTest(dispatcher) {
        every { queueManager.state } returns MutableStateFlow(QueueState(preferences = QueuePreferences(enabled = true)))
        every { queueManager.advanceAfterPlayback(any()) } returns null
        val vm = session()
        val events = events(vm)
        vm.queuePlaybackActive = true
        vm.queueWholeShowActive = true

        vm.end(PlayerSessionResult(100_000L, 100_000L, true, null, null, null), autoplayProfile)

        assertFalse(vm.queuePlaybackActive)
        assertFalse(vm.queueWholeShowActive)
        assertEquals(listOf<PlaybackNav>(PlaybackNav.ReturnFromPlayer()), events)
    }

    // ---- Starting playback ----

    @Test
    fun `starting from Details follows the player preference`() = runTest(dispatcher) {
        val vm = session()
        val events = events(vm)
        var persisted = false
        val stream = Stream(name = "A", url = "https://cdn/movie.mkv")

        vm.startFromDetails(
            origin = PlaybackOrigin("movie:tt9:0", "tt9", "Movie", "poster.jpg", ""),
            url = "https://cdn/movie.mkv", playbackId = "tt9", playbackType = "movie",
            playbackTitle = "Movie", stream = stream,
            addonSubtitles = emptyList(), availableStreams = emptyList(), episodes = emptyList(),
            playerPreference = "ask", persistProfileState = { persisted = true }
        )
        runCurrent()

        assertTrue(persisted)
        assertEquals("https://cdn/movie.mkv", vm.selectedVideoUrl)
        assertEquals("tt9", vm.selectedPlaybackId)
        assertEquals(PendingSourceSelection("tt9", stream, listOf(stream)), vm.pendingSourceSelection)
        assertEquals(listOf<PlaybackNav>(PlaybackNav.ShowPlayerChoice), events)
    }

    @Test
    fun `episode ids come from the Details page that started playback, not an earlier title`() = runTest(dispatcher) {
        val vm = session()
        val showA = listOf(MetaVideo(title = "A1", season = 1, episode = 1), MetaVideo(title = "A2", season = 1, episode = 2))
        val showB = listOf(MetaVideo(title = "B1", season = 1, episode = 1), MetaVideo(title = "B2", season = 1, episode = 2))
        // Title A was opened and played first; the viewer then reached show B (through a cast page).
        vm.startFromDetails(
            origin = PlaybackOrigin("series:ttA:0", "ttA", "Show A", "a.jpg", "a-logo"),
            url = "https://cdn/a1.mkv", playbackId = "ttA:1:1", playbackType = "series", playbackTitle = "A1",
            stream = Stream(url = "https://cdn/a1.mkv"), addonSubtitles = emptyList(),
            availableStreams = emptyList(), episodes = showA, playerPreference = null, persistProfileState = {}
        )
        runCurrent()
        val watchingB = Stream(name = "B", url = "https://cdn/b1.mkv")
        vm.startFromDetails(
            origin = PlaybackOrigin("series:ttB:2", "ttB", "Show B", "b.jpg", "b-logo"),
            url = "https://cdn/b1.mkv", playbackId = "ttB:1:1", playbackType = "series", playbackTitle = "B1",
            stream = watchingB, addonSubtitles = emptyList(),
            availableStreams = emptyList(), episodes = showB, playerPreference = null, persistProfileState = {}
        )
        runCurrent()

        assertEquals("ttB", vm.playbackSeriesId)
        assertEquals("Show B", vm.playbackSeriesTitle)
        assertEquals("b-logo", vm.playbackLogo)
        assertEquals("b.jpg", vm.selectedPlaybackPoster)
        val next = vm.nextEpisode()
        assertEquals("B2", next?.title)

        coEvery { addonRepository.getStreams("series", "ttB:1:2", any(), any()) } returns
            listOf(Stream(name = "B2", url = "https://cdn/b2.mkv"))
        vm.autoplayNextEpisode(next!!, null, autoplayProfile)
        runCurrent()

        coVerify { addonRepository.getStreams("series", "ttB:1:2", any(), any()) }
        coVerify(exactly = 0) { addonRepository.getStreams(any(), match { it.startsWith("ttA") }, any(), any()) }
        assertEquals("ttB:1:2", vm.selectedPlaybackId)
        assertEquals("https://cdn/b2.mkv", vm.selectedVideoUrl)

        // The resume hint goes back to show B's page only.
        vm.end(PlayerSessionResult(10_000L, 100_000L, false, "https://cdn/b2.mkv", null, null), autoplayProfile)
        assertEquals("ttB:1:2", vm.resumeHintFor("series:ttB:2"))
        assertNull(vm.resumeHintFor("series:ttA:0"))
    }

    @Test
    fun `a tmdb-keyed series plays under the resolved IMDb id, not the page key`() = runTest(dispatcher) {
        val vm = session()
        // The Details page was opened with a tmdb: key; DetailsViewModel resolved the IMDb id
        // behind it, and the page builds its episode playback ids from that id.
        val pageKeyId = "tmdb:1399"
        val details = DetailsViewModel.DetailsState(
            meta = MetaItem(id = pageKeyId, type = "series", name = "Show"),
            resolvedId = "tt0944947"
        )
        val showId = details.streamId(pageKeyId)
        assertEquals("tt0944947", showId)
        val episodes = listOf(
            MetaVideo(title = "One", season = 1, episode = 1),
            MetaVideo(title = "Two", season = 1, episode = 2)
        )
        val playbackId = episodePlaybackId(showId, episodes[0])

        vm.startFromDetails(
            origin = PlaybackOrigin("series:$pageKeyId:0", showId, "Show", "p.jpg", ""),
            url = "https://cdn/e1.mkv", playbackId = playbackId, playbackType = "series", playbackTitle = "One",
            stream = Stream(url = "https://cdn/e1.mkv"), addonSubtitles = emptyList(),
            availableStreams = emptyList(), episodes = episodes, playerPreference = null, persistProfileState = {}
        )
        runCurrent()
        assertEquals("tt0944947:1:1", vm.selectedPlaybackId)
        assertEquals("tt0944947", vm.playbackSeriesId)

        // The next episode matches by id, and its stream request and saved-progress id use tt….
        val next = vm.nextEpisode()
        assertEquals("Two", next?.title)
        coEvery { addonRepository.getStreams("series", "tt0944947:1:2", any(), any()) } returns
            listOf(Stream(name = "Two", url = "https://cdn/e2.mkv"))
        vm.autoplayNextEpisode(next!!, null, autoplayProfile)
        runCurrent()

        coVerify { addonRepository.getStreams("series", "tt0944947:1:2", any(), any()) }
        coVerify(exactly = 0) { addonRepository.getStreams(any(), match { it.startsWith("tmdb:") }, any(), any()) }
        assertEquals("tt0944947:1:2", vm.selectedPlaybackId)
        assertEquals("https://cdn/e2.mkv", vm.selectedVideoUrl)

        // The resume hint still goes back to the page that started it.
        vm.end(PlayerSessionResult(10_000L, 100_000L, false, "https://cdn/e2.mkv", null, null), autoplayProfile)
        assertEquals("tt0944947:1:2", vm.resumeHintFor("series:$pageKeyId:0"))
    }

    @Test
    fun `a debrid library item plays through the external preference`() = runTest(dispatcher) {
        val vm = session()
        val events = events(vm)

        vm.startResolved("42", "https://debrid/file.mkv", "File", "external")

        assertEquals("debrid_42", vm.selectedPlaybackId)
        assertNotNull(vm.currentStream)
        assertEquals(listOf<PlaybackNav>(PlaybackNav.LaunchExternal("https://debrid/file.mkv")), events)
    }
}
