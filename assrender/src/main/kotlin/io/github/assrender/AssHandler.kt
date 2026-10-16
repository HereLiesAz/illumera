package io.github.assrender

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import androidx.media3.common.Format
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Coordinates the ASS subtitle pipeline:
 * - Receives raw ASS data from [AssTrackOutput] (header + dialogue chunks)
 * - Feeds data to libass via [AssDirectBridge]
 * - Renders subtitle bitmaps synced to playback time
 * - Manages the [SubtitleOverlayView] display
 *
 * Threading: extractor callbacks arrive on ExoPlayer's loader thread, rendering
 * runs on the main thread (Choreographer) and track selection / release come
 * from the app. A libass context is not thread-safe, so every native call is
 * made while holding [lock]. The render loop only `tryLock`s, so a long
 * operation (font loading, event replay) skips frames instead of blocking the
 * UI thread.
 *
 * @param videoWidth initial render width in pixels (and, with [videoHeight], the
 *   cap on the longest render side). Once the overlay is laid out the render
 *   bitmap follows the overlay's aspect ratio and size, capped to this.
 */
class AssHandler(
    context: Context,
    val overlayView: SubtitleOverlayView,
    private val videoWidth: Int = 1920,
    private val videoHeight: Int = 1080
) {
    /** Called when the first ASS track is detected — use to hide ExoPlayer subtitles */
    var onAssTrackDetected: (() -> Unit)? = null

    companion object {
        private const val TAG = "assrender"

        /** Debounce before rebuilding libass' font selector after attachments arrive. */
        private const val FONT_FINALIZE_DELAY_MS = 300L

        /** libass has no system font provider here, so these are scanned and added. */
        private val SYSTEM_FONT_DIRS = listOf("/system/fonts", "/system_ext/fonts", "/product/fonts")
    }

    private class AssEvent(val startMs: Long, val durationMs: Long, val chunk: ByteArray)

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    // Debounced libass font rebuilds run here, never on the main or loader thread.
    private val workerThread = HandlerThread("assrender-fonts").apply { start() }
    private val workerHandler = Handler(workerThread.looper)

    /** Guards every native call and all state below. */
    private val lock = ReentrantLock()

    @Volatile private var nativeHandle: Long = 0L
    @Volatile private var initialized = false
    @Volatile private var released = false

    private var renderBitmap: Bitmap? = null
    private var renderWidth = 0
    private var renderHeight = 0

    // Track state
    private val trackFormats = mutableMapOf<Int, Format>()
    private val trackHeaders = mutableMapOf<Int, ByteArray>()
    private val trackEvents = mutableMapOf<Int, MutableList<AssEvent>>()
    // Keys of events already stored per track; the extractor re-emits samples after a seek.
    private val seenEvents = mutableMapOf<Int, MutableSet<String>>()
    private var activeTrackId: Int = -1
    private var fontsDirty = false

    // Buffer fonts that arrive before initialization
    private val pendingFonts = mutableListOf<Pair<String, ByteArray>>()

    // Playback time — updated by AssTimeRenderer or player reference
    @Volatile
    var currentTimeUs: Long = 0L

    // Direct player reference for accurate position
    @Volatile
    var player: ExoPlayer? = null

    // Main thread only
    private var choreographerCallback: Choreographer.FrameCallback? = null

    private val finalizeFontsRunnable = Runnable {
        lock.withLock {
            if (!initialized || nativeHandle == 0L || !fontsDirty) return@withLock
            AssDirectBridge.nativeFinalizeFonts(nativeHandle)
            fontsDirty = false
        }
    }

    /**
     * Called by [AssTrackOutput] when ASS header (codec private) is available.
     */
    fun onTrackHeader(trackId: Int, headerData: ByteArray, format: Format) {
        var detected = false
        lock.withLock {
            if (released) return
            trackFormats[trackId] = format
            trackHeaders[trackId] = headerData

            // Initialize on first ASS track
            if (initialized) return
            val handle = AssDirectBridge.nativeInit(videoWidth, videoHeight, 1.0f)
            if (handle == 0L) {
                Log.e(TAG, "Failed to init native context")
                return
            }
            nativeHandle = handle
            renderBitmap = Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888)
            renderWidth = videoWidth
            renderHeight = videoHeight
            initialized = true
            loadFonts(handle)
            detected = true
            // Don't auto-select track 0 — wait for syncAssTrackWithExoPlayer
            // to call selectTrackByFormat with the correct track
            Log.d(TAG, "Initialized (no track selected yet)")
        }
        if (detected) onAssTrackDetected?.invoke()
    }

    /** Add system, app and buffered attachment fonts, then rebuild the selector once. */
    private fun loadFonts(handle: Long) {
        for (dir in SYSTEM_FONT_DIRS + File(appContext.filesDir, "fonts").absolutePath) {
            AssDirectBridge.nativeAddFontDir(handle, dir)
        }
        for ((name, data) in pendingFonts) {
            AssDirectBridge.nativeAddFont(handle, name, data)
        }
        Log.d(TAG, "Flushed ${pendingFonts.size} pending fonts")
        pendingFonts.clear()
        AssDirectBridge.nativeFinalizeFonts(handle)
        fontsDirty = false
    }

    /**
     * Called by [AssTrackOutput] for each subtitle dialogue line.
     */
    fun onSubtitleSample(trackId: Int, timeUs: Long, data: ByteArray) {
        val line = String(data, Charsets.UTF_8)
        if (!line.startsWith("Dialogue:")) return

        val content = line.removePrefix("Dialogue:").trimStart()
        val parts = content.split(",", limit = 11)
        if (parts.size < 11) return

        val durationMs = parseAssTimeMs(parts[1].trim())
        val startMs = timeUs / 1000
        val chunkData = "${parts[2].trim()},${parts[3].trim()},${parts[4].trim()},${parts[5].trim()},${parts[6].trim()},${parts[7].trim()},${parts[8].trim()},${parts[9].trim()},${parts[10]}"
        val chunkBytes = chunkData.toByteArray(Charsets.UTF_8)
        val key = "$startMs|$durationMs|${parts[2].trim()}|${chunkData.hashCode()}"

        lock.withLock {
            if (released || !initialized || nativeHandle == 0L) return

            // A seek makes the extractor emit already-seen samples again; feeding
            // them to libass twice would draw each line doubled.
            if (!seenEvents.getOrPut(trackId) { HashSet() }.add(key)) return

            // Store event for this track (for replay on track switch)
            trackEvents.getOrPut(trackId) { ArrayList() }
                .add(AssEvent(startMs, durationMs, chunkBytes))

            // Only feed to libass if this is the active track
            if (trackId == activeTrackId) {
                AssDirectBridge.nativeProcessChunk(nativeHandle, chunkBytes, startMs, durationMs)
            }
        }
    }

    /**
     * Called by [AssMatroskaExtractor] for font attachments.
     */
    fun onFontAttachment(name: String, data: ByteArray) {
        lock.withLock {
            if (released) return
            if (!initialized || nativeHandle == 0L) {
                // Buffer fonts that arrive before libass is initialized
                pendingFonts.add(Pair(name, data))
                return
            }
            AssDirectBridge.nativeAddFont(nativeHandle, name, data)
            fontsDirty = true
            Log.d(TAG, "Added font: $name (${data.size} bytes)")
        }
        // Attachments come in bursts; rebuild the font selector once afterwards.
        workerHandler.removeCallbacks(finalizeFontsRunnable)
        workerHandler.postDelayed(finalizeFontsRunnable, FONT_FINALIZE_DELAY_MS)
    }

    /**
     * Switch to a different subtitle track.
     */
    fun selectTrack(trackId: Int) {
        lock.withLock {
            if (released || !initialized || nativeHandle == 0L) return
            val header = trackHeaders[trackId] ?: return
            activeTrackId = trackId

            // Flush and reload header
            AssDirectBridge.nativeFlush(nativeHandle)
            AssDirectBridge.nativeLoadHeader(nativeHandle, header)

            // Replay stored events for this track
            val events = trackEvents[trackId]
            if (events != null) {
                for (event in events) {
                    AssDirectBridge.nativeProcessChunk(nativeHandle, event.chunk, event.startMs, event.durationMs)
                }
                Log.d(TAG, "Switched to track $trackId, replayed ${events.size} events")
            } else {
                Log.d(TAG, "Switched to track $trackId")
            }
        }

        runOnMain {
            overlayView.clear()
            // Restart render loop (may have been stopped by clearOverlay)
            startRenderLoop()
        }
    }

    /**
     * Select ASS track by matching language/title from ExoPlayer's format.
     */
    fun selectTrackByFormat(format: Format) {
        val targetLang = format.language?.lowercase() ?: ""
        val targetLabel = format.label?.lowercase() ?: ""

        val match = lock.withLock {
            if (!initialized) return
            trackFormats.entries.firstOrNull { (_, fmt) ->
                val lang = fmt.language?.lowercase() ?: ""
                val label = fmt.label?.lowercase() ?: ""
                (targetLabel.isNotEmpty() && label == targetLabel) ||
                    (targetLang.isNotEmpty() && lang == targetLang)
            }?.key
        }
        if (match != null) {
            selectTrack(match)
            return
        }

        // No matching ASS track — clear overlay
        clearOverlay()
        Log.d(TAG, "No matching ASS track for lang=$targetLang, label=$targetLabel")
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    // Main thread only
    private fun startRenderLoop() {
        stopRenderLoop()
        if (released) return

        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                renderFrame()
                // Schedule next frame
                if (choreographerCallback === this) {
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }
        choreographerCallback = callback
        Choreographer.getInstance().postFrameCallback(callback)
    }

    // Main thread only
    private fun stopRenderLoop() {
        choreographerCallback?.let {
            Choreographer.getInstance().removeFrameCallback(it)
        }
        choreographerCallback = null
    }

    /** Render size for the current overlay: its aspect ratio, capped to the configured size. */
    private fun desiredRenderSize(): Pair<Int, Int> {
        val viewW = overlayView.width
        val viewH = overlayView.height
        if (viewW <= 0 || viewH <= 0) return Pair(videoWidth, videoHeight)
        val scale = min(1f, max(videoWidth, videoHeight).toFloat() / max(viewW, viewH))
        return Pair(max(1, (viewW * scale).roundToInt()), max(1, (viewH * scale).roundToInt()))
    }

    private fun renderFrame() {
        // Never block the UI thread behind font loading or an event replay.
        if (!lock.tryLock()) return
        try {
            if (released || !initialized || nativeHandle == 0L) return

            val (width, height) = desiredRenderSize()
            if (width != renderWidth || height != renderHeight) {
                if (AssDirectBridge.nativeResize(nativeHandle, width, height) != 0) return
                renderBitmap?.recycle()
                renderBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                renderWidth = width
                renderHeight = height
            }
            val bitmap = renderBitmap ?: return

            val positionMs = player?.currentPosition ?: (currentTimeUs / 1000)
            val hasContent = AssDirectBridge.nativeRender(nativeHandle, positionMs, bitmap)

            if (hasContent) {
                overlayView.updateBitmap(bitmap)
            } else {
                overlayView.clear()
            }
        } finally {
            lock.unlock()
        }
    }

    /** Parse "H:MM:SS:CC" to milliseconds */
    private fun parseAssTimeMs(time: String): Long {
        val parts = time.split(":")
        if (parts.size != 4) return 0
        val h = parts[0].toLongOrNull() ?: 0
        val m = parts[1].toLongOrNull() ?: 0
        val s = parts[2].toLongOrNull() ?: 0
        val cs = parts[3].toLongOrNull() ?: 0
        return h * 3600000 + m * 60000 + s * 1000 + cs * 10
    }

    /**
     * Clear the overlay and stop rendering ASS (e.g. when switching to non-ASS track).
     */
    fun clearOverlay() {
        lock.withLock {
            activeTrackId = -1
            if (nativeHandle != 0L) {
                AssDirectBridge.nativeFlush(nativeHandle)
            }
        }
        runOnMain { stopRenderLoop() }
        mainHandler.post { overlayView.clear() }
    }

    /**
     * Release all resources. The handler cannot be used afterwards; create a new one.
     */
    fun release() {
        released = true
        runOnMain { stopRenderLoop() }
        workerHandler.removeCallbacksAndMessages(null)
        workerThread.quitSafely()

        lock.withLock {
            if (nativeHandle != 0L) {
                AssDirectBridge.nativeDestroy(nativeHandle)
                nativeHandle = 0L
            }
            renderBitmap?.recycle()
            renderBitmap = null
            initialized = false
            activeTrackId = -1
            fontsDirty = false
            trackFormats.clear()
            trackHeaders.clear()
            trackEvents.clear()
            seenEvents.clear()
            pendingFonts.clear()
        }
        mainHandler.post { overlayView.clear() }
        Log.d(TAG, "Released")
    }
}
