package com.hereliesaz.illumera.ui.home

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** Fixed viewport expectations: one full rail plus the next row's heading and card tops. */
class PersistentPreviewHeightTest {
    @Test
    fun fullSizedTvKeepsTheEntireHeroHeight() {
        assertEquals(420.dp, persistentPreviewHeight(720.dp))
    }

    @Test
    fun tv1080pShowsTheNextRowBelowTheFirst() {
        assertEquals(290.dp, persistentPreviewHeight(540.dp))
    }

    @Test
    fun smallerTvReservesTheRowsBelowTheStage() {
        assertEquals(230.dp, persistentPreviewHeight(480.dp))
    }

    @Test
    fun compactViewportRemainsWithinTheVisibleScreen() {
        assertEquals(320.dp * 0.38f, persistentPreviewHeight(320.dp))
    }
}
