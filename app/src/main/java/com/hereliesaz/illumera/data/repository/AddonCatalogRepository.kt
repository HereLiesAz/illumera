package com.hereliesaz.illumera.data.repository

import com.hereliesaz.illumera.data.model.stremio.AddonCatalogItem
import com.hereliesaz.illumera.data.model.stremio.AddonCatalogSource
import com.hereliesaz.illumera.data.remote.StremioApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AddonCatalogRepository @Inject constructor(
    private val api: StremioApiService
) {
    suspend fun fetch(source: AddonCatalogSource): List<AddonCatalogItem> = withContext(Dispatchers.IO) {
        val items = withTimeout(CATALOG_TIMEOUT_MS) {
            when (source) {
                AddonCatalogSource.OFFICIAL -> fetchOfficial()
                AddonCatalogSource.COMMUNITY -> fetchCommunity()
            }
        }

        items
            .asSequence()
            .filter { item ->
                item.transportUrl.isNotBlank() &&
                    item.manifest.id.isNotBlank() &&
                    item.manifest.name.isNotBlank()
            }
            .distinctBy { it.transportUrl }
            .toList()
    }

    private suspend fun fetchOfficial(): List<AddonCatalogItem> {
        return runCatching {
            api.getAddonCollection(STREMIO_OFFICIAL_COLLECTION_URL)
        }.getOrElse {
            // Stremio also publishes the same official collection as source data.
            // Keep this fallback so a transient API worker failure does not make the
            // built-in catalog disappear from Illumera.
            api.getAddonCollection(STREMIO_OFFICIAL_FALLBACK_URL)
        }
    }

    private suspend fun fetchCommunity(): List<AddonCatalogItem> {
        return runCatching {
            // This is the addon_catalog endpoint Stremio Core itself requests from Cinemeta.
            api.getAddonCatalog(STREMIO_COMMUNITY_CATALOG_URL).addons
        }.getOrElse {
            // Public collection populated by addon authors through publishToCentral().
            api.getAddonCollection(STREMIO_COMMUNITY_COLLECTION_URL)
        }
    }

    companion object {
        private const val CATALOG_TIMEOUT_MS = 20_000L

        private const val STREMIO_OFFICIAL_COLLECTION_URL =
            "https://api.strem.io/addonsofficialcollection.json"
        private const val STREMIO_OFFICIAL_FALLBACK_URL =
            "https://raw.githubusercontent.com/Stremio/stremio-official-addons/master/index.json"
        private const val STREMIO_COMMUNITY_CATALOG_URL =
            "https://v3-cinemeta.strem.io/addon_catalog/all/community.json"
        private const val STREMIO_COMMUNITY_COLLECTION_URL =
            "https://api.strem.io/addonscollection.json"
    }
}
