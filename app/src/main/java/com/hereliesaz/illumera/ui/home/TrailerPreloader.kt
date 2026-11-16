package com.hereliesaz.illumera.ui.home

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.hereliesaz.illumera.data.model.stremio.MetaItem

/**
 * A player for a muted, looping-free preview. ExoPlayer's defaults buffer up to 50 seconds (tens
 * of megabytes of Java heap) per player, and Home can hold several at once: the focused one, the
 * next one made ready, and one in the persistent preview. A preview only needs seconds ahead.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun newPreviewPlayer(context: Context): ExoPlayer =
    ExoPlayer.Builder(context)
        .setLoadControl(
            androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(3_000, 10_000, 1_000, 2_000)
                .setTargetBufferBytes(PREVIEW_BUFFER_BYTES)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()
        )
        .build()

internal const val PREVIEW_BUFFER_BYTES = 12 * 1024 * 1024

internal fun trailerKey(item: MetaItem) = "${item.type}:${item.id}"

/**
 * Holds one trailer, resolved and buffered but paused, for the title that follows the one now
 * playing, so it can start the moment focus reaches it. Dropped as soon as the viewer goes
 * anywhere else. Used from the main thread only.
 */
internal class TrailerPreloader(private val context: Context) {
    private var wanted: String? = null
    private var key: String? = null
    private var url: String? = null
    private var player: ExoPlayer? = null

    /** The resolved URL of the held trailer for [itemKey], if that is what is held. */
    fun urlFor(itemKey: String): String? = url.takeIf { key == itemKey && player != null }

    /** Resolves [item]'s trailer and buffers it. Replaces anything else held. */
    suspend fun preload(item: MetaItem, resolve: suspend (MetaItem) -> String?) {
        val itemKey = trailerKey(item)
        if (key == itemKey || wanted == itemKey) return
        release()
        wanted = itemKey
        val resolved = resolve(item)
        // The viewer moved on while this was being resolved.
        if (resolved == null || wanted != itemKey) return
        player = newPreviewPlayer(context).apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_OFF
            setMediaItem(MediaItem.fromUri(resolved))
            playWhenReady = false
            prepare()
        }
        key = itemKey
        url = resolved
    }

    /** Hands the held player for [itemKey] to the caller, which now owns it. */
    fun take(itemKey: String): ExoPlayer? {
        if (key != itemKey) return null
        val held = player
        player = null
        key = null
        url = null
        wanted = null
        return held
    }

    /** Drops what is held (or being prepared) unless it is for [itemKey]. */
    fun discardUnless(itemKey: String) {
        if (key != itemKey && wanted != itemKey) release()
    }

    fun release() {
        player?.release()
        player = null
        key = null
        url = null
        wanted = null
    }
}

internal val LocalTrailerPreloader = staticCompositionLocalOf<TrailerPreloader?> { null }

/**
 * True for a title whose preview is its own web video (not a trailer), which starts halfway
 * through. Provided by the Home screen so every preview (hero, rows, persistent) agrees.
 */
internal val LocalPreviewStartsAtMidpoint = staticCompositionLocalOf<(MetaItem) -> Boolean> { { false } }
