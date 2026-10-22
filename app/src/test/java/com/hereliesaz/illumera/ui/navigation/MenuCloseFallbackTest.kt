package com.hereliesaz.illumera.ui.navigation

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Watchlist "Refresh suggestions" case: the element that had focus when the menu opened
 * becomes unfocusable and the screen's entry point isn't composed. Closing the menu must
 * still put focus back in the content instead of leaving the viewer stuck in the menu.
 */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MenuCloseFallbackTest {
    // v2 rules queue coroutines (StandardTestDispatcher); these tests rely on immediate dispatch.
    @Suppress("DEPRECATION")
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rightLeavesTheMenuEvenWhenTheSavedElementAndEntryAreGone() {
        val pressed = FocusRequester()
        val detachedEntry = FocusRequester() // never attached, like an empty list's entry
        lateinit var requesters: Map<NavDestination, FocusRequester>
        var pressedFocusable by mutableStateOf(true)
        compose.setContent {
            requesters = remember { NavDestination.entries.associateWith { FocusRequester() } }
            NavDrawer(
                currentDestination = NavDestination.Watchlist,
                currentProfile = null,
                drawerRequesters = requesters,
                onNavigate = {},
                onClose = { detachedEntry.requestFocus() }
            ) {
                Row(Modifier.fillMaxSize().padding(start = 120.dp)) {
                    Box(Modifier.size(100.dp).testTag("pressed").focusRequester(pressed).focusable(enabled = pressedFocusable))
                    Spacer(Modifier.width(20.dp))
                    Box(Modifier.size(100.dp).testTag("other").focusable())
                }
            }
        }
        compose.runOnIdle { pressed.requestFocus() }
        compose.runOnIdle { requesters.getValue(NavDestination.Watchlist).openNavDrawer() }
        compose.onNodeWithContentDescription("Watchlist").assertIsFocused()

        compose.runOnIdle { pressedFocusable = false }
        compose.onNodeWithContentDescription("Watchlist").performKeyInput { pressKey(Key.DirectionRight) }

        compose.onNodeWithContentDescription("Watchlist").assertIsNotFocused()
        compose.onNodeWithTag("other").assertIsFocused()
    }
}
