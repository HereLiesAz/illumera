package com.hereliesaz.illumera.data.tmdb

import android.util.Log
import com.hereliesaz.illumera.BuildConfig
import com.hereliesaz.illumera.data.remote.TmdbApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TmdbService"

@Singleton
class TmdbService @Inject constructor(
    private val tmdbApi: TmdbApiService
) {
    private val imdbToTmdbCache = com.hereliesaz.illumera.data.cache.boundedCache<String, Int>(2_000)
    private val tmdbToImdbCache = com.hereliesaz.illumera.data.cache.boundedCache<Int, String>(2_000)
    private val cacheMutex = Mutex()

    fun apiKey(): String = BuildConfig.TMDB_API_KEY

    /**
     * Convert an IMDB ID to a TMDB ID.
     */
    suspend fun imdbToTmdb(imdbId: String, mediaType: String): Int? = withContext(Dispatchers.IO) {
        if (!imdbId.startsWith("tt")) return@withContext null

        imdbToTmdbCache[imdbId]?.let { return@withContext it }

        try {
            val response = tmdbApi.findByExternalId(
                externalId = imdbId,
                apiKey = apiKey(),
                externalSource = "imdb_id"
            )
            if (!response.isSuccessful) return@withContext null

            val body = response.body() ?: return@withContext null
            val normalizedType = normalizeMediaType(mediaType)
            val result = when (normalizedType) {
                "movie" -> body.movieResults?.firstOrNull()
                "tv" -> body.tvResults?.firstOrNull()
                else -> body.movieResults?.firstOrNull() ?: body.tvResults?.firstOrNull()
            }

            result?.let { found ->
                cacheMutex.withLock {
                    imdbToTmdbCache[imdbId] = found.id
                    tmdbToImdbCache[found.id] = imdbId
                }
                found.id
            }
        } catch (e: Exception) {
            com.hereliesaz.illumera.crash.AppErrors.e(TAG, "Error looking up TMDB ID for $imdbId: ${e.message}")
            null
        }
    }

    /**
     * Convert a TMDB ID to an IMDB ID.
     */
    suspend fun tmdbToImdb(tmdbId: Int, mediaType: String): String? = withContext(Dispatchers.IO) {
        tmdbToImdbCache[tmdbId]?.let { return@withContext it }

        try {
            val normalizedType = normalizeMediaType(mediaType)
            val response = when (normalizedType) {
                "movie" -> tmdbApi.getMovieExternalIds(tmdbId, apiKey())
                else -> tmdbApi.getTvExternalIds(tmdbId, apiKey())
            }
            if (!response.isSuccessful) return@withContext null

            val body = response.body() ?: return@withContext null
            body.imdbId?.let { imdbId ->
                cacheMutex.withLock {
                    tmdbToImdbCache[tmdbId] = imdbId
                    imdbToTmdbCache[imdbId] = tmdbId
                }
                imdbId
            }
        } catch (e: Exception) {
            com.hereliesaz.illumera.crash.AppErrors.e(TAG, "Error looking up IMDB ID for $tmdbId: ${e.message}")
            null
        }
    }

    /**
     * Get a TMDB ID from any video ID format (IMDB, TMDB, prefixed).
     */
    suspend fun ensureTmdbId(videoId: String, mediaType: String): String? {
        val cleanId = videoId
            .removePrefix("tmdb:")
            .removePrefix("movie:")
            .removePrefix("series:")

        // Strip Stremio-style suffixes like tt1234567:1:3
        val idPart = cleanId
            .substringBefore(':')
            .substringBefore('/')
            .trim()

        if (idPart.startsWith("tt")) {
            return imdbToTmdb(idPart, normalizeMediaType(mediaType))?.toString()
        }

        if (idPart.all { it.isDigit() }) return idPart

        Log.w(TAG, "Unknown video ID format: $videoId")
        return null
    }

    fun normalizeMediaType(mediaType: String): String {
        return when (mediaType.lowercase()) {
            "series", "tv", "show", "tvshow" -> "tv"
            "movie", "film" -> "movie"
            else -> mediaType.lowercase()
        }
    }

    fun clearCache() {
        imdbToTmdbCache.clear()
        tmdbToImdbCache.clear()
    }

    fun preCacheMapping(imdbId: String, tmdbId: Int) {
        imdbToTmdbCache[imdbId] = tmdbId
        tmdbToImdbCache[tmdbId] = imdbId
    }
}
