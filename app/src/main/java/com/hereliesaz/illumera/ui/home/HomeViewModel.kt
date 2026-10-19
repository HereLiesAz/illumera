package com.hereliesaz.illumera.ui.home

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.WatchHistoryEntity
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.repository.AddonRepository
import com.hereliesaz.illumera.data.repository.distinctAggregatedMetas
import com.hereliesaz.illumera.data.remote.StremioApiService
import com.hereliesaz.illumera.data.trailer.YouTubeExtractor
import com.hereliesaz.illumera.data.tmdb.TmdbMetadataService
import com.hereliesaz.illumera.data.tmdb.TmdbService
import com.hereliesaz.illumera.domain.HomeRow
import com.hereliesaz.illumera.domain.HubGroupRow
import com.hereliesaz.illumera.ui.utils.ImagePrefetcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

import com.hereliesaz.illumera.domain.HomeRowItem
import com.hereliesaz.illumera.domain.CategoryRow
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.domain.DashboardTab
import com.hereliesaz.illumera.domain.heroFor
import com.hereliesaz.illumera.domain.HERO_DISABLED_MARKER
import com.hereliesaz.illumera.domain.ScreenGenreRows

/**
 * Keep usable YouTube trailer references ahead of addon search/results links.
 * Limited to two candidates so a dead addon does not starve TMDB/Cinemeta.
 */
internal fun prioritizedYouTubePreviewCandidates(values: Iterable<String>): List<String> {
    val videoId = Regex("^[A-Za-z0-9_-]{11}$")
    return values.filter { value ->
        videoId.matches(value) ||
            listOf("watch?v=", "youtu.be/", "/embed/", "/shorts/", "/live/")
                .any { marker -> marker in value }
    }.sortedByDescending(videoId::matches).take(2)
}

/**
 * Reconcile a catalog batch without changing other genre identities.
 * Canonical matches move out of former fallback rows; fallback batches never
 * re-add a title already assigned elsewhere.
 */
internal fun reconcileGenreRows(
    rows: List<HomeRow>,
    destination: String,
    newItems: List<MetaItem>,
    authoritative: Boolean,
    shareable: Boolean = false
): List<HomeRow> {
    if (newItems.isEmpty()) return rows
    return rows.map { row ->
        val items = when {
            row.configId == destination -> {
                // A canonical move must be allowed into its destination even
                // when the same title still lives in an old fallback row.
                // Fallback batches, conversely, must not duplicate an item
                // already assigned to another row.
                val existing = if (authoritative || shareable) row.items else
                    row.items + rows.filter { it.configId != destination }.flatMap { it.items }
                val additions = distinctAggregatedMetas(existing, newItems)
                if (additions.isEmpty()) row.items else row.items + additions
            }
            authoritative && row.configId.startsWith("catalog-aggregate:") -> {
                // Compare identities in the *existing -> incoming* direction.
                // A matching title is not novel; only that title leaves this row.
                // Keep untouched row and list instances for stable TV focus.
                val retained = row.items.filter { item ->
                    distinctAggregatedMetas(listOf(item), newItems).isNotEmpty()
                }
                if (retained.size == row.items.size) row.items else retained
            }
            else -> row.items
        }
        if (items === row.items) row else row.copy(items = items)
    }
}

