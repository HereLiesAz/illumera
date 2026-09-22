package com.hereliesaz.illumera.data.repository

import com.google.gson.Gson
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.AddonEntity
import com.hereliesaz.illumera.data.model.CatalogConfigEntity
import com.hereliesaz.illumera.data.model.stremio.CatalogManifest
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.data.model.stremio.Stream
import com.hereliesaz.illumera.data.remote.StremioApiService
import com.hereliesaz.illumera.domain.HomeRow
import com.hereliesaz.illumera.domain.HubGroupRow
import com.hereliesaz.illumera.domain.HubItem
import com.hereliesaz.illumera.domain.HubShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AddonRepository @Inject constructor(
    private val api: StremioApiService,
    private val dao: AddonDao
) {
    private val gson = Gson()
    private val MAX_CATALOG_PAGES = 30

    /** Filter out MetaItems where Gson injected null into non-null Kotlin fields */
    private fun List<MetaItem>.sanitize(): List<MetaItem> = filter { item ->
        @Suppress("SENSELESS_COMPARISON")
        item.id != null && item.name != null && item.type != null
    }
    /** Validate a single MetaItem from getMeta() — returns null if Gson injected nulls */
    @Suppress("SENSELESS_COMPARISON")
    private fun MetaItem?.sanitize(): MetaItem? =
        this?.takeIf { it.id != null && it.name != null && it.type != null }
    private val CATALOG_TIMEOUT_MS = 10_000L // 10 seconds per catalog request
    private val STREAM_TIMEOUT_MS = 20_000L  // 20 seconds per stream request (torrent addons need more time)

    /**
     * Fetches a single page of catalog items at the given skip offset.
     * Used for on-demand lazy loading as the user scrolls.
     */
    suspend fun fetchNextCatalogPage(baseUrl: String, skip: Int): List<MetaItem> = withContext(Dispatchers.IO) {
        try {
            val url = if (skip == 0) baseUrl else {
                if (baseUrl.endsWith(".json")) baseUrl.dropLast(5) + "/skip=$skip.json" else baseUrl + "/skip=$skip.json"
            }
            withTimeout(CATALOG_TIMEOUT_MS) { api.getCatalog(url) }.metas.orEmpty().sanitize()
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { emptyList() }
    }


    suspend fun searchMovies(query: String): List<MetaItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        val movieJob = async {
            try {
                withTimeout(CATALOG_TIMEOUT_MS) {
                    api.getCatalog("https://v3-cinemeta.strem.io/catalog/movie/top/search=$query.json")
                }.metas.orEmpty().sanitize()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { emptyList() }
        }

        val seriesJob = async {
            try {
                withTimeout(CATALOG_TIMEOUT_MS) {
                    api.getCatalog("https://v3-cinemeta.strem.io/catalog/series/top/search=$query.json")
                }.metas.orEmpty().sanitize()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { emptyList() }
        }

        val cinemeta = "https://v3-cinemeta.strem.io"
        return@withContext (movieJob.await() + seriesJob.await())
            .map { it.copy(addonBaseUrl = cinemeta) }
    }

    /**
     * Checks whether a specific catalog in an addon's manifest declares "skip" extra support.
     */
    private fun catalogSupportsSkip(addon: AddonEntity, catalogType: String, catalogId: String): Boolean {
        val catalogs: List<CatalogManifest> = try {
            gson.fromJson(addon.catalogsJson, Array<CatalogManifest>::class.java)?.toList()?.filterNotNull() ?: return false
        } catch (_: Exception) { return false }
        val catalog = catalogs.find { it.type == catalogType && it.id == catalogId } ?: return false
        return catalog.extra?.any { it.name == "skip" } ?: false
    }

    suspend fun getDashboardRows(
        screen: String,
        skipConfigs: Int = 0,
        maxConfigs: Int = Int.MAX_VALUE,
        catalogTimeoutMs: Long = CATALOG_TIMEOUT_MS
    ): List<HomeRow> = withContext(Dispatchers.IO) {
        val addons = dao.getAllAddons().firstOrNull()?.filter { it.isEnabled } ?: emptyList()
        val configs = dao.getAllCatalogConfigs().firstOrNull() ?: emptyList()
        val addonMap = addons.associateBy { it.transportUrl }

        val filteredConfigs = configs
            .filter { config ->
                when(screen) {
                    "home" -> config.showInHome
                    "movies" -> config.showInMovies
                    "series" -> config.showInSeries
                    else -> false
                }
            }
            .sortedBy { config ->
                when(screen) {
                    "home" -> config.homeOrder
                    "movies" -> config.moviesOrder
                    "series" -> config.seriesOrder
                    else -> 0
                }
            }
            .drop(skipConfigs.coerceAtLeast(0))
            .let { sliced ->
                if (maxConfigs == Int.MAX_VALUE) sliced else sliced.take(maxConfigs.coerceAtLeast(0))
            }

        val deferredJobs = filteredConfigs.mapNotNull { config ->
            val addon = addonMap[config.transportUrl] ?: return@mapNotNull null
            async {
                try {
                    val url = "${config.transportUrl}/catalog/${config.catalogType}/${config.catalogId}.json"
                    // Fetch only the first page for fast initial load
                    val rawMetas = try { withTimeout(catalogTimeoutMs) { api.getCatalog(url) }.metas.orEmpty().sanitize() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
                    if (rawMetas.isNotEmpty()) {
                        val metas = rawMetas.map { it.copy(addonBaseUrl = config.transportUrl) }
                        val typeSuffix = config.catalogType.replaceFirstChar { it.uppercase() }
                        val defaultTitle = if (config.catalogName != null) "${config.catalogName} - ${typeSuffix}" else "${config.addonName} - ${config.catalogId.replaceFirstChar { it.uppercase() }}"
                        val finalTitle = config.customTitle ?: defaultTitle
                        HomeRow(
                            configId = config.uniqueId,
                            title = finalTitle,
                            items = metas,
                            catalogUrl = url,
                            isInfiniteLoopEnabled = config.isInfiniteLoopEnabled,
                            visibleItemCount = config.visibleItemCount,
                            isInfiniteScrollingEnabled = config.isInfiniteScrollingEnabled,
                            order = when(screen) {
                                "home" -> config.homeOrder
                                "movies" -> config.moviesOrder
                                "series" -> config.seriesOrder
                                else -> 999
                            },
                            supportsSkip = catalogSupportsSkip(addon, config.catalogType, config.catalogId)
                        )
                    } else null
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { null }
            }
        }
        deferredJobs.awaitAll().filterNotNull()
    }

    /**
     * Fast single-page category fetch for lightweight surfaces like Hero Carousel.
     * Avoids paginating through the full catalog when only a handful of items are needed.
     */
    suspend fun getCategoryRowPreview(
        configId: String,
        maxItems: Int,
        timeoutMs: Long = CATALOG_TIMEOUT_MS
    ): HomeRow? = withContext(Dispatchers.IO) {
        val config = dao.getCatalogConfig(configId) ?: return@withContext null
        val addon = dao.getAddon(config.transportUrl) ?: return@withContext null
        if (!addon.isEnabled) return@withContext null

        try {
            val url = "${config.transportUrl}/catalog/${config.catalogType}/${config.catalogId}.json"
            val metas = try {
                withTimeout(timeoutMs) { api.getCatalog(url) }.metas.orEmpty().sanitize()
            } catch (_: Exception) {
                emptyList()
            }.take(maxItems.coerceAtLeast(1))

            if (metas.isNotEmpty()) {
                val typeSuffix = config.catalogType.replaceFirstChar { it.uppercase() }
                val defaultTitle = if (config.catalogName != null) "${config.catalogName} - ${typeSuffix}" else "${config.addonName} - ${config.catalogId.replaceFirstChar { it.uppercase() }}"
                val finalTitle = config.customTitle ?: defaultTitle
                HomeRow(
                    configId = config.uniqueId,
                    title = finalTitle,
                    items = metas,
                    catalogUrl = url,
                    isInfiniteLoopEnabled = config.isInfiniteLoopEnabled,
                    visibleItemCount = config.visibleItemCount,
                    isInfiniteScrollingEnabled = config.isInfiniteScrollingEnabled,
                    order = 999,
                    supportsSkip = catalogSupportsSkip(addon, config.catalogType, config.catalogId)
                )
            } else null
        } catch (_: Exception) { null }
    }

    suspend fun getHubRows(screen: String = "home"): List<HubGroupRow> = withContext(Dispatchers.IO) {
        val hubRows = dao.getAllHubRows().firstOrNull() ?: emptyList()
        val allItems = dao.getAllHubRowItems().firstOrNull() ?: emptyList()
        val itemsByRow = allItems.groupBy { it.hubRowId }

        hubRows
            .filter { row ->
                when(screen) {
                    "home" -> row.showInHome
                    "movies" -> row.showInMovies
                    "series" -> row.showInSeries
                    else -> false
                }
            }
            .sortedBy { row ->
                when(screen) {
                    "home" -> row.homeOrder
                    "movies" -> row.moviesOrder
                    "series" -> row.seriesOrder
                    else -> 0
                }
            }
            .map { row ->
                val items = (itemsByRow[row.id] ?: emptyList())
                    .sortedBy { it.itemOrder }
                    .map { item ->
                        HubItem(
                            id = "${row.id}:${item.configUniqueId}",
                            title = item.title,
                            categoryId = item.configUniqueId,
                            customImageUrl = item.customImageUrl
                        )
                    }
                HubGroupRow(
                    id = row.id,
                    title = row.title,
                    items = items,
                    shape = try { HubShape.valueOf(row.shape) } catch (_: Exception) { HubShape.HORIZONTAL },
                    order = when(screen) {
                        "home" -> row.homeOrder
                        "movies" -> row.moviesOrder
                        "series" -> row.seriesOrder
                        else -> 0
                    }
                )
            }
    }

    suspend fun getStreams(
        type: String,
        id: String,
        preferredAddonBaseUrl: String? = null,
        preferredAddonRequestId: String? = null
    ): List<Stream> = withContext(Dispatchers.IO) {
        val addons = dao.getAllAddons().firstOrNull()
            ?.filter { it.isEnabled && it.supportsStream }
            ?: emptyList()
        val preferredBase = preferredAddonBaseUrl?.trimEnd('/')

        val jobs = addons.map { addon ->
            async {
                val addonBase = addon.transportUrl.trimEnd('/')
                val isPreferred = preferredBase != null && addonBase == preferredBase
                val requestId = if (isPreferred && !preferredAddonRequestId.isNullOrBlank()) {
                    preferredAddonRequestId
                } else {
                    id
                }

                // The origin addon is authoritative for its own catalog/meta IDs. For all
                // other addons, honor manifest idPrefixes so a tmdb:/kitsu:/custom ID is
                // not blindly sent to an IMDb-only stream endpoint.
                if (!isPreferred && !addon.supportsIdPrefix(requestId)) {
                    return@async emptyList()
                }

                try {
                    val url = "$addonBase/stream/$type/$requestId.json"
                    val response = withTimeout(STREAM_TIMEOUT_MS) { api.getStreams(url) }
                    val sourceLabel = addon.nickname ?: addon.name
                    response.streams.orEmpty().map { stream ->
                        stream.copy(
                            addonTransportUrl = addon.transportUrl,
                            addonDisplayName = sourceLabel,
                            addonRequestType = type,
                            addonRequestId = requestId
                        )
                    }
                } catch (_: TimeoutCancellationException) {
                    emptyList()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

        jobs.awaitAll().flatten()
    }

    suspend fun getAddonSortOrders(): Map<String, Int> = withContext(Dispatchers.IO) {
        dao.getAllAddons().firstOrNull()
            ?.associate { it.transportUrl to it.sortOrder }
            ?: emptyMap()
    }

    suspend fun installAddonWithConfig(url: String, home: Boolean, movies: Boolean, series: Boolean) = withContext(Dispatchers.IO) {
        val manifest = withTimeout(30_000L) { api.getManifest(url) }
        // Gson deserializes straight into the fields without honoring Kotlin's default
        // values, so a manifest missing "id"/"name" would otherwise reach the DB as a
        // literal null against a non-null Room column and crash with a raw
        // SQLiteConstraintException instead of a meaningful error.
        @Suppress("SENSELESS_COMPARISON")
        if (manifest.id == null || manifest.name == null) {
            throw IllegalArgumentException("This addon's manifest is missing a required field (id/name)")
        }
        val transportUrl = url.removeSuffix("/manifest.json").trimEnd('/')
        val catalogsJson = gson.toJson(manifest.catalogs.orEmpty())
        val supportsMeta = manifest.resources?.any { element ->
            when {
                element.isJsonPrimitive -> element.asString == "meta"
                element.isJsonObject -> element.asJsonObject.get("name")?.asString == "meta"
                else -> false
            }
        } ?: false
        val supportsStream = manifest.resources?.any { element ->
            when {
                element.isJsonPrimitive -> element.asString == "stream"
                element.isJsonObject -> element.asJsonObject.get("name")?.asString == "stream"
                else -> false
            }
        } ?: false

        // Re-installing an already-installed addon (e.g. re-pasting the same manifest
        // URL) must not silently discard a nickname or per-catalog customizations the
        // user already made — preserve them instead of overwriting with defaults.
        val existingAddon = dao.getAddon(transportUrl)
        val entity = AddonEntity(
            transportUrl = transportUrl, id = manifest.id, name = manifest.name, version = manifest.version,
            description = manifest.description, iconUrl = manifest.logo,
            isTrusted = existingAddon?.isTrusted ?: false, isEnabled = true,
            nickname = existingAddon?.nickname, catalogsJson = catalogsJson,
            supportsMeta = supportsMeta,
            supportsStream = supportsStream,
            typesJson = gson.toJson(manifest.types.orEmpty()),
            idPrefixesJson = gson.toJson(manifest.idPrefixes.orEmpty()),
            sortOrder = existingAddon?.sortOrder ?: 999
        )
        dao.insertAddon(entity)

        val newConfigs = manifest.catalogs.orEmpty().map { catalog ->
            val uniqueId = "${transportUrl}/${catalog.type}/${catalog.id}"
            val isMovieCat = catalog.type == "movie"
            val isSeriesCat = catalog.type == "series"
            val existingConfig = dao.getCatalogConfig(uniqueId)
            if (existingConfig != null) {
                // Already configured — keep the user's customizations as-is.
                existingConfig.copy(addonName = manifest.name, catalogName = catalog.name)
            } else {
                CatalogConfigEntity(
                    uniqueId = uniqueId, transportUrl = transportUrl, addonName = manifest.name,
                    catalogType = catalog.type, catalogId = catalog.id,
                    catalogName = catalog.name, customTitle = null,
                    showInHome = home, showInMovies = movies && isMovieCat, showInSeries = series && isSeriesCat,
                    homeOrder = 999, moviesOrder = 999, seriesOrder = 999
                )
            }
        }
        dao.saveCatalogConfigs(newConfigs)
    }

    suspend fun installAddon(url: String) = installAddonWithConfig(url, true, true, true)

    suspend fun renameAddon(transportUrl: String, newName: String) = withContext(Dispatchers.IO) {
        val target = dao.getAddon(transportUrl)
        if (target != null) dao.insertAddon(target.copy(nickname = newName))
    }

    suspend fun deleteAddon(transportUrl: String) = withContext(Dispatchers.IO) {
        dao.deleteAddonCascading(transportUrl)
    }

    suspend fun fetchManifest(url: String) = withContext(Dispatchers.IO) { api.getManifest(url) }

    fun getAddons() = dao.getAllAddons()
    suspend fun updateAddons(addons: List<AddonEntity>) = withContext(Dispatchers.IO) { dao.insertAddons(addons) }

    suspend fun getMetaDetails(url: String) = withContext(Dispatchers.IO) { api.getMeta(url).meta.sanitize() }

    /**
     * Resolves meta details using a priority system:
     * 1. Try the preferred addon (the one the catalog item came from) first
     * 2. Fall back to all meta addons with type-based priority ordering
     * 3. Last resort: Cinemeta for standard types
     */
    suspend fun resolvePreferredMetaDetails(
        type: String,
        id: String,
        preferredAddonBaseUrl: String
    ): MetaItem? = withContext(Dispatchers.IO) {
        val baseUrl = preferredAddonBaseUrl.trimEnd('/')
        try {
            val url = "$baseUrl/meta/$type/$id.json"
            val meta = withTimeout(5_000L) { api.getMeta(url) }.meta.sanitize()
            meta
                ?.takeIf { it.id.isNotBlank() && it.name.isNotBlank() }
                ?.copy(addonBaseUrl = baseUrl)
        } catch (_: TimeoutCancellationException) {
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    suspend fun resolveMetaDetails(
        type: String,
        id: String,
        preferredAddonBaseUrl: String? = null
    ): MetaItem? = withContext(Dispatchers.IO) {
        val allAddons = dao.getAllAddons().firstOrNull()?.filter { it.isEnabled } ?: emptyList()
        // 1) Try preferred addon first (the addon the catalog item came from).
        // Don't check supportsMeta flag — it may be stale from older DB migrations.
        // If the addon can't serve meta, the request fails fast and we fall through.
        if (!preferredAddonBaseUrl.isNullOrBlank()) {
            resolvePreferredMetaDetails(type, id, preferredAddonBaseUrl)?.let {
                return@withContext it
            }
        }

        // 2) Priority-based fallback across all meta addons
        val addons = allAddons.filter { it.supportsMeta }

        val requestedType = type.trim()
        val inferredType = inferCanonicalType(requestedType, id)

        // Build prioritized candidate list (LinkedHashSet preserves insertion order, deduplicates)
        val candidates = linkedSetOf<Pair<AddonEntity, String>>()

        // Priority 1: Addons that explicitly support the requested type
        for (addon in addons) {
            if (addon.supportsMetaType(requestedType)) {
                candidates.add(addon to requestedType)
            }
        }

        // Priority 2: Addons that support the inferred canonical type (for custom catalog types)
        if (!inferredType.equals(requestedType, ignoreCase = true)) {
            for (addon in addons) {
                if (addon.supportsMetaType(inferredType)) {
                    candidates.add(addon to inferredType)
                }
            }
        }

        // Priority 3: First meta addon as ultimate addon fallback
        addons.firstOrNull()?.let { fallback ->
            val fallbackType = when {
                fallback.supportsMetaType(requestedType) -> requestedType
                fallback.supportsMetaType(inferredType) -> inferredType
                else -> inferredType.ifBlank { requestedType }
            }
            candidates.add(fallback to fallbackType)
        }

        // Try each candidate, skip the preferred addon (already tried above).
        // Validate that returned meta ID matches the request — addons may return
        // meta with a different ID (e.g. tmdb:12345 instead of tt1234567), which causes
        // the details screen to appear stuck since it checks movie.id == requestedId.
        for ((addon, candidateType) in candidates) {
            if (addon.transportUrl == preferredAddonBaseUrl) continue
            try {
                val url = "${addon.transportUrl}/meta/$candidateType/$id.json"
                val meta = withTimeout(CATALOG_TIMEOUT_MS) { api.getMeta(url) }.meta.sanitize()
                if (meta != null && meta.id == id) {
                    return@withContext meta.copy(addonBaseUrl = addon.transportUrl)
                }
            } catch (_: TimeoutCancellationException) {
                // A slow addon must not prevent later candidates from being tried.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { /* try next */ }
        }

        // Last resort: Cinemeta for standard types
        try {
            val url = "https://v3-cinemeta.strem.io/meta/$type/$id.json"
            return@withContext withTimeout(CATALOG_TIMEOUT_MS) { api.getMeta(url) }
                .meta
                .sanitize()
                ?.copy(addonBaseUrl = "https://v3-cinemeta.strem.io")
        } catch (_: TimeoutCancellationException) {
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }
    }

    private fun AddonEntity.supportsIdPrefix(id: String): Boolean {
        val prefixes: List<String> = try {
            gson.fromJson(idPrefixesJson, Array<String>::class.java)?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        if (prefixes.isEmpty()) return true
        val normalizedId = id.lowercase()
        return prefixes.any { prefix ->
            val normalizedPrefix = prefix.trim().lowercase()
            normalizedPrefix.isNotEmpty() && normalizedId.startsWith(normalizedPrefix)
        }
    }

    private fun AddonEntity.supportsMetaType(type: String): Boolean {
        if (!supportsMeta) return false
        val types: List<String> = try {
            gson.fromJson(typesJson, Array<String>::class.java)?.toList() ?: emptyList()
        } catch (_: Exception) { emptyList() }
        if (types.isEmpty()) return true
        return types.any { it.equals(type, ignoreCase = true) }
    }

    private fun inferCanonicalType(type: String, id: String): String {
        val known = setOf("movie", "series", "tv", "channel", "anime")
        if (type.lowercase() in known) return type
        val normalizedId = id.lowercase()
        return when {
            ":movie:" in normalizedId -> "movie"
            ":series:" in normalizedId -> "series"
            ":tv:" in normalizedId -> "tv"
            ":anime:" in normalizedId -> "anime"
            else -> type
        }
    }
}
