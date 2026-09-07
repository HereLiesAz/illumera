package com.hereliesaz.illumera.data.remote

import com.hereliesaz.illumera.data.model.trakt.TraktDeviceCodeResponse
import com.hereliesaz.illumera.data.model.trakt.TraktMovie
import com.hereliesaz.illumera.data.model.trakt.TraktShow
import com.hereliesaz.illumera.data.model.trakt.TraktTokenResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface TraktApiService {

    // ── Device Code OAuth2 Flow ──

    @POST("oauth/device/code")
    suspend fun getDeviceCode(
        @Body body: Map<String, String>
    ): Response<TraktDeviceCodeResponse>

    @POST("oauth/device/token")
    suspend fun pollToken(
        @Body body: Map<String, String>
    ): Response<TraktTokenResponse>

    @POST("oauth/token")
    suspend fun refreshToken(
        @Body body: Map<String, String>
    ): Response<TraktTokenResponse>

    @POST("oauth/revoke")
    suspend fun revokeToken(
        @Body body: Map<String, String>
    ): Response<Unit>

    // Personalized recommendations are based on the authenticated user's Trakt history.
    @GET("recommendations/movies")
    suspend fun getMovieRecommendations(
        @Query("limit") limit: Int = 30
    ): Response<List<TraktMovie>>

    @GET("recommendations/shows")
    suspend fun getShowRecommendations(
        @Query("limit") limit: Int = 30
    ): Response<List<TraktShow>>
}