/** Project each row under its own stable LazyColumn key, never the updated destination key. */
internal fun reconcileGenreMixedRows(
    mixedRows: List<HomeRowItem>,
    rows: List<HomeRow>
): List<HomeRowItem> {
    val byId = rows.associateBy { it.configId }
    return mixedRows.map { mixed ->
        if (mixed !is CategoryRow) mixed else {
            val own = byId[mixed.id]
            if (own != null && own.items != mixed.items) CategoryRow.fromHomeRow(own)
            else mixed
        }
    }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: AddonRepository,
    private val dao: AddonDao,
    @ApplicationContext private val context: Context,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val profileConfigurationManager: ProfileConfigurationManager,
    private val trailerApi: StremioApiService,
    private val youtubeExtractor: YouTubeExtractor
) : ViewModel() {

    /**
     * Resolve an autoplay trailer independently of the user's addon selection:
     * configured trailer addon first, TMDB when a key is available, then public
     * Cinemeta trailer metadata. Failed sources never remove the background art.
     */
    private data class CachedTrailer(val url: String, val fetchedAtMs: Long)
    private val trailerUrlCache = java.util.concurrent.ConcurrentHashMap<String, CachedTrailer>()

    suspend fun resolveTrailer(item: MetaItem): String? {
        if (item.id.isBlank() || item.type !in setOf("movie", "series")) return null
        val trailerKey = "${item.type}:${item.id}"
        trailerUrlCache[trailerKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.fetchedAtMs < 5 * 60_000L) return cached.url
            trailerUrlCache.remove(trailerKey)
        }
        return try {
            kotlinx.coroutines.withTimeout(115_000L) {
                val addonCandidates = linkedSetOf<String>()
                val tmdbCandidates = linkedSetOf<String>()
                val cinemetaCandidates = linkedSetOf<String>()
                val imdbId = Regex("^tt[0-9]+$").matchEntire(item.id)?.value
                    ?: kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                        val normalizedTitle = item.name.lowercase().filter { it.isLetterOrDigit() }
                        val year = Regex("(?:19|20)[0-9]{2}")
                            .find(item.releaseInfo.orEmpty())?.value
                            ?: return@withTimeoutOrNull null
                        repository.searchMovies(item.name).firstOrNull { found ->
                            found.type == item.type &&
                                found.name.lowercase().filter { it.isLetterOrDigit() } == normalizedTitle &&
                                Regex("(?:19|20)[0-9]{2}")
                                    .find(found.releaseInfo.orEmpty())?.value == year &&
                                Regex("^tt[0-9]+$").matches(found.id)
                        }?.id
                    }

                if (imdbId != null) {
                    val trailerAddon = dao.getAddon("https://stremio-trailer-addon.vercel.app")
                    if (trailerAddon?.isEnabled == true) {
                        try {
                            val response = kotlinx.coroutines.withTimeoutOrNull(7_000L) {
                                trailerApi.getStreams(
                                    "https://stremio-trailer-addon.vercel.app/stream/${item.type}/$imdbId.json"
                                )
                            }
                            response?.streams.orEmpty().forEach { stream ->
                                stream.ytId?.let { addonCandidates += it }
                                stream.externalUrl?.let { addonCandidates += it }
                                stream.url?.takeIf { it.contains("youtu", ignoreCase = true) }
                                    ?.let { addonCandidates += it }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Log.w("HomeTrailer", "Trailer addon failed for $trailerKey", error)
                        }
                    }
                }

                // TMDB's video index is app-level metadata, not a Stremio addon.
                if (com.hereliesaz.illumera.BuildConfig.TMDB_API_KEY.isNotBlank()) {
                    try {
                        val tmdbId = tmdbService.ensureTmdbId(imdbId ?: item.id, item.type)
                        if (tmdbId != null) {
                            kotlinx.coroutines.withTimeoutOrNull(8_000L) {
                                tmdbMetadataService.fetchBestTrailerKey(tmdbId, item.type)
                            }?.key?.let { tmdbCandidates += it }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w("HomeTrailer", "TMDB trailer lookup failed for $trailerKey", error)
                    }
                }

                if (imdbId != null) {
                    try {
                        val meta = kotlinx.coroutines.withTimeoutOrNull(6_000L) {
                            trailerApi.getJson("https://v3-cinemeta.strem.io/meta/${item.type}/$imdbId.json")
                                .asJsonObject.getAsJsonObject("meta")
                        }
                        for (field in listOf("trailerStreams", "trailers")) {
                            val trailers = meta?.get(field)?.takeIf { it.isJsonArray }?.asJsonArray
                                ?: continue
                            trailers.forEach { entry ->
                                if (!entry.isJsonObject) return@forEach
                                val trailer = entry.asJsonObject
                                trailer.get("ytId")?.takeIf { it.isJsonPrimitive }
                                    ?.asString?.let { cinemetaCandidates += it }
                                trailer.get("id")?.takeIf { it.isJsonPrimitive }
                                    ?.asString?.takeIf { it.length == 11 }
                                    ?.let { cinemetaCandidates += it }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w("HomeTrailer", "Cinemeta trailer lookup failed for $trailerKey", error)
                    }
                }

                // Bound each source separately: unplayable addon entries must
                // not exhaust the resolver before TMDB or Cinemeta is tried.
                // Limit extraction to five candidates, with a total bound of 75s.
                val attempted = mutableSetOf<String>()
                for (sourceCandidates in listOf(
                    prioritizedYouTubePreviewCandidates(addonCandidates),
                    tmdbCandidates.take(1),
                    cinemetaCandidates.take(2)
                )) {
                    for (candidate in sourceCandidates) {
                        if (!attempted.add(candidate)) continue
                        val source = kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                            youtubeExtractor.extractPlaybackSource(
                                candidate, preferCompatiblePreview = true
                            )
                        }
                        if (source != null) {
                            if (trailerUrlCache.size > 128) trailerUrlCache.clear()
                            trailerUrlCache[trailerKey] = CachedTrailer(
                                source.videoUrl, System.currentTimeMillis()
                            )
                            return@withTimeout source.videoUrl
                        }
                    }
                }
                null
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w("HomeTrailer", "Trailer resolution timed out for $trailerKey")
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("HomeTrailer", "Trailer resolution failed for $trailerKey", error)
            null
        }
    }

    private var loadJob: kotlinx.coroutines.Job? = null
    private var observedProfileId: Int? = profileConfigurationManager.activeProfileId.value
    private var lastFocusedKeyMemory: String? = null
    private val rowScrollPositionsMemory = mutableMapOf<String, Pair<Int, Int>>()
    private var verticalScrollPositionMemory: Pair<Int, Int> = Pair(0, 0)
    private var hadHistoryWhenPositionSaved: Boolean = false
    private var isRestoringPosition: Boolean = false

    data class HomeState(
        val mixedRows: List<HomeRowItem> = emptyList(),
        val rows: List<HomeRow> = emptyList(),
        val hubRows: List<HubGroupRow> = emptyList(),
        val history: List<WatchHistoryEntity> = emptyList(),
        val seriesNextUp: List<com.hereliesaz.illumera.data.model.SeriesNextUpEntity> = emptyList(),
        val isLoading: Boolean = true,
        val lastFocusedKey: String? = null,
        val loadedScreen: String? = null,
        // Row scroll positions: rowKey -> Pair(firstVisibleItemIndex, firstVisibleItemScrollOffset)
        val rowScrollPositions: Map<String, Pair<Int, Int>> = emptyMap(),
        // Vertical list scroll position: Pair(firstVisibleItemIndex, firstVisibleItemScrollOffset)
        val verticalScrollPosition: Pair<Int, Int> = Pair(0, 0),
        val heroRow: HomeRow? = null,
        val loadedProfileId: Int? = null,
        val watchedIds: Set<String> = emptySet(), // IMDb IDs of watched items (movies + series)
        val likedIds: Set<String> = emptySet(), // Bookmarked titles for the active profile
        val enrichedMeta: Map<String, MetaItem> = emptyMap(),
        val tmdbEnabled: Boolean = false,
        val tmdbEnrichedIds: Set<String> = emptySet()
    )

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state

    init {
        viewModelScope.launch {
            profileConfigurationManager.activeProfileId.collectLatest { activeProfileId ->
                if (activeProfileId == observedProfileId) return@collectLatest
                observedProfileId = activeProfileId
                loadJob?.cancel()
                loadJob = null
                cancelProfileScopedJobs()
                pendingRowItems.clear()
                allFetchedRowIds.clear()
                rowFetchOffsets.clear()
                exhaustedCatalogRows.clear()
                emptyCatalogPageStreak.clear()
                seenAggregateCandidates.clear()
                pairedRowFetchOffsets.clear()
                loadingMoreRows.clear()
                metadataRequestsInFlight.clear()
                tmdbEnrichmentInFlight.clear()
                tmdbProfileCache = null
                lastFocusedKeyMemory = null
                rowScrollPositionsMemory.clear()
                verticalScrollPositionMemory = Pair(0, 0)
                hadHistoryWhenPositionSaved = false
                isRestoringPosition = false
                _state.value = HomeState(isLoading = false)
            }
        }
    }

    fun getRowScrollPositions(): Map<String, Pair<Int, Int>> = rowScrollPositionsMemory

    /**
     * A main screen chosen fresh in the menu starts at the top: forget the saved focus and
     * scroll. The rows stay loaded. Back to a screen (from a page above it) keeps them.
     */
    fun forgetPosition() {
        lastFocusedKeyMemory = null
        rowScrollPositionsMemory.clear()
        verticalScrollPositionMemory = Pair(0, 0)
        hadHistoryWhenPositionSaved = false
        isRestoringPosition = false
        _state.update {
            it.copy(lastFocusedKey = null, rowScrollPositions = emptyMap(), verticalScrollPosition = Pair(0, 0))
        }
    }

    fun getVerticalScrollPosition(): Pair<Int, Int> = verticalScrollPositionMemory

    fun setLastFocusedKey(key: String?) {
        if (lastFocusedKeyMemory == key) return
        lastFocusedKeyMemory = key
        _state.value = _state.value.copy(lastFocusedKey = key)
    }
    
    fun setRowScrollPosition(rowKey: String, position: Pair<Int, Int>) {
        if (rowScrollPositionsMemory[rowKey] == position) return
        rowScrollPositionsMemory[rowKey] = position
    }
    
    fun setVerticalScrollPosition(position: Pair<Int, Int>, hasHistory: Boolean = hadHistoryWhenPositionSaved) {
        if (verticalScrollPositionMemory == position && hadHistoryWhenPositionSaved == hasHistory) return
        verticalScrollPositionMemory = position
        hadHistoryWhenPositionSaved = hasHistory
    }

    fun needsHistoryScrollAdjustment(hasHistory: Boolean): Boolean {
        return hasHistory && !hadHistoryWhenPositionSaved && isRestoringPosition
    }

    // Track which rows are currently loading more items to prevent duplicate fetches
    private val loadingMoreRows = mutableSetOf<String>()
    // Mutated from Dispatchers.IO and Main; must be thread-safe. Only successful
    // lookups are cached (ConcurrentHashMap cannot hold null values).
    private val metadataFallbackCache = java.util.concurrent.ConcurrentHashMap<String, MetadataFallback>()
    // IO jobs (metadata fallback / TMDB enrichment) that patch profile-scoped state;
    // cancelled on profile switch so they cannot patch the new profile's state.
    private val profileScopedJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<kotlinx.coroutines.Job>()
    private val metadataRequestsInFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val hubInitialLoadCount = 100
    private val initialDashboardBatchSize = 6
    private val initialDashboardTimeoutMs = 2_500L

    // UI batching: per-row pending buffers and dedup tracking
    companion object {
        private const val ROW_BATCH_SIZE = 50
    }
    private val pendingRowItems = mutableMapOf<String, MutableList<MetaItem>>()
    private val allFetchedRowIds = mutableMapOf<String, MutableSet<String>>()
    // Raw number of items fetched per row (including duplicates) — used as the catalog skip
    // so repeated items across pages don't cause the same page to be refetched forever.
    private val rowFetchOffsets = mutableMapOf<String, Int>()
    // Persisted for the current loaded screen so LazyColumn recomposition and
    // short/incomplete addon pages cannot restart exhausted genre loading.
    private val exhaustedCatalogRows = mutableSetOf<String>()
    private val emptyCatalogPageStreak = mutableMapOf<String, Int>()
    // Seen across ALL pages of a source genre, including titles redirected to
    // another primary genre; otherwise repeated pages look newly populated.
    private val seenAggregateCandidates = mutableMapOf<String, MutableList<MetaItem>>()

    private val pairedRowFetchOffsets = mutableMapOf<String, Int>()

    private data class MetadataFallback(
        val poster: String?,
        val background: String?,
        val logo: String?,
        val description: String?,
        val releaseInfo: String?,
        val imdbRating: String?,
        val runtime: String?,
        val genres: List<String>?
    )

    /**
     * Loads the next page of items for a specific catalog row.
     * Called when the user scrolls near the end of the row's current items.
     * Uses UI batching: reveals ROW_BATCH_SIZE items at a time from a pending buffer,
     * only fetching from the API when the buffer is empty.
     */
    private var screenDataGeneration = 0L

    fun loadMoreItems(configId: String) {
        if (configId.startsWith("catalog-aggregate:") || configId.startsWith("catalog-home:")) {
            if (configId in exhaustedCatalogRows) return
            val isHomePair = configId.startsWith("catalog-home:")
            val generation = screenDataGeneration
            val initiatingProfileId = _state.value.loadedProfileId
            val initiatingScreen = _state.value.loadedScreen
            if (!loadingMoreRows.add(configId)) return
            viewModelScope.launch {
                try {
                    val parts = configId.split(':', limit = 3)
                    if (!isHomePair && (parts.size != 3 ||
                        parts[1] !in setOf("movie", "series"))) return@launch
                    val type = if (isHomePair) "home" else parts[1]
                    val genre = if (isHomePair) null else parts[2].takeUnless { it == "all" }
                    var nextPage = rowFetchOffsets.getOrPut(configId) { 0 }
                    val existing = _state.value.rows.firstOrNull { it.configId == configId }
                        ?.items.orEmpty()
                    var unique = emptyList<MetaItem>()
                    val firstLayerCount = when {
                        isHomePair -> repository.homePairFirstLayerPageCount(configId)
                        else -> repository.aggregatedFirstLayerPageCount(type, genre)
                    }
                    if (firstLayerCount == 0) {
                        exhaustedCatalogRows.add(configId)
                        return@launch
                    }
                    var emptyStreak = emptyCatalogPageStreak[configId] ?: 0
                    // Previously a later empty page halted pagination permanently.
                    // Probe a bounded number until there is actual NEW primary-row content.
                    val probes = if (!isHomePair && existing.isEmpty()) {
                        (firstLayerCount * 2 - nextPage).coerceAtLeast(0)
                    } else if (nextPage < firstLayerCount) {
                        firstLayerCount - nextPage
                    } else 4
                    repeat(probes) {
                        if (unique.isEmpty() && configId !in exhaustedCatalogRows) {
                            val fetched = when {
                                isHomePair -> repository.getHomePairCatalogPage(configId, nextPage++)
                                else -> repository.getAggregatedCatalogPage(type, genre, nextPage++)
                            }
                            // Keep the identities of titles routed to other primary
                            // genres, preventing their source pages reappearing forever.
                            val seen = seenAggregateCandidates.getOrPut(configId) { mutableListOf() }
                            val freshCandidates = distinctAggregatedMetas(seen, fetched)
                            seen.addAll(freshCandidates)
                            emptyStreak = if (freshCandidates.isEmpty()) emptyStreak + 1 else 0
                            if (emptyStreak >= firstLayerCount) exhaustedCatalogRows.add(configId)
                            val currentItems = _state.value.rows
                                .firstOrNull { it.configId == configId }?.items.orEmpty()
                            val unseen = distinctAggregatedMetas(currentItems, freshCandidates)
                            if (genre == null) {
                                unique = unseen
                            } else if (!ScreenGenreRows.isStandardGenre(genre)) {
                                // Addon-specific genre (e.g. "Anime", "All Time"): its titles have
                                // no matching Cinemeta primary genre, so show what the addon
                                // returned for it rather than routing them away.
                                if (generation != screenDataGeneration ||
                                    _state.value.loadedProfileId != initiatingProfileId ||
                                    _state.value.loadedScreen != initiatingScreen
                                ) return@launch
                                appendItemsToRow(configId, unseen, shareable = true)
                                unique = unseen
                            } else {
                                // Incremental genre classification: show the FIRST completed
                                // batch instead of hiding a whole row while 100 slow Cinemeta
                                // requests finish. Still classify/route the complete page.
                                for (group in unseen.chunked(6)) {
                                    val classified = kotlinx.coroutines.coroutineScope {
                                        group.map { candidate ->
                                            async {
                                                val canonical = repository.primaryGenreFor(candidate)
                                                val lead = canonical
                                                    ?: candidate.genres.orEmpty()
                                                        .map(ScreenGenreRows::canonicalGenre)
                                                        .sorted()
                                                        .firstOrNull()
                                                val assigned = when (lead) {
                                                    "Action & Adventure" -> "Action"
                                                    "Sci-Fi & Fantasy" -> "Sci-Fi"
                                                    else -> lead
                                                }
                                                Triple(assigned, candidate, canonical != null)
                                            }
                                        }.awaitAll()
                                    }
                                    // Check the owner BEFORE every incremental UI append.
                                    if (generation != screenDataGeneration ||
                                        _state.value.loadedProfileId != initiatingProfileId ||
                                        _state.value.loadedScreen != initiatingScreen ||
                                        profileConfigurationManager.activeProfileId.value != initiatingProfileId
                                    ) return@launch
                                    val byDestination = classified.groupBy { (primary, _, _) ->
                                        _state.value.rows.firstOrNull { row ->
                                            row.title.equals(primary, ignoreCase = true)
                                        }?.configId
                                    }
                                    byDestination.forEach { (destination, entries) ->
                                        if (destination != null) {
                                            val authoritative = entries.filter { it.third }.map { it.second }
                                            val fallback = entries.filterNot { it.third }.map { it.second }
                                            // A new canonical match removes stale fallback
                                            // copies previously assigned to other genres.
                                            if (authoritative.isNotEmpty()) {
                                                appendItemsToRow(destination, authoritative, authoritative = true)
                                            }
                                            if (fallback.isNotEmpty()) appendItemsToRow(destination, fallback)
                                            if (destination == configId) {
                                                unique = unique + entries.map { it.second }
                                            }
                                        }
                                    }
                                }
                                // Titles route to their PRIMARY genre only. A row whose titles all
                                // have another (or a disabled) primary genre would stay empty, so
                                // while it is empty it also takes titles that list its genre.
                                val rowEmpty = _state.value.rows
                                    .firstOrNull { it.configId == configId }?.items.isNullOrEmpty()
                                if (rowEmpty) {
                                    val secondary = unseen.filter { candidate ->
                                        candidate.genres.orEmpty().any {
                                            ScreenGenreRows.matchesRequestedGenre(it, genre)
                                        }
                                    }.take(12)
                                    if (secondary.isNotEmpty()) {
                                        appendItemsToRow(configId, secondary)
                                        unique = unique + secondary
                                    }
                                }
                            }
                        }
                    }
                    // All catalog rows are scoped to their profile and screen.
                    if (generation != screenDataGeneration ||
                        _state.value.loadedProfileId != initiatingProfileId ||
                        _state.value.loadedScreen != initiatingScreen ||
                        profileConfigurationManager.activeProfileId.value != initiatingProfileId
                    ) return@launch
                    rowFetchOffsets[configId] = nextPage
                    emptyCatalogPageStreak[configId] = emptyStreak
                    if (genre == null && unique.isNotEmpty()) {
                        appendItemsToRow(configId, unique)
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (error: Exception) {
                    // A failed catalog source must not crash the Movies/Series
                    // screen or turn a D-pad focus change into an app shutdown.
                    Log.w("HomeGenreRows", "Catalog load failed for $configId", error)
                } finally {
                    if (generation == screenDataGeneration) loadingMoreRows.remove(configId)
                }
            }
            return
        }
        if (configId in loadingMoreRows) return // Already loading
        if (configId in exhaustedCatalogRows) return // Last page came back empty

        // Find the row in the current state
        val currentRows = _state.value.rows
        val row = currentRows.find { it.configId == configId } ?: return
        if (row.catalogUrl.isEmpty() || !row.supportsSkip) return

        // First: reveal items from the pending buffer (no API call needed)
        val pending = pendingRowItems[configId]
        if (pending != null && pending.isNotEmpty()) {
            val batch = pending.take(ROW_BATCH_SIZE)
            pendingRowItems[configId] = pending.drop(ROW_BATCH_SIZE).toMutableList()
            appendItemsToRow(configId, batch)
            return
        }

        // Pending buffer empty — fetch next page from API
        loadingMoreRows.add(configId)
        val generation = screenDataGeneration
        val initiatingProfileId = _state.value.loadedProfileId
        val initiatingScreen = _state.value.loadedScreen

        viewModelScope.launch {
            try {
                // Initialize dedup set from current row items if not yet tracked
                val fetchedIds = allFetchedRowIds.getOrPut(configId) {
                    row.items.map { "${it.type}:${it.id}" }.toMutableSet()
                }

                // Movie and series halves have independent pagination cursors.
                // Using the visible deduplicated card count for either skip
                // causes repeats and drops unseen titles from the other half.
                val firstOffset = rowFetchOffsets.getOrPut(configId) {
                    row.fetchedPrimaryCount
                }
                val secondOffset = pairedRowFetchOffsets.getOrPut(configId) {
                    row.fetchedSecondCount
                }
                // A half that is not requested counts as a successful empty page. A half that
                // timed out or errored is a failure, which says nothing about the row's end.
                val firstResult = if (row.primarySupportsSkip)
                    repository.fetchNextCatalogPageResult(row.catalogUrl, firstOffset)
                else Result.success(emptyList<MetaItem>())
                val secondResult = if (row.secondSupportsSkip && row.secondCatalogUrl != null)
                    repository.fetchNextCatalogPageResult(row.secondCatalogUrl, secondOffset)
                else Result.success(emptyList<MetaItem>())
                val firstItems = firstResult.getOrDefault(emptyList())
                val secondItems = secondResult.getOrDefault(emptyList())
                // Same owner guard as the aggregate branch: a page that arrives after a
                // profile/screen switch must not touch the new screen's rows or cursors.
                if (generation != screenDataGeneration ||
                    _state.value.loadedProfileId != initiatingProfileId ||
                    _state.value.loadedScreen != initiatingScreen ||
                    profileConfigurationManager.activeProfileId.value != initiatingProfileId
                ) return@launch
                if (firstItems.isEmpty() && secondItems.isEmpty()) {
                    // Only a successful empty page on both cursors means nothing is left; a
                    // timeout or error must stay retryable on the next scroll.
                    if (firstResult.isSuccess && secondResult.isSuccess) {
                        exhaustedCatalogRows.add(configId)
                    }
                    return@launch
                }
                rowFetchOffsets[configId] = firstOffset + firstItems.size
                pairedRowFetchOffsets[configId] = secondOffset + secondItems.size

                val candidates = buildList {
                    for (index in 0 until maxOf(firstItems.size, secondItems.size)) {
                        firstItems.getOrNull(index)?.let(::add)
                        secondItems.getOrNull(index)?.let(::add)
                    }
                }
                // IMDb and provider aliases are deduplicated within the merged
                // category while the movie and series type distinction survives.
                val newUniqueItems = distinctAggregatedMetas(row.items, candidates)
                newUniqueItems.forEach { item ->
                    fetchedIds.add("${item.type}:${item.id}")
                }

                if (newUniqueItems.isNotEmpty()) {
                    // Show first batch immediately, buffer the rest
                    val batch = newUniqueItems.take(ROW_BATCH_SIZE)
                    if (newUniqueItems.size > ROW_BATCH_SIZE) {
                        pendingRowItems.getOrPut(configId) { mutableListOf() }
                            .addAll(newUniqueItems.drop(ROW_BATCH_SIZE))
                    }
                    appendItemsToRow(configId, batch)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Silently fail - user can try scrolling again
            } finally {
                if (generation == screenDataGeneration) loadingMoreRows.remove(configId)
            }
        }
    }

    private fun appendItemsToRow(
        configId: String,
        newItems: List<MetaItem>,
        authoritative: Boolean = false,
        shareable: Boolean = false
    ) {
        _state.update { current ->
            val updatedRows = reconcileGenreRows(
                current.rows, configId, newItems, authoritative, shareable
            )
            val updatedMixed = reconcileGenreMixedRows(current.mixedRows, updatedRows)

            val configuredHero = current.loadedScreen?.let { screen ->
                tmdbProfileCache?.heroFor(DashboardTab.fromString(screen))
            }
            val updatedHero = current.heroRow?.let { hero ->
                updatedRows.firstOrNull { it.configId == hero.configId } ?: hero
            }
            val automaticHero = when {
                configuredHero?.categoryId == HERO_DISABLED_MARKER -> null
                current.loadedScreen == "home" && configuredHero?.categoryId == null ->
                    updatedHero?.takeIf { it.items.isNotEmpty() }
                        ?: updatedRows.firstOrNull { it.items.isNotEmpty() }
                updatedHero != null -> updatedHero
                current.loadedScreen in setOf("movies", "series") ->
                    updatedRows.firstOrNull { it.items.isNotEmpty() }
                else -> null
            }
            if (updatedRows == current.rows &&
                updatedMixed == current.mixedRows &&
                automaticHero == current.heroRow
            ) current else current.copy(
                rows = updatedRows,
                mixedRows = updatedMixed,
                heroRow = automaticHero
            )
        }
    }

    /**
     * Invalidates the cached screen data, forcing a reload on next loadScreen() call.
     * Call this after making changes in the Dashboard Editor.
     */
    fun invalidate() {
        pendingRowItems.clear()
        allFetchedRowIds.clear()
        rowFetchOffsets.clear()
        exhaustedCatalogRows.clear()
        emptyCatalogPageStreak.clear()
        seenAggregateCandidates.clear()
        pairedRowFetchOffsets.clear()
        _state.value = _state.value.copy(
            loadedScreen = null,
            loadedProfileId = null
        )
    }
    
    /**
     * Prefetch first visible images from loaded rows.
     * Called after data loads to ensure images are in cache before scroll starts.
     */
    private fun prefetchFirstVisibleImages(rows: List<HomeRow>) {
        // Prefetch first 10 images from first 3 rows (most likely to be visible on screen)
        val imagesToPrefetch = rows
            .take(3)
            .flatMap { row -> row.items.take(10) }
            .mapNotNull { it.poster }
        
        imagesToPrefetch.forEach { url ->
            ImagePrefetcher.prefetch(context, url)
        }
    }

    /**
     * Prefetch a small set of likely-visible metadata so cinematic/hero surfaces render smoothly.
     * Keep this intentionally tiny to avoid unnecessary metadata requests.
     */
    private fun prefetchLikelyVisibleMetadata(rows: List<HomeRow>, heroRow: HomeRow?) {
        val candidates = buildList {
            addAll(heroRow?.items?.take(4) ?: emptyList())
            addAll(rows.getOrNull(0)?.items?.take(3) ?: emptyList())
            addAll(rows.getOrNull(1)?.items?.take(2) ?: emptyList())
        }
            .distinctBy { "${it.type}:${it.id}" }
            .take(6)

        candidates.forEach { ensureMetadataFallback(it) }
    }

    private fun needsMetadataFallback(item: MetaItem): Boolean {
        return item.poster.isNullOrBlank() ||
            item.background.isNullOrBlank() ||
            item.logo.isNullOrBlank() ||
            item.description.isNullOrBlank() ||
            item.releaseInfo.isNullOrBlank() ||
            item.imdbRating.isNullOrBlank() ||
            item.runtime.isNullOrBlank() ||
            item.genres.isNullOrEmpty()
    }

    fun ensureMetadataFallback(item: MetaItem?) {
        if (item == null || !needsMetadataFallback(item)) return
        val ownerProfileId = profileConfigurationManager.activeProfileId.value ?: return
        val key = "$ownerProfileId:${item.type}:${item.id}"

        val cachedFallback = metadataFallbackCache[key]
        if (cachedFallback != null) {
            applyMetadataFallbackToState(type = item.type, id = item.id, fallback = cachedFallback, sourceItem = item)
            return
        }

        if (!metadataRequestsInFlight.add(key)) return

        trackProfileScopedJob(viewModelScope.launch(Dispatchers.IO) {
            try {
                profileConfigurationManager.withActiveProfileRuntime(ownerProfileId) {
                    val meta = repository.resolveMetaDetails(item.type, item.id)
                        ?: throw Exception("No meta found")
                    val fallback = MetadataFallback(
                        poster = meta.poster,
                        background = meta.background,
                        logo = meta.logo,
                        description = meta.description,
                        releaseInfo = meta.releaseInfo,
                        imdbRating = meta.imdbRating,
                        runtime = meta.runtime,
                        genres = meta.genres
                    )
                    metadataFallbackCache[key] = fallback
                    if (profileConfigurationManager.activeProfileId.value == ownerProfileId) {
                        applyMetadataFallbackToState(type = item.type, id = item.id, fallback = fallback, sourceItem = item)
                    }

                    // Persist resolved images to the same profile runtime that supplied
                    // the addon metadata. A profile switch waits on this runtime lock.
                    val historyItems = if (item.type == "series") {
                        dao.getHistoryItemsByPrefix(item.id)
                    } else {
                        listOfNotNull(dao.getHistoryItem(item.id))
                    }
                    for (historyItem in historyItems) {
                        val needsPoster = historyItem.poster.isNullOrBlank() && !fallback.poster.isNullOrBlank()
                        val needsBackground = historyItem.background.isNullOrBlank() && !fallback.background.isNullOrBlank()
                        val needsLogo = historyItem.logo.isNullOrBlank() && !fallback.logo.isNullOrBlank()
                        if (needsPoster || needsBackground || needsLogo) {
                            dao.updateHistoryImages(
                                id = historyItem.id,
                                poster = if (needsPoster) fallback.poster else historyItem.poster,
                                background = if (needsBackground) fallback.background else historyItem.background,
                                logo = if (needsLogo) fallback.logo else historyItem.logo
                            )
                        }
                    }
                    if (!fallback.poster.isNullOrBlank()) {
                        val nextUp = dao.getSeriesNextUp(ownerProfileId, item.id)
                        if (nextUp != null && nextUp.poster.isNullOrBlank()) {
                            dao.upsertSeriesNextUp(nextUp.copy(poster = fallback.poster))
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Don't cache failures: a transient error must not suppress retries forever.
            } finally {
                metadataRequestsInFlight.remove(key)
            }
        })
    }

    private fun trackProfileScopedJob(job: kotlinx.coroutines.Job) {
        profileScopedJobs.add(job)
        job.invokeOnCompletion { profileScopedJobs.remove(job) }
    }

    private fun cancelProfileScopedJobs() {
        profileScopedJobs.forEach { it.cancel() }
        profileScopedJobs.clear()
    }

    private fun applyFallbackToMeta(meta: MetaItem, fallback: MetadataFallback): MetaItem {
        val patchedPoster = if (meta.poster.isNullOrBlank()) fallback.poster else meta.poster
        val patchedBackground = if (meta.background.isNullOrBlank()) fallback.background else meta.background
        val patchedLogo = if (meta.logo.isNullOrBlank()) fallback.logo else meta.logo
        val patchedDescription = if (meta.description.isNullOrBlank()) fallback.description else meta.description
        val patchedReleaseInfo = if (meta.releaseInfo.isNullOrBlank()) fallback.releaseInfo else meta.releaseInfo
        val patchedImdbRating = if (meta.imdbRating.isNullOrBlank()) fallback.imdbRating else meta.imdbRating
        val patchedRuntime = if (meta.runtime.isNullOrBlank()) fallback.runtime else meta.runtime
        val patchedGenres = if (meta.genres.isNullOrEmpty()) fallback.genres else meta.genres

        return meta.copy(
            poster = patchedPoster,
            background = patchedBackground,
            logo = patchedLogo,
            description = patchedDescription,
            releaseInfo = patchedReleaseInfo,
            imdbRating = patchedImdbRating,
            runtime = patchedRuntime,
            genres = patchedGenres
        )
    }

    private fun patchMetaListWithFallback(
        items: List<MetaItem>,
        type: String,
        id: String,
        fallback: MetadataFallback
    ): Pair<List<MetaItem>, Boolean> {
        var changed = false
        val patched = items.map { meta ->
            if (meta.type == type && meta.id == id) {
                val merged = applyFallbackToMeta(meta, fallback)
                if (merged != meta) changed = true
                merged
            } else {
                meta
            }
        }
        return patched to changed
    }

    private fun applyMetadataFallbackToState(type: String, id: String, fallback: MetadataFallback, sourceItem: MetaItem? = null) {
        _state.update { current ->
            var stateChanged = false

            val updatedRows = current.rows.map { row ->
                val (patchedItems, changed) = patchMetaListWithFallback(row.items, type, id, fallback)
                if (changed) {
                    stateChanged = true
                    row.copy(items = patchedItems)
                } else {
                    row
                }
            }

            val updatedMixedRows = current.mixedRows.map { rowItem ->
                if (rowItem is CategoryRow) {
                    val (patchedItems, changed) = patchMetaListWithFallback(rowItem.items, type, id, fallback)
                    if (changed) {
                        stateChanged = true
                        rowItem.copy(items = patchedItems)
                    } else {
                        rowItem
                    }
                } else {
                    rowItem
                }
            }

            val updatedHeroRow = current.heroRow?.let { hero ->
                val (patchedItems, changed) = patchMetaListWithFallback(hero.items, type, id, fallback)
                if (changed) {
                    stateChanged = true
                    hero.copy(items = patchedItems)
                } else {
                    hero
                }
            }

            val enrichedKey = "$type:$id"
            val updatedEnrichedMeta = if (sourceItem != null && !current.enrichedMeta.containsKey(enrichedKey)) {
                val enriched = applyFallbackToMeta(sourceItem, fallback)
                if (enriched != sourceItem) {
                    stateChanged = true
                    current.enrichedMeta + (enrichedKey to enriched)
                } else current.enrichedMeta
            } else current.enrichedMeta

            if (!stateChanged) return@update current

            current.copy(
                rows = updatedRows,
                mixedRows = updatedMixedRows,
                heroRow = updatedHeroRow,
                enrichedMeta = updatedEnrichedMeta
            )
        }
    }

    private val tmdbEnrichmentInFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var tmdbProfileCache: com.hereliesaz.illumera.data.model.ProfileEntity? = null

    /**
     * Enriches a single item with TMDB metadata. Called for every item as it becomes visible,
     * piggybacking on the existing ensureMetadataFallback flow.
     */
    fun ensureTmdbEnrichment(item: MetaItem?) {
        if (item == null) return
        val profile = tmdbProfileCache ?: return
        if (!profile.tmdbEnabled) return

        val key = "tmdb:${item.type}:${item.id}"
        if (!tmdbEnrichmentInFlight.add(key)) return

        val language = profile.tmdbLanguage.ifBlank { java.util.Locale.getDefault().toLanguageTag() } // blank = device language
        val ownerProfileId = profileConfigurationManager.activeProfileId.value

        trackProfileScopedJob(viewModelScope.launch(Dispatchers.IO) {
            try {
                val mediaType = tmdbService.normalizeMediaType(item.type)
                val tmdbId = tmdbService.ensureTmdbId(item.id, mediaType)
                if (tmdbId == null) {
                    // Can't resolve (e.g. Kitsu IDs) — mark as done so UI doesn't stay hidden
                    markTmdbEnriched(item.type, item.id)
                    return@launch
                }
                val enrichment = tmdbMetadataService.fetchEnrichment(tmdbId, mediaType, language)
                if (enrichment == null) {
                    markTmdbEnriched(item.type, item.id)
                    return@launch
                }

                val fallback = MetadataFallback(
                    poster = null, // Keep addon poster
                    background = enrichment.backdrop,
                    logo = enrichment.logo,
                    description = enrichment.description,
                    releaseInfo = enrichment.releaseInfo,
                    imdbRating = enrichment.rating?.let { String.format(java.util.Locale.US, "%.1f", it) },
                    runtime = enrichment.runtimeMinutes?.let { "${it}m" },
                    genres = enrichment.genres.ifEmpty { null }
                )

                if (profileConfigurationManager.activeProfileId.value != ownerProfileId) return@launch
                applyTmdbEnrichmentToState(item.type, item.id, fallback, item)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w("HomeViewModel", "TMDB enrichment failed for ${item.id}: ${e.message}")
                markTmdbEnriched(item.type, item.id)
            } finally {
                tmdbEnrichmentInFlight.remove(key)
            }
        })
    }

    private fun markTmdbEnriched(type: String, id: String) {
        _state.update { it.copy(tmdbEnrichedIds = it.tmdbEnrichedIds + "$type:$id") }
    }

    /**
     * Applies TMDB enrichment to state — overwrites fields (unlike addon fallback which only fills blanks).
     * This ensures localized content from TMDB takes priority.
     */
    private fun applyTmdbEnrichmentToState(type: String, id: String, fallback: MetadataFallback, sourceItem: MetaItem) {
        _state.update { current ->
            var rowsChanged = false

            fun overwriteMeta(meta: MetaItem): MetaItem {
                if (meta.type != type || meta.id != id) return meta
                val updated = meta.copy(
                    background = fallback.background ?: meta.background,
                    logo = fallback.logo ?: meta.logo,
                    description = fallback.description ?: meta.description,
                    releaseInfo = fallback.releaseInfo ?: meta.releaseInfo,
                    imdbRating = fallback.imdbRating ?: meta.imdbRating,
                    runtime = fallback.runtime ?: meta.runtime,
                    genres = fallback.genres ?: meta.genres
                )
                if (updated != meta) rowsChanged = true
                return updated
            }

            val updatedRows = current.rows.map { row ->
                val patched = row.items.map { overwriteMeta(it) }
                if (patched.zip(row.items).any { (n, o) -> n !== o }) row.copy(items = patched) else row
            }

            val updatedMixedRows = current.mixedRows.map { rowItem ->
                if (rowItem is CategoryRow) {
                    val patched = rowItem.items.map { overwriteMeta(it) }
                    if (patched.zip(rowItem.items).any { (n, o) -> n !== o }) rowItem.copy(items = patched) else rowItem
                } else rowItem
            }

            val updatedHeroRow = current.heroRow?.let { hero ->
                val patched = hero.items.map { overwriteMeta(it) }
                if (patched.zip(hero.items).any { (n, o) -> n !== o }) hero.copy(items = patched) else hero
            }

            // Always store enriched preview in enrichedMeta so continue watching
            // items (which aren't in category rows) can pick up TMDB metadata.
            val enrichedKey = "$type:$id"
            val base = current.enrichedMeta[enrichedKey] ?: sourceItem
            val enriched = base.copy(
                background = fallback.background ?: base.background,
                logo = fallback.logo ?: base.logo,
                description = fallback.description ?: base.description,
                releaseInfo = fallback.releaseInfo ?: base.releaseInfo,
                imdbRating = fallback.imdbRating ?: base.imdbRating,
                runtime = fallback.runtime ?: base.runtime,
                genres = fallback.genres ?: base.genres
            )
            val updatedEnrichedMeta = if (enriched != base || !current.enrichedMeta.containsKey(enrichedKey)) {
                current.enrichedMeta + (enrichedKey to enriched)
            } else current.enrichedMeta

            current.copy(
                rows = if (rowsChanged) updatedRows else current.rows,
                mixedRows = if (rowsChanged) updatedMixedRows else current.mixedRows,
                heroRow = if (rowsChanged) updatedHeroRow else current.heroRow,
                enrichedMeta = updatedEnrichedMeta,
                tmdbEnrichedIds = current.tmdbEnrichedIds + "$type:$id"
            )
        }
    }

    fun loadScreen(screenName: String, currentProfile: com.hereliesaz.illumera.data.model.ProfileEntity?) {
        val currentProfileId = currentProfile?.id
        if (
            currentProfileId == null ||
            profileConfigurationManager.activeProfileId.value != currentProfileId
        ) {
            loadJob?.cancel()
            loadJob = null
            _state.value = HomeState(isLoading = false)
            return
        }
        // ... (existing code)
        // Skip reload if this screen is already loaded with data
        if (
            _state.value.loadedScreen == screenName &&
            _state.value.loadedProfileId == currentProfileId &&
            (_state.value.rows.isNotEmpty() || _state.value.hubRows.isNotEmpty())
        ) {
            isRestoringPosition = true
            return
        }

        loadJob?.cancel()
        screenDataGeneration++
        loadingMoreRows.clear()
        loadJob = viewModelScope.launch {
            // Clear 'rows' immediately so the UI doesn't show "Ghost Data" from the previous tab
            pendingRowItems.clear()
            allFetchedRowIds.clear()
            rowFetchOffsets.clear()
            exhaustedCatalogRows.clear()
            emptyCatalogPageStreak.clear()
            seenAggregateCandidates.clear()
            _state.value = _state.value.copy(
                isLoading = true,
                mixedRows = emptyList(),
                rows = emptyList(),
                hubRows = emptyList(),
                lastFocusedKey = null,  // Reset focus when loading new screen
                rowScrollPositions = emptyMap(),  // Reset scroll positions for new screen
                verticalScrollPosition = Pair(0, 0),  // Reset vertical scroll for new screen
                loadedProfileId = null,
                enrichedMeta = emptyMap(),
                tmdbEnrichedIds = emptySet(),
                likedIds = emptySet(),
                tmdbEnabled = currentProfile?.tmdbEnabled == true
            )
            tmdbEnrichmentInFlight.clear()
            lastFocusedKeyMemory = null
            rowScrollPositionsMemory.clear()
            verticalScrollPositionMemory = Pair(0, 0)
            hadHistoryWhenPositionSaved = false
            isRestoringPosition = false

            // Load History + Series Next Up only for Home
            if (screenName == "home") {
                launch {
                    dao.getWatchHistory().collect { history ->
                        _state.update { it.copy(history = history) }
                    }
                }
                launch {
                    dao.getActiveSeriesNextUp(currentProfileId).collect { nextUp ->
                        _state.update { it.copy(seriesNextUp = nextUp) }
                    }
                }
            } else {
                _state.value = _state.value.copy(history = emptyList(), seriesNextUp = emptyList())
            }

            if (screenName in setOf("movies", "series")) {
                launch {
                    dao.getWatchlist(currentProfileId).collect { bookmarks ->
                        _state.update { it.copy(likedIds = bookmarks.map { item -> item.id }.toSet()) }
                    }
                }
            }

            // Load watched IDs for all tabs (watched indicator on posters)
            launch {
                dao.getWatchedIds().collect { ids ->
                    // Extract canonical series ID from episode IDs (tt123:1:3 → tt123)
                    val canonicalIds = ids.map { id ->
                        val parts = id.split(":")
                        if (parts.size >= 3) parts.dropLast(2).joinToString(":") else id
                    }.toSet()
                    _state.update { it.copy(watchedIds = canonicalIds) }
                }
            }

            try {
                // Stage 1: Load only a small first batch so the screen opens quickly.
                val initialRowsDeferred = async {
                    if (screenName == "home") repository.getHomePairedRows()
                    else emptyList<HomeRow>()
                }
                val hubRowsDeferred = async {
                    if (screenName == "home") repository.getHubRows(screenName)
                    else listOfNotNull(streamingHubRow(screenName))
                }

                val configuredRows = initialRowsDeferred.await()
                // Movies and Series are entirely genre-first browsing surfaces:
                // every enabled Stremio genre gets its own independently paginated
                // row; the old unfiltered All Movies / All Series row is removed.
                val genreRows = if (screenName in setOf("movies", "series")) {
                    val type = if (screenName == "movies") "movie" else "series"
                    val available = repository.availableScreenGenres(type)
                    val enabled = ScreenGenreRows.enabledGenres(
                        context, currentProfileId, screenName, available
                    )
                    enabled.mapIndexed { index, genre ->
                        HomeRow(
                            configId = "catalog-aggregate:$type:$genre",
                            title = genre,
                            items = emptyList(),
                            isInfiniteScrollingEnabled = true,
                            supportsSkip = true,
                            order = -1000 + index
                        )
                    }
                } else emptyList()
                // A Home category is now one row per addon/category pair (movie + TV),
                // not a single global feed. Repository groups BEFORE pagination.
                val initialRows = if (screenName == "home") configuredRows else genreRows
                val hubRows = hubRowsDeferred.await()
                if (profileConfigurationManager.activeProfileId.value != currentProfileId) return@launch

                // Fetch HERO row separately (even if hidden in dashboard).
                val tabEnum = DashboardTab.fromString(screenName)
                val heroConfig = currentProfile?.heroFor(tabEnum)
                val automaticAggregateId = when (screenName) {
                    "movies" -> "catalog-aggregate:movie:all"
                    "series" -> "catalog-aggregate:series:all"
                    else -> null
                }
                val heroRow = when {
                    heroConfig?.categoryId == HERO_DISABLED_MARKER -> null
                    // Older editor versions persisted the synthetic aggregate ID.
                    // Treat it as automatic rather than making a failed catalog lookup.
                    automaticAggregateId != null && heroConfig?.categoryId == automaticAggregateId ->
                        initialRows.find { it.configId == automaticAggregateId }
                            ?: initialRows.firstOrNull { it.items.isNotEmpty() }
                    heroConfig?.categoryId != null -> {
                        val configured = initialRows.find { it.configId == heroConfig.categoryId }
                            ?: if (screenName == "home")
                                repository.getHomePairRowForConfig(heroConfig.categoryId)
                            else null
                        // Do not wait for hero network content before showing
                        // the screen and accepting focus. A selected Hero source
                        // is filled after the first composition below.
                        configured
                    }
                    screenName in setOf("movies", "series") ->
                        initialRows.firstOrNull { it.items.isNotEmpty() }
                    else -> initialRows.firstOrNull { it.items.isNotEmpty() }
                }

                // Prefetch first visible images BEFORE updating state.
                prefetchFirstVisibleImages(initialRows)

                val initialMixedList = (hubRows + initialRows.map { CategoryRow.fromHomeRow(it) })
                    .sortedBy { it.order }

                // Cache profile for TMDB enrichment (called per-item as they become visible)
                tmdbProfileCache = currentProfile

                _state.update {
                    it.copy(
                        mixedRows = initialMixedList,
                        rows = initialRows,
                        hubRows = hubRows,
                        heroRow = heroRow,
                        isLoading = false,
                        loadedScreen = screenName,
                        loadedProfileId = currentProfileId,
                        tmdbEnabled = currentProfile?.tmdbEnabled == true
                    )
                }

                // Resolve an explicitly chosen Hero after the rest of the
                // screen is interactive. An addon/network timeout must not
                // delay the browse surface or first DPAD event.
                val selectedHeroId = heroConfig?.categoryId
                if (selectedHeroId != null && selectedHeroId != HERO_DISABLED_MARKER &&
                    heroRow?.items?.isNotEmpty() != true
                ) {
                    val generation = screenDataGeneration
                    launch {
                        val loadedHero = try {
                            val configuredPair = heroRow?.takeIf {
                                screenName == "home" && it.configId.startsWith("catalog-home:")
                            }
                            if (configuredPair != null) {
                                val pages = repository.homePairFirstLayerPageCount(configuredPair.configId)
                                var items = emptyList<MetaItem>()
                                for (page in 0 until pages) {
                                    if (items.isEmpty()) {
                                        items = repository.getHomePairCatalogPage(configuredPair.configId, page)
                                    }
                                }
                                configuredPair.copy(items = items.take(heroConfig?.posterCount ?: 10))
                            } else repository.getCategoryRowPreview(
                                configId = selectedHeroId,
                                maxItems = heroConfig?.posterCount ?: 10,
                                timeoutMs = initialDashboardTimeoutMs
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                        if (loadedHero != null &&
                            generation == screenDataGeneration &&
                            profileConfigurationManager.activeProfileId.value == currentProfileId
                        ) {
                            _state.update { current ->
                                if (current.loadedScreen == screenName &&
                                    current.loadedProfileId == currentProfileId &&
                                    current.heroRow?.items?.isNotEmpty() != true
                                ) current.copy(heroRow = loadedHero) else current
                            }
                        }
                    }
                }

                // Start a tiny metadata warmup pass for items likely to render first.
                prefetchLikelyVisibleMetadata(rows = initialRows, heroRow = heroRow)

                // Present the scrollable screen first. Then fill the earliest
                // focusable genre rows before probing later rows. A shared queue
                // avoids a slow provider holding its entire fixed-index lane
                // hostage while other workers have already finished.
                if (screenName in setOf("movies", "series")) {
                    val generation = screenDataGeneration
                    val queuedRows = java.util.ArrayDeque(initialRows)
                    repeat(minOf(3, initialRows.size)) {
                        launch {
                            while (queuedRows.isNotEmpty() && generation == screenDataGeneration) {
                                val row = queuedRows.removeFirst()
                                loadMoreItems(row.configId)
                                while (row.configId in loadingMoreRows &&
                                    generation == screenDataGeneration
                                ) kotlinx.coroutines.delay(80)
                            }
                        }
                    }
                } else {
                    // Render the page before beginning network discovery. Only
                    // its first two Home rails need a warm cache immediately.
                    initialRows.take(2).forEach { loadMoreItems(it.configId) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * Opens a hub item, fetching the category content if it's not already loaded.
     */
    /** The "Streaming" row on top of Movies and Series: one card per streaming service. */
    private suspend fun streamingHubRow(screenName: String): HubGroupRow? {
        val type = when (screenName) {
            "movies" -> "movie"
            "series" -> "series"
            else -> return null
        }
        val services = repository.streamingServices(type)
        if (services.isEmpty()) return null
        return HubGroupRow(
            id = "streaming_$type",
            title = "Streaming",
            order = -2000,
            items = services.map {
                com.hereliesaz.illumera.domain.HubItem(
                    id = "streaming_$type:${it.catalogId}",
                    title = it.name,
                    categoryId = "streaming:$type:${it.catalogId}"
                )
            }
        )
    }

    fun openHub(
        hubItem: com.hereliesaz.illumera.domain.HubItem,
        onResult: (String, List<MetaItem>) -> Unit
    ) {
        if (hubItem.categoryId.startsWith("streaming:")) {
            val parts = hubItem.categoryId.split(':', limit = 3)
            if (parts.size == 3) {
                viewModelScope.launch {
                    val titles = try {
                        repository.getStreamingCatalog(parts[1], parts[2])
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                    onResult(hubItem.title, titles)
                }
            }
            return
        }
        // 1. Try to find in currently loaded rows (Legacy)
        val legacyRow = _state.value.rows.find { it.configId == hubItem.categoryId }
        if (legacyRow != null) {
            onResult(legacyRow.title, legacyRow.items)
            return
        }

        // 2. Try to find in mixed rows (New Architecture)
        val mixedRow = _state.value.mixedRows.find { it.id == hubItem.categoryId } as? CategoryRow
        if (mixedRow != null) {
            onResult(mixedRow.title, mixedRow.items)
            return
        }

        // 3. If not found, fetch from repository
        viewModelScope.launch {
            try {
                val fetchedRow = repository.getCategoryRowPreview(
                    configId = hubItem.categoryId,
                    maxItems = hubInitialLoadCount
                )
                if (fetchedRow != null) {
                    // The preview has already fetched its first page. Persist
                    // actual per-source counts so View More never retries skip=0.
                    val primaryType = if ("/catalog/series/" in fetchedRow.catalogUrl) "series"
                        else "movie"
                    val normalized = fetchedRow.copy(
                        fetchedPrimaryCount = maxOf(
                            fetchedRow.fetchedPrimaryCount,
                            if (fetchedRow.secondCatalogUrl != null)
                                fetchedRow.items.count { it.type == primaryType }
                            else fetchedRow.items.size
                        ),
                        fetchedSecondCount = maxOf(
                            fetchedRow.fetchedSecondCount,
                            if (fetchedRow.secondCatalogUrl != null)
                                fetchedRow.items.count { it.type != primaryType }
                            else 0
                        )
                    )
                    _state.update { current ->
                        val updatedRows = current.rows
                            .filterNot { it.configId == normalized.configId } + normalized
                        current.copy(rows = updatedRows)
                    }
                    onResult(normalized.title, normalized.items)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Ignore error
            }
        }
    }
}
