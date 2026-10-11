package com.hereliesaz.illumera.ui.playback

import com.hereliesaz.illumera.data.model.stremio.Stream
import com.hereliesaz.illumera.data.model.stremio.StreamBehaviorHints
import com.hereliesaz.illumera.data.player.PlaybackTrackSelectionStore
import com.hereliesaz.illumera.data.player.SourceSelectionStore
import com.hereliesaz.illumera.ui.player.PlayerSessionResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPayloadsTest {

    private val direct1 = Stream(name = "A", url = "https://cdn/a.mkv", addonTransportUrl = "https://addon")
    private val direct2 = Stream(name = "B", url = "https://cdn/b.mkv", addonTransportUrl = "https://addon")
    private val torrent = Stream(name = "T", infoHash = "abc123", fileIdx = 2)

    // --- resolvePlayableSourceUrl ---

    @Test
    fun `direct url wins and is trimmed`() {
        assertEquals("https://cdn/a.mkv", resolvePlayableSourceUrl(Stream(url = "  https://cdn/a.mkv ", infoHash = "abc")))
    }

    @Test
    fun `info hash becomes a magnet with addon trackers first`() {
        val stream = Stream(infoHash = " abc123 ", sources = listOf("tracker:udp://addon.tracker:1/announce", "dht:abc123"))
        val url = resolvePlayableSourceUrl(stream)!!
        assertTrue(url.startsWith("magnet:?xt=urn:btih:abc123&dn=Video&tr="))
        val trackers = url.split("&tr=").drop(1).map { java.net.URLDecoder.decode(it, "UTF-8") }
        assertEquals("udp://addon.tracker:1/announce", trackers.first())
        assertEquals(TORRENT_TRACKERS, trackers.drop(1))
    }

    @Test
    fun `stream with neither url nor hash has no playable url`() {
        assertNull(resolvePlayableSourceUrl(Stream(url = " ", infoHash = "")))
    }

    // --- buildSourcePayload ---

    @Test
    fun `selected stream is listed first and the rest keep their order`() {
        val payload = buildSourcePayload(listOf(direct1, direct2, torrent), selectedStream = torrent)
        assertEquals(
            listOf(resolvePlayableSourceUrl(torrent), direct1.url, direct2.url),
            payload.map { it.url }
        )
        assertEquals(2, payload.first().fileIdx)
    }

    @Test
    fun `sources ranked after the selection follow it, earlier ones go last`() {
        val payload = buildSourcePayload(listOf(direct1, direct2, torrent), selectedStream = direct2)
        assertEquals(
            listOf(direct2.url, resolvePlayableSourceUrl(torrent), direct1.url),
            payload.map { it.url }
        )
    }

    @Test
    fun `duplicates and unplayable streams are dropped`() {
        val payload = buildSourcePayload(listOf(direct1, direct1.copy(), Stream(name = "none"), direct2), selectedStream = direct2)
        assertEquals(listOf(direct2.url, direct1.url), payload.map { it.url })
    }

    // --- requestOrFallback ---

    @Test
    fun `request failure returns the fallback`() = runBlocking {
        assertEquals(listOf("x"), requestOrFallback(listOf("x")) { error("boom") })
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is not swallowed`() {
        runBlocking { requestOrFallback(0) { throw CancellationException("stop") } }
    }

    // --- handlePlayerSessionEnd ---

    private val sourceStore = mockk<SourceSelectionStore>(relaxed = true)
    private val trackStore = mockk<PlaybackTrackSelectionStore>(relaxed = true)

    private fun end(
        result: PlayerSessionResult,
        pending: PendingSourceSelection? = PendingSourceSelection("tt1", direct1, listOf(direct1, direct2)),
        playbackId: String = "tt1",
        remember: Boolean = true
    ): Pair<Boolean, String?> {
        var consumed = false
        var hint: String? = "unset"
        handlePlayerSessionEnd(
            sessionResult = result,
            selectedPlaybackId = playbackId,
            playbackTrackSelectionStore = trackStore,
            sourceSelectionStore = sourceStore,
            pendingSourceSelection = pending,
            onConsumePendingSelection = { consumed = true },
            onResumeHintResolved = { hint = it },
            rememberSourceSelection = remember
        )
        return consumed to hint
    }

    private fun result(positionMs: Long, completed: Boolean = false, sourceUrl: String? = null) =
        PlayerSessionResult(positionMs, null, completed, sourceUrl, null, null)

    @Test
    fun `watching past five seconds remembers the source the player ended on`() {
        val (consumed, hint) = end(result(positionMs = 60_000, sourceUrl = direct2.url))
        verify { sourceStore.rememberSelection("tt1", direct2) }
        assertTrue(consumed)
        assertEquals("tt1", hint)
    }

    @Test
    fun `completed playback remembers the launched source and gives no resume hint`() {
        val (_, hint) = end(result(positionMs = 0, completed = true))
        verify { sourceStore.rememberSelection("tt1", direct1) }
        assertNull(hint)
    }

    @Test
    fun `remember disabled never commits`() {
        end(result(positionMs = 60_000), remember = false)
        verify(exactly = 0) { sourceStore.rememberSelection(any(), any()) }
    }

    @Test
    fun `failing at the start clears a remembered source`() {
        every { sourceStore.findPreferredStream("tt1", listOf(direct1)) } returns direct1
        end(result(positionMs = 500))
        verify { sourceStore.clearSelection("tt1") }
        verify(exactly = 0) { sourceStore.rememberSelection(any(), any()) }
    }

    @Test
    fun `failing at the start leaves an unremembered source alone`() {
        every { sourceStore.findPreferredStream(any(), any()) } returns null
        end(result(positionMs = 500))
        verify(exactly = 0) { sourceStore.clearSelection(any()) }
    }

    @Test
    fun `stopping between one and five seconds neither commits nor clears`() {
        end(result(positionMs = 3_000))
        verify(exactly = 0) { sourceStore.rememberSelection(any(), any()) }
        verify(exactly = 0) { sourceStore.clearSelection(any()) }
    }

    @Test
    fun `blank playback id only consumes the pending selection`() {
        val (consumed, hint) = end(result(positionMs = 60_000), playbackId = "  ")
        assertTrue(consumed)
        assertNull(hint)
        verify(exactly = 0) { sourceStore.rememberSelection(any(), any()) }
    }

    @Test
    fun `track choices are stored only when something was chosen`() {
        end(result(positionMs = 60_000))
        verify(exactly = 0) { trackStore.updateSelection(any(), any(), any(), any(), any(), any(), any()) }
        end(result(positionMs = 60_000).copy(selectedAudioTrackId = "a1"))
        verify {
            trackStore.updateSelection(
                playbackId = "tt1", audioTrackId = "a1", subtitleTrackId = null, subtitleDelayMs = 0L,
                updateAudio = true, updateSubtitle = false, updateSubtitleDelay = true
            )
        }
    }

    // --- isSameFile ---

    @Test
    fun `a stream at the url being played is the file just watched`() {
        assertTrue(isSameFile(direct1, null, "https://cdn/a.mkv"))
        assertFalse(isSameFile(direct2, direct1, "https://cdn/a.mkv"))
    }

    @Test
    fun `another file of the same torrent is not the file just watched`() {
        assertTrue(isSameFile(torrent.copy(infoHash = "ABC123"), torrent, null))
        assertFalse(isSameFile(torrent.copy(fileIdx = 3), torrent, null))
    }

    @Test
    fun `a torrent without file index is told apart by file name`() {
        fun named(name: String?) = Stream(infoHash = "abc123", behaviorHints = StreamBehaviorHints(filename = name))
        assertFalse(isSameFile(named("S01E08.mkv"), named("S01E07.mkv"), null))
        assertTrue(isSameFile(named("S01E07.mkv"), named("S01E07.mkv"), null))
        assertTrue(isSameFile(named(null), named(null), null))
    }

    @Test
    fun `a season pack without file hints is told apart by episode tag`() {
        fun titled(title: String) = Stream(infoHash = "abc123", title = title)
        assertFalse(isSameFile(titled("Show S01E04 2160p"), titled("Show S01E03 2160p"), null))
        assertTrue(isSameFile(titled("Show S01E03 2160p"), titled("Show.s01.e03.mkv"), null))
        assertTrue(isSameFile(titled("Show Season 1 pack"), titled("Show S01E03"), null))
    }

    // --- playerSourceId ---

    @Test
    fun `sources from one addon get distinct ids that match the player`() {
        val a = playerSourceId(direct1)
        val b = playerSourceId(direct2)
        assertTrue(a != null && b != null && a != b)
        assertEquals(buildPlayerSourceOption(direct1)?.id, a)
    }

    @Test
    fun `stream with nothing playable has no source id`() {
        assertNull(playerSourceId(Stream(name = "X", externalUrl = "https://site")))
    }
}
