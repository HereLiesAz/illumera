package com.hereliesaz.illumera.ui.navigation

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The side menu is open exactly while it has focus. These pin down the owner's rules:
 * focus may only enter it by moving Left (or an explicit [openNavDrawer]), and closing it
 * returns focus to whatever content element had it before, without navigating.
 */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavDrawerFocusTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var focusManager: FocusManager
    private lateinit var requesters: Map<NavDestination, FocusRequester>
    private val first = FocusRequester()
    private val second = FocusRequester()
    private val entry = FocusRequester()
    private var closeCalls = 0
    private val navigations = mutableListOf<NavDestination>()

    private fun setUp() {
        compose.setContent {
            focusManager = LocalFocusManager.current
            requesters = remember { NavDestination.entries.associateWith { FocusRequester() } }
            NavDrawer(
                currentDestination = NavDestination.Home,
                currentProfile = null,
                drawerRequesters = requesters,
                onNavigate = { navigations += it },
                onClose = {
                    closeCalls++
                    entry.requestFocus()
                }
            ) {
                Row(Modifier.fillMaxSize().padding(start = 120.dp)) {
                    Box(Modifier.size(100.dp).testTag("first").focusRequester(first).focusRequester(entry).focusable())
                    Spacer(Modifier.width(20.dp))
                    Box(Modifier.size(100.dp).testTag("second").focusRequester(second).focusable())
                }
            }
        }
    }

    private fun menuItem(label: String) = compose.onNodeWithContentDescription(label, useUnmergedTree = false)

    private fun assertMenuClosed() {
        NavDestination.entries
            .filter { it != NavDestination.Profile }
            .forEach { menuItem(it.label).assertIsNotFocused() }
    }

    @Test
    fun plainRequestFocusAndDefaultFocusCannotOpenTheMenu() {
        setUp()
        compose.runOnIdle { requesters.getValue(NavDestination.Home).requestFocus() }
        compose.waitForIdle()
        menuItem("Home").assertIsNotFocused()

        compose.runOnIdle { focusManager.moveFocus(FocusDirection.Enter) }
        compose.waitForIdle()
        assertMenuClosed()
    }

    @Test
    fun leftWithNothingFocusedDoesNotOpenTheMenu() {
        setUp()
        compose.runOnIdle { focusManager.moveFocus(FocusDirection.Left) }
        compose.waitForIdle()
        assertMenuClosed()
        compose.onNode(isFocused() and !hasTestTag("first") and !hasTestTag("second")).assertDoesNotExist()
    }

    @Test
    fun onlyLeftFromTheLeftEdgeOpensTheMenu() {
        setUp()
        compose.runOnIdle { second.requestFocus() }
        // Left from the second item moves within the content, not into the menu.
        compose.runOnIdle { focusManager.moveFocus(FocusDirection.Left) }
        compose.onNodeWithTag("first").assertIsFocused()
        assertMenuClosed()

        // Up / Down never reach the menu.
        compose.runOnIdle {
            focusManager.moveFocus(FocusDirection.Up)
            focusManager.moveFocus(FocusDirection.Down)
        }
        assertMenuClosed()

        // Left at the natural left edge does.
        compose.runOnIdle { focusManager.moveFocus(FocusDirection.Left) }
        compose.onNodeWithTag("first").assertIsNotFocused()
        // Focus is now on the nearest rail item (not content).
        compose.onNode(isFocused() and !hasTestTag("first") and !hasTestTag("second")).assertExists()
    }

    @Test
    fun tappingToTheRightOfTheOpenMenuClosesItWithoutNavigating() {
        setUp()
        compose.runOnIdle { second.requestFocus() }
        compose.runOnIdle { requesters.getValue(NavDestination.Home).openNavDrawer() }
        menuItem("Home").assertIsFocused()
        // The tap lands on the expansion shadow, not on the content under it.
        compose.onNodeWithTag("second").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("second").assertIsFocused()
        assertMenuClosed()
        assertTrue(navigations.isEmpty())
    }

    @Test
    fun rightClosesAndRestoresThePreviouslyFocusedContent() {
        setUp()
        compose.runOnIdle { second.requestFocus() }
        compose.runOnIdle { assertTrue(requesters.getValue(NavDestination.Home).openNavDrawer()) }
        menuItem("Home").assertIsFocused()

        menuItem("Home").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("second").assertIsFocused()
        assertMenuClosed()
        assertEquals(0, closeCalls)
        assertTrue(navigations.isEmpty())
    }

    @Test
    fun backClosesWithoutNavigating() {
        setUp()
        compose.runOnIdle { second.requestFocus() }
        compose.runOnIdle { requesters.getValue(NavDestination.Home).openNavDrawer() }
        menuItem("Home").performKeyInput { pressKey(Key.Back) }
        compose.onNodeWithTag("second").assertIsFocused()
        assertTrue(navigations.isEmpty())
    }

    @Test
    fun closingWithNothingToRestoreFallsBackToTheEntryRequester() {
        setUp()
        compose.runOnIdle { assertTrue(requesters.getValue(NavDestination.Home).openNavDrawer()) }
        menuItem("Home").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("first").assertIsFocused()
        assertEquals(1, closeCalls)
    }

    @Test
    fun menuCannotBeLeftByUpDownTraversal() {
        setUp()
        compose.runOnIdle { second.requestFocus() }
        compose.runOnIdle {
            assertTrue(requesters.getValue(NavDestination.Exit).openNavDrawer())
        }
        menuItem("Exit").assertIsFocused()
        compose.runOnIdle { focusManager.moveFocus(FocusDirection.Down) }
        menuItem("Exit").assertIsFocused()
        compose.runOnIdle {
            assertTrue(requesters.getValue(NavDestination.Search).openNavDrawer())
        }
        menuItem("Search").assertIsFocused()
        compose.runOnIdle { focusManager.moveFocus(FocusDirection.Up) }
        compose.runOnIdle { focusManager.moveFocus(FocusDirection.Up) }
        compose.onNodeWithTag("first").assertIsNotFocused()
        compose.onNodeWithTag("second").assertIsNotFocused()
    }

    @Test
    fun exitTrapsDownButStillAllowsUpToSettingsAndBack() {
        setUp()
        compose.runOnIdle { second.requestFocus() }
        compose.runOnIdle {
            assertTrue(requesters.getValue(NavDestination.Exit).openNavDrawer())
        }
        menuItem("Exit").assertIsFocused()

        compose.runOnIdle { focusManager.moveFocus(FocusDirection.Down) }
        menuItem("Exit").assertIsFocused()
        compose.runOnIdle { focusManager.moveFocus(FocusDirection.Up) }
        menuItem("Settings").assertIsFocused()
        compose.runOnIdle { focusManager.moveFocus(FocusDirection.Down) }
        menuItem("Exit").assertIsFocused()
        compose.onNodeWithTag("second").assertIsNotFocused()
    }

    @Test
    fun programmaticContentFocusWhileOpenGoesWhereRequested() {
        setUp()
        compose.runOnIdle { second.requestFocus() }
        compose.runOnIdle { requesters.getValue(NavDestination.Home).openNavDrawer() }
        // e.g. a newly selected screen focusing its own entry element
        compose.runOnIdle { first.requestFocus() }
        compose.onNodeWithTag("first").assertIsFocused()
        assertMenuClosed()
    }

    @Test
    fun swipeFromLeftEdgeOpensAndSwipeRightOnMenuCloses() {
        setUp()
        compose.runOnIdle { second.requestFocus() }
        // A swipe that starts well inside the content does nothing.
        compose.onNodeWithTag("second").performTouchInput { swipeRight() }
        compose.onNodeWithTag("second").assertIsFocused()

        menuItem("Home").performTouchInput {
            swipeRight(startX = 1f, endX = 1f + 70.dp.toPx())
        }
        compose.waitForIdle()
        menuItem("Home").assertIsFocused()

        menuItem("Home").performTouchInput { swipeRight(startX = 60.dp.toPx(), endX = 60.dp.toPx() + 100.dp.toPx()) }
        compose.waitForIdle()
        compose.onNodeWithTag("second").assertIsFocused()
        assertTrue(navigations.isEmpty())
    }

    @Test
    fun swipeNotStartingAtTheEdgeDoesNotOpen() {
        setUp()
        menuItem("Home").performTouchInput {
            swipeRight(startX = 40.dp.toPx(), endX = 40.dp.toPx() + 70.dp.toPx())
        }
        compose.waitForIdle()
        assertFalse(runCatching { menuItem("Home").assertIsFocused() }.isSuccess)
    }
}
