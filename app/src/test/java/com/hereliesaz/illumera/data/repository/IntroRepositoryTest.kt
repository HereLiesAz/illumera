package com.hereliesaz.illumera.data.repository

import com.hereliesaz.illumera.data.model.introdb.IntroDbSegment
import com.hereliesaz.illumera.data.model.introdb.IntroDbSegmentsResponse
import com.hereliesaz.illumera.data.remote.IntroDbService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntroRepositoryTest {

    @Test
    fun successfulLookupBuildsExpectedUrlAndCachesResponse() = runTest {
        val api = mockk<IntroDbService>()
        val response = IntroDbSegmentsResponse(intro = IntroDbSegment(start_ms = 1_000, end_ms = 9_000))
        coEvery {
            api.getSegments("https://api.introdb.app/segments?imdb_id=tt123&season=2&episode=4")
        } returns response
        val repository = IntroRepository(api)

        assertEquals(response, repository.getSegments("tt123", 2, 4))
        assertEquals(response, repository.getSegments("tt123", 2, 4))

        coVerify(exactly = 1) {
            api.getSegments("https://api.introdb.app/segments?imdb_id=tt123&season=2&episode=4")
        }
    }

    @Test
    fun cacheKeySeparatesSeasonEpisodeAndImdbId() = runTest {
        val api = mockk<IntroDbService>()
        coEvery { api.getSegments(any()) } answers {
            IntroDbSegmentsResponse(intro = IntroDbSegment(start_ms = firstArg<String>().hashCode().toLong()))
        }
        val repository = IntroRepository(api)

        repository.getSegments("tt1", 1, 1)
        repository.getSegments("tt1", 1, 2)
        repository.getSegments("tt1", 2, 1)
        repository.getSegments("tt2", 1, 1)

        coVerify(exactly = 4) { api.getSegments(any()) }
    }

    @Test
    fun failuresReturnNullAndAreNotCached() = runTest {
        val api = mockk<IntroDbService>()
        coEvery { api.getSegments(any()) } throws IllegalStateException("network")
        val repository = IntroRepository(api)

        assertNull(repository.getSegments("tt123", 1, 1))
        assertNull(repository.getSegments("tt123", 1, 1))

        coVerify(exactly = 2) { api.getSegments(any()) }
    }
}
