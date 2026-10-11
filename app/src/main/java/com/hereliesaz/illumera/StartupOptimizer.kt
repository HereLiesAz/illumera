package com.hereliesaz.illumera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Best-effort warmup during splash/profile selection: allocates a few poster-sized bitmaps
 * and runs one tiny Coil request so the image pipeline's lazy initialization happens before
 * the first scroll. It may reduce first-scroll jank; it does not guarantee eliminating it.
 */
@Singleton
class StartupOptimizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageLoader: ImageLoader
) {
    
    companion object {
        private const val TAG = "StartupOptimizer"
        private const val POSTER_WIDTH = 280
        private const val POSTER_HEIGHT = 420
        private const val WARMUP_DECODE_COUNT = 3
    }
    
    private val isWarmedUp = java.util.concurrent.atomic.AtomicBoolean(false)
    private val warmupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Call this early in app startup (onCreate or during splash/profile screen)
     * Warms up image decoding pipeline without blocking UI
     */
    fun warmup() {
        if (!isWarmedUp.compareAndSet(false, true)) return
        
        warmupScope.launch {
            try {
                // 1. Allocate/draw a few poster-sized bitmaps (touches the Bitmap/Canvas
                // native paths; it does not decode anything or populate Coil's pools).
                warmupBitmapDecoding()
                
                // 2. Run one tiny Coil request so the loader's lazy components (fetchers,
                // decoders, memory cache) are initialized before real requests arrive.
                primeMemoryCache()
                
                if (com.hereliesaz.illumera.BuildConfig.DEBUG) Log.d(TAG, "Warmup complete")
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (com.hereliesaz.illumera.BuildConfig.DEBUG) Log.w(TAG, "Warmup failed (non-critical): ${e.message}")
            }
        }
    }
    
    /**
     * Allocates and draws a few poster-sized bitmaps. This is not a decode and does not
     * touch Coil's decoder dispatcher; it only exercises Bitmap/Canvas allocation.
     */
    private fun warmupBitmapDecoding() {
        repeat(WARMUP_DECODE_COUNT) {
            try {
                // Poster-sized (280x420) to match the real allocation size
                val bitmap = Bitmap.createBitmap(POSTER_WIDTH, POSTER_HEIGHT, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(0xFF121212.toInt())
                bitmap.recycle()
            } catch (e: Exception) {
                // Ignore - this is just a warmup
            }
        }
    }
    
    /**
     * Executes a 1x1 data-URI request through Coil to initialize the loader lazily.
     */
    private suspend fun primeMemoryCache() {
        try {
            // A data URI avoids the network; running it initializes Coil's lazy components.
            val dummyRequest = ImageRequest.Builder(context)
                .data("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
                .size(1, 1)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .build()
            
            imageLoader.execute(dummyRequest)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // Ignore
        }
    }
    
}
