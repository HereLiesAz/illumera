package com.hereliesaz.illumera.data.trakt

import com.hereliesaz.illumera.BuildConfig
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktAuthInterceptor @Inject constructor(
    private val traktAuthManager: TraktAuthManager
) : Interceptor {

    // Guards refreshAccessToken() so concurrent requests (this interceptor is a
    // @Singleton shared across every call OkHttp dispatches) single-flight a
    // refresh instead of racing: a thread that loses the lock re-checks the
    // current token first and only calls the network refresh if it's still stale.
    private val refreshLock = Any()

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
     * Refreshes the Trakt access token, but only one caller performs the actual
     * network refresh at a time. A caller that was blocked on the lock re-checks
     * the token first: if another thread already refreshed past [staleToken]
     * while it waited, it reuses that result instead of refreshing again.
     */
    private fun refreshTokenSynchronized(staleToken: String?): String? = synchronized(refreshLock) {
        val currentToken = traktAuthManager.getAccessToken()
        if (currentToken != null && currentToken != staleToken) {
            currentToken
        } else {
            runBlocking { traktAuthManager.refreshAccessToken() }
        }
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
