package com.hereliesaz.illumera.ui.utils

import android.content.Context
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest

/**
 * Netflix-grade image prefetcher for smooth scrolling.
 * Prefetches images ahead of the focused item to eliminate loading delays.
 */
object ImagePrefetcher {
    
    // Standard poster size matching LumeraCard for cache hit
    private const val POSTER_WIDTH = 280
    private const val POSTER_HEIGHT = 420

    // Landscape card size matching LumeraLandscapeCard (2x 190dp at 16:9)
    private const val LANDSCAPE_WIDTH = 380
    private const val LANDSCAPE_HEIGHT = 214
    
    // Number of items to prefetch ahead
    private const val PREFETCH_COUNT = 8
    private const val PREFETCH_DEDUP_WINDOW_MS = 450L
    private const val AROUND_PREFETCH_SKIP_WINDOW_MS = 100L
    private const val AROUND_PREFETCH_REDUCED_WINDOW_MS = 220L
    private const val RECENT_PREFETCH_CAPACITY = 512
    private val recentPrefetches = LinkedHashMap<String, Long>(
        RECENT_PREFETCH_CAPACITY,
        0.75f,
        true
    )
    private var lastAroundPrefetchAtMs = 0L

    @Synchronized
    private fun shouldEnqueue(url: String): Boolean {
        val now = System.currentTimeMillis()
        val lastEnqueuedAt = recentPrefetches[url]
        if (lastEnqueuedAt != null && now - lastEnqueuedAt < PREFETCH_DEDUP_WINDOW_MS) {
            return false
        }

        recentPrefetches[url] = now
        if (recentPrefetches.size > RECENT_PREFETCH_CAPACITY) {
            val oldest = recentPrefetches.entries.iterator()
            if (oldest.hasNext()) {
                oldest.next()
                oldest.remove()
            }
        }
        return true
    }

    @Synchronized
    private fun effectiveAroundCount(requestedCount: Int): Int {
        val now = System.currentTimeMillis()
        val delta = now - lastAroundPrefetchAtMs

        val safeRequested = requestedCount.coerceAtLeast(1)
        val count = when {
            delta in 0 until AROUND_PREFETCH_SKIP_WINDOW_MS -> 0
            delta in AROUND_PREFETCH_SKIP_WINDOW_MS until AROUND_PREFETCH_REDUCED_WINDOW_MS ->
                (safeRequested / 2).coerceAtLeast(2)
            else -> safeRequested
        }
        // Only a prefetch that actually runs starts a new window; skipped calls must not
        // keep extending it, or steady fast scrolling would never prefetch at all.
        if (count > 0) lastAroundPrefetchAtMs = now
        return count
    }
    
    /**
     * Prefetch a single image URL
     */
    fun prefetch(context: Context, url: String?) {
        if (url.isNullOrBlank()) return
        if (!shouldEnqueue(url)) return
        
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(POSTER_WIDTH, POSTER_HEIGHT)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            // Don't need the result, just cache it
            .memoryCacheKey(url)
            .diskCacheKey(url)
            .build()
        
        context.imageLoader.enqueue(request)
    }
    
    /**
     * Prefetch multiple images around a focus index
     * Symmetric prefetching: equal in both directions for smooth bi-directional scrolling
     */
    fun prefetchAround(
        context: Context,
        items: List<String?>,
        focusedIndex: Int,
        count: Int = PREFETCH_COUNT
    ) {
        val aroundCount = effectiveAroundCount(count)
        if (aroundCount == 0) return

        // Prefetch ahead (right direction)
        for (i in 1..aroundCount) {
            val aheadIndex = focusedIndex + i
            if (aheadIndex < items.size) {
                prefetch(context, items[aheadIndex])
            }
        }

        // Prefetch behind (left direction) - symmetric for smooth reverse scrolling
        for (i in 1..aroundCount) {
            val behindIndex = focusedIndex - i
            if (behindIndex >= 0) {
                prefetch(context, items[behindIndex])
            }
        }
    }

    /**
     * Prefetch a single landscape-sized image URL
     */
    fun prefetchLandscape(context: Context, url: String?) {
        if (url.isNullOrBlank()) return
        if (!shouldEnqueue(url)) return

        val request = ImageRequest.Builder(context)
            .data(url)
            .size(LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCacheKey(url)
            .diskCacheKey(url)
            .build()

        context.imageLoader.enqueue(request)
    }

    /**
     * Prefetch landscape images around a focus index
     */
    fun prefetchAroundLandscape(
        context: Context,
        items: List<String?>,
        focusedIndex: Int,
        count: Int = PREFETCH_COUNT
    ) {
        val aroundCount = effectiveAroundCount(count)
        if (aroundCount == 0) return

        for (i in 1..aroundCount) {
            val aheadIndex = focusedIndex + i
            if (aheadIndex < items.size) {
                prefetchLandscape(context, items[aheadIndex])
            }
        }

        for (i in 1..aroundCount) {
            val behindIndex = focusedIndex - i
            if (behindIndex >= 0) {
                prefetchLandscape(context, items[behindIndex])
            }
        }
    }
}
