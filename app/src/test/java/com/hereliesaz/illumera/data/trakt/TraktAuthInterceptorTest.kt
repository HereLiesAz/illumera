package com.hereliesaz.illumera.data.trakt

import com.hereliesaz.illumera.BuildConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class TraktAuthInterceptorTest {
    private lateinit var server: MockWebServer
    private lateinit var authManager: TraktAuthManager

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        authManager = mockk()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun unauthenticatedRequestGetsRequiredTraktHeadersWithoutAuthorization() {
        every { authManager.getAccessToken() } returns null
        every { authManager.needsRefresh } returns false
        server.enqueue(MockResponse().setResponseCode(200))

        executeRequest()

        val request = server.takeRequest()
        assertEquals("application/json", request.getHeader("Content-Type"))
        assertEquals("2", request.getHeader("trakt-api-version"))
        assertEquals(BuildConfig.TRAKT_CLIENT_ID, request.getHeader("trakt-api-key"))
        assertEquals("Lumera/${BuildConfig.VERSION_NAME}", request.getHeader("User-Agent"))
        assertNull(request.getHeader("Authorization"))
        coVerify(exactly = 0) { authManager.refreshAccessToken() }
    }

    @Test
    fun authenticatedRequestUsesCurrentBearerToken() {
        every { authManager.getAccessToken() } returns "token-one"
        every { authManager.needsRefresh } returns false
        server.enqueue(MockResponse().setResponseCode(200))

        executeRequest()

        assertEquals("Bearer token-one", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun proactivelyRefreshesExpiringTokenBeforeFirstRequest() {
        every { authManager.getAccessToken() } returns "stale"
        every { authManager.needsRefresh } returns true
        coEvery { authManager.refreshAccessToken() } returns "fresh"
        server.enqueue(MockResponse().setResponseCode(200))

        executeRequest()

        assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
        coVerify(exactly = 1) { authManager.refreshAccessToken() }
    }

    @Test
    fun retriesExactlyOnceWithRefreshedTokenAfter401() {
        every { authManager.getAccessToken() } returns "stale"
        every { authManager.needsRefresh } returns false
        coEvery { authManager.refreshAccessToken() } returns "fresh"
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val responseCode = executeRequest()

        assertEquals(200, responseCode)
        assertEquals("Bearer stale", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
        assertEquals(2, server.requestCount)
        coVerify(exactly = 1) { authManager.refreshAccessToken() }
    }

    @Test
    fun failedRefreshReturnsOriginal401WithoutRetryLoop() {
        every { authManager.getAccessToken() } returns "stale"
        every { authManager.needsRefresh } returns false
        coEvery { authManager.refreshAccessToken() } returns null
        server.enqueue(MockResponse().setResponseCode(401))

        val responseCode = executeRequest()

        assertEquals(401, responseCode)
        assertEquals(1, server.requestCount)
        coVerify(exactly = 1) { authManager.refreshAccessToken() }
    }

    @Test
    fun callerBlockedOnRefreshLockReusesTokenAlreadyRefreshedElsewhere() {
        every { authManager.getAccessToken() } returnsMany listOf("stale", "fresh")
        every { authManager.needsRefresh } returns false
        coEvery { authManager.refreshAccessToken() } returns "should-not-be-used"
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200))

        val responseCode = executeRequest()

        assertEquals(200, responseCode)
        assertEquals("Bearer stale", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
        coVerify(exactly = 0) { authManager.refreshAccessToken() }
    }

    private fun executeRequest(): Int {
        val client = OkHttpClient.Builder()
            .addInterceptor(TraktAuthInterceptor(authManager))
            .build()
        val request = Request.Builder().url(server.url("/sync")).build()
        return client.newCall(request).execute().use { it.code }
    }
}
