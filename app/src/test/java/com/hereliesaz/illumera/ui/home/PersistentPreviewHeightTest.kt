package com.hereliesaz.illumera.ui.home

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** Fixed viewport expectations: the stage takes precedence over a fully visible final row. */
class PersistentPreviewHeightTest {
    @Test
    fun fullSizedTvKeepsTheEntireHeroHeight() {
        assertEquals(420.dp, persistentPreviewHeight(720.dp))
    }

    @Test
    fun smallerTvReservesOneRailWithoutTruncatingTheStage() {
        assertEquals(300.dp, persistentPreviewHeight(480.dp))
    }

    @Test
    fun compactViewportRemainsWithinTheVisibleScreen() {
        assertEquals(140.dp, persistentPreviewHeight(320.dp))
    }
}
