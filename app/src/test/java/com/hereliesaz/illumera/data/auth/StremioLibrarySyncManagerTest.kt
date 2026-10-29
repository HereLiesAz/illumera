package com.hereliesaz.illumera.data.auth

import android.content.Context
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.WatchlistEntity
import com.hereliesaz.illumera.data.remote.StremioLibraryItem
import com.hereliesaz.illumera.data.remote.StremioLibraryItemState
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
        coEvery { dao.getWatchlistOnce(any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun unplayedSavedMovieIsPushedToStremioLibrary() = runTest {
        coEvery { dao.getWatchlistOnce(1) } returns listOf(
            WatchlistEntity(1, "tt1234567", "movie", "Saved Film", "https://poster", 1_600_000_000_000L)
        )
        coEvery { authService.datastoreMeta("auth-1") } returns emptyMap()
        val manager = newManager()
        manager.syncLibrary()
        coVerify(exactly = 1) {
            authService.datastorePut("auth-1", match { entries ->
                entries.size == 1 &&
                    entries.single().id == "tt1234567" &&
                    entries.single().name == "Saved Film" &&
                    entries.single().type == "movie"
            })
        }
    }

    @Test
    fun stremioBookmarkWithoutPlaybackIsNotWatchHistory() = runTest {
        val time = "2026-10-29T10:00:00Z"
        val mtime = java.time.Instant.parse(time).toEpochMilli()
        coEvery { authService.datastoreMeta("auth-1") } returns
            mapOf("tt0000001" to mtime)
        coEvery { authService.datastoreGet("auth-1", any()) } returns listOf(
            StremioLibraryItem(
                id = "tt0000001", name = "Only bookmarked", type = "movie",
                mtime = time, state = StremioLibraryItemState()
            )
        )
        val manager = newManager()
        assertTrue(manager.syncLibrary().isSuccess)
        coVerify(exactly = 1) {
            dao.addToWatchlist(match { it.id == "tt0000001" && it.title == "Only bookmarked" })
        }
        coVerify(exactly = 0) { dao.upsertHistory(any()) }
    }

    @Test
    fun newerUnplayedBookmarkCannotEraseLocalPlayback() = runTest {
        val lastPlayed = 1_600_000_000_000L
        val local = com.hereliesaz.illumera.data.model.WatchHistoryEntity(
            id = "tt0000002", title = "Actually watched", poster = null,
            position = 60_000L, duration = 300_000L, lastWatched = lastPlayed, type = "movie"
        )
        coEvery { dao.getAllWatchHistoryOnce() } returns listOf(local)
        coEvery { dao.getHistoryItem("tt0000002") } returns local
        val time = "2026-10-29T10:00:00Z"
        coEvery { authService.datastoreMeta("auth-1") } returns
            mapOf("tt0000002" to java.time.Instant.parse(time).toEpochMilli())
        coEvery { authService.datastoreGet("auth-1", any()) } returns listOf(
            StremioLibraryItem(
                id = "tt0000002", name = "Metadata updated", type = "movie",
                mtime = time, state = StremioLibraryItemState()
            )
        )
        val manager = newManager()
        assertTrue(manager.syncLibrary().isSuccess)
        coVerify(exactly = 0) { dao.upsertHistory(any()) }
    }

    @Test
    fun completedAndRealProgressStremioStatesAreAllowedButEmptyStatesAreNot() {
        assertTrue(!stremioStateHasPlaybackEvidence(StremioLibraryItemState()))
        assertTrue(!stremioStateHasPlaybackEvidence(
            StremioLibraryItemState(timeOffset = 4_999L, duration = 300_000L)
        ))
        assertTrue(stremioStateHasPlaybackEvidence(
            StremioLibraryItemState(timeOffset = 5_000L, duration = 300_000L)
        ))
        assertTrue(stremioStateHasPlaybackEvidence(StremioLibraryItemState(timesWatched = 1)))
        assertTrue(stremioStateHasPlaybackEvidence(StremioLibraryItemState(flaggedWatched = 1)))
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
        // A profile switch is reported as a failed sync, not thrown as a cancellation
        // into the caller's still-active scope.
        val result = manager.syncLibrary()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is StremioLibrarySyncManager.ProfileChangedException)
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
