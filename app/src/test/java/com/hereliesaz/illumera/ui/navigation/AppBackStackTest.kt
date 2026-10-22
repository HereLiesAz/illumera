package com.hereliesaz.illumera.ui.navigation

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The root back stack's contract: what each navigation does to the list ([BackStackOps]),
 * and how NavDisplay, set up as MainActivity sets it up, hands Back to the entries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppBackStackTest {
    // v2 rules queue coroutines (StandardTestDispatcher); these tests rely on immediate dispatch.
    @Suppress("DEPRECATION")
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun stack(vararg keys: NavKey): MutableList<NavKey> = keys.toMutableList()

    // ---- BackStackOps ----

    @Test
    fun detailsThenPlayerThenBackReturnsToDetails() {
        val s = stack(HomeKey())
        BackStackOps.openDetails(s, type = "movie", id = "tt1")
        BackStackOps.openPlayer(s)
        assertEquals(PlayerKey, s.last())

        // The player's Back ends the session, which asks to return from the player.
        assertTrue(BackStackOps.returnFromPlayer(s))
        assertEquals(listOf(HomeKey(), DetailsKey("movie", "tt1")), s)
    }

    @Test
    fun trailerEndReturnsToDetails() {
        val s = stack(HomeKey())
        BackStackOps.openDetails(s, type = "series", id = "tt2")
        BackStackOps.openPlayer(s) // trailers use the same player entry
        BackStackOps.returnFromPlayer(s)
        assertEquals(DetailsKey("series", "tt2"), s.last())
    }

    @Test
    fun debridLibraryPlaybackFromMainReturnsToMain() {
        val s = stack(HomeKey())
        BackStackOps.openPlayer(s)
        BackStackOps.returnFromPlayer(s)
        assertEquals(listOf<NavKey>(HomeKey()), s)
    }

    @Test
    fun queueAdvancePushesDetailsOnTopAndTwoBacksReachMain() {
        val s = stack(HomeKey())
        BackStackOps.openDetails(s, type = "series", id = "tt3")
        BackStackOps.openPlayer(s)

        BackStackOps.queueAdvance(s, type = "series", id = "tt3", title = "Next", poster = "")
        assertEquals(3, s.size)
        val advanced = s.last() as DetailsKey
        assertEquals("tt3", advanced.id)
        // Same title twice on the stack stays two distinct entries.
        assertTrue(advanced != s[1])

        assertTrue(BackStackOps.pop(s))
        assertEquals(DetailsKey("series", "tt3"), s.last())
        assertTrue(BackStackOps.pop(s))
        assertEquals(listOf<NavKey>(HomeKey()), s)
        // Main alone: Back belongs to the menu area.
        assertFalse(BackStackOps.pop(s))
        assertEquals(listOf<NavKey>(HomeKey()), s)
    }

    @Test
    fun castAndRecommendedDetailsAreFlatEntriesBackUnwindsThemInOrder() {
        val s = stack(HomeKey())
        BackStackOps.openDetails(s, type = "movie", id = "A")
        BackStackOps.openCast(s, personId = 7, name = "Actor")
        BackStackOps.openDetails(s, type = "series", id = "B")
        BackStackOps.openPlayer(s)
        assertEquals(
            listOf(HomeKey(), DetailsKey("movie", "A"), CastKey(7, "Actor"), DetailsKey("series", "B", instance = 1), PlayerKey),
            s
        )

        // Leaving the player lands on the page that started it, not the first Details.
        assertTrue(BackStackOps.returnFromPlayer(s))
        assertEquals(DetailsKey("series", "B", instance = 1), s.last())
        assertTrue(BackStackOps.pop(s))
        assertEquals(CastKey(7, "Actor"), s.last())
        assertTrue(BackStackOps.pop(s))
        assertEquals(DetailsKey("movie", "A"), s.last())
        assertTrue(BackStackOps.pop(s))
        assertEquals(listOf<NavKey>(HomeKey()), s)
    }

    @Test
    fun samePersonOrStudioTwiceOnOneStackStaysTwoEntries() {
        val s = stack(HomeKey())
        BackStackOps.openDetails(s, type = "movie", id = "A")
        BackStackOps.openCast(s, 7, "Actor")
        BackStackOps.openStudio(s, 3, "company", "Studio", "movie")
        BackStackOps.openDetails(s, type = "movie", id = "B")
        BackStackOps.openCast(s, 7, "Actor")
        BackStackOps.openStudio(s, 3, "company", "Studio", "movie")
        assertEquals(s.size, s.toSet().size)
    }

    @Test
    fun queueAdvanceCarriesTheAutoPlayIdInTheKey() {
        val s = stack(HomeKey())
        BackStackOps.openDetails(s, type = "series", id = "tt3")
        BackStackOps.openPlayer(s)
        BackStackOps.queueAdvance(s, type = "series", id = "tt4", title = "Next", poster = "", queueAutoPlayId = "tt4:1:1")
        assertEquals("tt4:1:1", (s.last() as DetailsKey).queueAutoPlayId)
        assertNull((s[1] as DetailsKey).queueAutoPlayId)
    }

    @Test
    fun gridRestoredWithoutItemsPops() {
        val s = stack(HomeKey(), GridKey("Popular", "cfg"))
        assertFalse(BackStackOps.dropEmptyGrid(s, hasItems = true))
        assertEquals(GridKey("Popular", "cfg"), s.last())

        assertTrue(BackStackOps.dropEmptyGrid(s, hasItems = false))
        assertEquals(listOf<NavKey>(HomeKey()), s)
    }

    @Test
    fun gridThenDetailsBackReturnsToGrid() {
        val s = stack(HomeKey())
        BackStackOps.openGrid(s, "Popular", "cfg")
        BackStackOps.openDetails(s, type = "movie", id = "tt4")
        BackStackOps.pop(s)
        assertEquals(GridKey("Popular", "cfg"), s.last())
    }

    @Test
    fun returnFromPlayerLeavesOtherPagesAlone() {
        val s = stack(HomeKey(), DetailsKey("movie", "tt5"))
        assertFalse(BackStackOps.returnFromPlayer(s))
        assertEquals(2, s.size)
    }

    @Test
    fun openPlayerTwiceKeepsOnePlayer() {
        val s = stack(HomeKey())
        BackStackOps.openPlayer(s)
        BackStackOps.openPlayer(s)
        assertEquals(listOf(HomeKey(), PlayerKey), s)
    }

    @Test
    fun resetToMainLeavesAFreshHomeAlone() {
        val s = stack(SettingsKey(), GridKey("a", ""), DetailsKey("movie", "x"), PlayerKey)
        BackStackOps.resetToMain(s)
        assertEquals(listOf<NavKey>(HomeKey(nonce = 1)), s)
    }

    @Test
    fun backStackSurvivesSaveAndRestore() {
        val tester = StateRestorationTester(compose)
        var restored: NavBackStack<NavKey>? = null
        tester.setContent { restored = rememberNavBackStack(AppBackStackConfiguration, HomeKey()) }
        compose.runOnUiThread {
            val s = restored!!
            BackStackOps.openGrid(s, "Popular", "cfg")
            BackStackOps.openDetails(s, type = "series", id = "tt9", addon = "https://addon", title = "T")
            BackStackOps.openCast(s, 7, "Actor")
            BackStackOps.openStudio(s, 3, "network", "Net", "series")
            BackStackOps.openDetails(s, type = "series", id = "tt10", queueAutoPlayId = "tt10:1:1")
            BackStackOps.openPlayer(s)
        }
        val before = restored!!.toList()
        restored = null
        tester.emulateSavedInstanceStateRestore()
        assertEquals(before, restored!!.toList())
    }

    // ---- NavDisplay, configured as in MainActivity ----

    /** Stands in for DetailsViewModel: remembers the started queue auto-play id in its SavedStateHandle. */
    class QueueAutoPlayVm(private val handle: SavedStateHandle) : ViewModel() {
        val consumed = handle.getStateFlow<String?>("queueAutoPlayConsumed", null)
        fun consume(id: String) { handle["queueAutoPlayConsumed"] = id }
    }

    private lateinit var backStack: NavBackStack<NavKey>
    private var nextToken = 0
    private val detailsTokens = mutableMapOf<DetailsKey, Int>()
    private val detailsVms = mutableMapOf<DetailsKey, QueueAutoPlayVm>()
    private val autoPlayStarts = mutableListOf<String>()
    private var playerBacks = 0

    private fun setUpDisplay(playerOwnsBack: Boolean) {
        compose.setContent {
            backStack = rememberNavBackStack(AppBackStackConfiguration, HomeKey())
            NavDisplay(
                backStack = backStack,
                onBack = { BackStackOps.pop(backStack) },
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator()
                ),
                transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                predictivePopTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                entryProvider = entryProvider {
                    entry<HomeKey> { Text("main") }
                    entry<DetailsKey> { key ->
                        detailsTokens[key] = rememberSaveable { ++nextToken }
                        // The entry's own ViewModelStore, as DetailsScreen's hiltViewModel() gets it.
                        val vm = viewModel { QueueAutoPlayVm(createSavedStateHandle()) }
                        detailsVms[key] = vm
                        val consumed by vm.consumed.collectAsState()
                        val pending = key.queueAutoPlayId?.takeIf { it != consumed }
                        LaunchedEffect(pending) {
                            val requested = pending ?: return@LaunchedEffect
                            autoPlayStarts += requested
                            vm.consume(requested)
                        }
                        Text("details ${key.id}")
                    }
                    entry<CastKey> { Text("cast ${it.personId}") }
                    entry<StudioKey> { Text("studio ${it.entityId}") }
                    entry<PlayerKey> {
                        if (playerOwnsBack) BackHandler { playerBacks++ }
                        Text("player")
                    }
                }
            )
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

    @Test
    fun atTheRootAloneNavDisplayLeavesBackToTheScreens() {
        setUpDisplay(playerOwnsBack = true)
        assertFalse(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test
    fun backFromDetailsPopsToMain() {
        setUpDisplay(playerOwnsBack = true)
        onStack { BackStackOps.openDetails(it, "movie", "tt1") }
        pressBack()
        assertEquals(listOf<NavKey>(HomeKey()), backStack.toList())
    }

    @Test
    fun playerBackHandlerOwnsBackOverNavDisplay() {
        setUpDisplay(playerOwnsBack = true)
        onStack { BackStackOps.openDetails(it, "movie", "tt1") }
        onStack { BackStackOps.openPlayer(it) }

        pressBack()
        assertEquals(1, playerBacks)
        assertEquals(PlayerKey, backStack.last())
    }

    @Test
    fun mainDetailsCastDetailsPlayerBackUnwindsOnePageAtATime() {
        setUpDisplay(playerOwnsBack = true)
        onStack { BackStackOps.openDetails(it, "movie", "A") }
        val detailsA = backStack.last() as DetailsKey
        val tokenA = detailsTokens.getValue(detailsA)
        val vmA = detailsVms.getValue(detailsA)
        onStack { BackStackOps.openCast(it, 7, "Actor") }
        onStack { BackStackOps.openDetails(it, "series", "B") }
        val detailsB = backStack.last() as DetailsKey
        val tokenB = detailsTokens.getValue(detailsB)
        // Each Details page has its own ViewModel.
        assertNotSame(vmA, detailsVms.getValue(detailsB))

        onStack { BackStackOps.openPlayer(it) }
        // The session ends the player; the page that started it comes back as it was.
        onStack { BackStackOps.returnFromPlayer(it) }
        assertEquals(detailsB, backStack.last())
        assertEquals(tokenB, detailsTokens.getValue(detailsB))

        pressBack()
        assertEquals(CastKey(7, "Actor"), backStack.last())
        pressBack()
        assertEquals(detailsA, backStack.last())
        assertEquals(tokenA, detailsTokens.getValue(detailsA))
        assertSame(vmA, detailsVms.getValue(detailsA))
        pressBack()
        assertEquals(listOf<NavKey>(HomeKey()), backStack.toList())
    }

    @Test
    fun queueAutoPlayStartsOnceAndNotAgainOnReturnFromThePlayer() {
        setUpDisplay(playerOwnsBack = true)
        onStack { BackStackOps.openDetails(it, "series", "S") }
        onStack { BackStackOps.openPlayer(it) }
        onStack {
            BackStackOps.queueAdvance(it, type = "series", id = "S2", title = "Next", poster = "", queueAutoPlayId = "S2:1:1")
        }
        assertEquals(listOf("S2:1:1"), autoPlayStarts)

        // The advanced-to page starts playback; the viewer comes back to it.
        onStack { BackStackOps.openPlayer(it) }
        onStack { BackStackOps.returnFromPlayer(it) }
        assertEquals(DetailsKey("series", "S2", title = "Next", queueAutoPlayId = "S2:1:1", instance = 1), backStack.last())
        assertEquals(listOf("S2:1:1"), autoPlayStarts)

        // Back to the previous show's page, which had nothing queued.
        pressBack()
        assertEquals(DetailsKey("series", "S"), backStack.last())
        assertEquals(listOf("S2:1:1"), autoPlayStarts)
    }
}
