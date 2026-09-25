package com.hereliesaz.illumera.data.soundtrack

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SoundtrackRepositoryTest {

    private val requested = mutableListOf<String>()

    private fun repository(body: String, code: Int = 200) = SoundtrackRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            requested += chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("x").body(body.toResponseBody()).build()
        }.build()
    )

    private val episodeJson = """
        {"title":"Show","groups":[
          {"season":1,"episode":2,"title":"Pilot","songs":[{"title":"Song A","artist":"Band"},{"title":"Song B"}]},
          {"season":1,"episode":3,"title":"Empty","songs":[]}
        ]}
    """

    @Test
    fun `loads an episode and drops groups without songs`() = runTest {
        val soundtrack = repository(episodeJson).load("series", "tt123", season = 1, episode = 2)!!

        assertEquals(listOf("Song A", "Song B"), soundtrack.groups.single().songs.map { it.title })
        assertEquals("Band", soundtrack.groups.single().songs.first().artist)
        assertEquals(true, requested.single().endsWith("/soundtrack/series/tt123:1:2.json"))
    }

    @Test
    fun `caches per title and skips non-imdb ids and failures`() = runTest {
        val repo = repository(episodeJson)
        repo.load("movie", "tt9")
        repo.load("movie", "tt9")
        assertEquals(1, requested.size)

        assertNull(repo.load("movie", "kitsu:1"))
        assertNull(repository("", code = 404).load("movie", "tt1"))
    }
}
