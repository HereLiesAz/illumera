package com.hereliesaz.illumera.data.trakt

import com.hereliesaz.illumera.BuildConfig
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktAuthInterceptor @Inject constructor(
    private val traktAuthManager: TraktAuthManager
) : Interceptor {

    // Guards the token-state check so concurrent OkHttp threads single-flight a
    // refresh. The Mutex is held only during the cheap check; the actual network
    // call happens outside the lock so we don't block all Trakt API threads for
    // the duration of a full HTTP round-trip.
    private val refreshMutex = Mutex()

    // In-flight refresh deferred — shared by all waiters so only one network
    // call is outstanding at a time.
    @Volatile private var refreshInFlight: Deferred<String?>? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        var token = traktAuthManager.getAccessToken()

        // Fix #7: proactively refresh if token is expired or about to expire
        if (token != null && traktAuthManager.needsRefresh) {
            val newToken = refreshTokenSynchronized(token)
            if (newToken != null) {
                token = newToken
            }
        }

        val response = chain.proceed(buildRequest(chain.request(), token))

        // If we get a 401, try refreshing the token and retry once
        if (response.code == 401 && token != null) {
            val newToken = refreshTokenSynchronized(token)

            if (newToken != null) {
                response.close()
                return chain.proceed(buildRequest(chain.request(), newToken))
            }
            // Refresh didn't produce a usable token — retrying with the same
            // one would just fail the same way, so return the original 401.
        }

        return response
    }

    /**
     * Refreshes the Trakt access token with minimal lock contention.
     *
     * The [refreshMutex] is held only for the cheap check-and-deferred-setup
     * step, not for the network call itself, so other Trakt API threads are not
     * stalled for the duration of the HTTP round-trip. A caller that finds
     * [staleToken] already superseded reuses the new token without going to the
     * network at all.
     */
    private fun refreshTokenSynchronized(staleToken: String?): String? = runBlocking {
        // Fast path: check outside any lock first.
        val currentToken = traktAuthManager.getAccessToken()
        if (currentToken != null && currentToken != staleToken) return@runBlocking currentToken

        // Acquire the mutex only long enough to either reuse an in-flight
        // deferred or start a new one, then release before awaiting the result.
        val deferred: Deferred<String?> = refreshMutex.withLock {
            val recheck = traktAuthManager.getAccessToken()
            if (recheck != null && recheck != staleToken) {
                // Another coroutine already finished a refresh — return early.
                return@runBlocking recheck
            }
            refreshInFlight?.takeIf { it.isActive } ?: run {
                async { traktAuthManager.refreshAccessToken() }
                    .also { refreshInFlight = it }
            }
        }

        // Await the network call outside the mutex.
        deferred.await()
    }

    private fun buildRequest(original: okhttp3.Request, token: String?): okhttp3.Request {
        val builder = original.newBuilder()
            .header("Content-Type", "application/json")
            .header("trakt-api-version", "2")
            .header("trakt-api-key", BuildConfig.TRAKT_CLIENT_ID)
            .header("User-Agent", "Lumera/${BuildConfig.VERSION_NAME}")

        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }

        return builder.build()
    }
}
