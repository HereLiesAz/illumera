package com.hereliesaz.illumera.data.auth

import com.hereliesaz.illumera.data.remote.StremioAuthError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialLoginPollingTest {

    @Test
    fun repollsWhilePollWindowExpiresThenReturnsSuccess() = runTest {
        var calls = 0
        val result = pollSocialLogin(timeoutMs = 60_000L, repollDelayMs = 1_000L) {
            calls++
            if (calls < 3) Result.failure(StremioAuthError.PollWindowExpired("not yet"))
            else Result.success("key")
        }
        assertEquals("key", result?.getOrNull())
        assertEquals(3, calls)
    }

    @Test
    fun otherErrorsReturnImmediately() = runTest {
        var calls = 0
        val result = pollSocialLogin(timeoutMs = 60_000L) {
            calls++
            Result.failure(StremioAuthError.NetworkError("boom"))
        }
        assertTrue(result?.exceptionOrNull() is StremioAuthError.NetworkError)
        assertEquals(1, calls)
    }

    @Test
    fun returnsNullAtDeadline() = runTest {
        val result = pollSocialLogin(timeoutMs = 10_000L, repollDelayMs = 1_000L) {
            Result.failure(StremioAuthError.PollWindowExpired("not yet"))
        }
        assertNull(result)
    }
}
