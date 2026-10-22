package com.hereliesaz.illumera.ui.player.base

import org.junit.Assert.assertEquals
import org.junit.Test

class Stereo3dTest {
    @Test
    fun detectsSideBySidePackings() {
        assertEquals(StereoLayout.SIDE_BY_SIDE, Stereo3d.detect("Avatar.2009.1080p.3D.HSBS.BluRay"))
        assertEquals(StereoLayout.SIDE_BY_SIDE, Stereo3d.detect("Movie 3D Half-SBS x264"))
        assertEquals(StereoLayout.SIDE_BY_SIDE, Stereo3d.detect("Gravity 2013 3D 1080p"))
    }

    @Test
    fun detectsTopBottomPackings() {
        assertEquals(StereoLayout.TOP_BOTTOM, Stereo3d.detect("Hugo.2011.3D.HOU.1080p"))
        assertEquals(StereoLayout.TOP_BOTTOM, Stereo3d.detect("Film 3D Half-OU"))
        assertEquals(StereoLayout.TOP_BOTTOM, Stereo3d.detect("Title 3D TAB 1080p"))
    }

    @Test
    fun ordinaryTitlesAreNot3d() {
        assertEquals(StereoLayout.OFF, Stereo3d.detect("Le Bon ou le Mauvais 1080p WEB-DL"))
        assertEquals(StereoLayout.OFF, Stereo3d.detect("Show S01E02 2160p HDR"))
        assertEquals(StereoLayout.OFF, Stereo3d.detect(null, ""))
    }

    @Test
    fun eyeAspectAccountsForFullAndHalfPacking() {
        assertEquals(16f / 9f, Stereo3d.eyeAspect(StereoLayout.SIDE_BY_SIDE, 32f / 9f), 0.01f)
        assertEquals(16f / 9f, Stereo3d.eyeAspect(StereoLayout.SIDE_BY_SIDE, 16f / 9f), 0.01f)
        assertEquals(16f / 9f, Stereo3d.eyeAspect(StereoLayout.TOP_BOTTOM, 8f / 9f), 0.01f)
        assertEquals(16f / 9f, Stereo3d.eyeAspect(StereoLayout.TOP_BOTTOM, 16f / 9f), 0.01f)
    }
}
