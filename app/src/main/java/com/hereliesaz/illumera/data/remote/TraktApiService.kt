package com.hereliesaz.illumera.data.remote

import com.hereliesaz.illumera.data.model.trakt.TraktDeviceCodeResponse
import com.hereliesaz.illumera.data.model.trakt.TraktTokenResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

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
}
