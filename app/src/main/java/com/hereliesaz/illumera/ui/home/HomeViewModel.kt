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
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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

internal fun headKeys(items: List<MetaItem>, count: Int = 20): List<String> =
    items.take(count).map { "${it.type}:${it.id}" }

/**
 * Brings a row up to date from just the first page of its catalog: the titles in [head] that the
 * row does not have (nor [elsewhere]) go in front of the [current] ones, and nothing is removed.
 * Null when the head matches what the row starts with, so nothing further needs loading.
 */
internal fun mergeCatalogHead(
    current: List<MetaItem>,
    elsewhere: List<MetaItem>,
    head: List<MetaItem>,
    maxItems: Int = 200,
    maxNew: Int = 20
): List<MetaItem>? {
    if (headKeys(head) == headKeys(current)) return null
    val fresh = distinctAggregatedMetas(current + elsewhere, head).take(maxNew)
    if (fresh.isEmpty()) return null
    return (fresh + current).take(maxItems)
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
    private val youtubeExtractor: YouTubeExtractor,
    private val similarTitlesEngine: com.hereliesaz.illumera.data.recommend.SimilarTitlesEngine,
    private val tasteStore: com.hereliesaz.illumera.data.taste.TasteStore
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
    /**
     * Where the viewer was on one main screen. Home, Movies and Series each keep their own: a
     * screen being left writes its final position as it is disposed, which happens after the
     * screen replacing it has started, so a shared memory would hand Home's place to Movies.
     */
    private class PositionMemory {
        var lastFocusedKey: String? = null
        val rowScrollPositions = mutableMapOf<String, Pair<Int, Int>>()
        var verticalScrollPosition: Pair<Int, Int> = Pair(0, 0)
        var hadHistoryWhenSaved: Boolean = false
        var isRestoring: Boolean = false

        fun clear() {
            lastFocusedKey = null
            rowScrollPositions.clear()
            verticalScrollPosition = Pair(0, 0)
            hadHistoryWhenSaved = false
            isRestoring = false
        }
    }

    /**
     * What a main screen showed when the viewer left it. Coming back within [SNAPSHOT_TTL_MS]
     * shows it at once instead of refetching every row from empty; rows that were still empty
     * are finished in the background.
     */
    private class ScreenSnapshot(
        val profileId: Int,
        val savedAtMs: Long,
        val signature: String,
        val state: HomeState
    )

    /** The enabled-addon signature the screen in front was loaded under. */
    private var activeSignature: String = ""

    private val screenSnapshots = mutableMapOf<String, ScreenSnapshot>()
    private val screenCache = com.hereliesaz.illumera.data.cache.ScreenCacheStore(context)
    private var preloadJob: kotlinx.coroutines.Job? = null

    private val positionMemories = mutableMapOf<String, PositionMemory>()
    private fun positions(screen: String) = positionMemories.getOrPut(screen) { PositionMemory() }
    private var activeScreen: String? = null

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
        val tmdbEnrichedIds: Set<String> = emptySet(),
        val dislikedIds: Set<String> = emptySet(), // "Don't like" verdicts; hidden everywhere
        // Home only: "More like …" / "Because you watched …" (0–2 rows), shown above catalogs
        val personalRows: List<CategoryRow> = emptyList()
    )

    private val _state = MutableStateFlow(HomeState())

    init {
        // Don't like hides a title on every discovery screen at once.
        viewModelScope.launch {
            tasteStore.entries.collect { entries ->
                val disliked = entries.values
                    .filter { it.taste == com.hereliesaz.illumera.data.taste.Taste.DISLIKE }
                    .mapTo(mutableSetOf()) { it.id }
                _state.update { it.copy(dislikedIds = disliked) }
            }
        }
    }

    val state: StateFlow<HomeState> = _state

    init {
        // Building the language/country tables takes a moment on a TV box; do it off the
        // first screen open instead of during it.
        viewModelScope.launch(Dispatchers.Default) { ScreenGenreRows.warmUp() }
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
                positionMemories.values.forEach { it.clear() }
                screenSnapshots.clear()
                clearDemand()
                _state.value = HomeState(isLoading = false)
            }
        }
    }

    fun getRowScrollPositions(screen: String): Map<String, Pair<Int, Int>> =
        positions(screen).rowScrollPositions

    /**
     * A main screen chosen fresh in the menu starts at the top: forget the saved focus and
     * scroll. The rows stay loaded. Back to a screen (from a page above it) keeps them.
     */
    fun forgetPosition(screen: String) {
        positions(screen).clear()
        _state.update {
            it.copy(lastFocusedKey = null, rowScrollPositions = emptyMap(), verticalScrollPosition = Pair(0, 0))
        }
    }

    fun getVerticalScrollPosition(screen: String): Pair<Int, Int> = positions(screen).verticalScrollPosition

    fun setLastFocusedKey(key: String?, screen: String) {
        val memory = positions(screen)
        if (memory.lastFocusedKey == key) return
        memory.lastFocusedKey = key
        // Only the screen in front shows this key; one being left must not overwrite it.
        if (screen == activeScreen) _state.value = _state.value.copy(lastFocusedKey = key)
    }

    fun setRowScrollPosition(rowKey: String, position: Pair<Int, Int>, screen: String) {
        val saved = positions(screen).rowScrollPositions
        if (saved[rowKey] == position) return
        saved[rowKey] = position
    }

    fun setVerticalScrollPosition(position: Pair<Int, Int>, hasHistory: Boolean, screen: String) {
        val memory = positions(screen)
        if (memory.verticalScrollPosition == position && memory.hadHistoryWhenSaved == hasHistory) return
        memory.verticalScrollPosition = position
        memory.hadHistoryWhenSaved = hasHistory
    }

    fun needsHistoryScrollAdjustment(hasHistory: Boolean, screen: String): Boolean {
        val memory = positions(screen)
        return hasHistory && !memory.hadHistoryWhenSaved && memory.isRestoring
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
        private const val SNAPSHOT_TTL_MS = 10 * 60_000L
        // Demand-driven loading: titles shown per step, how far past the focused card to keep
        // loaded, rows looked ahead, and bounds on concurrent/repeated/per-call work.
        private const val GRID_REVEAL_STEP = 30
        private const val INITIAL_VISIBLE_ROWS = 3
        private const val LOOKAHEAD_ROWS = 3
        private const val MAX_CONCURRENT_ROW_LOADS = 3
        private const val CLASSIFY_BUDGET = 36
        private const val TRIM_INTERVAL_MS = 5_000L
        private const val DISK_CACHE_MAX_AGE_MS = 24 * 60 * 60_000L
        private const val REVALIDATE_MIN_AGE_MS = 60_000L
        private const val REVALIDATE_ROWS = 10
        private const val PRELOAD_ROWS = 6
    }
    private val pendingRowItems = mutableMapOf<String, MutableList<MetaItem>>()
    private val allFetchedRowIds = mutableMapOf<String, MutableSet<String>>()
    // ── Demand-driven loading ──────────────────────────────────────────────────────────────
    // What gets loaded follows what the viewer can see and where they are going: five titles for
    // each row on screen first, then five for the rows just below, then the next five on screen;
    // a row the viewer moves along keeps being extended. Everything fetched beyond what is shown
    // waits in a backlog, and what is far from the viewer is dropped.
    private val revealTargets = mutableMapOf<String, Int>()
    private val rowBacklogs = mutableMapOf<String, MutableList<MetaItem>>()
    private val candidateBacklogs = mutableMapOf<String, java.util.ArrayDeque<MetaItem>>()
    private val demandAttempts = mutableMapOf<String, Int>()
    private val rowLoadJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private var visibleRowIds: List<String> = emptyList()
    private var lookaheadRowIds: List<String> = emptyList()
    private var focusedRowId: String? = null
    private var lastTrimMs = 0L

    private fun clearDemand() {
        rowLoadJobs.values.forEach { it.cancel() }
        rowLoadJobs.clear()
        revealTargets.clear()
        rowBacklogs.clear()
        candidateBacklogs.clear()
        demandAttempts.clear()
        visibleRowIds = emptyList()
        lookaheadRowIds = emptyList()
        focusedRowId = null
    }

    private fun revealTarget(configId: String) =
        revealTargets[configId] ?: RowDemandPlanner.REVEAL_STEP

    private fun shownCount(configId: String) =
        _state.value.rows.firstOrNull { it.configId == configId }?.items?.size ?: 0

    private fun raiseTarget(configId: String, target: Int) {
        if (target > (revealTargets[configId] ?: 0)) {
            revealTargets[configId] = target
            demandAttempts.remove(configId)
        }
    }

    /** Shows waiting titles of the row up to its target. */
    private fun revealFromBacklog(configId: String) {
        val backlog = rowBacklogs[configId] ?: return
        if (backlog.isEmpty()) return
        val room = revealTarget(configId) - shownCount(configId)
        if (room <= 0) return
        val batch = backlog.take(room)
        repeat(batch.size) { backlog.removeAt(0) }
        appendItemsToRow(configId, batch, bypassWindow = true)
    }

    private fun heroSourceRowId(): String? =
        _state.value.heroRow?.configId
            ?: _state.value.rows.firstOrNull { it.items.isNotEmpty() }?.configId

    private fun factsFor(configId: String): RowFacts? {
        val row = _state.value.rows.firstOrNull { it.configId == configId } ?: return null
        return RowFacts(
            shown = row.items.size,
            waiting = rowBacklogs[configId]?.size ?: 0,
            exhausted = configId in exhaustedCatalogRows,
            loading = configId in loadingMoreRows,
            attempts = demandAttempts[configId] ?: 0
        )
    }

    private fun currentDemands(): List<Pair<String, Int>> = RowDemandPlanner.demands(
        focusedRowId = focusedRowId,
        focusedTarget = focusedRowId?.let { revealTarget(it) } ?: 0,
        visible = visibleRowIds,
        heroRowId = heroSourceRowId(),
        lookahead = lookaheadRowIds
    )

    /** Gives up a running load; whatever it had not recorded is fetched again when it is next asked for. */
    private fun cancelRowLoad(configId: String) {
        rowLoadJobs.remove(configId)?.cancel()
        loadingMoreRows.remove(configId)
        demandAttempts[configId] = ((demandAttempts[configId] ?: 1) - 1).coerceAtLeast(0)
    }

    /**
     * Starts as much of the wanted loading as there are free slots for. When every slot is busy,
     * a load for something less urgent than what is waiting is given up so the urgent row (the
     * one the viewer is on) is not stuck behind a slow one.
     */
    private fun schedule() {
        var guard = 0
        while (guard++ < 50) {
            val demands = currentDemands()
            val index = RowDemandPlanner.nextIndex(demands, ::factsFor) ?: break
            if (loadingMoreRows.size >= MAX_CONCURRENT_ROW_LOADS) {
                val victim = RowDemandPlanner.victim(demands, index, loadingMoreRows.toList()) ?: break
                cancelRowLoad(victim)
                continue
            }
            val (configId, target) = demands[index]
            raiseTarget(configId, target)
            revealFromBacklog(configId)
            if (shownCount(configId) < target && configId !in exhaustedCatalogRows &&
                configId !in loadingMoreRows
            ) {
                demandAttempts[configId] = (demandAttempts[configId] ?: 0) + 1
                loadRow(configId)
            }
        }
    }

    /** Which rows are on screen, and which are the next few below. Reported by the screen. */
    fun onRowsVisible(visible: List<String>, lookahead: List<String>) {
        if (visible == visibleRowIds && lookahead == lookaheadRowIds) return
        visibleRowIds = visible
        lookaheadRowIds = lookahead
        schedule()
        trimCaches()
    }

    /** The viewer is on card [itemIndex] of a row: keep a little ahead of them loaded. */
    fun onRowFocused(configId: String, itemIndex: Int) {
        focusedRowId = configId
        raiseTarget(configId, RowDemandPlanner.focusTarget(itemIndex))
        revealFromBacklog(configId)
        schedule()
        trimCaches()
    }

    /** A row came into view and may need its first titles. */
    fun requestRowItems(@Suppress("UNUSED_PARAMETER") configId: String) = schedule()

    private fun seedDemand(rows: List<HomeRow>) {
        visibleRowIds = rows.take(INITIAL_VISIBLE_ROWS).map { it.configId }
        lookaheadRowIds = rows.drop(INITIAL_VISIBLE_ROWS).take(LOOKAHEAD_ROWS).map { it.configId }
        schedule()
    }

    /**
     * Drops what was fetched ahead for rows the viewer is not near, and enrichment for titles
     * they are not near. It is fetched again when their movements ask for it.
     */
    private fun trimCaches() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastTrimMs < TRIM_INTERVAL_MS) return
        lastTrimMs = now
        val state = _state.value
        val near = (visibleRowIds + lookaheadRowIds + listOfNotNull(focusedRowId)).toSet()
        state.rows.forEach { row ->
            if (row.configId in near) return@forEach
            val dropped = (rowBacklogs.remove(row.configId) != null) or
                (candidateBacklogs.remove(row.configId) != null)
            if (dropped) {
                // The next demand starts the row's catalog over; what it shows stays.
                rowFetchOffsets.remove(row.configId)
                seenAggregateCandidates.remove(row.configId)
                exhaustedCatalogRows.remove(row.configId)
                emptyCatalogPageStreak.remove(row.configId)
                demandAttempts.remove(row.configId)
            }
        }
        val keep = buildSet {
            state.rows.filter { it.configId in near }.forEach { row ->
                row.items.forEach { add("${it.type}:${it.id}") }
            }
            state.heroRow?.items?.forEach { add("${it.type}:${it.id}") }
        }
        val stale = (state.enrichedMeta.keys + state.tmdbEnrichedIds).filterNot { it in keep }.toSet()
        if (stale.size > 40) {
            _state.update {
                it.copy(
                    enrichedMeta = it.enrichedMeta - stale,
                    tmdbEnrichedIds = it.tmdbEnrichedIds - stale
                )
            }
        }
    }

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

    /** The "view more" grid wants a longer list: show a bigger window and fetch on. */
    fun loadMoreItems(configId: String) {
        raiseTarget(configId, shownCount(configId) + GRID_REVEAL_STEP)
        revealFromBacklog(configId)
        loadRow(configId)
    }

    private fun loadRow(configId: String) {
        if (configId.startsWith("catalog-aggregate:") || configId.startsWith("catalog-home:")) {
            if (configId in exhaustedCatalogRows) return
            val isHomePair = configId.startsWith("catalog-home:")
            val generation = screenDataGeneration
            val initiatingProfileId = _state.value.loadedProfileId
            val initiatingScreen = _state.value.loadedScreen
            if (!loadingMoreRows.add(configId)) return
            // Started only once it is registered, so it can be given up by name.
            val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
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
                    // Titles fetched earlier but not yet classified come before another fetch.
                    if (genre != null && ScreenGenreRows.isStandardGenre(genre) &&
                        candidateBacklogs[configId]?.isNotEmpty() == true
                    ) {
                        unique = unique + (routeGenreCandidates(
                            configId, generation, initiatingProfileId, initiatingScreen
                        ) ?: return@launch)
                    }
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
                                // Titles wait for classification and are routed a few at a time
                                // (see routeGenreCandidates), only as far as this row needs.
                                candidateBacklogs.getOrPut(configId) { java.util.ArrayDeque() }
                                    .addAll(unseen)
                                val landed = routeGenreCandidates(
                                    configId, generation, initiatingProfileId, initiatingScreen
                                ) ?: return@launch
                                unique = unique + landed
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
                    if (generation == screenDataGeneration) {
                        // A load that was given up is no longer the one registered.
                        if (rowLoadJobs[configId] === coroutineContext[kotlinx.coroutines.Job]) {
                            rowLoadJobs.remove(configId)
                            loadingMoreRows.remove(configId)
                        }
                        schedule()
                    }
                }
            }
            rowLoadJobs[configId] = job
            job.start()
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
            appendItemsToRow(configId, batch, bypassWindow = true)
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
                    appendItemsToRow(configId, batch, bypassWindow = true)
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

    /**
     * Classifies the titles waiting for [configId] into their primary-genre rows, six at a time,
     * and stops once the row has the titles it was asked for (or after a bounded number), leaving
     * the rest waiting. Returns the titles that landed in [configId]'s own row, or null when the
     * screen changed while it worked.
     */
    private suspend fun routeGenreCandidates(
        configId: String,
        generation: Long,
        profileId: Int?,
        screen: String?
    ): List<MetaItem>? {
        val queue = candidateBacklogs[configId] ?: return emptyList()
        val landed = mutableListOf<MetaItem>()
        var budget = CLASSIFY_BUDGET
        while (queue.isNotEmpty() && budget > 0 && shownCount(configId) < revealTarget(configId)) {
            // Taken off the queue only once routed, so a load that is given up loses nothing.
            val group = queue.take(6)
            budget -= group.size
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
                _state.value.loadedProfileId != profileId ||
                _state.value.loadedScreen != screen ||
                profileConfigurationManager.activeProfileId.value != profileId
            ) return null
            repeat(group.size) { queue.removeFirst() }
            val byDestination = classified.groupBy { (primary, _, _) ->
                _state.value.rows.firstOrNull { row ->
                    row.title.equals(primary, ignoreCase = true)
                }?.configId
            }
            byDestination.forEach { (destination, entries) ->
                if (destination != null) {
                    val authoritative = entries.filter { it.third }.map { it.second }
                    val fallback = entries.filterNot { it.third }.map { it.second }
                    // A new canonical match removes stale fallback copies assigned elsewhere.
                    if (authoritative.isNotEmpty()) {
                        appendItemsToRow(destination, authoritative, authoritative = true)
                    }
                    if (fallback.isNotEmpty()) appendItemsToRow(destination, fallback)
                    if (destination == configId) landed += entries.map { it.second }
                }
            }
        }
        return landed
    }

    private fun appendItemsToRow(
        configId: String,
        newItems: List<MetaItem>,
        authoritative: Boolean = false,
        shareable: Boolean = false,
        bypassWindow: Boolean = false
    ) {
        // A row shows only as many titles as it has been asked for; the rest wait in a backlog
        // until the viewer's movements call for them.
        var shown = newItems
        if (!bypassWindow) {
            val room = (revealTarget(configId) - shownCount(configId)).coerceAtLeast(0)
            if (shown.size > room) {
                rowBacklogs.getOrPut(configId) { mutableListOf() }.addAll(shown.drop(room))
                shown = shown.take(room)
            }
        }
        if (shown.isEmpty()) return
        _state.update { current ->
            val updatedRows = reconcileGenreRows(
                current.rows, configId, shown, authoritative, shareable
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
        screenSnapshots.clear()
        clearDemand()
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
        activeScreen = screenName
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
            positions(screenName).isRestoring = true
            return
        }

        // Keep what the screen being left showed, and reuse what this one showed last time.
        val leaving = _state.value
        val leavingScreen = leaving.loadedScreen
        if (leavingScreen != null && leavingScreen != screenName &&
            leaving.loadedProfileId == currentProfileId &&
            (leaving.rows.isNotEmpty() || leaving.hubRows.isNotEmpty())
        ) {
            screenSnapshots[leavingScreen] =
                ScreenSnapshot(currentProfileId, System.currentTimeMillis(), activeSignature, leaving)
            persistRows(currentProfileId, leavingScreen, activeSignature, leaving.rows)
        }
        val memorySnapshot = screenSnapshots[screenName]?.takeIf {
            it.profileId == currentProfileId &&
                System.currentTimeMillis() - it.savedAtMs < SNAPSHOT_TTL_MS
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
            clearDemand()
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
                personalRows = if (screenName == "home") _state.value.personalRows else emptyList(),
                tmdbEnabled = currentProfile.tmdbEnabled
            )
            tmdbEnrichmentInFlight.clear()
            positions(screenName).clear()

            // This screen as it was: in memory, else from the cache on disk (titles only; the
            // rows themselves are rebuilt from the current settings so disabled genres stay gone).
            // Neither is used if the enabled addons have changed since.
            val signature = try {
                repository.enabledAddonSignature()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ""
            }
            activeSignature = signature
            val validSnapshot = memorySnapshot?.takeIf { it.signature == signature }
            val restored: HomeState? = validSnapshot?.state ?: try {
                val cached = withContext(Dispatchers.IO) {
                    screenCache.load(currentProfileId, screenName, DISK_CACHE_MAX_AGE_MS, signature)
                }
                cached?.let {
                    stateFrom(screenName, currentProfile, buildShell(screenName, currentProfileId), it)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }

            // Load History + Series Next Up only for Home
            if (screenName == "home") {
                launch {
                    dao.getWatchHistory().collect { history ->
                        _state.update { it.copy(history = history) }
                    }
                }
                // Rebuilt only when the most recently watched title changes, not on every
                // progress tick.
                launch {
                    val language = currentProfile.tmdbLanguage.ifBlank { null }
                        ?: java.util.Locale.getDefault().toLanguageTag()
                    dao.getWatchHistory()
                        .distinctUntilChangedBy { history ->
                            history.take(12).map { it.seriesId ?: it.id.substringBefore(':') }
                        }
                        .collectLatest { history ->
                            val rows = try {
                                similarTitlesEngine.personalRows(
                                    history, _state.value.watchedIds, language
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                emptyList()
                            }
                            _state.update { it.copy(personalRows = rows) }
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
                    if (restored == null && screenName == "home") repository.getHomePairedRows()
                    else emptyList<HomeRow>()
                }
                val hubRowsDeferred = async {
                    if (restored != null) emptyList()
                    else if (screenName == "home") repository.getHubRows(screenName)
                    else listOfNotNull(streamingHubRow(screenName))
                }

                val configuredRows = initialRowsDeferred.await()
                // Movies and Series are entirely genre-first browsing surfaces:
                // every enabled Stremio genre gets its own independently paginated
                // row; the old unfiltered All Movies / All Series row is removed.
                val genreRows = if (restored == null && screenName in setOf("movies", "series")) {
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
                val initialRows = when {
                    restored != null -> restored.rows
                    screenName == "home" -> configuredRows
                    else -> genreRows
                }
                val hubRows = restored?.hubRows ?: hubRowsDeferred.await()
                if (profileConfigurationManager.activeProfileId.value != currentProfileId) return@launch

                // Fetch HERO row separately (even if hidden in dashboard).
                val tabEnum = DashboardTab.fromString(screenName)
                val heroConfig = currentProfile.heroFor(tabEnum)
                val automaticAggregateId = when (screenName) {
                    "movies" -> "catalog-aggregate:movie:all"
                    "series" -> "catalog-aggregate:series:all"
                    else -> null
                }
                val heroRow = when {
                    restored != null -> restored.heroRow
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

                val initialMixedList = restored?.mixedRows
                    ?: (hubRows + initialRows.map { CategoryRow.fromHomeRow(it) }).sortedBy { it.order }

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
                        enrichedMeta = restored?.enrichedMeta ?: it.enrichedMeta,
                        tmdbEnrichedIds = restored?.tmdbEnrichedIds ?: it.tmdbEnrichedIds,
                        tmdbEnabled = currentProfile.tmdbEnabled
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
                                configuredPair.copy(items = items.take(heroConfig.posterCount))
                            } else repository.getCategoryRowPreview(
                                configId = selectedHeroId,
                                maxItems = heroConfig.posterCount,
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

                // Present the scrollable screen first. What loads next follows what is on screen
                // (reported by the screen) and where the viewer goes; until it reports, the first
                // rows are assumed visible.
                seedDemand(initialRows)

                if (restored != null &&
                    (validSnapshot == null ||
                        System.currentTimeMillis() - validSnapshot.savedAtMs > REVALIDATE_MIN_AGE_MS)
                ) {
                    val generation = screenDataGeneration
                    launch { revalidateHeads(screenName, generation) }
                }
                // Keep what was loaded for the next visit.
                val persistGeneration = screenDataGeneration
                launch {
                    kotlinx.coroutines.delay(10_000L)
                    if (persistGeneration == screenDataGeneration) {
                        _state.value.takeIf { it.loadedScreen == screenName }
                            ?.let { persistRows(currentProfileId, screenName, signature, it.rows) }
                    }
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
    /**
     * Keys (see [com.hereliesaz.illumera.domain.StreamingOriginals.key]) of the titles a streaming
     * service produced itself, from TMDB. Null when TMDB is off for the profile, the service has
     * no TMDB network/company mapped, or nothing came back; the grid then omits "Originals".
     */
    suspend fun streamingOriginalKeys(configId: String): Set<String>? {
        if (tmdbProfileCache?.tmdbEnabled != true) return null
        val parts = configId.split(':', limit = 3)
        if (parts.size != 3 || parts[0] != "streaming") return null
        val isSeries = parts[1] == "series"
        val (kind, entityId) = if (isSeries) {
            com.hereliesaz.illumera.domain.StreamingOriginals.tvNetworks[parts[2]]?.let { "network" to it }
        } else {
            com.hereliesaz.illumera.domain.StreamingOriginals.movieCompanies[parts[2]]?.let { "company" to it }
        } ?: return null
        return try {
            kotlinx.coroutines.coroutineScope {
                (1..10).map { page ->
                    async {
                        tmdbMetadataService.fetchDiscover(
                            entityId = entityId, kind = kind,
                            mediaType = if (isSeries) "tv" else "movie",
                            sortBy = "popularity.desc", page = page
                        ).first
                    }
                }.awaitAll()
            }.flatten().map {
                com.hereliesaz.illumera.domain.StreamingOriginals.key(it.name, it.releaseInfo)
            }.toSet().takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private class ScreenShell(val rows: List<HomeRow>, val hubRows: List<HubGroupRow>)

    /** A screen's rows without titles: from settings and the database only, no network. */
    private suspend fun buildShell(screenName: String, profileId: Int): ScreenShell {
        if (screenName == "home") {
            return ScreenShell(repository.getHomePairedRows(), repository.getHubRows("home"))
        }
        val type = if (screenName == "movies") "movie" else "series"
        val enabled = ScreenGenreRows.enabledGenres(
            context, profileId, screenName, repository.availableScreenGenres(type)
        )
        val rows = enabled.mapIndexed { index, genre ->
            HomeRow(
                configId = "catalog-aggregate:$type:$genre",
                title = genre,
                items = emptyList(),
                isInfiniteScrollingEnabled = true,
                supportsSkip = true,
                order = -1000 + index
            )
        }
        return ScreenShell(rows, listOfNotNull(streamingHubRow(screenName)))
    }

    private fun stateFrom(
        screenName: String,
        profile: com.hereliesaz.illumera.data.model.ProfileEntity?,
        shell: ScreenShell,
        titles: Map<String, List<MetaItem>>
    ): HomeState {
        val rows = shell.rows.map { row ->
            titles[row.configId]?.takeIf { it.isNotEmpty() }?.let { row.copy(items = it) } ?: row
        }
        val mixed = (shell.hubRows + rows.map { CategoryRow.fromHomeRow(it) }).sortedBy { it.order }
        return HomeState(
            mixedRows = mixed,
            rows = rows,
            hubRows = shell.hubRows,
            isLoading = false,
            loadedScreen = screenName,
            loadedProfileId = profile?.id,
            tmdbEnabled = profile?.tmdbEnabled == true
        )
    }

    private fun persistRows(profileId: Int, screenName: String, signature: String, rows: List<HomeRow>) {
        val titles = rows.filter { it.items.isNotEmpty() }.associate { it.configId to it.items }
        if (titles.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { screenCache.save(profileId, screenName, signature, titles) }
    }

    /** Just the first page of a row's catalog, enough to tell whether the row has changed. */
    private suspend fun fetchRowHead(row: HomeRow): List<MetaItem> = try {
        when {
            row.configId.startsWith("catalog-home:") ->
                repository.getHomePairCatalogPage(row.configId, 0)
            row.configId.startsWith("catalog-aggregate:") -> {
                val parts = row.configId.split(':', limit = 3)
                if (parts.size != 3) emptyList() else {
                    val genre = parts[2].takeUnless { it == "all" }
                    repository.getAggregatedCatalogPage(parts[1], genre, 0).filter { meta ->
                        genre == null || meta.genres.orEmpty().any {
                            ScreenGenreRows.matchesRequestedGenre(it, genre)
                        }
                    }
                }
            }
            else -> emptyList()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }

    /** Brings the rows of the screen in front up to date from each row's first page only. */
    private suspend fun revalidateHeads(screenName: String, generation: Long) {
        val rows = _state.value.rows.take(REVALIDATE_ROWS)
        val gate = kotlinx.coroutines.sync.Semaphore(3)
        kotlinx.coroutines.coroutineScope {
            rows.map { row ->
                async {
                    gate.withPermit {
                        if (generation != screenDataGeneration) return@withPermit
                        val head = fetchRowHead(row)
                        if (head.isNotEmpty()) mergeRowHead(screenName, generation, row.configId, head)
                    }
                }
            }.awaitAll()
        }
    }

    private fun mergeRowHead(screenName: String, generation: Long, configId: String, head: List<MetaItem>) {
        _state.update { current ->
            if (current.loadedScreen != screenName || generation != screenDataGeneration) return@update current
            val row = current.rows.firstOrNull { it.configId == configId } ?: return@update current
            // The row the viewer is on keeps its cards where they are.
            val focusedRowId = current.lastFocusedKey?.substringBefore('_')?.toIntOrNull()
                ?.let { current.mixedRows.getOrNull(it)?.id }
            if (focusedRowId == configId) return@update current
            val elsewhere = if (configId.startsWith("catalog-aggregate:"))
                current.rows.filter { it.configId != configId }.flatMap { it.items }
            else emptyList()
            val merged = mergeCatalogHead(row.items, elsewhere, head) ?: return@update current
            val rows = current.rows.map { if (it.configId == configId) it.copy(items = merged) else it }
            current.copy(
                rows = rows,
                mixedRows = reconcileGenreMixedRows(current.mixedRows, rows),
                heroRow = current.heroRow?.let { hero -> rows.firstOrNull { it.configId == hero.configId } ?: hero }
            )
        }
    }

    /**
     * Prepares the other main screens while the one in front is in use, so choosing them opens at
     * once: from the cache on disk, else the first page of their first rows.
     */
    fun preloadScreens(profile: com.hereliesaz.illumera.data.model.ProfileEntity?) {
        val profileId = profile?.id ?: return
        preloadJob?.cancel()
        preloadJob = viewModelScope.launch {
            // Let the screen in front finish its own first stage first.
            kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                _state.first { !it.isLoading && it.loadedScreen != null }
            } ?: return@launch
            kotlinx.coroutines.delay(1_500L)
            for (screen in listOf("home", "movies", "series")) {
                if (screen == activeScreen) continue
                if (profileConfigurationManager.activeProfileId.value != profileId) return@launch
                val existing = screenSnapshots[screen]
                if (existing != null && existing.profileId == profileId &&
                    System.currentTimeMillis() - existing.savedAtMs < SNAPSHOT_TTL_MS
                ) continue
                try {
                    preloadScreen(screen, profile, profileId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A screen that cannot be prepared just loads normally when chosen.
                }
            }
        }
    }

    private suspend fun preloadScreen(
        screenName: String,
        profile: com.hereliesaz.illumera.data.model.ProfileEntity,
        profileId: Int
    ) {
        val shell = buildShell(screenName, profileId)
        if (shell.rows.isEmpty() && shell.hubRows.isEmpty()) return
        val signature = repository.enabledAddonSignature()
        val titles = (withContext(Dispatchers.IO) {
            screenCache.load(profileId, screenName, DISK_CACHE_MAX_AGE_MS, signature)
        } ?: emptyMap()).toMutableMap()
        val missing = shell.rows.filter { titles[it.configId].isNullOrEmpty() }.take(PRELOAD_ROWS)
        if (missing.isNotEmpty()) {
            val gate = kotlinx.coroutines.sync.Semaphore(3)
            val heads = kotlinx.coroutines.coroutineScope {
                missing.map { row ->
                    async { gate.withPermit { row.configId to fetchRowHead(row).take(60) } }
                }.awaitAll()
            }
            heads.forEach { (configId, head) -> if (head.isNotEmpty()) titles[configId] = head }
        }
        if (profileConfigurationManager.activeProfileId.value != profileId) return
        screenSnapshots[screenName] = ScreenSnapshot(
            profileId, System.currentTimeMillis(), signature, stateFrom(screenName, profile, shell, titles)
        )
        withContext(Dispatchers.IO) { screenCache.save(profileId, screenName, signature, titles) }
    }

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
