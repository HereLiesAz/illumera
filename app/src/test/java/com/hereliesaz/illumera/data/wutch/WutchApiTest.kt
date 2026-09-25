package com.hereliesaz.illumera.data.wutch

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WutchApiTest {
    private val requests = mutableListOf<Request>()

    private fun api(vararg routes: Pair<String, Pair<Int, String>>): WutchApi {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            requests += request
            val (code, body) = routes.firstOrNull { request.url.encodedPath.endsWith(it.first) }?.second ?: (404 to "{}")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()
        return WutchApi(client)
    }

    @Test
    fun watchlistKeepsOnlyRowsWithImdbIdsAndSendsTheApiKey() {
        val wl = api("/list/watchlist/export" to (200 to """[
            {"type":"movie","title":"Interstellar","poster_url":"p","imdb_id":"tt0816692","tmdb_id":"157336"},
            {"type":"tv_show","title":"Show","poster_url":null,"imdb_id":"tt1","tmdb_id":"2"},
            {"type":"movie","title":"No id","imdb_id":null}
        ]""")).watchlist("KEY")

        assertEquals(listOf("tt0816692" to true, "tt1" to false), wl.map { it.imdbId to it.isMovie })
        assertEquals("KEY", requests.single().url.queryParameter("api-key"))
        assertEquals("json", requests.single().url.queryParameter("type"))
    }

    @Test
    fun upNextReadsShowSlugAndResumePosition() {
        val items = api("/dashboard/up-next" to (200 to """{"up_next":[
            {"id":42,"slug":"some-show","name":"Ep","tv_show_slug":"some-show","tv_show_name":"Some Show","type":"tv_show_episode","season_number":2,"episode_number":3,"resume_position_seconds":600,"resume_runtime_minutes":30,"last_watched":"2026-08-20 21:04:11"},
            {"id":7,"slug":"a-movie","name":"A Movie","type":"movie","resume_position_seconds":120,"runtime":100}
        ]}""")).upNext("KEY")

        assertEquals("some-show", items[0].slug)
        assertEquals(2, items[0].season)
        assertEquals(600L, items[0].resumeSeconds)
        assertTrue(items[1].isMovie)
        assertEquals(100, items[1].runtimeMinutes)
    }

    @Test
    fun resolveReturnsNullWhileWutchImportsTheTitle() {
        assertNull(api("/import-content" to (202 to """{"status":"queued"}""")).resolve("tt9", "KEY"))
        val ref = api("/import-content" to (200 to """{"type":"tv","id":406,"slug":"south-park-1997"}""")).resolve("tt0121955", "KEY")
        assertEquals(WutchApi.Ref("tv", 406, "south-park-1997"), ref)
    }

    @Test
    fun loginErrorsCarryWutchsMessage() {
        val error = runCatching { api("/api/login" to (401 to """{"code":401,"message":"Invalid credentials."}""")).login("a@b.c", "x") }
            .exceptionOrNull() as WutchApi.WutchException
        assertEquals(401, error.code)
        assertEquals("Invalid credentials.", error.message)
    }

    @Test
    fun playbackIdsAndTimesParse() {
        assertEquals(Triple("tt1", null, null), WutchManager.parsePlaybackId("tt1"))
        assertEquals(Triple("tt1", 2, 3), WutchManager.parsePlaybackId("tt1:2:3"))
        assertNull(WutchManager.parsePlaybackId("kitsu:1:2"))
        assertEquals(1787184000000L, WutchManager.parseTime("2026-08-20 21:20:00")?.let { it - it % 86_400_000L })
        assertEquals(WutchManager.parseTime("2026-08-20T21:04:11+00:00"), WutchManager.parseTime("2026-08-20 21:04:11"))
    }
}
