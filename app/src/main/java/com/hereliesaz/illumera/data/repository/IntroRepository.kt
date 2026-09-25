package com.hereliesaz.illumera.data.repository

import com.hereliesaz.illumera.data.model.introdb.IntroDbSegmentsResponse
import com.hereliesaz.illumera.data.remote.IntroDbService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntroRepository @Inject constructor(
    private val api: IntroDbService
) {
    private val cache = com.hereliesaz.illumera.data.cache.boundedCache<String, IntroDbSegmentsResponse>(200)

    suspend fun getSegments(
        imdbId: String,
        season: Int,
        episode: Int
    ): IntroDbSegmentsResponse? = withContext(Dispatchers.IO) {
        val cacheKey = "$imdbId:$season:$episode"
        cache[cacheKey]?.let { return@withContext it }

        try {
            val url = "$BASE_URL/segments?imdb_id=$imdbId&season=$season&episode=$episode"
            val response = withTimeout(TIMEOUT_MS) { api.getSegments(url) }
            cache[cacheKey] = response
            response
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val BASE_URL = "https://api.introdb.app"
        private const val TIMEOUT_MS = 5_000L
    }
}
