package com.hereliesaz.illumera.ui.navigation

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.hereliesaz.illumera.ui.navigation.focus.RestoreFocusOnResume
import com.hereliesaz.illumera.ui.navigation.focus.focusMemoryRoot
import com.hereliesaz.illumera.ui.navigation.focus.rememberFocusMemory
import com.hereliesaz.illumera.ui.navigation.focus.restorableFocus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Back at a main screen, set up as MainActivity sets it up: the screen unwinds its own state,
 * then root Back opens the menu (through the openNavDrawer gate), and Back on a menu that root
 * Back opened leaves the app. A menu opened by Left or a swipe closes on Back instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackPolicyTest {
    // v2 rules queue coroutines (StandardTestDispatcher); these tests rely on immediate dispatch.
    @Suppress("DEPRECATION")
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var backStack: NavBackStack<NavKey>
    private lateinit var requesters: Map<NavDestination, FocusRequester>
    private lateinit var shell: MainShellState
    private lateinit var focusManager: FocusManager
    private val primary = FocusRequester()
    private val option2 = FocusRequester()
    private var exits = 0
    private var query by mutableStateOf("")

    /** A main screen with a query to clear first, like Search. */
    @Composable
    private fun RootScreen() {
        val memory = rememberFocusMemory()
        RestoreFocusOnResume(memory = memory, fallback = primary)
        BackHandler(enabled = query.isNotEmpty()) { query = "" }
        Row(Modifier.fillMaxSize().padding(start = 120.dp, top = 90.dp).focusMemoryRoot(memory)) {
            Box(
                Modifier.size(100.dp).testTag("option1").focusRequester(primary)
                    .restorableFocus("option1", memory).focusable()
            )
            Spacer(Modifier.width(20.dp))
            Box(
                Modifier.size(100.dp).testTag("option2").focusRequester(option2)
                    .restorableFocus("option2", memory).focusable()
            )
        }
    }

    private fun setUp() {
        compose.setContent {
            backStack = rememberAppBackStack()
            requesters = remember { NavDestination.entries.associateWith { FocusRequester() } }
            focusManager = LocalFocusManager.current
            shell = rememberMainShellState()
            val top = backStack.last()
            MainShell(
                navPosition = "left",
                showChrome = isMainAreaKey(top),
                currentDestination = BackStackOps.currentRoot(backStack).destination,
                currentProfile = null,
                menuRequesters = requesters,
                onNavigate = { BackStackOps.navigateToMainRoot(backStack, it) },
                onEnterContent = { runCatching { primary.requestFocus() } },
                onLogout = {},
                onExit = { exits++ },
                state = shell
            ) {
                NavDisplay(
                    backStack = backStack,
                    onBack = { BackStackOps.pop(backStack) },
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                        rememberMenuBackNavEntryDecorator(shell)
                    ),
                    transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                    popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                    predictivePopTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                    entryProvider = entryProvider {
                        entry<HomeKey> {
                            MainRootBackHandler(
                                state = shell,
                                menuRequester = requesters.getValue(NavDestination.Home),
                                fallbackRequester = requesters.getValue(NavDestination.Home)
                            )
                            RootScreen()
                        }
                        entry<DetailsKey> { Text("details") }
                    }
                )
            }
        }
        compose.waitForIdle()
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun menuItem(label: String) = compose.onNodeWithContentDescription(label)

    private fun focusOption2() {
        compose.runOnIdle { option2.requestFocus() }
        compose.onNodeWithTag("option2").assertIsFocused()
    }

    @Test
    fun rootBackOpensTheMenuAndBackAgainExits() {
        setUp()
        focusOption2()

        pressBack()
        menuItem("Home").assertIsFocused()
        assertEquals(MenuOpener.RootBack, shell.openedBy)
        assertEquals(0, exits)

        pressBack()
        assertEquals(1, exits)
        assertEquals(1, backStack.size)
    }

    @Test
    fun backKeyOnAMenuThatRootBackOpenedExits() {
        setUp()
        focusOption2()
        pressBack()
        menuItem("Home").assertIsFocused()

        // The D-pad Back key reaches the rail before the dispatcher does.
        menuItem("Home").performKeyInput { pressKey(Key.Back) }
        compose.waitForIdle()
        assertEquals(1, exits)
    }

    @Test
    fun rootBackGoesThroughTheOpenNavDrawerGate() {
        setUp()
        focusOption2()
        // A plain requestFocus on the menu is refused; root Back is let in.
        compose.runOnIdle { requesters.getValue(NavDestination.Home).requestFocus() }
        compose.waitForIdle()
        menuItem("Home").assertIsNotFocused()
        assertFalse(shell.isMenuOpen)

        pressBack()
        assertTrue(shell.isMenuOpen)
        menuItem("Home").assertIsFocused()
    }

    @Test
    fun menuOpenedByLeftClosesOnBack() {
        setUp()
        compose.runOnIdle { primary.requestFocus() }
        compose.runOnIdle { focusManager.moveFocus(FocusDirection.Left) }
        compose.waitForIdle()
        assertTrue(shell.isMenuOpen)
        assertEquals(MenuOpener.Left, shell.openedBy)

        pressBack()
        compose.onNodeWithTag("option1").assertIsFocused()
        assertFalse(shell.isMenuOpen)
        assertEquals(0, exits)

        // And through the rail's own key handling.
        compose.runOnIdle { focusManager.moveFocus(FocusDirection.Left) }
        compose.waitForIdle()
        menuItem("Home").performKeyInput { pressKey(Key.Back) }
        compose.waitForIdle()
        compose.onNodeWithTag("option1").assertIsFocused()
        assertEquals(0, exits)
    }

    @Test
    fun menuOpenedBySwipeClosesOnBack() {
        setUp()
        focusOption2()
        menuItem("Home").performTouchInput { swipeRight(startX = 1f, endX = 1f + 70.dp.toPx()) }
        compose.waitForIdle()
        menuItem("Home").assertIsFocused()
        assertEquals(MenuOpener.Swipe, shell.openedBy)

        pressBack()
        compose.onNodeWithTag("option2").assertIsFocused()
        assertEquals(0, exits)
    }

    @Test
    fun rightClosesEvenWhenRootBackOpenedTheMenu() {
        setUp()
        focusOption2()
        pressBack()
        menuItem("Home").performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNodeWithTag("option2").assertIsFocused()
        assertEquals(0, exits)

        // Opened by root Back again, the new opener decides.
        pressBack()
        assertEquals(MenuOpener.RootBack, shell.openedBy)
    }

    @Test
    fun theScreenUnwindsItsOwnStateBeforeTheMenuOpens() {
        setUp()
        focusOption2()
        compose.runOnIdle { query = "dune" }
        compose.waitForIdle()

        pressBack()
        assertEquals("", query)
        assertFalse(shell.isMenuOpen)
        compose.onNodeWithTag("option2").assertIsFocused()

        pressBack()
        menuItem("Home").assertIsFocused()
    }

    @Test
    fun backFromAPageAboveTheRootRestoresFocusWithoutOpeningTheMenu() {
        setUp()
        focusOption2()
        compose.runOnUiThread { BackStackOps.openDetails(backStack, "movie", "tt1") }
        compose.waitForIdle()

        pressBack()
        assertTrue(backStack.single() is HomeKey)
        compose.onNodeWithTag("option2").assertIsFocused()
        assertFalse(shell.isMenuOpen)
    }
}
