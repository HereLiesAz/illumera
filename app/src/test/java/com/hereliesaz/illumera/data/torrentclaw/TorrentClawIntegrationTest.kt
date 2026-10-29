package com.hereliesaz.illumera.data.torrentclaw

import android.content.Context
import com.google.gson.JsonParser
import com.hereliesaz.illumera.data.debrid.DebridHttp
import com.hereliesaz.illumera.data.model.debrid.DebridResult
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TorrentClawIntegrationTest {
    private lateinit var context: Context
    private lateinit var http: DebridHttp
    private lateinit var profiles: ProfileConfigurationManager
    private val activeProfile = MutableStateFlow<Int?>(1)

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("illumera_torrentclaw", Context.MODE_PRIVATE)
            .edit().clear().commit()
        http = mockk()
        profiles = mockk()
        activeProfile.value = 1
        every { profiles.activeProfileId } returns activeProfile
    }

    @After
    fun teardown() {
        context.getSharedPreferences("illumera_torrentclaw", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun integration() = TorrentClawIntegration(context, profiles, http)

    @Test
    fun disabledIntegrationNeverContactsRemoteSearch() = runTest {
        val client = integration()
        assertFalse(client.isEnabled())

        assertEquals(emptyList<com.hereliesaz.illumera.data.model.stremio.MetaItem>(),
            (client.search("inception") as DebridResult.Success).value)
        coVerify(exactly = 0) { http.get(any(), any(), any()) }
    }

    @Test
    fun enabledDiscoveryMapsOnlyCanonicalImdbMoviesAndSeries() = runTest {
        val client = integration()
        client.setEnabled(true)
        coEvery { http.get(any(), any(), any()) } returns DebridResult.Success(JsonParser.parseString(
            """{"results":[
                {"imdbId":"tt1375666","contentType":"movie","title":"Inception","year":2010,"ratingImdb":"8.8","posterUrl":"https://image.test/1.jpg"},
                {"imdbId":"tt0903747","contentType":"series","title":"Breaking Bad"},
                {"imdbId":"tt1375666","contentType":"movie","title":"Duplicate"},
                {"tmdbId":"100","contentType":"movie","title":"Unroutable"},
                {"imdbId":"tt1375666","contentType":"game","title":"Wrong category"}
            ]}"""
        ))

        val items = (client.search("inception") as DebridResult.Success).value
        assertEquals(listOf("tt1375666", "tt0903747"), items.map { it.id })
        assertEquals(listOf("movie", "series"), items.map { it.type })
        assertEquals("Inception", items.first().name)
        coVerify(exactly = 1) {
            http.get("https://torrentclaw.com/api/v1/search", emptyMap(),
                mapOf("q" to "inception", "limit" to "20"))
        }
    }

    @Test
    fun enabledFlagIsIsolatedPerProfile() {
        val client = integration()
        client.setEnabled(true)
        assertTrue(client.isEnabled())
        activeProfile.value = 2
        assertFalse(client.isEnabled())
        client.setEnabled(true)
        activeProfile.value = 1
        assertTrue(client.isEnabled())
    }

    @Test
    fun rateLimitsRemainFailuresRatherThanFakeEmptySearches() = runTest {
        val client = integration()
        client.setEnabled(true)
        coEvery { http.get(any(), any(), any()) } returns DebridResult.Failure("HTTP 429")

        assertEquals("HTTP 429", (client.search("inception") as DebridResult.Failure).message)
    }
}
