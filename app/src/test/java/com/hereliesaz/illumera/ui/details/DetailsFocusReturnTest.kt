package com.hereliesaz.illumera.ui.details

import androidx.activity.ComponentActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Text
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.data.player.EpisodeBrowseStore
import com.hereliesaz.illumera.data.player.PlaybackTrackSelectionStore
import com.hereliesaz.illumera.data.player.SourceSelectionStore
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.repository.AddonRepository
import com.hereliesaz.illumera.data.repository.SubtitleRepository
import com.hereliesaz.illumera.data.stream.StreamSortingService
import com.hereliesaz.illumera.data.tmdb.TmdbMetadataService
import com.hereliesaz.illumera.data.tmdb.TmdbService
import com.hereliesaz.illumera.data.trakt.TraktSyncManager
import com.hereliesaz.illumera.data.wutch.WutchManager
import com.hereliesaz.illumera.ui.navigation.BackStackOps
import com.hereliesaz.illumera.ui.navigation.DetailsKey
import com.hereliesaz.illumera.ui.navigation.HomeKey
import com.hereliesaz.illumera.ui.navigation.PlayerKey
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Back from the player lands on the Details hero button that started it, not on the first
 * button (the page's FocusMemory, kept in its back-stack entry).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DetailsFocusReturnTest {
    // v2 rules queue coroutines (StandardTestDispatcher); these tests rely on immediate dispatch.
    @Suppress("DEPRECATION")
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun newViewModel(): DetailsViewModel {
        val dao = mockk<AddonDao>(relaxed = true)
        val sourceSelectionStore = mockk<SourceSelectionStore>(relaxed = true)
        val repository = mockk<AddonRepository>(relaxed = true)
        // A relaxed StateFlow mock hands collectAsState a non-List value (ClassCastException).
        every { repository.lastStreamOutcomes } returns kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        val profileConfigurationManager = mockk<ProfileConfigurationManager>(relaxed = true)
        every { profileConfigurationManager.getLastActiveProfileId() } returns null
        every { sourceSelectionStore.isSourceListDisabled(any()) } returns false
        every { sourceSelectionStore.getExcludedSources(any()) } returns emptySet()
        coEvery { repository.resolveMetaDetails("movie", "tt1", null) } returns
            MetaItem(id = "tt1", type = "movie", name = "A Film", description = "About it.")
        return DetailsViewModel(
            dao = dao,
            sourceSelectionStore = sourceSelectionStore,
            episodeBrowseStore = mockk<EpisodeBrowseStore>(relaxed = true),
            playbackTrackSelectionStore = mockk<PlaybackTrackSelectionStore>(relaxed = true),
            repository = repository,
            subtitleRepository = mockk<SubtitleRepository>(relaxed = true),
            profileConfigurationManager = profileConfigurationManager,
            streamSortingService = StreamSortingService(),
            tmdbService = mockk<TmdbService>(relaxed = true),
            tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true),
            traktSyncManager = mockk<TraktSyncManager>(relaxed = true),
            wutchManager = mockk<WutchManager>(relaxed = true),
            savedStateHandle = androidx.lifecycle.SavedStateHandle()
        )
    }

    /** No Hilt here: the Love / Like / Don't like buttons get a stub with no verdicts. */
    private fun tasteActions(): com.hereliesaz.illumera.ui.components.MediaActionsViewModel =
        mockk(relaxed = true) {
            every { tastes } returns kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
        }

    /** Play's label follows the resume state: "Loading…", "Resume" or "Play Movie". */
    private fun playButton() = compose.onNode(
        hasContentDescription("Play Movie") or hasContentDescription("Loading…") or hasContentDescription("Resume")
    )

    @Test
    fun backFromThePlayerFocusesTheHeroButtonThatStartedIt() {
        val vm = newViewModel()
        val actions = tasteActions()
        lateinit var backStack: NavBackStack<NavKey>
        compose.setContent {
            backStack = androidx.compose.runtime.remember { NavBackStack(HomeKey(), DetailsKey("movie", "tt1")) }
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
                    entry<HomeKey> { Text("home") }
                    entry<DetailsKey> { key ->
                        DetailsScreen(
                            type = key.type,
                            id = key.id,
                            onPlayClick = { _, _, _, _, _, _, _, _, _, _ -> },
                            viewModel = vm,
                            tasteActions = actions
                        )
                    }
                    entry<PlayerKey> { Text("player") }
                }
            )
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            runCatching { compose.onNodeWithContentDescription("Sources").assertExists() }.isSuccess
        }
        compose.waitForIdle()

        // First load: the Play button. Then the viewer moves to Sources and starts playback there.
        compose.onNodeWithContentDescription("Sources").assertIsNotFocused()
        playButton().assertIsFocused()
        playButton()
            .performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Sources").assertIsFocused()

        compose.runOnUiThread { BackStackOps.openPlayer(backStack) }
        compose.waitForIdle()
        compose.runOnUiThread { BackStackOps.returnFromPlayer(backStack) }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Sources").assertIsFocused()
        playButton().assertIsNotFocused()
    }
}
