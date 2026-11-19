package com.hereliesaz.illumera.ui.utils

import androidx.compose.ui.unit.dp
import coil.size.Size
import coil.size.pxOrElse
import com.hereliesaz.illumera.ui.home.cinematicInlineWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSizingTest {
    private fun Size.px() = width.pxOrElse { -1 } to height.pxOrElse { -1 }

    @Test fun aPosterCardDecodesItsOwnPixelsPlusFocusScale() {
        // 140x210dp at 1.0 density, drawn 1.05x when focused: 147x221.
        assertEquals(147 to 221, posterDecodeSize(1f).px())
        // At the 1.33 density of a 1080p TV: 196x294, not the old fixed 280x420.
        assertEquals(196 to 294, posterDecodeSize(4f / 3f).px())
        // At 2.0: 294x441. The old fixed 280x420 was only ever right at one density.
        assertEquals(294 to 441, posterDecodeSize(2f).px())
    }

    @Test fun aPosterWiderThanTheDefaultDecodesMore() {
        val wide = posterDecodeSize(2f, widthDp = 200f).px()
        assertTrue(wide.first > posterDecodeSize(2f).px().first)
        assertTrue(kotlin.math.abs(wide.first * 3 - wide.second * 2) <= 3)
    }

    @Test fun aLandscapeCardDecodesTheOwnPixelsPlusFocusScale() {
        // 190dp at 1.33 density: ceil(190 * 1.3333 * 1.05) = 266 wide, 16:9.
        val (w, h) = landscapeDecodeSize(4f / 3f).px()
        assertEquals(266, w)
        assertEquals(150, h)
    }

    @Test fun theInRowPreviewIsAboutFiveHundredBySixHundredEightyAtTvDensity() {
        val (w, h) = inlinePreviewDecodeSize(4f / 3f).px()
        assertTrue("width $w", w in 480..540)
        assertTrue("height $h", h in 270..300)
        val (fullW, fullH) = Size(1920, 1080).px()
        // A small fraction of a 1080p backdrop.
        assertTrue(w.toLong() * h < fullW.toLong() * fullH / 6)
    }

    @Test fun thePreviewNeverDecodesMoreThanAFullBackdrop() {
        val (w, h) = inlinePreviewDecodeSize(8f).px()
        assertEquals(MAX_BACKDROP_WIDTH_PX, w)
        assertTrue(h <= 1080)
        // The cap shrinks both sides together, so the 16:9 shape survives.
        assertEquals(w * 9.0 / 16, h.toDouble(), 2.0)
    }

    @Test fun thePreviewGeometryMatchesTheRowsOwn() {
        // InfiniteLoopRow sizes the focused card from cinematicInlineWidth at a 210dp height; the
        // decode size must describe the same box.
        assertEquals(
            cinematicInlineWidth(2_000.dp, 0.dp).value,
            INLINE_PREVIEW_WIDTH_DP,
            0.01f
        )
    }
}
