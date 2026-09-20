package com.hereliesaz.illumera.ui.home

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.WatchHistoryEntity
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.repository.AddonRepository
import com.hereliesaz.illumera.data.tmdb.TmdbMetadataService
import com.hereliesaz.illumera.data.tmdb.TmdbService
import com.hereliesaz.illumera.domain.HomeRow
import com.hereliesaz.illumera.domain.HubGroupRow
import com.hereliesaz.illumera.ui.utils.ImagePrefetcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
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

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: AddonRepository,
    private val dao: AddonDao,
    @ApplicationContext private val context: Context,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val profileConfigurationManager: ProfileConfigurationManager
) : ViewModel() {

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
                pendingRowItems.clear()
                allFetchedRowIds.clear()
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
    private val metadataFallbackCache = mutableMapOf<String, MetadataFallback?>()
    private val metadataRequestsInFlight = mutableSetOf<String>()
    private val hubInitialLoadCount = 100
    private val initialDashboardBatchSize = 6
    private val initialDashboardTimeoutMs = 2_500L

    // UI batching: per-row pending buffers and dedup tracking
    companion object {
        private const val ROW_BATCH_SIZE = 50
    }
    private val pendingRowItems = mutableMapOf<String, MutableList<MetaItem>>()
    private val allFetchedRowIds = mutableMapOf<String, MutableSet<String>>()

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
    fun loadMoreItems(configId: String) {
        if (configId in loadingMoreRows) return // Already loading

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

        viewModelScope.launch {
            try {
                // Initialize dedup set from current row items if not yet tracked
                val fetchedIds = allFetchedRowIds.getOrPut(configId) {
                    row.items.map { "${it.type}:${it.id}" }.toMutableSet()
                }

                // Skip = total fetched count for this row
                val nextSkip = fetchedIds.size
                val newItems = repository.fetchNextCatalogPage(row.catalogUrl, nextSkip)

                // Deduplicate against all previously fetched items
                val newUniqueItems = newItems.filter { item ->
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
            } catch (_: Exception) {
                // Silently fail - user can try scrolling again
            } finally {
                loadingMoreRows.remove(configId)
            }
        }
    }

    private fun appendItemsToRow(configId: String, newItems: List<MetaItem>) {
        _state.update { current ->
            val updatedRows = current.rows.map { r ->
                if (r.configId == configId) r.copy(items = r.items + newItems)
                else r
            }

            val updatedMixed = current.mixedRows.map { item ->
                if (item is CategoryRow && item.id == configId) {
                    val updatedRow = updatedRows.find { it.configId == configId }
                    if (updatedRow != null) CategoryRow.fromHomeRow(updatedRow) else item
                } else item
            }

            current.copy(
                rows = updatedRows,
                mixedRows = updatedMixed
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

        if (metadataFallbackCache.containsKey(key)) {
            val cachedFallback = metadataFallbackCache[key]
            if (cachedFallback != null) {
                applyMetadataFallbackToState(type = item.type, id = item.id, fallback = cachedFallback, sourceItem = item)
            }
            return
        }

        if (!metadataRequestsInFlight.add(key)) return

        viewModelScope.launch(Dispatchers.IO) {
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
                    applyMetadataFallbackToState(type = item.type, id = item.id, fallback = fallback, sourceItem = item)

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
                metadataFallbackCache[key] = null
            } finally {
                metadataRequestsInFlight.remove(key)
            }
        }
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

    private val tmdbEnrichmentInFlight = mutableSetOf<String>()
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

        val language = profile.tmdbLanguage.ifBlank { null } ?: "en"

        viewModelScope.launch(Dispatchers.IO) {
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
                    imdbRating = enrichment.rating?.let { String.format("%.1f", it) },
                    runtime = enrichment.runtimeMinutes?.let { "${it}m" },
                    genres = enrichment.genres.ifEmpty { null }
                )

                applyTmdbEnrichmentToState(item.type, item.id, fallback, item)
            } catch (e: Exception) {
                Log.w("HomeViewModel", "TMDB enrichment failed for ${item.id}: ${e.message}")
                markTmdbEnriched(item.type, item.id)
            } finally {
                tmdbEnrichmentInFlight.remove(key)
            }
        }
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
                if (rowsChanged) row.copy(items = patched) else row
            }

            val updatedMixedRows = current.mixedRows.map { rowItem ->
                if (rowItem is CategoryRow) {
                    val patched = rowItem.items.map { overwriteMeta(it) }
                    if (rowsChanged) rowItem.copy(items = patched) else rowItem
                } else rowItem
            }

            val updatedHeroRow = current.heroRow?.let { hero ->
                val patched = hero.items.map { overwriteMeta(it) }
                if (rowsChanged) hero.copy(items = patched) else hero
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
        loadJob = viewModelScope.launch {
            // Clear 'rows' immediately so the UI doesn't show "Ghost Data" from the previous tab
            pendingRowItems.clear()
            allFetchedRowIds.clear()
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

            // Load watched IDs for all tabs (watched indicator on posters)
            launch {
                dao.getWatchedIds().collect { ids ->
                    // Extract canonical series ID from episode IDs (tt123:1:3 → tt123)
                    val canonicalIds = ids.map { id ->
                        val parts = id.split(":")
                        if (parts.size >= 3) parts.first() else id
                    }.toSet()
                    _state.update { it.copy(watchedIds = canonicalIds) }
                }
            }

            try {
                // Stage 1: Load only a small first batch so the screen opens quickly.
                val initialRowsDeferred = async {
                    repository.getDashboardRows(
                        screen = screenName,
                        skipConfigs = 0,
                        maxConfigs = initialDashboardBatchSize,
                        catalogTimeoutMs = initialDashboardTimeoutMs
                    )
                }
                val hubRowsDeferred = async { repository.getHubRows(screenName) }

                val initialRows = initialRowsDeferred.await()
                val hubRows = hubRowsDeferred.await()
                if (profileConfigurationManager.activeProfileId.value != currentProfileId) return@launch

                // Fetch HERO row separately (even if hidden in dashboard).
                val tabEnum = DashboardTab.fromString(screenName)
                val heroConfig = currentProfile?.heroFor(tabEnum)
                val heroRow = if (heroConfig?.categoryId != null) {
                    initialRows.find { it.configId == heroConfig.categoryId }
                        ?: repository.getCategoryRowPreview(
                            configId = heroConfig.categoryId,
                            maxItems = heroConfig.posterCount,
                            timeoutMs = initialDashboardTimeoutMs
                        )
                } else null

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

                // Start a tiny metadata warmup pass for items likely to render first.
                prefetchLikelyVisibleMetadata(rows = initialRows, heroRow = heroRow)

                // Stage 2: Load remaining categories in the background and append.
                launch {
                    val remainingRows = repository.getDashboardRows(
                        screen = screenName,
                        skipConfigs = initialDashboardBatchSize
                    )
                    if (remainingRows.isEmpty()) return@launch

                    _state.update { currentState ->
                        if (currentState.loadedScreen != screenName || currentState.loadedProfileId != currentProfileId) {
                            return@update currentState
                        }

                        val allRows = (currentState.rows + remainingRows)
                            .distinctBy { it.configId }
                            .sortedBy { it.order }

                        val resolvedHeroRow = when {
                            currentState.heroRow != null -> currentState.heroRow
                            heroConfig?.categoryId != null -> allRows.find { it.configId == heroConfig.categoryId }
                            else -> null
                        }

                        // Append newly-arrived rows after the ones already rendered instead of
                        // re-sorting the whole merged list by `order`. Re-sorting can shift an
                        // already-visible row's position in mixedRows (e.g. a hub row that sat
                        // after the initial batch can end up before a newly-inserted catalog row
                        // with a lower `order`), which would invalidate any focus-restoration key
                        // saved against that row's old index.
                        val existingIds = currentState.mixedRows.mapTo(mutableSetOf()) { it.id }
                        val newRows = allRows
                            .map { CategoryRow.fromHomeRow(it) }
                            .filter { it.id !in existingIds }
                            .sortedBy { it.order }
                        val mixedList = currentState.mixedRows + newRows

                        currentState.copy(
                            mixedRows = mixedList,
                            rows = allRows,
                            heroRow = resolvedHeroRow
                        )
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
    fun openHub(
        hubItem: com.hereliesaz.illumera.domain.HubItem,
        onResult: (String, List<MetaItem>) -> Unit
    ) {
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
                    // Cache fetched row so GridView can lazy-load additional pages via loadMoreItems().
                    _state.update { current ->
                        val updatedRows = current.rows
                            .filterNot { it.configId == fetchedRow.configId } + fetchedRow
                        current.copy(rows = updatedRows)
                    }
                    onResult(fetchedRow.title, fetchedRow.items)
                }
            } catch (_: Exception) {
                // Ignore error
            }
        }
    }
}
