package com.hereliesaz.illumera.ui.navigation.focus

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.hereliesaz.illumera.ui.home.resolveRowRestoreIndex
import com.hereliesaz.illumera.ui.navigation.MainShell
import com.hereliesaz.illumera.ui.navigation.MenuOpener
import com.hereliesaz.illumera.ui.navigation.NavDestination
import com.hereliesaz.illumera.ui.navigation.openNavDrawer
import com.hereliesaz.illumera.ui.navigation.rememberMainShellState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FocusMemory: a screen's focused element comes back when its entry resumes, a vanished one
 * gives way to its nearest neighbour, the key survives saved-state restore, and restore never
 * touches the menu.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FocusMemoryTest {
    // v2 rules queue coroutines (StandardTestDispatcher); these tests rely on immediate dispatch.
    @Suppress("DEPRECATION")
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var itemCount by mutableStateOf(4)
    private var shown by mutableStateOf(true)
    private val primary = FocusRequester()
    private lateinit var memory: FocusMemory

    /** A screen with a row of tagged cards "row:<i>"; its primary item is the first card. */
    @Composable
    private fun Screen(fallback: FocusRequester? = primary) {
        memory = rememberFocusMemory()
        RestoreFocusOnResume(memory = memory, fallback = fallback, enabled = itemCount > 0)
        Row(Modifier.fillMaxSize().padding(start = 120.dp, top = 90.dp).focusMemoryRoot(memory)) {
            repeat(itemCount) { index ->
                Box(
                    Modifier
                        .size(60.dp)
                        .testTag("card$index")
                        .then(if (index == 0) Modifier.focusRequester(primary) else Modifier)
                        .restorableFocus("row:$index", memory)
                        .focusable()
                )
                Spacer(Modifier.width(10.dp))
            }
        }
    }

    /** The screen as a back-stack entry: its saveable state outlives it leaving the screen. */
    private fun setUpEntry() {
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            if (shown) holder.SaveableStateProvider("entry") { Screen() }
        }
        compose.waitForIdle()
    }

    private fun card(index: Int) = compose.onNodeWithTag("card$index")

    private fun leaveAndReturn(change: () -> Unit = {}) {
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        compose.runOnIdle(change)
        compose.runOnIdle { shown = true }
        compose.waitForIdle()
    }

    @Test
    fun freshScreenFocusesItsPrimaryItem() {
        setUpEntry()
        card(0).assertIsFocused()
        assertEquals("row:0", memory.savedKey)
    }

    @Test
    fun taggedItemIsRestoredOnResume() {
        setUpEntry()
        compose.onNodeWithTag("card2").requestFocusViaSemantics()
        card(2).assertIsFocused()
        assertEquals("row:2", memory.savedKey)

        leaveAndReturn()
        card(2).assertIsFocused()
        card(0).assertIsNotFocused()
    }

    @Test
    fun removedItemGivesWayToItsNearestNeighbour() {
        setUpEntry()
        compose.onNodeWithTag("card3").requestFocusViaSemantics()

        // The row lost its last two cards while the page above it was showing.
        leaveAndReturn { itemCount = 2 }
        card(1).assertIsFocused()
    }

    @Test
    fun savedKeySurvivesSavedStateRestore() {
        val tester = StateRestorationTester(compose)
        tester.setContent { Screen() }
        compose.waitForIdle()
        compose.onNodeWithTag("card2").requestFocusViaSemantics()
        card(2).assertIsFocused()

        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        card(2).assertIsFocused()
        assertEquals("row:2", memory.savedKey)
    }

    @Test
    fun saverRoundTripsNoKeyAsNull() {
        val empty = FocusMemory()
        val saved = with(FocusMemory.Saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(empty) }!!
        assertNull(FocusMemory.Saver.restore(saved)!!.savedKey)
        val some = FocusMemory("poster:tt1")
        val saved2 = with(FocusMemory.Saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(some) }!!
        assertEquals("poster:tt1", FocusMemory.Saver.restore(saved2)!!.savedKey)
    }

    // ---- Decision 3: rows keep refreshing, the viewer keeps their place ----

    private var ids by mutableStateOf(listOf("a", "b", "c", "d"))
    private var generation by mutableStateOf(0)

    /** A Home-style row: cards keyed "0_<id>_<index>", re-keyed on every refresh. */
    @Composable
    private fun RefreshingRow() {
        memory = rememberFocusMemory()
        val currentIds = ids
        RestoreFocusOnResume(
            memory = memory,
            fallback = primary,
            neighbour = { saved ->
                resolveRowRestoreIndex(saved, 0, currentIds)?.let { "0_${currentIds[it]}_$it" }
            }
        )
        LazyRow(Modifier.fillMaxSize().padding(start = 120.dp).focusMemoryRoot(memory)) {
            itemsIndexed(currentIds, key = { index, id -> "${id}_${index}_$generation" }) { index, id ->
                Box(
                    Modifier
                        .size(60.dp)
                        .testTag("item_$id")
                        .then(if (index == 0) Modifier.focusRequester(primary) else Modifier)
                        .restorableFocus("0_${id}_$index", memory)
                        .focusable()
                )
            }
        }
    }

    @Test
    fun refreshThatReKeysRowsKeepsTheFocusedItem() {
        compose.setContent { RefreshingRow() }
        compose.waitForIdle()
        compose.onNodeWithTag("item_c").requestFocusViaSemantics()
        compose.onNodeWithTag("item_c").assertIsFocused()

        // Same items, new keys: every card is recomposed from scratch.
        compose.runOnIdle { generation++ }
        compose.waitForIdle()
        compose.onNodeWithTag("item_c").assertIsFocused()
    }

    @Test
    fun refreshThatDropsTheFocusedItemFocusesItsNeighbour() {
        compose.setContent { RefreshingRow() }
        compose.waitForIdle()
        compose.onNodeWithTag("item_c").requestFocusViaSemantics()

        // "c" was marked watched and left the row; "d" moved into its place.
        compose.runOnIdle {
            ids = listOf("a", "b", "d")
            generation++
        }
        compose.waitForIdle()
        compose.onNodeWithTag("item_d").assertIsFocused()
    }

    // ---- The menu ----

    private lateinit var menuRequesters: Map<NavDestination, FocusRequester>

    private fun setUpInShell(fallback: FocusRequester?) {
        compose.setContent {
            menuRequesters = remember { NavDestination.entries.associateWith { FocusRequester() } }
            val shell = rememberMainShellState()
            shellState = shell
            MainShell(
                navPosition = "left",
                showChrome = true,
                currentDestination = NavDestination.Home,
                currentProfile = null,
                menuRequesters = menuRequesters,
                onNavigate = {},
                onEnterContent = {},
                onLogout = {},
                onExit = {},
                state = shell
            ) {
                val holder = rememberSaveableStateHolder()
                if (shown) holder.SaveableStateProvider("entry") { Screen(fallback) }
            }
        }
        compose.waitForIdle()
    }

    private var shellState: com.hereliesaz.illumera.ui.navigation.MainShellState? = null

    private fun assertMenuNotFocused() {
        NavDestination.entries.filter { it != NavDestination.Profile }.forEach {
            compose.onNodeWithContentDescription(it.label).assertIsNotFocused()
        }
    }

    @Test
    fun restoreWithNothingToFocusNeverFocusesTheMenu() {
        setUpInShell(fallback = null)
        compose.onNodeWithTag("card2").requestFocusViaSemantics()
        // Everything the screen had is gone, and it has no primary item to fall back on.
        leaveAndReturn { itemCount = 0 }
        assertMenuNotFocused()
    }

    @Test
    fun refreshWhileTheMenuIsOpenLeavesTheMenuOpen() {
        setUpInShell(fallback = primary)
        compose.runOnIdle { primary.requestFocus() }
        card(0).assertIsFocused()
        compose.runOnIdle { menuRequesters.getValue(NavDestination.Home).openNavDrawer() }
        compose.waitForIdle()
        assertTrue(shellState!!.isMenuOpen)
        assertEquals(MenuOpener.Left, shellState!!.openedBy)

        // Focus leaving the screen for the menu is not a loss to repair, and a refresh under
        // the open menu doesn't pull focus out of it either.
        card(0).assertIsNotFocused()
        compose.runOnIdle { itemCount = 3 }
        compose.waitForIdle()
        assertTrue(shellState!!.isMenuOpen)
        card(0).assertIsNotFocused()
        assertEquals("row:0", memory.savedKey)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.requestFocusViaSemantics() {
        performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        compose.waitForIdle()
    }
}
