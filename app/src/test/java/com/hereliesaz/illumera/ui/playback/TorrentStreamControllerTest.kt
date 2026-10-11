package com.hereliesaz.illumera.ui.playback

import androidx.lifecycle.SavedStateHandle
import com.hereliesaz.illumera.data.model.stremio.Stream
import com.hereliesaz.illumera.data.torrent.TorrentProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.mockk.mockk
import com.hereliesaz.illumera.ui.player.PlayerSessionResult

class TorrentStreamControllerTest {

    private class FakeLauncher : TorrentServiceLauncher {
        val starts = mutableListOf<Triple<String, Int, String>>()
        var stops = 0
        override fun start(magnet: String, fileIdx: Int, fileName: String) {
            starts += Triple(magnet, fileIdx, fileName)
        }
        override fun stop() { stops++ }
    }

    /** Stands in for TorrentService's static callback fields. */
    private class FakeCallbacks : TorrentCallbackRegistry {
        var ready: ((String) -> Unit)? = null
        var error: ((String) -> Unit)? = null
        var progress: ((TorrentProgress?) -> Unit)? = null
        override fun install(
            onReady: (String) -> Unit,
            onError: (String) -> Unit,
            onProgress: (TorrentProgress?) -> Unit
        ) {
            ready = onReady; error = onError; progress = onProgress
        }
        override fun clearIfOwned(onReady: (String) -> Unit) {
            if (ready === onReady) { ready = null; error = null; progress = null }
        }
    }

    private val launcher = FakeLauncher()
    private val callbacks = FakeCallbacks()
    private val controller = TorrentStreamController(launcher, callbacks)

    private fun session(handle: SavedStateHandle = SavedStateHandle()) =
        newSession(handle, controller)

    private fun newSession(handle: SavedStateHandle, controller: TorrentStreamController) =
        PlaybackSessionViewModel(
            handle, controller,
            addonRepository = mockk(relaxed = true),
            subtitleRepository = mockk(relaxed = true),
            streamSortingService = mockk(relaxed = true),
            sourceSelectionStore = mockk(relaxed = true),
            playbackTrackSelectionStore = mockk(relaxed = true),
            queueManager = mockk(relaxed = true),
            debridManager = mockk(relaxed = true),
            youTubeExtractor = mockk(relaxed = true)
        )

    @Test
    fun `start launches the service and shows starting progress`() {
        val vm = session()
        vm.startTorrent("magnet:?xt=urn:btih:abc", 3, "ep.mkv")
        assertEquals(listOf(Triple("magnet:?xt=urn:btih:abc", 3, "ep.mkv")), launcher.starts)
        assertEquals("Starting torrent", vm.torrentProgress?.status)
    }

    @Test
    fun `service callbacks write into the session`() {
        val vm = session()
        vm.startTorrent("magnet:a", -1, "")
        callbacks.progress!!(TorrentProgress("Fetching", peers = 4))
        assertEquals(4, vm.torrentProgress?.peers)
        callbacks.ready!!("http://127.0.0.1:8090/stream")
        assertNull(vm.torrentProgress)
        assertEquals("http://127.0.0.1:8090/stream", vm.selectedVideoUrl)
    }

    @Test
    fun `a stream ready after the screen is recreated still reaches the same session`() {
        val vm = session()
        vm.startTorrent("magnet:a", -1, "")
        // Rotation: the composition is rebuilt, the activity-scoped session is not.
        callbacks.ready!!("http://local/a")
        assertEquals("http://local/a", vm.selectedVideoUrl)
    }

    @Test
    fun `errors clear progress unless asked to keep it`() {
        val vm = session()
        val errors = mutableListOf<String>()
        vm.startTorrent("magnet:a", -1, "", onError = { errors += it })
        callbacks.error!!("no peers")
        assertNull(vm.torrentProgress)
        assertEquals(listOf("no peers"), errors)

        vm.startTorrent("magnet:b", -1, "", clearProgressOnError = false)
        callbacks.progress!!(TorrentProgress("Stalled", sourceError = true))
        callbacks.error!!("dead")
        assertTrue(vm.torrentProgress!!.sourceError)
    }

    @Test
    fun `a custom onReady replaces the default`() {
        val vm = session()
        var got: String? = null
        vm.startTorrent("magnet:a", -1, "", onReady = { got = it })
        callbacks.ready!!("http://local/x")
        assertEquals("http://local/x", got)
        assertEquals("", vm.selectedVideoUrl)
    }

