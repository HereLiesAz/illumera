package com.hereliesaz.illumera.ui.utils

import android.content.Context
import coil.imageLoader
import coil.request.ImageRequest

/**
 * Netflix-grade image prefetcher for smooth scrolling.
 * Prefetches images ahead of the focused item to eliminate loading delays.
 */
object ImagePrefetcher {
    
    // Every request is built by CardImages, the same builder the cards use, so a prefetch has the
    // card's key, size and scale and the card finds it in the memory cache.

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
     * Prefetch a single poster URL at the size of a Home row poster card. [widthDp] null is for
     * the grids and lists whose cards are sized by their parent: they decode at
     * [CardImages.legacyPoster]'s fixed size, and so must the prefetch that warms them.
     */
    fun prefetch(
        context: Context,
        url: String?,
        widthDp: Float? = POSTER_CARD_WIDTH_DP
    ) {
        if (url.isNullOrBlank()) return
        if (!shouldEnqueue(url)) return

        context.imageLoader.enqueue(posterRequest(context, url, widthDp))
    }

    internal fun posterRequest(
        context: Context,
        url: String,
        widthDp: Float?,
        density: Float = context.resources.displayMetrics.density
    ): ImageRequest =
        if (widthDp == null) CardImages.legacyPoster(context, url)
        else CardImages.poster(context, url, density, widthDp)

    /**
     * Prefetch a title's cinematic backdrop. With [fullScreen] false (the default) it is decoded at
     * the size of the focused card's in-row preview, which is what the card paints from cache the
     * moment focus lands; [fullScreen] true is for the persistent preview, which fills the screen.
     */
    fun prefetchBackdrop(context: Context, url: String?, fullScreen: Boolean = false) {
        if (url.isNullOrBlank()) return
        if (!shouldEnqueue("backdrop:${if (fullScreen) "full" else "card"}:$url")) return

        context.imageLoader.enqueue(backdropRequest(context, url, fullScreen))
    }

    internal fun backdropRequest(
        context: Context,
        url: String,
        fullScreen: Boolean,
        density: Float = context.resources.displayMetrics.density
    ): ImageRequest =
        if (fullScreen) CardImages.fullBackdrop(context, url)
        else CardImages.inlineBackdrop(context, url, density)

    /**
     * Prefetch multiple images around a focus index
     * Symmetric prefetching: equal in both directions for smooth bi-directional scrolling
     */
    fun prefetchAround(
        context: Context,
        items: List<String?>,
        focusedIndex: Int,
        count: Int = PREFETCH_COUNT,
        widthDp: Float? = POSTER_CARD_WIDTH_DP
    ) {
        val aroundCount = effectiveAroundCount(count)
        if (aroundCount == 0) return

        // Prefetch ahead (right direction)
        for (i in 1..aroundCount) {
            val aheadIndex = focusedIndex + i
            if (aheadIndex < items.size) {
                prefetch(context, items[aheadIndex], widthDp)
            }
        }

        // Prefetch behind (left direction) - symmetric for smooth reverse scrolling
        for (i in 1..aroundCount) {
            val behindIndex = focusedIndex - i
            if (behindIndex >= 0) {
                prefetch(context, items[behindIndex], widthDp)
            }
        }
    }

    /**
     * Prefetch a single landscape-sized image URL
     */
    fun prefetchLandscape(context: Context, url: String?) {
        if (url.isNullOrBlank()) return
        if (!shouldEnqueue(url)) return

        context.imageLoader.enqueue(landscapeRequest(context, url))
    }

    internal fun landscapeRequest(
        context: Context,
        url: String,
        density: Float = context.resources.displayMetrics.density
    ): ImageRequest = CardImages.landscape(context, url, density)

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
