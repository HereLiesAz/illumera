package com.hereliesaz.illumera.data.repository

import com.google.gson.Gson
import com.hereliesaz.illumera.crash.AppErrors
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.AddonEntity
import com.hereliesaz.illumera.data.model.CatalogConfigEntity
import com.hereliesaz.illumera.data.model.stremio.CatalogManifest
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.data.model.stremio.Stream
import com.hereliesaz.illumera.data.remote.StremioApiService
import com.hereliesaz.illumera.domain.ScreenGenreRows
import com.hereliesaz.illumera.domain.HomeRow
import com.hereliesaz.illumera.domain.HubGroupRow
import com.hereliesaz.illumera.domain.HubItem
import com.hereliesaz.illumera.domain.HubShape
import com.hereliesaz.illumera.ui.playback.playerSourceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/** Cross-provider identity candidates, retaining IMDb plus year-qualified title matching. */
fun aggregatedMediaIdentityKeys(meta: MetaItem): Set<String> {
    val imdb = Regex("tt[0-9]+").find(meta.id)?.value
    val title = meta.name.lowercase().filter { it.isLetterOrDigit() }
    val year = Regex("(?:19|20)[0-9]{2}").find(meta.releaseInfo.orEmpty())?.value
    return buildSet {
        if (imdb != null) add("${meta.type}:imdb:$imdb")
        if (title.isNotBlank() && year != null) add("${meta.type}:title:$title:$year")
        if (isEmpty()) add("${meta.type}:id:${meta.id}")
    }
}

/**
 * Cross-addon deduplication: canonical IMDb identifiers win when present.
 * Only collapse by title/year when at least one side lacks an IMDb ID.
 * Two films with distinct IMDb IDs must survive even if titles and years match.
 */
fun distinctAggregatedMetas(
    existing: List<MetaItem>, incoming: List<MetaItem>
): List<MetaItem> {
    val seenIds = mutableSetOf<String>()
    val imdbIds = mutableSetOf<String>()
    val titleIdentities = mutableMapOf<String, MutableSet<String?>>()
    val imdbRegex = Regex("tt[0-9]+")
    fun titleKey(meta: MetaItem): String? {
        val title = meta.name.lowercase().filter { it.isLetterOrDigit() }
        val year = Regex("(?:19|20)[0-9]{2}").find(meta.releaseInfo.orEmpty())?.value
        return if (title.isNotEmpty() && year != null) "${meta.type}:$title:$year" else null
    }
    fun register(meta: MetaItem) {
        seenIds.add("${meta.type}:${meta.id}")
        val imdb = imdbRegex.find(meta.id)?.value
        if (imdb != null) imdbIds.add("${meta.type}:$imdb")
        titleKey(meta)?.let { title ->
            titleIdentities.getOrPut(title) { mutableSetOf() }.add(imdb)
        }
    }
    existing.forEach(::register)
    return incoming.filter { meta ->
        val id = "${meta.type}:${meta.id}"
        val imdb = imdbRegex.find(meta.id)?.value
        val title = titleKey(meta)
        val old = title?.let { titleIdentities[it] }
        val duplicate = id in seenIds ||
            (imdb != null && "${meta.type}:$imdb" in imdbIds) ||
            (title != null && old != null &&
                (imdb == null || null in old))
        if (!duplicate) register(meta)
        !duplicate
    }
}

