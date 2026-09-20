package com.hereliesaz.illumera.ui.player

import com.hereliesaz.illumera.data.auth.StremioLibrarySyncManager
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.ProfileEntity
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.trakt.TraktScrobbleManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
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
            stremioLibrarySyncManager = mockk<StremioLibrarySyncManager>(relaxed = true),
            profileConfigurationManager = profileManager
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
    fun invalidStoredThresholdIsClampedToSupportedRange() = runTest(dispatcher) {
        profileOne.value = profileOne.value.copy(watchedThreshold = 5)
        val viewModel = PlayerViewModel(
            dao = dao,
            traktScrobbleManager = mockk<TraktScrobbleManager>(relaxed = true),
            stremioLibrarySyncManager = mockk<StremioLibrarySyncManager>(relaxed = true),
            profileConfigurationManager = profileManager
        )
        advanceUntilIdle()

        assertEquals(0.50, viewModel.watchedThreshold.value, 0.0001)

        profileOne.value = profileOne.value.copy(watchedThreshold = 150)
        advanceUntilIdle()
        assertEquals(0.99, viewModel.watchedThreshold.value, 0.0001)
    }
}
