package io.github.assrender

import android.graphics.Bitmap
import androidx.annotation.Keep

/**
 * JNI bridge for the direct libass pipeline (no FFmpeg).
 * Subtitle data is fed directly from ExoPlayer's extraction.
 *
 * A native context is not thread-safe: callers must serialize every call that
 * uses the same handle (see [AssHandler]).
 */
@Keep
object AssDirectBridge {

    init {
        System.loadLibrary("assrender")
    }

    /** Initialize libass context. Returns handle or 0 on failure. */
    external fun nativeInit(width: Int, height: Int, fontScale: Float): Long

    /** Load ASS header (codec private data from Format.initializationData). */
    external fun nativeLoadHeader(handle: Long, headerData: ByteArray): Int

    /** Resize the render frame; the bitmap given to [nativeRender] must match. */
    external fun nativeResize(handle: Long, width: Int, height: Int): Int

    /** Add a font (attachment or file). Call [nativeFinalizeFonts] after a batch. */
    external fun nativeAddFont(handle: Long, name: String?, fontData: ByteArray)

    /**
     * Add every .ttf/.otf/.ttc in [dir] (size-bounded). libass is built without a
     * system font provider, so this is how system fonts become available.
     * Returns the number of fonts added. Call [nativeFinalizeFonts] afterwards.
     */
    external fun nativeAddFontDir(handle: Long, dir: String): Int

    /** Rebuild libass' font selector once after a batch of added fonts. */
    external fun nativeFinalizeFonts(handle: Long)

    /** Process a subtitle chunk (dialogue line with timing). */
    external fun nativeProcessChunk(handle: Long, data: ByteArray, startMs: Long, durationMs: Long)

    /** Process raw ASS data (full dialogue lines with embedded timing). */
    external fun nativeProcessData(handle: Long, data: ByteArray)

    /**
     * Render subtitle frame at given time into [bitmap], which must be an
     * ARGB_8888 bitmap of exactly the current frame size (premultiplied alpha).
     * Returns true if content rendered.
     */
    external fun nativeRender(handle: Long, timeMs: Long, bitmap: Bitmap): Boolean

    /** Flush all events (on seek or track change). */
    external fun nativeFlush(handle: Long)

    /** Destroy context and free resources. */
    external fun nativeDestroy(handle: Long)
}
