package com.hereliesaz.illumera.ui.home

import android.content.Context
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.taste.TasteStore
import com.hereliesaz.illumera.domain.DashboardTab
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * The remembered focus lives outside [HomeViewModel.HomeState]: moving the D-pad changes it on
 * every press, and a state emission per press rebuilt every row list on the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelFocusTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val profiles = mockk<ProfileConfigurationManager>(relaxed = true)
        every { profiles.activeProfileId } returns MutableStateFlow(null)
        val taste = mockk<TasteStore>(relaxed = true)
        every { taste.entries } returns MutableStateFlow(emptyMap())
        viewModel = HomeViewModel(
            repository = mockk(relaxed = true),
            dao = mockk(relaxed = true),
            context = mockk<Context>(relaxed = true),
            tmdbService = mockk(relaxed = true),
            tmdbMetadataService = mockk(relaxed = true),
            profileConfigurationManager = profiles,
            trailerApi = mockk(relaxed = true),
            youtubeExtractor = mockk(relaxed = true),
            similarTitlesEngine = mockk(relaxed = true),
            tasteStore = taste,
            personalRowPrefs = mockk(relaxed = true),
            memoryMonitor = mockk(relaxed = true)
        )
        // The screen in front, so its focus is the one remembered.
        viewModel.loadScreen("home", null)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `moving focus does not emit a new state or new row lists`() = runTest {
        val states = mutableListOf<HomeViewModel.HomeState>()
        val displayed = mutableListOf<HomeViewModel.HomeState>()
        val display = viewModel.displayState(DashboardTab.HOME, 1)
        val collectState = launch(dispatcher) { viewModel.state.collect { states += it } }
        val collectDisplay = launch(dispatcher) { display.collect { displayed += it } }

        val stateBefore = viewModel.state.value
        val rowsBefore = display.value.mixedRows
        val emittedStates = states.size
        val emittedDisplays = displayed.size

        listOf("0_0", "0_1", "0_2", "1_0", "1_1").forEach { viewModel.setLastFocusedKey(it, "home") }

        assertEquals("1_1", viewModel.lastFocusedKey.value)
        assertSame(stateBefore, viewModel.state.value)
        assertSame(rowsBefore, display.value.mixedRows)
        assertEquals(emittedStates, states.size)
        assertEquals(emittedDisplays, displayed.size)
        collectState.cancel()
        collectDisplay.cancel()
    }

    @Test
    fun `focus is remembered for the screen in front only`() {
        viewModel.setLastFocusedKey("0_3", "home")
        assertEquals("0_3", viewModel.lastFocusedKey.value)

        // A screen being left writes its final position after the next one has started.
        viewModel.setLastFocusedKey("2_2", "movies")
        assertEquals("0_3", viewModel.lastFocusedKey.value)
    }

    @Test
    fun `a main screen chosen fresh starts with no remembered focus`() {
        viewModel.setLastFocusedKey("0_3", "home")
        viewModel.forgetPosition("home")
        assertNull(viewModel.lastFocusedKey.value)
    }

    @Test
    fun `loading another screen forgets the previous screen's focus`() {
        viewModel.setLastFocusedKey("0_3", "home")
        viewModel.loadScreen("movies", null)
        assertNull(viewModel.lastFocusedKey.value)
    }

    @Test
    fun `the displayed rows stay the same lists while focus moves`() = runTest {
        val display = viewModel.displayState(DashboardTab.HOME, 1)
        val mixedBefore = display.value.mixedRows
        viewModel.setLastFocusedKey("0_0", "home")
        viewModel.setLastFocusedKey("0_1", "home")
        assertSame(mixedBefore, display.value.mixedRows)
    }
}
