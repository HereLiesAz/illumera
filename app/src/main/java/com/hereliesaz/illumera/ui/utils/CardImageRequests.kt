package com.hereliesaz.illumera.ui.utils

import android.content.Context
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import coil.size.Size

/**
 * The one place that builds the image requests of Home's cards, so a card and the prefetch that
 * warms it cannot drift apart. Coil serves a memory-cache entry only for the same key, and only if
 * the entry suits the request's size and scale; a prefetch that differs in any of them decodes an
 * image that the card then throws away and decodes again.
 *
 * Every request here shares one memory key per image URL (a larger decode of a URL replaces a
 * smaller one and still serves the smaller request), the same disk key, FILL scale and INEXACT
 * precision (what `AsyncImage` applies to a Crop image), and hardware bitmaps.
 */
internal object CardImages {

    /** The memory-cache key every card request for [url] uses. */
    fun memoryKey(url: String): MemoryCache.Key = MemoryCache.Key(url)

    /** A card image of exactly [size]. */
    fun request(context: Context, url: String?, size: Size): ImageRequest {
        val builder = ImageRequest.Builder(context)
            .data(url)
            .size(size)
            .scale(Scale.FILL)
            .precision(Precision.INEXACT)
            .crossfade(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .allowHardware(true)
        if (!url.isNullOrBlank()) {
            builder.memoryCacheKey(memoryKey(url))
            builder.diskCacheKey(url)
        }
        return builder.build()
    }

    /** A Home row poster card (140dp wide, 2:3 by default). */
    fun poster(
        context: Context,
        url: String?,
        density: Float,
        widthDp: Float = POSTER_CARD_WIDTH_DP
    ): ImageRequest = request(context, url, posterDecodeSize(density, widthDp))

    /** A Home row landscape card (190dp wide, 16:9). */
    fun landscape(context: Context, url: String?, density: Float): ImageRequest =
        request(context, url, landscapeDecodeSize(density))

    /** The focused card's in-row preview backdrop (about 373x210dp). */
    fun inlineBackdrop(context: Context, url: String?, density: Float): ImageRequest =
        request(context, url, inlinePreviewDecodeSize(density))

    /**
     * A full-screen backdrop ([backdropDecodeSize]). Only the persistent preview and the Hero show
     * one this large.
     */
    fun fullBackdrop(context: Context, url: String?): ImageRequest =
        request(context, url, backdropDecodeSize(context))

    /**
     * The size cards had before they were sized from their layout, for the grids and lists whose
     * cell width is decided by their parent and so is not known to the card.
     */
    fun legacyPoster(context: Context, url: String?): ImageRequest =
        request(context, url, Size(LEGACY_POSTER_WIDTH_PX, LEGACY_POSTER_HEIGHT_PX))

    const val LEGACY_POSTER_WIDTH_PX = 280
    const val LEGACY_POSTER_HEIGHT_PX = 420
}
