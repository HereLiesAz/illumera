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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The main area as MainActivity composes it: [MainShell] over a NavDisplay whose bottom entry
 * is one main root. Menu selection, re-selection, the layout switch and Back at a root.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Stands in for an entry-scoped screen ViewModel. */
    class ScreenVm : ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }

    private lateinit var backStack: NavBackStack<NavKey>
    private lateinit var requesters: Map<NavDestination, FocusRequester>
    private var navPosition by mutableStateOf("left")
    private val entry = FocusRequester()
    private val option2 = FocusRequester()
    private var nextToken = 0
    private val tokens = mutableMapOf<NavKey, Int>()
    private val vms = mutableMapOf<NavKey, ScreenVm>()

    /** A main screen: two options, and Back opens the menu once nothing else wants it. */
    @Composable
    private fun RootScreen(key: MainRootKey) {
        tokens[key] = rememberSaveable { ++nextToken }
        vms[key] = viewModel { ScreenVm() }
        BackHandler { requesters.getValue(key.destination).openNavDrawer() }
        Row(Modifier.fillMaxSize().padding(start = 120.dp, top = 90.dp)) {
            Box(Modifier.size(100.dp).testTag("option1").focusRequester(entry).focusable())
            Spacer(Modifier.width(20.dp))
            Box(Modifier.size(100.dp).testTag("option2").focusRequester(option2).focusable())
        }
    }

    private fun setUp() {
        compose.setContent {
            backStack = rememberNavBackStack(AppBackStackConfiguration, HomeKey())
            requesters = remember { NavDestination.entries.associateWith { FocusRequester() } }
            val top = backStack.last()
            val shellState = rememberMainShellState()
            MainShell(
                navPosition = navPosition,
                showChrome = isMainAreaKey(top),
                currentDestination = BackStackOps.currentRoot(backStack).destination,
                currentProfile = null,
                menuRequesters = requesters,
                onNavigate = { BackStackOps.navigateToMainRoot(backStack, it) },
                onEnterContent = { runCatching { entry.requestFocus() } },
                onLogout = {},
                onExit = {},
                state = shellState
            ) {
                NavDisplay(
                    backStack = backStack,
                    onBack = { BackStackOps.pop(backStack) },
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                        rememberMenuBackNavEntryDecorator(shellState)
                    ),
                    transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                    popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                    predictivePopTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                    entryProvider = entryProvider {
                        entry<HomeKey> { RootScreen(it) }
                        entry<MoviesKey> { RootScreen(it) }
                        entry<SeriesKey> { RootScreen(it) }
                        entry<SearchKey> { RootScreen(it) }
                        entry<WatchlistKey> { RootScreen(it) }
                        entry<AddonsKey> { RootScreen(it) }
                        entry<SettingsKey> { RootScreen(it) }
                        entry<GridKey> { Text("grid") }
                        entry<DetailsKey> { Text("details") }
                    }
                )
            }
        }
        compose.waitForIdle()
    }

    private fun onStack(change: (NavBackStack<NavKey>) -> Unit) {
        compose.runOnUiThread { change(backStack) }
        compose.waitForIdle()
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun menuItem(label: String) = compose.onNodeWithContentDescription(label)

    // ---- BackStackOps ----

    @Test
    fun menuSelectionFromDetailsLeavesThatRootAlone() {
        val s = mutableListOf<NavKey>(HomeKey(), DetailsKey("movie", "A"), CastKey(7, "Actor"), PlayerKey)
        assertTrue(BackStackOps.navigateToMainRoot(s, NavDestination.Movies))
        assertEquals(listOf<NavKey>(MoviesKey(nonce = 1)), s)
    }

    @Test
    fun logOutAndExitAreActionsNotRoots() {
        val s = mutableListOf<NavKey>(SearchKey(), DetailsKey("movie", "A"))
        assertFalse(BackStackOps.navigateToMainRoot(s, NavDestination.Profile))
        assertFalse(BackStackOps.navigateToMainRoot(s, NavDestination.Exit))
        assertEquals(listOf(SearchKey(), DetailsKey("movie", "A")), s)
    }

    @Test
    fun addonsIsANavigableSavedMainRootRatherThanASettingsSection() {
        val s = mutableListOf<NavKey>(SettingsKey(), DetailsKey("movie", "tt1"))
        assertTrue(BackStackOps.navigateToMainRoot(s, NavDestination.Addons))
        assertEquals(listOf<NavKey>(AddonsKey(nonce = 1)), s)
        assertEquals("Add-ons", NavDestination.Addons.label)
    }

    @Test
    fun reselectingTheCurrentRootMakesADistinctKey() {
        val s = mutableListOf<NavKey>(SettingsKey())
        BackStackOps.navigateToMainRoot(s, NavDestination.Settings)
        assertEquals(listOf<NavKey>(SettingsKey(nonce = 1)), s)
        assertNotEquals(SettingsKey(), s.single())
    }

    @Test
    fun everyMainRootSurvivesSaveAndRestore() {
        val tester = StateRestorationTester(compose)
        var restored: NavBackStack<NavKey>? = null
        tester.setContent { restored = rememberNavBackStack(AppBackStackConfiguration, HomeKey()) }
        for (destination in listOf(
            NavDestination.Movies, NavDestination.Series, NavDestination.Search,
            NavDestination.Watchlist, NavDestination.Addons,
            NavDestination.Settings, NavDestination.Home
        )) {
            compose.runOnUiThread { BackStackOps.navigateToMainRoot(restored!!, destination) }
            val before = restored!!.toList()
            restored = null
            tester.emulateSavedInstanceStateRestore()
            assertEquals(before, restored!!.toList())
            assertEquals(destination, (restored!!.single() as MainRootKey).destination)
        }
    }

    // ---- MainShell over NavDisplay ----

    @Test
    fun selectingAMenuItemFromTheGridLandsOnThatRootAlone() {
        setUp()
        onStack { BackStackOps.openGrid(it, "Popular", "cfg") }
        compose.onNodeWithTag("option1").assertDoesNotExist()

        // The grid shows the menu; choosing Series there leaves Series alone on the stack.
        menuItem("Series").performClick()
        compose.waitForIdle()
        assertEquals(1, backStack.size)
        assertTrue(backStack.single() is SeriesKey)
        compose.onNodeWithTag("option1").assertExists()
    }

    @Test
    fun menuIsHiddenOverDetails() {
        setUp()
        onStack { BackStackOps.openDetails(it, "movie", "tt1") }
        menuItem("Home").assertDoesNotExist()
        pressBack()
        assertTrue(backStack.single() is HomeKey)
        menuItem("Home").assertExists()
    }

    @Test
    fun reselectingTheCurrentScreenRecreatesItsEntry() {
        setUp()
        val first = backStack.single()
        val firstToken = tokens.getValue(first)
        val firstVm = vms.getValue(first)

        menuItem("Home").performClick()
        compose.waitForIdle()

        val second = backStack.single()
        assertTrue(second is HomeKey)
        assertNotEquals(first, second)
        assertNotEquals(firstToken, tokens.getValue(second))
        assertNotSame(firstVm, vms.getValue(second))
        assertTrue(firstVm.cleared)
    }

    @Test
    fun switchingMenuLayoutKeepsTheStackTheScreenAndItsFocus() {
        setUp()
        menuItem("Settings").performClick()
        compose.waitForIdle()
        val settings = backStack.single()
        assertTrue(settings is SettingsKey)
        val token = tokens.getValue(settings)
        val vm = vms.getValue(settings)
        focusOption2()

        // No delay, no refocus: the screen never leaves the tree.
        compose.runOnIdle { navPosition = "top" }
        compose.waitForIdle()
        compose.onNodeWithTag("option2").assertIsFocused()
        assertEquals(listOf<NavKey>(settings), backStack.toList())
        assertEquals(token, tokens.getValue(settings))
        assertSame(vm, vms.getValue(settings))

        compose.runOnIdle { navPosition = "left" }
        compose.waitForIdle()
        compose.onNodeWithTag("option2").assertIsFocused()
        assertEquals(token, tokens.getValue(settings))
    }

    @Test
    fun switchingMenuLayoutOverTheGridKeepsTheStack() {
        setUp()
        onStack { BackStackOps.openGrid(it, "Popular", "cfg") }
        val before = backStack.toList()
        compose.runOnIdle { navPosition = "top" }
        compose.waitForIdle()
        assertEquals(before, backStack.toList())
        compose.onNodeWithText("grid").assertExists()
    }

    @Test
    fun backAtARootOpensTheMenuAndBackAgainClosesIt() {
        setUp()
        focusOption2()

        pressBack()
        menuItem("Home").assertIsFocused()
        assertEquals(1, backStack.size)

        pressBack()
        compose.onNodeWithTag("option2").assertIsFocused()
        assertEquals(1, backStack.size)
    }

    @Test
    fun anOpenMenuStillTakesBackFirstAfterReturningToTheRoot() {
        setUp()
        onStack { BackStackOps.openGrid(it, "Popular", "cfg") }
        // The grid has no Back of its own here: NavDisplay pops it.
        pressBack()
        assertTrue(backStack.single() is HomeKey)

        // The root screen was composed again after the menu; the menu's handler still wins.
        focusOption2()
        pressBack()
        menuItem("Home").assertIsFocused()
        pressBack()
        compose.onNodeWithTag("option2").assertIsFocused()
        menuItem("Home").assertIsNotFocused()
    }

    private fun focusOption2() {
        compose.runOnIdle { option2.requestFocus() }
        compose.onNodeWithTag("option2").assertIsFocused()
    }
}
