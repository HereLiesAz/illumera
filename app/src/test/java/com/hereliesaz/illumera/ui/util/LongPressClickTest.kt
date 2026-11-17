package com.hereliesaz.illumera.ui.util

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LongPressClickTest {
    @Suppress("DEPRECATION")
    @get:Rule
    val compose = createComposeRule()

    private var clicks = 0
    private var longs = 0

    private fun setUp() {
        clicks = 0; longs = 0
        compose.setContent {
            Box(
                Modifier.size(100.dp).testTag("t")
                    .longPressClick(onClick = { clicks++ }, onLongClick = { longs++ })
                    .focusable()
            )
        }
    }

    @Test fun aTapIsAClickNotALongPress() {
        setUp()
        compose.onNodeWithTag("t").performClick()
        assertEquals(1, clicks)
        assertEquals(0, longs)
    }

    @Test fun aHeldTouchIsALongPressNotAClick() {
        setUp()
        compose.onNodeWithTag("t").performTouchInput { longClick(durationMillis = 1_500) }
        assertEquals(0, clicks)
        assertEquals(1, longs)
    }

    @Test fun aQuickOkPressOnTheRemoteIsAClick() {
        setUp()
        compose.onNodeWithTag("t").requestFocus()
        compose.onNodeWithTag("t").performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, clicks)
        assertEquals(0, longs)
    }
}
