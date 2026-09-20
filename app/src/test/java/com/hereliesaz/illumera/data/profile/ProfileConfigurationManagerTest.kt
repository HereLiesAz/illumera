package com.hereliesaz.illumera.data.profile

import android.content.Context
import com.hereliesaz.illumera.data.auth.StremioAuthManager
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.repository.AddonRepository
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ProfileConfigurationManagerTest {
    private lateinit var context: Context
    private lateinit var dao: AddonDao
    private lateinit var authManager: StremioAuthManager
    private lateinit var addonRepository: AddonRepository
    private lateinit var manager: ProfileConfigurationManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, SNAPSHOT_DIR).deleteRecursively()
        dao = mockk(relaxed = true)
        authManager = mockk(relaxed = true)
        addonRepository = mockk(relaxed = true)
        manager = ProfileConfigurationManager(context, dao, authManager, addonRepository)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, SNAPSHOT_DIR).deleteRecursively()
    }

    @Test
    fun pendingSetupStateRoundTripsIndependentlyPerProfile() {
        manager.markPendingSetup(1)
        manager.markPendingSetup(2)

        assertTrue(manager.needsInitialSetup(1))
        assertTrue(manager.needsInitialSetup(2))
        assertFalse(manager.needsInitialSetup(3))

        manager.clearPendingSetup(1)

        assertFalse(manager.needsInitialSetup(1))
        assertTrue(manager.needsInitialSetup(2))
    }

    @Test
    fun lastActiveProfileDefaultsToNullAndCanBeCleared() {
        assertNull(manager.getLastActiveProfileId())

        setLastActiveProfile(42)
        assertEquals(42, manager.getLastActiveProfileId())
        assertEquals(42, manager.activeProfileId.value)

        manager.clearLastActiveProfileId()
        assertNull(manager.getLastActiveProfileId())
        assertNull(manager.activeProfileId.value)
    }

    @Test
    fun withActiveProfileRuntimeRunsBlockForStillActiveProfile() = runTest {
        setLastActiveProfile(7)

        val result = manager.withActiveProfileRuntime(7) { "completed" }

        assertEquals("completed", result)
    }

    @Test(expected = CancellationException::class)
    fun withActiveProfileRuntimeCancelsWorkForStaleProfile() = runTest {
        setLastActiveProfile(8)

        manager.withActiveProfileRuntime(7) { "must not run" }
    }

    @Test
    fun deleteProfileStateClearsSetupSnapshotCredentialsAndActiveMarker() {
        val profileId = 9
        manager.markPendingSetup(profileId)
        setLastActiveProfile(profileId)
        val snapshot = File(File(context.filesDir, SNAPSHOT_DIR), "profile_$profileId.json")
        snapshot.parentFile?.mkdirs()
        snapshot.writeText("{}")

        manager.deleteProfileState(profileId)

        assertFalse(manager.needsInitialSetup(profileId))
        assertFalse(snapshot.exists())
        assertNull(manager.getLastActiveProfileId())
        verify(exactly = 1) { authManager.clearCredentialsForProfile(profileId) }
    }

    @Test
    fun deleteInactiveProfileStateDoesNotClearCurrentActiveProfile() {
        setLastActiveProfile(2)

        manager.deleteProfileState(1)

        assertEquals(2, manager.getLastActiveProfileId())
        verify(exactly = 1) { authManager.clearCredentialsForProfile(1) }
    }

    private fun setLastActiveProfile(profileId: Int) {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LAST_ACTIVE_PROFILE_ID, profileId)
            .commit()
        // Simulate a fresh process reading the persisted active profile.
        manager = ProfileConfigurationManager(context, dao, authManager, addonRepository)
    }

    companion object {
        private const val PREFS_FILE = "profile_configuration_prefs"
        private const val KEY_LAST_ACTIVE_PROFILE_ID = "last_active_profile_id"
        private const val SNAPSHOT_DIR = "profile_snapshots"
    }
}
