package com.hereliesaz.illumera.ui.player.base

import org.junit.Assert.assertEquals
import org.junit.Test

class LoadingStepFeedTest {
    private fun feed(vararg snapshots: LoadingSnapshot): List<String> {
        var previous: LoadingSnapshot? = null
        val lines = mutableListOf<String>()
        for (snapshot in snapshots) {
            lines += loadingStepsBetween(previous, snapshot)
            previous = snapshot
        }
        return lines
    }

    @Test
    fun torrentStartupProducesOneLinePerMilestone() {
        val lines = feed(
            LoadingSnapshot(torrentStatus = "Starting torrent", isTorrent = true),
            LoadingSnapshot(torrentStatus = "Starting the torrent engine", isTorrent = true),
            LoadingSnapshot(torrentStatus = "Adding the torrent", isTorrent = true),
            LoadingSnapshot(torrentStatus = "Fetching the file list from peers", isTorrent = true),
            LoadingSnapshot(torrentStatus = "Opening the video file", isTorrent = true),
            // The engine hands its local stream to the player.
            LoadingSnapshot(),
            LoadingSnapshot(torrentStatus = "Connecting to peers", peers = 7, isTorrent = true),
            LoadingSnapshot(torrentStatus = "Connecting to peers", peers = 9, downloadSpeed = 2_097_152, progress = 0.1f, isTorrent = true),
            // Peer count and speed drift alone produce nothing.
            LoadingSnapshot(torrentStatus = "Connecting to peers", peers = 12, downloadSpeed = 900_000, progress = 0.2f, isTorrent = true),
            LoadingSnapshot(torrentStatus = "Connecting to peers", peers = 12, downloadSpeed = 900_000, progress = 0.55f, isTorrent = true),
        )

        assertEquals(
            listOf(
                "Starting torrent",
                "Starting the torrent engine",
                "Adding the torrent",
                "Fetching the file list from peers",
                "Opening the video file",
                "Opening the video stream",
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

    @Test
    fun appStatusLeadsAndIsNotRepeated() {
        val lines = feed(
            LoadingSnapshot(statusMessage = "That source didn't play · trying source 2 of 5", hasRenderedFirstFrame = true),
            LoadingSnapshot(statusMessage = "Finding subtitles for source 2", hasRenderedFirstFrame = true),
            LoadingSnapshot(statusMessage = "Opening source 2 of 5"),
            LoadingSnapshot(statusMessage = "Opening source 2 of 5", isReady = true),
        )
        assertEquals(
            listOf(
                "That source didn't play · trying source 2 of 5",
                "Finding subtitles for source 2",
                "Opening source 2 of 5",
                "Preparing video",
            ),
            lines
        )
    }
}
