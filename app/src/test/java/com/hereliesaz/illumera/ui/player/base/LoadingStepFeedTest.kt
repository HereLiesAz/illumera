package com.hereliesaz.illumera.ui.player.base

import org.junit.Assert.assertEquals
import org.junit.Test

class LoadingStepFeedTest {
    @Test
    fun torrentStartupProducesOneLinePerMilestone() {
        var previous: LoadingSnapshot? = null
        val lines = mutableListOf<String>()
        fun step(snapshot: LoadingSnapshot) {
            lines += loadingStepsBetween(previous, snapshot)
            previous = snapshot
        }

        step(LoadingSnapshot(torrentStatus = "Starting engine...", isTorrent = true))
        step(LoadingSnapshot(torrentStatus = "Fetching metadata...", isTorrent = true))
        step(LoadingSnapshot(torrentStatus = "Connecting to peers...", peers = 7, isTorrent = true))
        step(LoadingSnapshot(torrentStatus = "Connecting to peers...", peers = 9, downloadSpeed = 2_097_152, progress = 0.1f, isTorrent = true))
        // Peer count and speed drift alone produce nothing.
        step(LoadingSnapshot(torrentStatus = "Connecting to peers...", peers = 12, downloadSpeed = 900_000, progress = 0.2f, isTorrent = true))
        step(LoadingSnapshot(torrentStatus = "Connecting to peers...", peers = 12, downloadSpeed = 900_000, progress = 0.55f, isTorrent = true))

        assertEquals(
            listOf(
                "Preparing torrent",
                "Starting engine",
                "Fetching metadata",
                "Connecting to peers",
                "Found 7 peers",
                "Downloading at 2.0 MB/s",
                "Preloaded 50%",
            ),
            lines
        )
    }

    @Test
    fun directStreamAndRebuffering() {
        val opening = LoadingSnapshot()
        val ready = LoadingSnapshot(isReady = true)
        assertEquals(listOf("Opening stream"), loadingStepsBetween(null, opening))
        assertEquals(listOf("Preparing video"), loadingStepsBetween(opening, ready))
        assertEquals(
            listOf("Rebuffering"),
            loadingStepsBetween(null, LoadingSnapshot(isReady = true, hasRenderedFirstFrame = true, isBuffering = true))
        )
    }
}
