package com.hereliesaz.illumera.data.repository

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import com.hereliesaz.illumera.data.model.stremio.AddonCatalogItem
import com.hereliesaz.illumera.data.model.stremio.AddonCatalogResponse
import com.hereliesaz.illumera.data.model.stremio.AddonCatalogSource
import com.hereliesaz.illumera.data.model.stremio.AddonCollectionLoadResult
import com.hereliesaz.illumera.data.model.stremio.LegacyAddonRepository
import com.hereliesaz.illumera.data.model.stremio.LegacyAddonRepositoryItem
import com.hereliesaz.illumera.data.model.stremio.Manifest
import com.hereliesaz.illumera.data.model.stremio.SavedAddonCollection
import com.hereliesaz.illumera.data.remote.StremioApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AddonCatalogRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val api: StremioApiService
) {
    private val gson = Gson()
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val savedCollectionListType = object : TypeToken<List<SavedAddonCollection>>() {}.type
    private val addonItemListType = object : TypeToken<List<AddonCatalogItem>>() {}.type

    fun getSavedCollections(): List<SavedAddonCollection> = synchronized(this) {
        runCatching {
            gson.fromJson<List<SavedAddonCollection>>(
                prefs.getString(KEY_COLLECTIONS, "[]"),
                savedCollectionListType
            )
        }.getOrNull().orEmpty()
            .filter { it.name.isNotBlank() && it.url.isNotBlank() }
            .distinctBy { it.url }
    }

    fun removeCollection(url: String) = synchronized(this) {
        val normalized = normalizeExternalUrl(url) ?: url.trim()
        val next = getSavedCollections().filterNot { it.url == normalized }
        prefs.edit().putString(KEY_COLLECTIONS, gson.toJson(next)).apply()
    }

    suspend fun fetch(
        source: AddonCatalogSource,
        collection: SavedAddonCollection? = null
    ): List<AddonCatalogItem> = withContext(Dispatchers.IO) {
        val items = withTimeout(CATALOG_TIMEOUT_MS) {
            when (source) {
                AddonCatalogSource.OFFICIAL -> fetchOfficial()
                AddonCatalogSource.COMMUNITY -> fetchCommunity()
                AddonCatalogSource.COLLECTION -> {
                    requireNotNull(collection) { "Choose an addon collection first" }
                    fetchCollection(collection.url, save = false).addons
                }
            }
        }
        sanitize(items)
    }

    suspend fun addCollection(rawUrl: String): AddonCollectionLoadResult =
        fetchCollection(rawUrl, save = true)

    suspend fun fetchCollection(
        rawUrl: String,
        save: Boolean = false
    ): AddonCollectionLoadResult = withContext(Dispatchers.IO) {
        val url = normalizeExternalUrl(rawUrl)
            ?: throw IllegalArgumentException("Use an HTTP/HTTPS or stremio:// addon collection URL")

        val result = withTimeout(CATALOG_TIMEOUT_MS) {
            parseCollection(url, api.getJson(url))
        }
        val clean = result.copy(addons = sanitize(result.addons))
        if (clean.addons.isEmpty()) {
            throw IllegalArgumentException("This collection contains no compatible Stremio addons")
        }

        if (save) saveCollection(clean.collection)
        clean
    }

    private suspend fun parseCollection(url: String, json: JsonElement): AddonCollectionLoadResult {
        if (json.isJsonArray) {
            val items = runCatching {
                gson.fromJson<List<AddonCatalogItem>>(json, addonItemListType)
            }.getOrNull().orEmpty()
            return AddonCollectionLoadResult(
                collection = SavedAddonCollection(nameFromUrl(url), url),
                addons = items
            )
        }

        if (!json.isJsonObject) {
            throw IllegalArgumentException("Unsupported addon collection format")
        }

        val obj = json.asJsonObject
        val addonsElement = obj.get("addons")
        if (addonsElement?.isJsonArray == true) {
            val addonsArray = addonsElement.asJsonArray
            val modern = addonsArray.firstOrNull()?.let { first ->
                first.isJsonObject &&
                    (first.asJsonObject.has("manifest") || first.asJsonObject.has("transportUrl"))
            } == true

            if (modern) {
                val response = gson.fromJson(json, AddonCatalogResponse::class.java)
                val name = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                    ?.takeIf { it.isNotBlank() }
                    ?: nameFromUrl(url)
                return AddonCollectionLoadResult(
                    collection = SavedAddonCollection(name, url),
                    addons = response.addons
                )
            }

            // Older Stremio repository JSON uses addon metadata plus endpoint arrays.
            val legacy = gson.fromJson(json, LegacyAddonRepository::class.java)
            return AddonCollectionLoadResult(
                collection = SavedAddonCollection(
                    legacy.name?.takeIf { it.isNotBlank() } ?: nameFromUrl(url),
                    url
                ),
                addons = resolveLegacyRepository(legacy)
            )
        }

        // Modern addon-catalog providers are themselves regular Stremio addons whose
        // manifest advertises addonCatalogs. Accepting the manifest URL here means
        // Illumera can use third-party addon repositories without knowing them ahead of time.
        val manifest = runCatching { gson.fromJson(json, Manifest::class.java) }.getOrNull()
        @Suppress("SENSELESS_COMPARISON")
        if (manifest != null && manifest.id != null && manifest.name != null && !manifest.addonCatalogs.isNullOrEmpty()) {
            val base = url.removeSuffix("/manifest.json").trimEnd('/')
            val items = coroutineScope {
                manifest.addonCatalogs.orEmpty().map { catalog ->
                    async {
                        runCatching {
                            val type = Uri.encode(catalog.type)
                            val id = Uri.encode(catalog.id)
                            api.getAddonCatalog("$base/addon_catalog/$type/$id.json").addons
                        }.getOrDefault(emptyList())
                    }
                }.awaitAll().flatten()
            }
            return AddonCollectionLoadResult(
                collection = SavedAddonCollection(manifest.name, url),
                addons = items
            )
        }

        throw IllegalArgumentException("Unsupported addon collection format")
    }

    private suspend fun resolveLegacyRepository(
        repository: LegacyAddonRepository
    ): List<AddonCatalogItem> = coroutineScope {
        data class EndpointRef(val endpoint: String, val meta: LegacyAddonRepositoryItem?)

        val refs = buildList {
            repository.addons.orEmpty().forEach { addon ->
                addon.endpoints.orEmpty().forEach { endpoint -> add(EndpointRef(endpoint, addon)) }
            }
            repository.endpoints.orEmpty().forEach { endpoint -> add(EndpointRef(endpoint, null)) }
        }
            .distinctBy { it.endpoint }
            .take(MAX_LEGACY_ENDPOINTS)

        refs.map { ref ->
            async(Dispatchers.IO) { resolveLegacyEndpoint(ref.endpoint, ref.meta) }
        }.awaitAll().filterNotNull()
    }

    private suspend fun resolveLegacyEndpoint(
        rawEndpoint: String,
        meta: LegacyAddonRepositoryItem?
    ): AddonCatalogItem? {
        val endpoint = normalizeExternalUrl(rawEndpoint) ?: return null
        val clean = endpoint.substringBefore('?').trimEnd('/')

        // The old /stremio/v1 transport is a different protocol from the manifest
        // protocol Illumera uses. Do not pretend it is installable. Modern endpoints
        // either are manifest URLs or expose /manifest.json from their base URL.
        if (clean.endsWith("/stremio/v1", ignoreCase = true)) return null

        val manifestUrl = if (clean.endsWith("manifest.json", ignoreCase = true)) {
            endpoint
        } else {
            "$clean/manifest.json"
        }

        val manifest = runCatching {
            withTimeout(LEGACY_MANIFEST_TIMEOUT_MS) { api.getManifest(manifestUrl) }
        }.getOrNull() ?: return null

        @Suppress("SENSELESS_COMPARISON")
        if (manifest.id == null || manifest.name == null || manifest.id.isBlank() || manifest.name.isBlank()) {
            return null
        }

        val enriched = if (manifest.logo.isNullOrBlank() && !meta?.logo.isNullOrBlank()) {
            manifest.copy(logo = meta?.logo)
        } else {
            manifest
        }
        return AddonCatalogItem(
            transportUrl = manifestUrl,
            manifest = enriched
        )
    }

    private fun sanitize(items: List<AddonCatalogItem>): List<AddonCatalogItem> =
        items.asSequence()
            .filter { item ->
                @Suppress("SENSELESS_COMPARISON")
                item.transportUrl != null && item.manifest != null &&
                    item.transportUrl.isNotBlank() &&
                    item.manifest.id != null && item.manifest.name != null &&
                    item.manifest.id.isNotBlank() && item.manifest.name.isNotBlank()
            }
            .distinctBy { it.transportUrl }
            .toList()

    private fun saveCollection(collection: SavedAddonCollection) = synchronized(this) {
        val existing = getSavedCollections()
        val next = (existing.filterNot { it.url == collection.url } + collection)
            .sortedBy { it.name.lowercase() }
        prefs.edit().putString(KEY_COLLECTIONS, gson.toJson(next)).apply()
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

    private fun normalizeExternalUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return null
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> trimmed
            "stremio" -> if (uri.host.isNullOrBlank()) null
                else uri.buildUpon().scheme("https").build().toString()
            else -> null
        }
    }

    private fun nameFromUrl(url: String): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        return uri?.host?.removePrefix("www.")?.takeIf { it.isNotBlank() }
            ?: "Addon collection"
    }

    companion object {
        private const val PREFS_FILE = "illumera_addon_catalogs"
        private const val KEY_COLLECTIONS = "saved_collections"
        private const val CATALOG_TIMEOUT_MS = 20_000L
        private const val LEGACY_MANIFEST_TIMEOUT_MS = 5_000L
        private const val MAX_LEGACY_ENDPOINTS = 100

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
