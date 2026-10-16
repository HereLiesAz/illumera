package com.hereliesaz.illumera.data.auth

import com.hereliesaz.illumera.data.remote.StremioAuthError
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Overall deadline for a browser/QR social login (Facebook or Apple). */
const val SOCIAL_LOGIN_TIMEOUT_MS = 5 * 60_000L

/** Pause between bounded server polls so a fast-failing poll can't spin. */
internal const val SOCIAL_LOGIN_REPOLL_DELAY_MS = 1_000L

/**
 * The Stremio service polls Facebook for ~25 seconds and Apple for ~50 seconds per
 * call. Repeats those bounded polls while they end in
 * [StremioAuthError.PollWindowExpired] (the user hasn't finished OAuth yet), until
 * [timeoutMs] elapses. Returns null on timeout; any other result is returned as is.
 */
suspend fun pollSocialLogin(
    timeoutMs: Long = SOCIAL_LOGIN_TIMEOUT_MS,
    repollDelayMs: Long = SOCIAL_LOGIN_REPOLL_DELAY_MS,
    attempt: suspend () -> Result<String>
): Result<String>? = withTimeoutOrNull(timeoutMs) {
    var result = attempt()
    while (result.exceptionOrNull() is StremioAuthError.PollWindowExpired) {
        delay(repollDelayMs)
        result = attempt()
    }
    result
}
