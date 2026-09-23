package com.hereliesaz.illumera.ui.player.base

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hereliesaz.illumera.data.torrent.TorrentProgress
import kotlinx.coroutines.delay

/**
 * Loading feedback as a short, paced feed of what is happening behind the scenes.
 *
 * Each new step fades in while rising into the bottom row; every row above rises one
 * row at the same time. With [VISIBLE_ROWS] rows showing, the top row fades out as it
 * rises past the top while the newest fades in at the bottom. Steps are released at a
 * fixed cadence, never faster, so motion stays even no matter how bursty the source is.
 */
private const val VISIBLE_ROWS = 5
private const val STEP_INTERVAL_MS = 900L
private const val MOVE_DURATION_MS = 650
private const val MAX_PENDING_STEPS = 2

/** What the loading state looked like at one moment, reduced to what steps depend on. */
internal data class LoadingSnapshot(
    val torrentStatus: String? = null,
    val peers: Int = 0,
    val downloadSpeed: Long = 0,
    val progress: Float? = null,
    val isTorrent: Boolean = false,
    val isReady: Boolean = false,
    val hasRenderedFirstFrame: Boolean = false,
    val isBuffering: Boolean = false,
)

internal fun LoadingSnapshot(
    torrentProgress: TorrentProgress?,
    isReady: Boolean,
    hasRenderedFirstFrame: Boolean,
    isBuffering: Boolean,
) = LoadingSnapshot(
    torrentStatus = torrentProgress?.status,
    peers = torrentProgress?.peers ?: 0,
    downloadSpeed = torrentProgress?.downloadSpeed ?: 0,
    progress = torrentProgress?.progress,
    isTorrent = torrentProgress != null,
    isReady = isReady,
    hasRenderedFirstFrame = hasRenderedFirstFrame,
    isBuffering = isBuffering,
)

/**
 * Human summaries of what changed between two snapshots. Only milestones produce a
 * line; routine churn (speed wobble, peer count drift, percent ticks) does not.
 */
internal fun loadingStepsBetween(previous: LoadingSnapshot?, current: LoadingSnapshot): List<String> {
    val steps = mutableListOf<String>()
    if (previous == null) {
        steps += when {
            current.hasRenderedFirstFrame -> "Rebuffering"
            current.isTorrent -> "Preparing torrent"
            else -> "Opening stream"
        }
    }

    val status = current.torrentStatus?.let(::summarizeStatus)
    if (status != null && status != previous?.torrentStatus?.let(::summarizeStatus)) steps += status

    if (current.peers > 0 && (previous?.peers ?: 0) == 0) {
        steps += if (current.peers == 1) "Found 1 peer" else "Found ${current.peers} peers"
    }
    if (current.downloadSpeed > 0 && (previous?.downloadSpeed ?: 0L) == 0L) {
        steps += "Downloading at ${formatStepSpeed(current.downloadSpeed)}"
    }

    val before = ((previous?.progress ?: 0f) * 100).toInt()
    val now = ((current.progress ?: 0f) * 100).toInt()
    listOf(25, 50, 75).lastOrNull { it in (before + 1)..now }?.let { steps += "Preloaded $it%" }

    if (previous != null) {
        if (current.isReady && !previous.isReady && !current.hasRenderedFirstFrame) steps += "Preparing video"
        if (current.hasRenderedFirstFrame && current.isBuffering && !previous.isBuffering) steps += "Rebuffering"
    }
    return steps
}

private fun summarizeStatus(status: String): String? =
    status.trim().trimEnd('.', '…').trim().takeIf { it.isNotEmpty() }

private fun formatStepSpeed(bytesPerSec: Long): String = when {
    bytesPerSec >= 1_048_576 -> "%.1f MB/s".format(bytesPerSec / 1_048_576.0)
    bytesPerSec >= 1_024 -> "%.0f KB/s".format(bytesPerSec / 1_024.0)
    else -> "$bytesPerSec B/s"
}

private data class FeedLine(val id: Long, val text: String)

@Composable
internal fun LoadingStepFeed(snapshot: LoadingSnapshot, modifier: Modifier = Modifier) {
    val pending = remember { ArrayDeque<String>() }
    val lines = remember { mutableStateListOf<FeedLine>() }
    var previous by remember { mutableStateOf<LoadingSnapshot?>(null) }
    var nextId by remember { mutableStateOf(0L) }

    LaunchedEffect(snapshot) {
        for (step in loadingStepsBetween(previous, snapshot)) {
            val last = pending.lastOrNull() ?: lines.lastOrNull()?.text
            if (step != last) pending.addLast(step)
        }
        // Stay current rather than falling behind: keep only the freshest few.
        while (pending.size > MAX_PENDING_STEPS) pending.removeFirst()
        previous = snapshot
    }

    // One cadence for every line, however bursty the source.
    LaunchedEffect(Unit) {
        while (true) {
            pending.removeFirstOrNull()?.let { text ->
                lines += FeedLine(nextId++, text)
                // The row leaving past the top needs one extra slot to finish fading.
                while (lines.size > VISIBLE_ROWS + 1) lines.removeAt(0)
            }
            delay(STEP_INTERVAL_MS)
        }
    }

    val rowHeight = 34.dp
    val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }
    Box(
        modifier = modifier.width(460.dp).height(rowHeight * VISIBLE_ROWS),
        contentAlignment = Alignment.BottomStart
    ) {
        lines.forEachIndexed { index, line ->
            // 0 = bottom (newest) row, counting upward.
            val slot = (lines.size - 1 - index).toFloat()
            key(line.id) {
                val position = remember { Animatable(-1f) }
                LaunchedEffect(slot) {
                    position.animateTo(slot, tween(MOVE_DURATION_MS, easing = FastOutSlowInEasing))
                }
                val p = position.value
                val alpha = when {
                    p < 0f -> 1f + p
                    p > VISIBLE_ROWS - 1 -> (VISIBLE_ROWS - p).coerceIn(0f, 1f)
                    else -> 1f
                }
                Text(
                    text = line.text,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .height(rowHeight)
                        .graphicsLayer {
                            translationY = -p * rowHeightPx
                            this.alpha = alpha
                        }
                )
            }
        }
    }
}
