package com.hereliesaz.illumera.ui.player

import com.hereliesaz.illumera.data.auth.StremioLibrarySyncManager
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.ProfileEntity
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.trakt.TraktScrobbleManager
import com.hereliesaz.illumera.data.wutch.WutchManager
import com.hereliesaz.illumera.data.repository.AddonEventDispatcher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import androidx.lifecycle.viewModelScope

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var dao: AddonDao
    private lateinit var profileManager: ProfileConfigurationManager
    private lateinit var activeProfileId: MutableStateFlow<Int?>
    private lateinit var profileOne: MutableStateFlow<ProfileEntity>
    private lateinit var profileTwo: MutableStateFlow<ProfileEntity>

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        dao = mockk()
        profileManager = mockk()
        activeProfileId = MutableStateFlow(1)
        profileOne = MutableStateFlow(ProfileEntity(id = 1, name = "One", watchedThreshold = 90))
        profileTwo = MutableStateFlow(ProfileEntity(id = 2, name = "Two", watchedThreshold = 70))

        every { profileManager.activeProfileId } returns activeProfileId
        every { dao.getProfileFlow(1) } returns profileOne
        every { dao.getProfileFlow(2) } returns profileTwo
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun watchedThresholdTracksProfileEditsSwitchesAndLogout() = runTest(dispatcher) {
        val viewModel = PlayerViewModel(
            dao = dao,
            traktScrobbleManager = mockk<TraktScrobbleManager>(relaxed = true),
            wutchManager = mockk<WutchManager>(relaxed = true),
            stremioLibrarySyncManager = mockk<StremioLibrarySyncManager>(relaxed = true),
            profileConfigurationManager = profileManager,
            addonEvents = mockk<AddonEventDispatcher>(relaxed = true)
        )
        advanceUntilIdle()

        assertEquals(0.90, viewModel.watchedThreshold.value, 0.0001)
        assertFalse(viewModel.isCompleted(850_000L, 1_000_000L, "movie"))

        profileOne.value = profileOne.value.copy(watchedThreshold = 80)
        advanceUntilIdle()
        assertEquals(0.80, viewModel.watchedThreshold.value, 0.0001)
        assertTrue(viewModel.isCompleted(850_000L, 1_000_000L, "movie"))

        activeProfileId.value = 2
        advanceUntilIdle()
        assertEquals(0.70, viewModel.watchedThreshold.value, 0.0001)

        activeProfileId.value = null
        advanceUntilIdle()
        assertEquals(0.85, viewModel.watchedThreshold.value, 0.0001)
    }

    @Test
    fun progressPersistenceRejectsStaleOwningProfile() = runTest(dispatcher) {
        val trakt = mockk<TraktScrobbleManager>(relaxed = true)
        val viewModel = PlayerViewModel(
            dao = dao,
            traktScrobbleManager = trakt,
            wutchManager = mockk<WutchManager>(relaxed = true),
            stremioLibrarySyncManager = mockk<StremioLibrarySyncManager>(relaxed = true),
            profileConfigurationManager = profileManager,
            addonEvents = mockk<AddonEventDispatcher>(relaxed = true)
        )
        coEvery {
            profileManager.withActiveProfileRuntime<Boolean>(1, any())
        } throws CancellationException("profile switched before save")

        val saved = viewModel.persistProgressForProfile(
            ownerProfileId = 1,
            id = "movie-1",
            type = "movie",
            title = "Movie",
            poster = null,
            position = 600_000L,
            duration = 1_000_000L
        )

        assertFalse(saved)
        coVerify(exactly = 0) { dao.upsertHistory(any()) }
    }

    @Test
    fun invalidStoredThresholdIsClampedToSupportedRange() = runTest(dispatcher) {
        profileOne.value = profileOne.value.copy(watchedThreshold = 5)
        val viewModel = PlayerViewModel(
            dao = dao,
            traktScrobbleManager = mockk<TraktScrobbleManager>(relaxed = true),
            wutchManager = mockk<WutchManager>(relaxed = true),
            stremioLibrarySyncManager = mockk<StremioLibrarySyncManager>(relaxed = true),
            profileConfigurationManager = profileManager,
            addonEvents = mockk<AddonEventDispatcher>(relaxed = true)
        )
        advanceUntilIdle()

        assertEquals(0.50, viewModel.watchedThreshold.value, 0.0001)

        profileOne.value = profileOne.value.copy(watchedThreshold = 150)
        advanceUntilIdle()
        assertEquals(0.99, viewModel.watchedThreshold.value, 0.0001)
    }

    @Test
    fun scrobblesAndProgressFinishWhenThePlayerEntryIsCleared() = runTest(dispatcher) {
        val trakt = mockk<TraktScrobbleManager>(relaxed = true)
        val wutch = mockk<WutchManager>(relaxed = true)
        val finished = mutableListOf<String>()
        coEvery { trakt.scrobbleStart(any(), any(), any(), any()) } coAnswers { delay(5_000); finished += "start" }
        coEvery { trakt.scrobblePause(any(), any(), any(), any(), any()) } coAnswers { delay(5_000); finished += "pause" }
        coEvery { trakt.scrobbleStop(any(), any(), any(), any()) } coAnswers { delay(5_000); finished += "stop" }
        val viewModel = PlayerViewModel(
            dao = dao,
            traktScrobbleManager = trakt,
            wutchManager = wutch,
            stremioLibrarySyncManager = mockk<StremioLibrarySyncManager>(relaxed = true),
            profileConfigurationManager = profileManager,
            addonEvents = mockk<AddonEventDispatcher>(relaxed = true)
        )
        viewModel.ioDispatcher = dispatcher

        val hour = 60L * 60_000L
        viewModel.scrobbleStart("tt1", "movie", 1_000L, hour)
        viewModel.scrobblePause("tt1", "movie", 2_000L, hour)
        viewModel.scrobbleStop("tt1", "movie", 3_000L, hour)
        advanceTimeBy(1_000)
        // The player leaves: its entry's ViewModelStore clears this ViewModel mid-request.
        viewModel.viewModelScope.cancel()
        // Sent after the clear too (the player's dispose runs after it).
        viewModel.scrobbleStop("tt1", "movie", 4_000L, hour)
        advanceUntilIdle()

        assertEquals(listOf("start", "pause", "stop", "stop"), finished)
        coVerify { wutch.onPlayback("tt1", 2_000L, finished = false) }
        coVerify { wutch.onPlayback("tt1", 4_000L, any()) }
    }
}
