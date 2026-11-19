package com.hereliesaz.illumera.ui.utils

import android.content.Context
import coil.size.Size
import kotlin.math.ceil

/**
 * Decode size for full-bleed 16:9 backdrops: the screen's longest edge, capped at 1080p, so
 * phones don't decode TV-sized bitmaps. Landscape TVs still get 1920x1080. Only for backdrops
 * that really fill the screen (the Hero, the persistent preview, a hero background); a card or the
 * focused card's in-row preview uses [cardDecodeSize] / [inlinePreviewDecodeSize].
 */
fun backdropDecodeSize(context: Context): Size {
    val metrics = context.resources.displayMetrics
    val width = maxOf(metrics.widthPixels, metrics.heightPixels).coerceIn(640, MAX_BACKDROP_WIDTH_PX)
    return Size(width, width * 9 / 16)
}

/** The focused card is drawn at this scale (`ClickableSurfaceDefaults.scale(focusedScale = 1.05f)`). */
internal const val CARD_FOCUS_SCALE = 1.05f

/** Home row poster cards: 140dp wide, 2:3. */
internal const val POSTER_CARD_WIDTH_DP = 140f
internal const val POSTER_ASPECT = 2f / 3f

/** Home row landscape cards: 190dp wide, 16:9. */
internal const val LANDSCAPE_CARD_WIDTH_DP = 190f
internal const val LANDSCAPE_ASPECT = 16f / 9f

/**
 * The focused card's inline preview: 210dp high and 16:9, so about 373dp wide
 * (`cinematicInlineWidth` caps it lower only on screens narrower than that plus the row padding,
 * where this still decodes at most a little more than needed).
 */
internal const val INLINE_PREVIEW_HEIGHT_DP = 210f
internal const val INLINE_PREVIEW_WIDTH_DP = INLINE_PREVIEW_HEIGHT_DP * LANDSCAPE_ASPECT

/**
 * The pixels a card of [widthDp] x ([widthDp] / [aspect]) dp covers on a screen of [density]
 * pixels per dp, with room for the focus scale so the focused card is not drawn stretched. Never
 * more than [maxWidthPx] wide.
 */
internal fun cardDecodeSize(
    widthDp: Float,
    aspect: Float,
    density: Float,
    maxWidthPx: Int = Int.MAX_VALUE
): Size {
    val fullWidth = ceil(widthDp * density * CARD_FOCUS_SCALE)
    val shrink = if (fullWidth > maxWidthPx) maxWidthPx / fullWidth else 1f
    val width = ceil(fullWidth * shrink).toInt().coerceAtLeast(1)
    val height = ceil(widthDp / aspect * density * CARD_FOCUS_SCALE * shrink).toInt().coerceAtLeast(1)
    return Size(width, height)
}

/** [cardDecodeSize] for a Home row poster card. */
internal fun posterDecodeSize(density: Float, widthDp: Float = POSTER_CARD_WIDTH_DP): Size =
    cardDecodeSize(widthDp, POSTER_ASPECT, density)

/** [cardDecodeSize] for a Home row landscape card. */
internal fun landscapeDecodeSize(density: Float): Size =
    cardDecodeSize(LANDSCAPE_CARD_WIDTH_DP, LANDSCAPE_ASPECT, density)

/**
 * The focused card's in-row preview backdrop: what the 373x210dp preview box covers, not the
 * 1920x1080 a full-screen backdrop needs (that is a quarter of the pixels at 1.33 density).
 * Capped at [MAX_BACKDROP_WIDTH_PX] so a very dense phone never decodes more than a full backdrop.
 */
internal fun inlinePreviewDecodeSize(density: Float): Size =
    cardDecodeSize(INLINE_PREVIEW_WIDTH_DP, LANDSCAPE_ASPECT, density, MAX_BACKDROP_WIDTH_PX)

internal const val MAX_BACKDROP_WIDTH_PX = 1920
