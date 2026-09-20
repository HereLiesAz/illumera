package com.hereliesaz.illumera.data.trakt

import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.remote.TraktSyncApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class TraktScrobbleManagerTest {

    @Test
    fun pauseDebounceIsIndependentPerAccount() = runTest {
        val api = mockk<TraktSyncApiService>()
        val auth = mockk<TraktAuthManager>()
        val dao = mockk<AddonDao>(relaxed = true)
        var token = "token-a"

        every { auth.getAccessToken() } answers { token }
        coEvery { api.scrobblePause(any()) } returns mockk<Response<okhttp3.ResponseBody>>(relaxed = true)

        val manager = TraktScrobbleManager(api, auth, dao)

        manager.scrobblePause("tt1", "movie", 10_000L, 100_000L)
        token = "token-b"
        manager.scrobblePause("tt2", "movie", 20_000L, 100_000L)
        manager.scrobblePause("tt2", "movie", 21_000L, 100_000L)

        coVerify(exactly = 2) { api.scrobblePause(any()) }
    }

    @Test
    fun scrobbleCancellationPropagates() = runTest {
        val api = mockk<TraktSyncApiService>()
        val auth = mockk<TraktAuthManager>()
        val dao = mockk<AddonDao>(relaxed = true)

        every { auth.getAccessToken() } returns "token-a"
        coEvery { api.scrobbleStart(any()) } throws CancellationException("cancel scrobble")

        val manager = TraktScrobbleManager(api, auth, dao)

        var cancelled = false
        try {
            manager.scrobbleStart("tt1", "movie", 10_000L, 100_000L)
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
    }
}