@Singleton
class AddonRepository @Inject constructor(
    private val api: StremioApiService,
    private val dao: AddonDao
) {
    private val gson = Gson()
    // Cinemeta is a fixed public metadata source, independent of installed addons.
    // Never cache failures: a temporary network error must not become a permanent genre.
    private val primaryGenreCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val primaryGenreRequests = kotlinx.coroutines.sync.Semaphore(6)

    /**
     * Resolve a stable, provider-independent lead genre before assigning an item
     * to exactly one Movies/Series row. Addon genre order is not authoritative.
     */
    suspend fun primaryGenreFor(meta: MetaItem): String? = withContext(Dispatchers.IO) {
        if (meta.type !in setOf("movie", "series")) return@withContext null
        val title = meta.name.lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }
        val year = Regex("(?:19|20)[0-9]{2}").find(meta.releaseInfo.orEmpty())?.value
        val titleKey = if (title.isNotBlank() && year != null) "${meta.type}:$title:$year" else null
        val directId = Regex("tt[0-9]+").find(meta.id)?.value
        // IMDb IDs are authoritative. A shared title and release year must
        // never make two distinct canonical IDs inherit one another's genres.
        val cached = if (directId != null) {
            primaryGenreCache["${meta.type}:$directId"]
        } else {
            titleKey?.let { primaryGenreCache[it] }
        }
        if (cached != null) return@withContext cached

        val imdbId = directId ?: try {
            kotlinx.coroutines.withTimeoutOrNull(4_000L) {
                // No year means a title-only search could pick an unrelated remake.
                if (year == null) return@withTimeoutOrNull null
                searchMovies(meta.name).firstOrNull { candidate ->
                    candidate.type == meta.type &&
                        candidate.name.lowercase(java.util.Locale.ROOT)
                            .filter { it.isLetterOrDigit() } == title &&
                        Regex("(?:19|20)[0-9]{2}")
                            .find(candidate.releaseInfo.orEmpty())?.value == year &&
                        Regex("^tt[0-9]+$").matches(candidate.id)
                }?.id
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return@withContext null

        val primary = try {
            primaryGenreRequests.withPermit {
                kotlinx.coroutines.withTimeoutOrNull(4_000L) {
                    api.getMeta("https://v3-cinemeta.strem.io/meta/${meta.type}/$imdbId.json")
                        .meta.sanitize()?.genres.orEmpty()
                        .map(ScreenGenreRows::canonicalGenre)
                        .firstOrNull { it.isNotBlank() }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (primary != null) {
            primaryGenreCache["${meta.type}:$imdbId"] = primary
            if (titleKey != null) primaryGenreCache[titleKey] = primary
        }
        primary
    }

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
    private val TAG = "AddonRepository"
    private val STREAM_TIMEOUT_MS = 20_000L  // 20 seconds per stream request (torrent addons need more time)

    /** What one addon contributed to the last stream request: a count, or why it gave none. */
    data class AddonStreamOutcome(val addonName: String, val count: Int, val problem: String? = null)

    private val _lastStreamOutcomes = kotlinx.coroutines.flow.MutableStateFlow<List<AddonStreamOutcome>>(emptyList())
    /** Per-addon results of the most recent [getStreams], so a short source list can say why. */
    val lastStreamOutcomes: kotlinx.coroutines.flow.StateFlow<List<AddonStreamOutcome>> = _lastStreamOutcomes

    /**
     * Fetches a single page of catalog items at the given skip offset.
     * Used for on-demand lazy loading as the user scrolls.
     *
     * A failed or timed-out request yields an empty list, indistinguishable from the last page;
     * callers that must not treat a failure as "no more items" use [fetchNextCatalogPageResult].
     */
    suspend fun fetchNextCatalogPage(baseUrl: String, skip: Int): List<MetaItem> =
        fetchNextCatalogPageResult(baseUrl, skip).getOrDefault(emptyList())

    /**
     * Like [fetchNextCatalogPage] but separates a successful (possibly empty) page from a
     * failure: `success(emptyList())` means the addon really has nothing more at [skip];
     * `failure` means a timeout, network or parse error and nothing can be said about the end.
     * Caller cancellation still propagates.
     */
    suspend fun fetchNextCatalogPageResult(baseUrl: String, skip: Int): Result<List<MetaItem>> =
        withContext(Dispatchers.IO) {
            try {
                val url = if (skip == 0) baseUrl else {
                    if (baseUrl.endsWith(".json")) baseUrl.dropLast(5) + "/skip=$skip.json" else baseUrl + "/skip=$skip.json"
                }
                Result.success(withTimeout(CATALOG_TIMEOUT_MS) { api.getCatalog(url) }.metas.orEmpty().sanitize())
            } catch (e: TimeoutCancellationException) {
                // A slow addon yields no page; it must not cancel the caller.
                Result.failure(e)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { Result.failure(e) }
        }


    /**
     * Searches Cinemeta movies + series. A single failing request degrades to partial results;
     * if every request fails, the last error is rethrown so callers can surface a failure state.
     */
    suspend fun searchMovies(query: String): List<MetaItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        val cinemeta = "https://v3-cinemeta.strem.io"
        // Stremio extra values are URL-encoded path segments; spaces must be %20, not '+'.
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")

        val jobs = listOf("movie", "series").map { type ->
            async {
                try {
                    Result.success(
                        withTimeout(CATALOG_TIMEOUT_MS) {
                            api.getCatalog("$cinemeta/catalog/$type/top/search=$encoded.json")
                        }.metas.orEmpty().sanitize()
                    )
                } catch (e: TimeoutCancellationException) { Result.failure<List<MetaItem>>(e)
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { Result.failure(e) }
            }
        }
        val outcomes = jobs.awaitAll()
        if (outcomes.all { it.isFailure }) {
            val cause = outcomes.last().exceptionOrNull()
            throw java.io.IOException("All search requests failed", cause)
        }
        return@withContext outcomes.flatMap { it.getOrDefault(emptyList()) }
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

    /**
     * One lazily browsed aggregated catalog per media type, without Stremio's catalog selector.
     * Every enabled addon's matching catalog is a candidate. Page numbers advance across
     * catalog sources before fetching subsequent skip-enabled pages, keeping requests bounded.
     * Deduplication within a page happens here; the caller deduplicates across pages.
     */
    /**
     * Identifies which addons (and which of their catalogs) are enabled. Anything saved from
     * their catalogs is only good while this stays the same.
     */
    suspend fun enabledAddonSignature(): String = withContext(Dispatchers.IO) {
        dao.getAllAddons().firstOrNull().orEmpty()
            .filter { it.isEnabled }
            .map { "${it.transportUrl}|${it.catalogsJson.hashCode()}" }
            .sorted()
            .joinToString(",")
            .hashCode()
            .toString()
    }

    /** Official Stremio genre dropdown plus real genres advertised by enabled addons. */
    suspend fun availableScreenGenres(type: String): List<String> = withContext(Dispatchers.IO) {
        val catalogs = dao.getAllAddons().firstOrNull().orEmpty()
            .filter { it.isEnabled }
            .flatMap { addon ->
                runCatching {
                    gson.fromJson(addon.catalogsJson, Array<CatalogManifest>::class.java)
                        ?.toList().orEmpty()
                }.getOrDefault(emptyList())
            }
        ScreenGenreRows.availableGenres(type, catalogs)
    }

    private suspend fun aggregatedCatalogSources(
        type: String, genre: String?
    ): List<Triple<AddonEntity, CatalogManifest, String?>> {
        val addons = dao.getAllAddons().firstOrNull().orEmpty().filter { it.isEnabled }
        val sources = addons.flatMap { addon ->
            val manifests = runCatching {
                gson.fromJson(addon.catalogsJson, Array<CatalogManifest>::class.java)
                    ?.toList().orEmpty()
            }.getOrDefault(emptyList())
            manifests.filter { it.type == type }.flatMap { catalog ->
                val extras = catalog.extra.orEmpty()
                if (extras.any { it.isRequired && it.name !in setOf("genre", "skip") }) {
                    emptyList()
                } else {
                    val genreExtra = extras.firstOrNull { it.name == "genre" }
                    val options = genreExtra?.options.orEmpty()
                    val sourceGenres: List<String?> = when {
                        !genre.isNullOrBlank() && genreExtra != null -> {
                            val matchingOption = options.firstOrNull {
                                ScreenGenreRows.canonicalGenre(it).equals(
                                    ScreenGenreRows.canonicalGenre(genre), ignoreCase = true
                                ) || (ScreenGenreRows.canonicalGenre(it) == "Action & Adventure" &&
                                    ScreenGenreRows.canonicalGenre(genre) in setOf("Action", "Adventure"))
                            }
                            when {
                                matchingOption != null -> listOf(matchingOption)
                                options.isEmpty() -> listOf(genre)
                                genreExtra.isRequired -> emptyList()
                                // Optional genre unsupported by this addon. Fetch unfiltered,
                                // then match actual metadata genres on the client.
                                else -> listOf(null)
                            }
                        }
                        genreExtra?.isRequired == true -> options.distinct().map { it }
                        else -> listOf(null)
                    }
                    sourceGenres.map { sourceGenre -> Triple(addon, catalog, sourceGenre) }
                }
            }
        }.distinctBy { (addon, catalog, sourceGenre) ->
            "${addon.transportUrl}/${catalog.type}/${catalog.id}/${sourceGenre.orEmpty()}"
        }

        return sources
    }

    /** Number of bounded source batches to probe before concluding a row is empty. */
    suspend fun aggregatedFirstLayerPageCount(type: String, genre: String?): Int =
        withContext(Dispatchers.IO) {
            (aggregatedCatalogSources(type, genre).size + 3) / 4
        }

    suspend fun getAggregatedCatalogPage(
        type: String,
        genre: String? = null,
        page: Int = 0,
        sourcesPerPage: Int = 4
    ): List<MetaItem> = withContext(Dispatchers.IO) {
        require(type == "movie" || type == "series")
        val sources = aggregatedCatalogSources(type, genre)
        if (sources.isEmpty()) return@withContext emptyList()
        val batchSize = sourcesPerPage.coerceIn(1, 8)
        val batchCount = (sources.size + batchSize - 1) / batchSize
        val layer = page.coerceAtLeast(0) / batchCount
        val offset = (page.coerceAtLeast(0) % batchCount) * batchSize
        kotlinx.coroutines.coroutineScope {
            val sourceGroups = listOf(sources.drop(offset).take(batchSize))
            var selected = emptyList<MetaItem>()

            for (group in sourceGroups) {
                selected = group.map { (addon, catalog, sourceGenre) ->
                async {
                    val supportsSkip = catalog.extra.orEmpty().any { it.name == "skip" }
                    val supportsGenre = catalog.extra.orEmpty().any { it.name == "genre" }
                    if (layer > 0 && !supportsSkip) return@async emptyList<MetaItem>()
                    val extras = buildList {
                        if (!sourceGenre.isNullOrBlank() && supportsGenre) {
                            val encoded = java.net.URLEncoder.encode(sourceGenre, "UTF-8").replace("+", "%20")
                            add("genre=$encoded")
                        }
                        if (layer > 0 || catalog.extra.orEmpty().any {
                            it.name == "skip" && it.isRequired
                        }) add("skip=${layer * 100}")
                    }
                    val suffix = if (extras.isEmpty()) "" else "/" + extras.joinToString("&")
                    val url = "${addon.transportUrl.trimEnd('/')}/catalog/$type/${catalog.id}$suffix.json"
                    val result = try {
                        withTimeout(5_000L) { api.getCatalog(url).metas.orEmpty().sanitize() }
                    } catch (_: TimeoutCancellationException) {
                        emptyList()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                    result.asSequence().filter { it.type == type }
                        .filter { meta ->
                            genre.isNullOrBlank() ||
                                // Trust an addon to filter only when a supported
                                // genre value was actually supplied in its request.
                                (!sourceGenre.isNullOrBlank() &&
                                    (meta.genres.isNullOrEmpty() || meta.genres.orEmpty().any {
                                        ScreenGenreRows.matchesRequestedGenre(it, genre)
                                    })) ||
                                meta.genres.orEmpty().any {
                                    ScreenGenreRows.matchesRequestedGenre(it, genre)
                                }
                        }.map { it.copy(addonBaseUrl = addon.transportUrl) }.toList()
                }
                }.awaitAll().flatten().let { distinctAggregatedMetas(emptyList(), it) }
                if (selected.isNotEmpty()) break
            }
            selected
        }
    }

    /** A streaming service offered by an enabled "Streaming Catalogs"-style addon. */
    data class StreamingService(val catalogId: String, val name: String)

    private val streamingServiceNames = linkedMapOf(
        "nfx" to "Netflix", "hbm" to "HBO Max", "dnp" to "Disney+", "amp" to "Prime Video",
        "atp" to "Apple TV+", "pmp" to "Paramount+", "hlu" to "Hulu", "pcp" to "Peacock"
    )

    private suspend fun streamingSources(type: String, catalogId: String? = null):
        List<Pair<AddonEntity, CatalogManifest>> {
        val addons = dao.getAllAddons().firstOrNull().orEmpty().filter { it.isEnabled }
        return addons.flatMap { addon ->
            val manifests = runCatching {
                gson.fromJson(addon.catalogsJson, Array<CatalogManifest>::class.java)
                    ?.toList().orEmpty()
            }.getOrDefault(emptyList())
            manifests.filter { catalog ->
                catalog.type == type && catalog.id in streamingServiceNames &&
                    (catalogId == null || catalog.id == catalogId) &&
                    catalog.extra.orEmpty().none { it.isRequired }
            }.map { addon to it }
        }
    }

    /** Streaming services available for [type] ("movie" or "series"), in a stable order. */
    suspend fun streamingServices(type: String): List<StreamingService> =
        withContext(Dispatchers.IO) {
            val present = streamingSources(type).map { it.second.id }.toSet()
            streamingServiceNames.filterKeys { it in present }
                .map { (id, name) -> StreamingService(id, name) }
        }

    /** Every title one streaming service lists for [type]; pages through skip when supported. */
    suspend fun getStreamingCatalog(type: String, catalogId: String): List<MetaItem> =
        withContext(Dispatchers.IO) {
            kotlinx.coroutines.coroutineScope {
                streamingSources(type, catalogId).map { (addon, catalog) ->
                    async {
                        val supportsSkip = catalog.extra.orEmpty().any { it.name == "skip" }
                        val collected = mutableListOf<MetaItem>()
                        var page = 0
                        while (page < 10) {
                            val suffix = if (page == 0) "" else "/skip=${page * 100}"
                            val url = "${addon.transportUrl.trimEnd('/')}/catalog/$type/${catalog.id}$suffix.json"
                            val metas = try {
                                withTimeout(CATALOG_TIMEOUT_MS) {
                                    api.getCatalog(url).metas.orEmpty().sanitize()
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                emptyList()
                            }
                            val fresh = distinctAggregatedMetas(collected, metas.filter { it.type == type })
                            if (fresh.isEmpty()) break
                            collected += fresh.map { it.copy(addonBaseUrl = addon.transportUrl) }
                            if (!supportsSkip) break
                            page++
                        }
                        collected.toList()
                    }
                }.awaitAll().flatten().let { distinctAggregatedMetas(emptyList(), it) }
            }
        }

    /**
     * Merge each enabled addon's movie/series catalog PAIR within the same
     * semantic category; never collapse Latest, Trending, Netflix etc. together.
     */
    /**
     * One Home row per *category pair*, e.g. Trending Movies + Trending Series
     * become Trending. Matching labels across enabled addons also contribute to
     * that same category; no unrelated Latest/Popular/Netflix/HBO Max rows merge.
     */
    private data class HomeCatalogPair(
        val id: String,
        val title: String,
        val sources: List<CatalogConfigEntity>,
        val order: Int
    )

    private fun homeCategoryTitle(config: CatalogConfigEntity): String {
        val candidate = config.customTitle?.takeIf { it.isNotBlank() }
            ?: config.catalogName?.takeIf { it.isNotBlank() }
            ?: config.catalogId.replace('-', ' ').replace('_', ' ')
        // TV/channel rows aren't movie/series halves. Preserve titles such as
        // "Live TV" instead of stripping their category names.
        if (config.catalogType !in setOf("movie", "series")) return candidate.trim()
        // Whole-label generic names must pair ("Movies" + "Series").
        // Match TV Shows before plain Shows so no stray "TV" grouping remains.
        val mediaSuffix = """(?:tv\s*shows?|tv\s*series|movies?|films?|series|shows?|tv)"""
        val withoutParen = candidate.replace(
            Regex("""(?i)\s*\($mediaSuffix\)\s*$"""), ""
        )
        val normalized = withoutParen.replace(
            Regex("""(?i)(?:^|[-:/\s]+)$mediaSuffix\s*$"""), ""
        ).trim()
        return normalized.ifBlank { config.addonName }
    }

    private suspend fun homeCatalogPairs(): List<HomeCatalogPair> {
        val activeAddons = dao.getAllAddons().firstOrNull().orEmpty()
            .filter { it.isEnabled }.mapTo(mutableSetOf()) { it.transportUrl.trimEnd('/') }
        val configs = dao.getAllCatalogConfigs().firstOrNull().orEmpty()
            .filter { it.showInHome && it.transportUrl.trimEnd('/') in activeAddons }
            .sortedWith(compareBy({ it.homeOrder }, { it.uniqueId }))
        return configs.groupBy { config ->
            if (config.catalogType in setOf("movie", "series")) {
                homeCategoryTitle(config).lowercase().filter { it.isLetterOrDigit() }
            } else {
                // Non-movie catalog types (channels, live TV) remain individually
                // configured Home rows rather than silently disappearing.
                "standalone:" + config.uniqueId
            }
        }.map { (key, group) ->
            HomeCatalogPair(
                id = "catalog-home:$key",
                title = homeCategoryTitle(group.first()),
                sources = group,
                order = group.minOf { it.homeOrder }
            )
        }.sortedWith(compareBy({ it.order }, { it.title }))
    }

    suspend fun getHomePairedRows(): List<HomeRow> = withContext(Dispatchers.IO) {
        homeCatalogPairs().map { group ->
            val config = group.sources.first()
            HomeRow(
                configId = group.id,
                title = group.title,
                items = emptyList(),
                order = group.order,
                // Aggregated rows are genuinely paginated, so do not use the
                // old synthetic infinite-loop grid's Int.MAX_VALUE indices.
                isInfiniteLoopEnabled = false,
                visibleItemCount = config.visibleItemCount,
                isInfiniteScrollingEnabled = true,
                supportsSkip = true
            )
        }
    }

    /** Map a legacy single-type Screen Editor Hero source to its mixed Home pair. */
    suspend fun getHomePairRowForConfig(configId: String): HomeRow? =
        withContext(Dispatchers.IO) {
            val group = homeCatalogPairs().firstOrNull { pair ->
                pair.sources.any { it.uniqueId == configId }
            } ?: return@withContext null
            getHomePairedRows().firstOrNull { it.configId == group.id }
        }

    suspend fun homePairFirstLayerPageCount(rowId: String, sourcesPerPage: Int = 4): Int =
        withContext(Dispatchers.IO) {
            val count = homeCatalogPairs().firstOrNull { it.id == rowId }?.sources?.size ?: 0
            (count + sourcesPerPage.coerceIn(1, 8) - 1) / sourcesPerPage.coerceIn(1, 8)
        }

    suspend fun getHomePairCatalogPage(
        rowId: String, page: Int, sourcesPerPage: Int = 4
    ): List<MetaItem> = withContext(Dispatchers.IO) {
        val group = homeCatalogPairs().firstOrNull { it.id == rowId }
            ?: return@withContext emptyList()
        val size = sourcesPerPage.coerceIn(1, 8)
        val batchCount = (group.sources.size + size - 1) / size
        if (batchCount == 0) return@withContext emptyList()
        val safePage = page.coerceAtLeast(0)
        val layer = safePage / batchCount
        val offset = (safePage % batchCount) * size
        kotlinx.coroutines.coroutineScope {
            val fetched = group.sources.drop(offset).take(size).map { config ->
                async {
                    val addon = dao.getAddon(config.transportUrl)
                        ?: return@async emptyList<MetaItem>()
                    val supportsSkip = catalogSupportsSkip(
                        addon, config.catalogType, config.catalogId
                    )
                    if (layer > 0 && !supportsSkip) return@async emptyList<MetaItem>()
                    val extras = runCatching {
                        gson.fromJson(addon.catalogsJson, Array<CatalogManifest>::class.java)
                            ?.firstOrNull {
                                it.type == config.catalogType && it.id == config.catalogId
                            }?.extra.orEmpty()
                    }.getOrDefault(emptyList())
                    val requiresSkip = extras.any { it.name == "skip" && it.isRequired }
                    // A catalog that demands something other than a genre (search text) has
                    // no meaningful request without it; one that demands a genre is asked
                    // for its first option rather than failing every time.
                    if (extras.any { it.isRequired && it.name !in setOf("genre", "skip") }) {
                        return@async emptyList<MetaItem>()
                    }
                    val requiredGenre = extras.firstOrNull { it.name == "genre" && it.isRequired }
                        ?.let { genreExtra ->
                            genreExtra.options.orEmpty().firstOrNull { it.isNotBlank() }
                                ?: return@async emptyList<MetaItem>()
                        }
                    val extraParts = buildList {
                        if (requiredGenre != null) {
                            add("genre=" + java.net.URLEncoder.encode(requiredGenre, "UTF-8")
                                .replace("+", "%20"))
                        }
                        if (layer > 0 || requiresSkip) add("skip=${layer * 100}")
                    }
                    val url = buildString {
                        append(config.transportUrl.trimEnd('/'))
                        append("/catalog/")
                        append(config.catalogType)
                        append("/")
                        append(config.catalogId)
                        if (extraParts.isNotEmpty()) append("/").append(extraParts.joinToString("&"))
                        append(".json")
                    }
                    try {
                        withTimeout(5_000L) {
                            api.getCatalog(url).metas.orEmpty().sanitize()
                        }.filter { it.type == config.catalogType }
                            .map { it.copy(addonBaseUrl = config.transportUrl) }
                    } catch (_: TimeoutCancellationException) {
                        emptyList()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
            val movies = fetched.filter { it.type == "movie" }
            val series = fetched.filter { it.type == "series" }
            val standalone = fetched.filterNot { it.type in setOf("movie", "series") }
            distinctAggregatedMetas(emptyList(), buildList {
                for (index in 0 until maxOf(movies.size, series.size)) {
                    movies.getOrNull(index)?.let { add(it) }
                    series.getOrNull(index)?.let { add(it) }
                }
                addAll(standalone)
            })
        }
    }

    suspend fun getDashboardRows(
        screen: String,
        skipConfigs: Int = 0,
        maxConfigs: Int = Int.MAX_VALUE,
        catalogTimeoutMs: Long = CATALOG_TIMEOUT_MS
    ): List<HomeRow> = withContext(Dispatchers.IO) {
        val addons = dao.getAllAddons().firstOrNull().orEmpty().filter { it.isEnabled }
        val configs = dao.getAllCatalogConfigs().firstOrNull().orEmpty()
        val addonMap = addons.associateBy { it.transportUrl }
        val ordered = configs.filter { config ->
            addonMap.containsKey(config.transportUrl) && when (screen) {
                "home" -> config.showInHome
                "movies" -> config.showInMovies
                "series" -> config.showInSeries
                else -> false
            }
        }.sortedBy { config ->
            when (screen) {
                "home" -> config.homeOrder
                "movies" -> config.moviesOrder
                "series" -> config.seriesOrder
                else -> 0
            }
        }

        // A category may call its halves "Popular Movies" and "Popular Series".
        // Pairing requires the SAME provider and opposite media types.
        fun categoryKey(value: String): String = value.trim().lowercase()
            .replace(Regex("""[-_: ]+(?:tv[ -]?shows?|tv[ -]?series|movies?|series|shows?|tv)$"""), "")
            .replace(Regex("""[^a-z0-9]+"""), "")
        fun matches(a: CatalogConfigEntity, b: CatalogConfigEntity): Boolean {
            if (a.transportUrl != b.transportUrl ||
                setOf(a.catalogType, b.catalogType) != setOf("movie", "series")) return false
            val aid = categoryKey(a.catalogId)
            val bid = categoryKey(b.catalogId)
            val aname = categoryKey(a.catalogName ?: a.catalogId)
            val bname = categoryKey(b.catalogName ?: b.catalogId)
            return (aid.isNotEmpty() && aid == bid) ||
                (aname.isNotEmpty() && aname == bname)
        }
        val groups = mutableListOf<MutableList<CatalogConfigEntity>>()
        for (config in ordered) {
            val paired = if (screen == "home") groups.firstOrNull {
                it.size == 1 && matches(it.first(), config)
            } else null
            if (paired == null) groups.add(mutableListOf(config)) else paired.add(config)
        }
        val chosen = groups.drop(skipConfigs.coerceAtLeast(0)).let {
            if (maxConfigs == Int.MAX_VALUE) it else it.take(maxConfigs.coerceAtLeast(0))
        }
        kotlinx.coroutines.coroutineScope {
            chosen.map { group -> async {
                val sorted = group.sortedBy { if (it.catalogType == "movie") 0 else 1 }
                val primary = sorted.first()
                val secondary = sorted.getOrNull(1)
                val addon = addonMap[primary.transportUrl] ?: return@async null

                suspend fun retrieve(config: CatalogConfigEntity): Pair<String, List<MetaItem>> {
                    val url = "${config.transportUrl.trimEnd('/')}/catalog/${config.catalogType}/${config.catalogId}.json"
                    val items = try {
                        withTimeout(catalogTimeoutMs) {
                            api.getCatalog(url).metas.orEmpty().sanitize()
                        }
                    } catch (_: TimeoutCancellationException) {
                        // A single slow addon is an empty catalog, not cancellation
                        // of every other Home category loaded in this coroutine scope.
                        emptyList()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                    return url to items.filter { it.type == config.catalogType }
                        .map { it.copy(addonBaseUrl = config.transportUrl) }
                }

                val primaryJob = async { retrieve(primary) }
                val secondaryJob = secondary?.let { async { retrieve(it) } }
                val (primaryUrl, moviesOrSeries) = primaryJob.await()
                val (secondaryUrl, secondType) = secondaryJob?.await() ?: ("" to emptyList<MetaItem>())
                if (moviesOrSeries.isEmpty() && secondType.isEmpty()) return@async null
                val mixed = buildList {
                    for (index in 0 until maxOf(moviesOrSeries.size, secondType.size)) {
                        moviesOrSeries.getOrNull(index)?.let(::add)
                        secondType.getOrNull(index)?.let(::add)
                    }
                }
                val categoryTitle = (primary.customTitle ?: secondary?.customTitle
                    ?: primary.catalogName ?: primary.catalogId)
                    .replace(Regex("""[-_: ]+(?:TV[ -]?Shows?|TV[ -]?Series|Movies?|Series|Shows?|TV)$""",
                        RegexOption.IGNORE_CASE), "")
                HomeRow(
                    configId = primary.uniqueId,
                    title = categoryTitle,
                    items = distinctAggregatedMetas(emptyList(), mixed),
                    catalogUrl = primaryUrl,
                    isInfiniteLoopEnabled = primary.isInfiniteLoopEnabled,
                    visibleItemCount = primary.visibleItemCount,
                    isInfiniteScrollingEnabled = primary.isInfiniteScrollingEnabled,
                    order = when (screen) {
                        "home" -> group.minOf { it.homeOrder }
                        "movies" -> primary.moviesOrder
                        "series" -> primary.seriesOrder
                        else -> 999
                    },
                    supportsSkip = catalogSupportsSkip(addon, primary.catalogType, primary.catalogId) ||
                        (secondary?.let {
                            catalogSupportsSkip(addon, it.catalogType, it.catalogId)
                        } ?: false),
                    secondCatalogUrl = secondaryUrl.takeIf { secondary != null },
                    secondSupportsSkip = secondary?.let {
                        catalogSupportsSkip(addon, it.catalogType, it.catalogId)
                    } ?: false,
                    fetchedPrimaryCount = moviesOrSeries.size,
                    fetchedSecondCount = secondType.size,
                    primarySupportsSkip = catalogSupportsSkip(addon, primary.catalogType, primary.catalogId)
                )
            } }.awaitAll().filterNotNull()
        }
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

    /**
     * Asks every enabled stream addon in parallel.
     *
     * [timeoutMs] caps each addon; null waits as long as the request runs, as Stremio does
     * (an addon resolving torrents through a debrid service can take well over 20s).
     * [onProgress], when given, receives the streams of every addon answered so far, in addon
     * order, each time one more answers — so a list can fill in instead of waiting on the
     * slowest addon.
     */
    suspend fun getStreams(
        type: String,
        id: String,
        preferredAddonBaseUrl: String? = null,
        preferredAddonRequestId: String? = null,
        timeoutMs: Long? = STREAM_TIMEOUT_MS,
        onProgress: (suspend (List<Stream>) -> Unit)? = null
    ): List<Stream> = withContext(Dispatchers.IO) {
        val addons = dao.getAllAddons().firstOrNull()
            ?.filter { it.isEnabled && it.supportsStream }
            ?: emptyList()
        val preferredBase = preferredAddonBaseUrl?.trimEnd('/')
        // Per addon, in addon order: outcome (null problem + count while answered; "loading" until then)
        // and the streams it returned. Guarded by progressLock.
        val outcomes = arrayOfNulls<AddonStreamOutcome>(addons.size)
        val results = arrayOfNulls<List<Stream>>(addons.size)
        val progressLock = kotlinx.coroutines.sync.Mutex()
        addons.forEachIndexed { i, addon -> outcomes[i] = AddonStreamOutcome(addon.nickname ?: addon.name, 0, "loading") }
        _lastStreamOutcomes.value = outcomes.filterNotNull()

        val jobs = addons.mapIndexed { index, addon ->
            async {
                val addonBase = addon.transportUrl.trimEnd('/')
                val isPreferred = preferredBase != null && addonBase == preferredBase
                val requestId = if (isPreferred && !preferredAddonRequestId.isNullOrBlank()) {
                    preferredAddonRequestId
                } else {
                    id
                }

                // Every enabled stream addon is asked. Gating on the manifest's top-level
                // idPrefixes silently dropped whole addons: Stremio lets the stream resource
                // declare its own prefixes, and stored manifests are never refreshed. An addon
                // that can't serve this id answers empty, which costs one request.

                val sourceLabel = addon.nickname ?: addon.name
                var outcome = AddonStreamOutcome(sourceLabel, 0, null)
                val streams: List<Stream> = try {
                    val url = "$addonBase/stream/$type/$requestId.json"
                    // Parsed per stream: one malformed entry used to fail the whole
                    // response, silently dropping every stream that addon returned.
                    val json = if (timeoutMs != null) withTimeout(timeoutMs) { api.getJson(url) } else api.getJson(url)
                    val elements = json.takeIf { it.isJsonObject }
                        ?.asJsonObject?.get("streams")
                        ?.takeIf { it.isJsonArray }?.asJsonArray
                        ?.toList().orEmpty()
                    var skipped = 0
                    val parsed = elements.mapNotNull { element ->
                        runCatching { gson.fromJson(element, Stream::class.java) }
                            .getOrNull() ?: run { skipped++; null }
                    }
                    if (skipped > 0) {
                        AppErrors.w(TAG, "Streams from ${addon.name}: skipped $skipped malformed of ${elements.size}",
                            IllegalStateException("malformed stream entries"))
                    }
                    outcome = AddonStreamOutcome(
                        sourceLabel, parsed.size,
                        if (skipped > 0) "$skipped unreadable" else null
                    )
                    parsed.map { stream ->
                        val tagged = stream.copy(
                            addonTransportUrl = addon.transportUrl,
                            addonDisplayName = sourceLabel,
                            addonRequestType = type,
                            addonRequestId = requestId
                        )
                        // Without a per-stream id every key fell back to the addon URL, so
                        // excluding one source excluded the whole addon.
                        tagged.copy(sourceSelectionId = playerSourceId(tagged))
                    }
                } catch (timeout: TimeoutCancellationException) {
                    AppErrors.w(TAG, "Streams from ${addon.name} timed out after ${timeoutMs}ms", timeout)
                    outcome = AddonStreamOutcome(sourceLabel, 0, "timed out")
                    emptyList()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: retrofit2.HttpException) {
                    // 404 is an addon saying it has no streams for this id (or no stream
                    // resource at all: rows stored before supportsStream was read default
                    // to true). Not an error; anything else still reports.
                    if (e.code() != 404) {
                        AppErrors.w(TAG, "Streams from ${addon.name} failed for $type/$requestId", e)
                    }
                    outcome = AddonStreamOutcome(sourceLabel, 0, if (e.code() == 404) null else "HTTP ${e.code()}")
                    emptyList()
                } catch (e: Exception) {
                    AppErrors.w(TAG, "Streams from ${addon.name} failed for $type/$requestId", e)
                    outcome = AddonStreamOutcome(sourceLabel, 0, "failed")
                    emptyList()
                }
                progressLock.withLock {
                    outcomes[index] = outcome
                    results[index] = streams
                    _lastStreamOutcomes.value = outcomes.filterNotNull()
                    onProgress?.invoke(results.filterNotNull().flatten())
                }
                streams
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
        val supportsMeta = manifest.supportsResource("meta")
        val supportsStream = manifest.supportsResource("stream")

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
            supportsStream = supportsStream &&
                transportUrl != "https://stremio-trailer-addon.vercel.app",
            typesJson = gson.toJson(manifest.types.orEmpty()),
            idPrefixesJson = gson.toJson(manifest.idPrefixes.orEmpty()),
            sortOrder = existingAddon?.sortOrder ?: 999
        )
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
        // Addon row + catalog configs are written atomically, and configs for catalogs the
        // manifest no longer lists are pruned so they don't linger as dead rows.
        dao.installAddonAtomically(entity, newConfigs)
    }

    suspend fun installAddon(url: String) = installAddonWithConfig(url, true, true, true)

    suspend fun renameAddon(transportUrl: String, newName: String) = withContext(Dispatchers.IO) {
        val target = dao.getAddon(transportUrl)
        if (target != null) dao.insertAddon(target.copy(nickname = newName))
    }

    /** A disabled addon stays installed but contributes no catalogs, metadata or streams. */
    suspend fun setAddonEnabled(transportUrl: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        dao.setAddonEnabled(transportUrl, enabled)
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
    /**
     * Rebuild the episode index independently of the winning catalog metadata.
     * Many catalog/meta addons return title art but no `videos`, while the UI
     * and playback controller require the complete episode list.
     *
     * For non-IMDb ids, only use an exact title/year Cinemeta search match.
     * A failed lookup must never invent episodes or change the show's identity.
     */
    suspend fun recoverSeriesEpisodes(
        seriesId: String,
        title: String? = null,
        releaseInfo: String? = null
    ): List<com.hereliesaz.illumera.data.model.stremio.MetaVideo> = withContext(Dispatchers.IO) {
        val preferred = if (seriesId.matches(Regex("^tt[0-9]+$"))) seriesId else null
        val imdbId = preferred ?: title?.takeIf { it.isNotBlank() }?.let { target ->
            val nameKey = target.lowercase().filter { it.isLetterOrDigit() }
            val year = Regex("(?:19|20)[0-9]{2}").find(releaseInfo.orEmpty())?.value
            try {
                withTimeout(5_000L) {
                    searchMovies(target).firstOrNull { candidate ->
                        candidate.type == "series" &&
                            candidate.id.matches(Regex("^tt[0-9]+$")) &&
                            candidate.name.lowercase().filter { it.isLetterOrDigit() } == nameKey &&
                            (year == null || year == Regex("(?:19|20)[0-9]{2}")
                                .find(candidate.releaseInfo.orEmpty())?.value)
                    }?.id
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        } ?: return@withContext emptyList()

        try {
            withTimeout(8_000L) {
                api.getMeta("https://v3-cinemeta.strem.io/meta/series/$imdbId.json")
                    .meta.sanitize()?.videos.orEmpty()
                    .filter { it.season > 0 && it.episode > 0 }
                    .distinctBy { it.season to it.episode }
                    .sortedWith(compareBy({ it.season }, { it.episode }))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun resolvePreferredMetaDetails(
        type: String,
        id: String,
        preferredAddonBaseUrl: String
    ): MetaItem? = withContext(Dispatchers.IO) {
        val baseUrl = preferredAddonBaseUrl.trim().trimEnd('/')
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
            if (addon.transportUrl.trimEnd('/') == preferredAddonBaseUrl?.trim()?.trimEnd('/')) continue
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
