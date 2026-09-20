package com.hereliesaz.illumera.data.auth

import android.content.Context
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.remote.StremioAuthService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class StremioLibrarySyncManagerTest {
    private lateinit var context: Context
    private lateinit var dao: AddonDao
    private lateinit var authManager: StremioAuthManager
    private lateinit var authService: StremioAuthService
    private lateinit var profileManager: ProfileConfigurationManager
    private var activeProfileId: Int? = 1

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        dao = mockk(relaxed = true)
        authManager = mockk()
        authService = mockk(relaxed = true)
        profileManager = mockk()

        every { profileManager.getLastActiveProfileId() } answers { activeProfileId }
        every { authManager.getStoredAuthKey() } answers {
            activeProfileId?.let { "auth-$it" }
        }
        coEvery {
            profileManager.withActiveProfileRuntime<Any?>(any(), any())
        } coAnswers {
            val requestedProfileId = firstArg<Int>()
            if (activeProfileId != requestedProfileId) {
                throw CancellationException("Active profile changed")
            }
            secondArg<suspend () -> Any?>().invoke()
        }
        coEvery { dao.getAllWatchHistoryOnce() } returns emptyList()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun throttleIsIndependentPerProfile() = runTest {
        coEvery { authService.datastoreMeta("auth-1") } returns emptyMap()
        coEvery { authService.datastoreMeta("auth-2") } returns emptyMap()

        val manager = newManager()
        manager.syncLibrary()

        activeProfileId = 2
        manager.syncLibrary()

        coVerify(exactly = 1) { authService.datastoreMeta("auth-1") }
        coVerify(exactly = 1) { authService.datastoreMeta("auth-2") }
    }

    @Test
    fun profileSwitchDuringRemoteFetchCancelsBeforePushOrLocalApply() = runTest {
        coEvery { authService.datastoreMeta("auth-1") } coAnswers {
            activeProfileId = 2
            emptyMap()
        }

        val manager = newManager()
        var cancelled = false
        try {
            manager.syncLibrary()
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        coVerify(exactly = 0) { authService.datastorePut(any(), any()) }
        coVerify(exactly = 0) { authService.datastoreGet(any(), any()) }
    }

    @Test
    fun cancelledSyncReleasesThrottleForImmediateRetry() = runTest {
        var attempts = 0
        coEvery { authService.datastoreMeta("auth-1") } coAnswers {
            attempts++
            if (attempts == 1) throw CancellationException("cancel first attempt")
            emptyMap()
        }

        val manager = newManager()
        try {
            manager.syncLibrary()
        } catch (_: CancellationException) {
            // Expected.
        }

        manager.syncLibrary()

        coVerify(exactly = 2) { authService.datastoreMeta("auth-1") }
    }

    private fun newManager() = StremioLibrarySyncManager(
        context = context,
        stremioAuthManager = authManager,
        stremioAuthService = authService,
        dao = dao,
        profileConfigurationManager = profileManager
    )

    companion object {
        private const val PREFS_FILE = "stremio_library_sync"
    }
}