    @Test
    fun `callbacks from an earlier start are ignored`() {
        val vm = session()
        vm.startTorrent("magnet:old", -1, "")
        val staleReady = callbacks.ready!!
        val staleProgress = callbacks.progress!!
        vm.startTorrent("magnet:new", -1, "")
        staleProgress(TorrentProgress("old progress"))
        staleReady("http://local/old")
        assertEquals("Starting torrent", vm.torrentProgress?.status)
        assertEquals("", vm.selectedVideoUrl)
        callbacks.ready!!("http://local/new")
        assertEquals("http://local/new", vm.selectedVideoUrl)
    }

    @Test
    fun `release detaches its own callbacks and drops late deliveries`() {
        controller.start("magnet:a", -1, "", onReady = {})
        val late = callbacks.progress!!
        controller.release()
        assertNull(callbacks.ready)
        late(TorrentProgress("late"))
        assertEquals("Starting torrent", controller.progress?.status)
    }

    @Test
    fun `release leaves callbacks another owner installed`() {
        controller.start("magnet:a", -1, "", onReady = {})
        val other: (String) -> Unit = {}
        callbacks.install(other, {}, {})
        controller.release()
        assertTrue(callbacks.ready === other)
    }

    @Test
    fun `stop stops the service`() {
        session().stopTorrent()
        assertEquals(1, launcher.stops)
    }

    @Test
    fun `a stream ready after stop is dropped`() {
        val vm = session()
        vm.startTorrent("magnet:a", -1, "")
        val late = callbacks.ready!!
        vm.stopTorrent()
        vm.selectedVideoUrl = "https://cdn/direct.mkv"
        late("http://local/a")
        assertEquals("https://cdn/direct.mkv", vm.selectedVideoUrl)
        assertNull(vm.torrentProgress)
    }

    @Test
    fun `saved fields survive process death, transient ones do not`() {
        val handle = SavedStateHandle()
        val vm = session(handle)
        vm.selectedVideoUrl = "https://cdn/a.mkv"
        vm.selectedPlaybackId = "tt1:1:2"
        vm.selectedPlaybackType = "series"
        vm.queueWholeShowActive = true
        vm.currentStream = Stream(url = "https://cdn/a.mkv")
        vm.playbackStatus = "Opening"

        val restored = newSession(SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }), TorrentStreamController(launcher, callbacks))
        assertEquals("https://cdn/a.mkv", restored.selectedVideoUrl)
        assertEquals("tt1:1:2", restored.selectedPlaybackId)
        assertEquals("series", restored.selectedPlaybackType)
        assertTrue(restored.queueWholeShowActive)
        assertFalse(restored.queueStartPending)
        assertNull(restored.currentStream)
        assertNull(restored.playbackStatus)
        assertNull(restored.torrentProgress)
    }

    @Test
    fun `defaults match the old rememberSaveable initial values`() {
        val vm = session()
        assertEquals("", vm.selectedVideoUrl)
        assertEquals("movie", vm.selectedPlaybackType)
        assertFalse(vm.queuePlaybackActive)
    }

    @Test
    fun `leaving the player leaves no stuck episode switch`() {
        val vm = session()
        val result = PlayerSessionResult(0L, null, false, null, null, null)
        vm.isEpisodeSwitchLoading = true
        vm.pendingEpisodeSwitch = PendingEpisodeSwitch("p", "t", "s", streams = null, addonSubs = emptyList(), playerCurrentSourceUrl = null)
        vm.playbackStatus = "Next episode · finding sources"
        val generation = vm.episodeSwitchGeneration
        vm.end(result, profile = null)
        assertFalse(vm.isEpisodeSwitchLoading)
        assertNull(vm.pendingEpisodeSwitch)
        assertNull(vm.playbackStatus)
        assertEquals(generation + 1, vm.episodeSwitchGeneration)

        // A source list already on screen stays: the viewer can still pick from it.
        val listed = PendingEpisodeSwitch("p", "t", "s", streams = emptyList(), addonSubs = emptyList(), playerCurrentSourceUrl = null)
        vm.pendingEpisodeSwitch = listed
        vm.end(result, profile = null)
        assertEquals(listed, vm.pendingEpisodeSwitch)
    }
}
