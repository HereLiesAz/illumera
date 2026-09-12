package com.hereliesaz.illumera.data.remote

import com.hereliesaz.illumera.data.model.stremio.AddonCatalogItem
import com.hereliesaz.illumera.data.model.stremio.AddonCatalogResponse
import com.hereliesaz.illumera.data.model.stremio.CatalogResponse
import com.hereliesaz.illumera.data.model.stremio.Manifest
import com.hereliesaz.illumera.data.model.stremio.MetaResponse
import com.hereliesaz.illumera.data.model.stremio.SubtitleResponse
import com.hereliesaz.illumera.data.model.stremio.StreamResponse
import retrofit2.http.GET
import retrofit2.http.Url

interface StremioApiService {

    @GET
    suspend fun getManifest(@Url url: String): Manifest

    @GET
    suspend fun getCatalog(@Url url: String): CatalogResponse

    @GET
    suspend fun getAddonCatalog(@Url url: String): AddonCatalogResponse

    @GET
    suspend fun getAddonCollection(@Url url: String): List<AddonCatalogItem>

    @GET
    suspend fun getMeta(@Url url: String): MetaResponse

    @GET
    suspend fun getStreams(@Url url: String): StreamResponse

    @GET
    suspend fun getSubtitles(@Url url: String): SubtitleResponse
}
