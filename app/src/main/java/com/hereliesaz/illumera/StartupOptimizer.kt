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
 * Pre-warms Coil's image decoder pool and memory cache during splash/profile selection
 * to eliminate first-scroll lag.
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
                // 1. Warm up bitmap decode pool with dummy bitmaps
                // This triggers JNI initialization and thread pool creation
                warmupBitmapDecoding()
                
                // 2. Pre-fill a small portion of memory cache
                // This ensures the cache data structures are allocated
                primeMemoryCache()
                
                if (com.hereliesaz.illumera.BuildConfig.DEBUG) Log.d(TAG, "Warmup complete - decoder pool primed")
            } catch (e: Exception) {
                if (com.hereliesaz.illumera.BuildConfig.DEBUG) Log.w(TAG, "Warmup failed (non-critical): ${e.message}")
            }
        }
    }
    
    /**
     * Creates dummy bitmap decodes to warm up the decoder thread pool.
     * Uses actual poster dimensions so the decoder thread pool is primed
     * for the real work it will do during first scroll.
     */
    private fun warmupBitmapDecoding() {
        repeat(WARMUP_DECODE_COUNT) {
            try {
                // Use actual poster size (280x420) to warm the decoder for real workloads
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
     * Makes a small allocation in the memory cache to ensure it's initialized
     */
    private suspend fun primeMemoryCache() {
        try {
            // Create a small image request with a data URI to avoid network
            // This forces the memory cache to initialize its data structures
            val dummyRequest = ImageRequest.Builder(context)
                .data("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
                .size(1, 1)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .build()
            
            imageLoader.execute(dummyRequest)
        } catch (e: Exception) {
            // Ignore
        }
    }
    
}
