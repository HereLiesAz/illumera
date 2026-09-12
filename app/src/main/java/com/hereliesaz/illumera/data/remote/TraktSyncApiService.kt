package com.hereliesaz.illumera.data.remote

import com.hereliesaz.illumera.data.model.trakt.TraktLastActivities
import com.hereliesaz.illumera.data.model.trakt.TraktMovie
import com.hereliesaz.illumera.data.model.trakt.TraktPlaybackItem
import com.hereliesaz.illumera.data.model.trakt.TraktShow
import com.hereliesaz.illumera.data.model.trakt.TraktShowProgress
import com.hereliesaz.illumera.data.model.trakt.TraktScrobbleRequest
import com.hereliesaz.illumera.data.model.trakt.TraktSyncRequest
import com.hereliesaz.illumera.data.model.trakt.TraktSyncResponse
import com.hereliesaz.illumera.data.model.trakt.TraktWatchedMovie
import com.hereliesaz.illumera.data.model.trakt.TraktWatchedShow
import com.hereliesaz.illumera.data.model.trakt.TraktWatchlistItem
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface TraktSyncApiService {

    // ── Activity ──

    @GET("sync/last_activities")
    suspend fun getLastActivities(): Response<TraktLastActivities>

    // ── Personalized recommendations ──

    @GET("recommendations/movies")
    suspend fun getMovieRecommendations(
        @Query("limit") limit: Int = 30
    ): Response<List<TraktMovie>>

    @GET("recommendations/shows")
    suspend fun getShowRecommendations(
        @Query("limit") limit: Int = 30
    ): Response<List<TraktShow>>

    // ── Watchlist ──

    @GET("sync/watchlist")
    suspend fun getWatchlist(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 100
    ): Response<List<TraktWatchlistItem>>

    @POST("sync/watchlist")
    suspend fun addToWatchlist(
        @Body body: TraktSyncRequest
    ): Response<TraktSyncResponse>

    @POST("sync/watchlist/remove")
    suspend fun removeFromWatchlist(
        @Body body: TraktSyncRequest
    ): Response<TraktSyncResponse>

    // ── Collection / Trakt Library ──

    @POST("sync/collection")
    suspend fun addToCollection(
        @Body body: TraktSyncRequest
    ): Response<TraktSyncResponse>

    @POST("sync/collection/remove")
    suspend fun removeFromCollection(
        @Body body: TraktSyncRequest
    ): Response<TraktSyncResponse>

    // ── Playback Progress (Continue Watching) ──

    @GET("sync/playback")
    suspend fun getPlaybackProgress(): Response<List<TraktPlaybackItem>>

    @DELETE("sync/playback/{id}")
    suspend fun deletePlaybackItem(
        @Path("id") playbackId: Long
    ): Response<Unit>

    // ── Watched History ──

    @GET("sync/watched/movies")
    suspend fun getWatchedMovies(): Response<List<TraktWatchedMovie>>

    @GET("sync/watched/shows")
    suspend fun getWatchedShows(): Response<List<TraktWatchedShow>>

    @GET("shows/{id}/progress/watched")
    suspend fun getShowProgress(
        @Path("id") showId: String
    ): Response<TraktShowProgress>

    @POST("sync/history")
    suspend fun addToHistory(
        @Body body: TraktSyncRequest
    ): Response<TraktSyncResponse>

    @POST("sync/history/remove")
    suspend fun removeFromHistory(
        @Body body: TraktSyncRequest
    ): Response<TraktSyncResponse>

    // ── Scrobble ──

    @POST("scrobble/start")
    suspend fun scrobbleStart(
        @Body body: TraktScrobbleRequest
    ): Response<okhttp3.ResponseBody>

    @POST("scrobble/pause")
    suspend fun scrobblePause(
        @Body body: TraktScrobbleRequest
    ): Response<okhttp3.ResponseBody>

    @POST("scrobble/stop")
    suspend fun scrobbleStop(
        @Body body: TraktScrobbleRequest
    ): Response<okhttp3.ResponseBody>
}
