package com.hereliesaz.illumera.ui.home

import com.hereliesaz.illumera.ui.utils.backdropDecodeSize
import com.hereliesaz.illumera.ui.navigation.focus.FocusMemory
import com.hereliesaz.illumera.ui.navigation.focus.LocalFocusMemory
import com.hereliesaz.illumera.ui.navigation.focus.RestoreFocusOnResume
import com.hereliesaz.illumera.ui.navigation.focus.focusMemoryRoot
import com.hereliesaz.illumera.ui.navigation.focus.rememberFocusMemory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import com.hereliesaz.illumera.ui.audio.rememberCinematicAudioPreferences
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.hereliesaz.illumera.R
import com.hereliesaz.illumera.data.model.ProfileEntity
import com.hereliesaz.illumera.data.model.WatchHistoryEntity
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.domain.DashboardTab
import com.hereliesaz.illumera.domain.HeroConfig
import com.hereliesaz.illumera.domain.HubGroupRow
import com.hereliesaz.illumera.domain.CategoryRow
import com.hereliesaz.illumera.domain.HomeRowItem
import com.hereliesaz.illumera.domain.heroFor
import com.hereliesaz.illumera.domain.HERO_DISABLED_MARKER
import com.hereliesaz.illumera.ui.components.LumeraBackground
import com.hereliesaz.illumera.ui.components.LumeraCard
import kotlinx.coroutines.delay

private const val DPAD_REPEAT_INTERVAL_HORIZONTAL_MS = 150L
private const val DPAD_REPEAT_INTERVAL_VERTICAL_MS = 200L

@Composable
fun HomeScreen(
    tab: DashboardTab,
    entryRequester: FocusRequester,
    drawerRequester: FocusRequester,
    viewModel: HomeViewModel = hiltViewModel(),
    currentProfile: ProfileEntity?,
    onMovieClick: (MetaItem) -> Unit,
    onViewMore: (String, List<MetaItem>, String) -> Unit = { _, _, _ -> }
) {
    val state by viewModel.state.collectAsState()
    val displayState = remember(tab, state) {
        if (tab != DashboardTab.HOME) {
            state
        } else {
            // Home-only rule: once a movie or any episode of a series is watched,
            // remove that title from Home catalog/hero lists immediately. The
            // Continue Watching data remains untouched and manages itself from history.
            val hiddenIds = state.watchedIds
            if (hiddenIds.isEmpty()) {
                state
            } else {
                state.copy(
                    rows = state.rows.map { row -> row.copy(items = row.items.filterNot { it.id in hiddenIds }) },
                    mixedRows = state.mixedRows.map { row ->
                        if (row is CategoryRow) row.copy(items = row.items.filterNot { it.id in hiddenIds }) else row
                    },
                    heroRow = state.heroRow?.let { row -> row.copy(items = row.items.filterNot { it.id in hiddenIds }) }
                )
            }
        }
    }
    // All discovery tabs share the non-configurable cinematic row hierarchy:
    // inactive landscape rails and portrait cards only in the focused row.
    val layoutMode = "cinematic"
    val isTopNav = currentProfile?.navPosition == "top"
    val cinematicAudio = rememberCinematicAudioPreferences(currentProfile?.id)
    val isLandscapeContinueWatching = currentProfile?.continueWatchingShape == "landscape"
    // 120dp clears the side NavDrawer (80dp collapsed) plus a comfortable margin on a
    // wide/TV screen; on a phone that margin alone eats a third of the available width,
    // so trim it down there while still clearing the drawer.
    val isCompactHome = LocalConfiguration.current.screenWidthDp < 600
    val startPadding = when {
        isTopNav -> 50.dp
        isCompactHome -> 96.dp
        else -> 120.dp
    }

    val screenName = remember(tab) {
        when (tab) {
            DashboardTab.HOME -> "home"
            DashboardTab.MOVIES -> "movies"
            DashboardTab.SERIES -> "series"
        }
    }

    // During tab switch, ignore persisted focus/scroll until this tab is actually loaded.
    // This avoids one-frame carry-over from the previous tab.
    val isCurrentTabLoaded = state.loadedScreen == screenName && state.loadedProfileId == currentProfile?.id
    val lastFocusedKey = if (isCurrentTabLoaded) state.lastFocusedKey else null
    val rowScrollPositions = if (isCurrentTabLoaded) viewModel.getRowScrollPositions() else emptyMap()
    val verticalScrollPosition = if (isCurrentTabLoaded) viewModel.getVerticalScrollPosition() else Pair(0, 0)

    // Back is the main-root Back (MainRootBackHandler): Home has no in-screen Back steps.
    // The viewer's place (focused row item, row and vertical scroll) lives in the shared
    // HomeViewModel, since rows recycle and refresh, and the rows attach entryRequester to the
    // remembered item (or its nearest neighbour, resolveRowRestoreIndex). This FocusMemory is
    // the trigger: it restores on resume, and when a refresh re-keys or drops the focused card
    // it goes back to that card's key (or the row's nearest card) before entryRequester.
    val focusMemory = rememberFocusMemory()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusMemoryRoot(focusMemory)
    ) {
        LumeraBackground {
        CompositionLocalProvider(
            com.hereliesaz.illumera.ui.components.LocalWatchedIds provides state.watchedIds,
            // The rows tag their cards with their focus keys, so a refresh that re-keys or drops
            // the focused card can put focus back on it (or its neighbour).
            LocalFocusMemory provides focusMemory
        ) {
        // LOGIC: If we are just starting OR the ViewModel is loading, show the Loading Box.
        // This box accepts focus immediately, which forces the NavDrawer to collapse.
        if (state.isLoading || state.loadedScreen != screenName || state.loadedProfileId != currentProfile?.id) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(entryRequester)
                    .focusable()
            ) {
            }
        } else {
            // DATA IS READY: Show Content
            val hasInProgressHistory = remember(state.history, state.seriesNextUp) {
                state.history.any { !it.watched } || state.seriesNextUp.any { !it.isComplete }
            }
            if (layoutMode == "cinematic") {
                val heroConfig = currentProfile?.heroFor(tab) ?: HeroConfig(null, 10, 0)
                val heroItems = remember(displayState.heroRow, displayState.rows, heroConfig.posterCount, heroConfig.categoryId) {
                    if (heroConfig.categoryId == HERO_DISABLED_MARKER) emptyList() else
                        (displayState.heroRow?.items?.takeIf { it.isNotEmpty() } ?: displayState.rows.firstOrNull { it.items.isNotEmpty() }?.items)
                            .orEmpty().take(heroConfig.posterCount)
                }
                CinematicLayout(
                    heroItems = heroItems,
                    heroAutoScrollSeconds = heroConfig.autoScrollSeconds,
                    resolveTrailer = viewModel::resolveTrailer,
                    autoPlayPreviews = cinematicAudio.autoPlayPreviews,
                    previewSound = cinematicAudio.previewSound,
                    startPadding = startPadding,
                    isTopNav = isTopNav,
                    state = displayState,
                    onMovieClick = onMovieClick,
                    onViewMore = onViewMore,
                    onHubClick = { hubItem ->
                        viewModel.openHub(hubItem) { title, items ->
                            onViewMore(title, items, hubItem.categoryId)
                        }
                    },
                    onLoadMore = { configId -> viewModel.loadMoreItems(configId) },
                    entryRequester = entryRequester,
                    drawerRequester = drawerRequester,
                    focusMemory = focusMemory,
                    lastFocusedKey = lastFocusedKey,
                    rowScrollPositions = rowScrollPositions,
                    verticalScrollPosition = verticalScrollPosition,
                    historyScrollAdjustment = if (viewModel.needsHistoryScrollAdjustment(hasInProgressHistory)) 1 else 0,
                    onFocusChange = { viewModel.setLastFocusedKey(it) },
                    onScrollPositionChange = { key, pos -> viewModel.setRowScrollPosition(key, pos) },
                    onVerticalScrollChange = { viewModel.setVerticalScrollPosition(it, hasInProgressHistory) },
                    onPreviewItemVisible = { viewModel.ensureMetadataFallback(it); viewModel.ensureTmdbEnrichment(it) }
                )
            } else {
                val heroConfig = currentProfile?.heroFor(tab) ?: HeroConfig(null, 10, 0)
                val heroItems = remember(displayState.heroRow, heroConfig.posterCount, heroConfig.categoryId) {
                    if (heroConfig.categoryId == HERO_DISABLED_MARKER) emptyList() else displayState.heroRow?.items?.take(heroConfig.posterCount) ?: emptyList()
                }

                SimpleLayout(
                    startPadding = startPadding,
                    isTopNav = isTopNav,
                    state = displayState,
                    heroItems = heroItems,
                    heroAutoScrollSeconds = heroConfig.autoScrollSeconds,
                    onMovieClick = onMovieClick,
                    onViewMore = onViewMore,
                    onHubClick = { hubItem ->
                        viewModel.openHub(hubItem) { title, items ->
                            onViewMore(title, items, hubItem.categoryId)
                        }
                    },
                    onLoadMore = { configId -> viewModel.loadMoreItems(configId) },
                    entryRequester = entryRequester,
                    drawerRequester = drawerRequester,
                    focusMemory = focusMemory,
                    lastFocusedKey = lastFocusedKey,
                    rowScrollPositions = rowScrollPositions,
                    verticalScrollPosition = verticalScrollPosition,
                    historyScrollAdjustment = if (
                        viewModel.needsHistoryScrollAdjustment(hasInProgressHistory) &&
                        (heroItems.isEmpty() || verticalScrollPosition.first > 0)
                    ) 1 else 0,
                    onFocusChange = { viewModel.setLastFocusedKey(it) },
                    onScrollPositionChange = { key, pos -> viewModel.setRowScrollPosition(key, pos) },
                    onVerticalScrollChange = { viewModel.setVerticalScrollPosition(it, hasInProgressHistory) },
                    onHeroItemVisible = { viewModel.ensureMetadataFallback(it); viewModel.ensureTmdbEnrichment(it) },
                    isLandscapeContinueWatching = isLandscapeContinueWatching
                )
            }
        }
        } // CompositionLocalProvider
    }
}
}

/** Restore a row's portrait geometry only for its actual focus owner. */
internal fun isCinematicRowActive(
    focusedRowKey: String?,
    rowKey: String,
    restoredCardKey: String?,
    cardKeyPrefix: String
): Boolean = focusedRowKey == rowKey ||
    (focusedRowKey == null && restoredCardKey?.startsWith(cardKeyPrefix) == true)

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CinematicLayout(
    heroItems: List<MetaItem>,
    heroAutoScrollSeconds: Int,
    resolveTrailer: suspend (MetaItem) -> String?,
    autoPlayPreviews: Boolean,
    previewSound: Boolean,
    startPadding: androidx.compose.ui.unit.Dp,
    isTopNav: Boolean,
    state: HomeViewModel.HomeState,
    onMovieClick: (MetaItem) -> Unit,
    onViewMore: (String, List<MetaItem>, String) -> Unit,
    onHubClick: (com.hereliesaz.illumera.domain.HubItem) -> Unit,
    onLoadMore: (String) -> Unit,
    entryRequester: FocusRequester,
    drawerRequester: FocusRequester,
    focusMemory: FocusMemory,
    lastFocusedKey: String?,
    rowScrollPositions: Map<String, Pair<Int, Int>>,
    verticalScrollPosition: Pair<Int, Int>,
    historyScrollAdjustment: Int,
    onFocusChange: (String) -> Unit,
    onScrollPositionChange: (String, Pair<Int, Int>) -> Unit,
    onVerticalScrollChange: (Pair<Int, Int>) -> Unit,
    onPreviewItemVisible: (MetaItem) -> Unit
) {
    // The row only controls whether its active poster expands inline.
    // The Hero is an ordinary LazyColumn item, never a sticky header.
    var activeCinematicRow by remember(state.loadedScreen) { mutableStateOf<String?>(null) }

    // Cache the history items transformation to avoid allocating new list on every recomposition
    // Stable key: only rebuild when items/order/watched-state change, not when images update
    val historyKey = remember(state.history) {
        state.history.map { "${it.id}:${it.watched}:${it.lastWatched}:${it.poster != null}" }
    }
    val nextUpKey = remember(state.seriesNextUp) {
        state.seriesNextUp.map { "${it.seriesId}:${it.isComplete}:${it.nextSeason}:${it.nextEpisode}:${it.updatedAt}:${it.poster != null}" }
    }
    val historyItems = remember(historyKey, nextUpKey) {
        buildContinueWatchingItems(state.history, state.seriesNextUp)
    }

    // Empty genres have no focusable row. Preserve the original index for
    // persisted poster keys while assigning compact indices to LazyColumn.
    val visibleMixedRows = remember(state.mixedRows, state.loadedScreen) {
        state.mixedRows.withIndex().filter { indexed ->
            val row = indexed.value
            state.loadedScreen !in setOf("movies", "series") ||
                row !is CategoryRow || row.items.isNotEmpty()
        }
    }
    val firstVisibleRowIndex = visibleMixedRows.firstOrNull()?.index
    // All visible rows (including Hub spacers) participate in vertical order.
    // Only media rails register cards, so navigation into a Hub/Hero keeps its
    // original focus behavior instead of jumping over that row.
    val verticalFocusCoordinator = remember(state.loadedScreen) {
        CinematicVerticalFocusCoordinator()
    }
    val verticalRowOrder = remember(historyItems, visibleMixedRows) {
        buildList {
            if (historyItems.isNotEmpty()) add(-1)
            visibleMixedRows.forEach { add(it.index) }
        }
    }
    SideEffect {
        verticalFocusCoordinator.orderedRowIndices = verticalRowOrder
    }

    // Proactive metadata fetch for continue watching cards (posters + landscape)
    LaunchedEffect(historyItems) {
        historyItems.take(15).forEach { item -> onPreviewItemVisible(item) }
    }

    // Redirect stale continue-watching focus key after progress was cleared
    val effectiveLastFocusedKey = remember(lastFocusedKey, historyItems) {
        resolveEffectiveFocusKey(lastFocusedKey, historyItems)
    }
    val historyFocusRedirected = effectiveLastFocusedKey != lastFocusedKey

    // Focus the remembered item (entryRequester follows it) once there is something to focus:
    // on resume, and again if a refresh takes the focused card away.
    val firstRowPivotRequester = remember { FocusRequester() }

    RestoreFocusOnResume(
        memory = focusMemory,
        fallback = entryRequester,
        enabled = heroItems.isNotEmpty() || historyItems.isNotEmpty() || visibleMixedRows.isNotEmpty(),
        neighbour = { resolveHomeFocusNeighbour(it, focusMemory.registeredKeys) }
    )

    val density = LocalDensity.current
    // Keep the preceding compact landscape rail almost entirely on-screen
    // when a newly focused portrait row reaches its vertical pivot.
    val titleHeadroomPx = remember(density) { with(density) { 175.dp.toPx() } }
    
    // Skip vertical scroll on restoration (when we have a saved focus key)
    val isVerticalRestoration = effectiveLastFocusedKey != null
    var skipVerticalScroll by remember { mutableStateOf(isVerticalRestoration) }

    // Reset skip flag after restoration is complete
    LaunchedEffect(isVerticalRestoration) {
        if (isVerticalRestoration) {
            delay(300)
            skipVerticalScroll = false
        }
    }

    val verticalPivot = remember(titleHeadroomPx) {
        FocusPivotSpec(
            customOffset = titleHeadroomPx,
            skipScrollProvider = { skipVerticalScroll },
            stiffnessProvider = { Spring.StiffnessLow }
        )
    }

    val wrappedOnFocusChange: (String) -> Unit = onFocusChange

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // LIST SECTION
            Box(modifier = Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides verticalPivot) {
                    // Use persisted vertical scroll position for instant restoration.
                    // When the continue watching row first appears at index 0, all saved
                    // indices are off by 1 — adjust the initial index to compensate.
                    val verticalListState = rememberLazyListState(
                        initialFirstVisibleItemIndex = verticalScrollPosition.first + if (heroItems.isNotEmpty() && verticalScrollPosition.first == 0) 0 else historyScrollAdjustment,
                        initialFirstVisibleItemScrollOffset = verticalScrollPosition.second
                    )

                    // Global UP key debouncer to prevent accidental navbar navigation
                    val upKeyDebouncer = remember { UpKeyDebouncer() }
                    val dpadRepeatGate = remember {
                        DpadRepeatGate(
                            horizontalRepeatIntervalMs = DPAD_REPEAT_INTERVAL_HORIZONTAL_MS,
                            verticalRepeatIntervalMs = DPAD_REPEAT_INTERVAL_VERTICAL_MS
                        )
                    }

                    PersistLazyListPosition(
                        listState = verticalListState,
                        key = "cinematic_vertical",
                        minOffsetDeltaPx = 36,
                        onPositionChanged = onVerticalScrollChange
                    )

                    // Pre-scroll warmup: compose off-screen row to populate recycler + compile GPU shaders
                    LaunchedEffect(Unit) {
                        withFrameNanos { }
                        val idx = verticalListState.firstVisibleItemIndex
                        val off = verticalListState.firstVisibleItemScrollOffset
                        if (idx + 1 < verticalListState.layoutInfo.totalItemsCount) {
                            verticalListState.scrollToItem(idx + 1)
                            verticalListState.scrollToItem(idx, off)
                        }
                    }

                    // The Hero is part of the scrolling column, not a persistent
                    // background player. Stop fetching/playing once it leaves view.
                    val heroVisible by remember(verticalListState) {
                        derivedStateOf {
                            verticalListState.layoutInfo.visibleItemsInfo.any {
                                it.key == "cinematic_hero_carousel"
                            }
                        }
                    }

                    LazyColumn(
                        state = verticalListState,
                        modifier = Modifier
                            .fillMaxSize(), // Focus managed by items
                        contentPadding = PaddingValues(top = 5.dp, bottom = 400.dp),
                        // A negative gap placed the active poster over the next row heading.
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        if (heroItems.isNotEmpty()) {
                            // Ordinary list item: the Hero must scroll away entirely.
                            item(key = "cinematic_hero_carousel", contentType = "hero") {
                                HeroCarousel(
                                    items = heroItems,
                                    autoScrollSeconds = heroAutoScrollSeconds,
                                    onItemClick = onMovieClick,
                                    startPadding = startPadding,
                                    onFocusChange = {
                                        activeCinematicRow = "__hero__"
                                        wrappedOnFocusChange("hero_$it")
                                    },
                                    onCurrentItemVisible = onPreviewItemVisible,
                                    resolveTrailer = resolveTrailer,
                                    autoPlayPreviews = autoPlayPreviews,
                                    previewSound = previewSound,
                                    // Only one preview owns audio: once a row is focused,
                                    // the Hero cannot continue playing underneath it.
                                    previewVisible = heroVisible &&
                                        (activeCinematicRow == null || activeCinematicRow == "__hero__"),
                                    onNavigateDown = { runCatching { firstRowPivotRequester.requestFocus() } },
                                    entryRequester = if (effectiveLastFocusedKey == null ||
                                        effectiveLastFocusedKey.startsWith("hero_")) entryRequester else null,
                                    drawerRequester = drawerRequester,
                                    isFirstItem = true,
                                    isTopNav = isTopNav,
                                    upKeyDebouncer = upKeyDebouncer,
                                    repeatGate = dpadRepeatGate,
                                    restoreItemId = effectiveLastFocusedKey
                                        ?.takeIf { it.startsWith("hero_") }
                                        ?.removePrefix("hero_"),
                                    tmdbEnabled = state.tmdbEnabled,
                                    tmdbEnrichedIds = state.tmdbEnrichedIds
                                )
                            }
                        }

                        if (historyItems.isNotEmpty()) {
                            item(key = "history_header", contentType = "row") {
                                Column {
                                val historyRowActive = isCinematicRowActive(
                                    activeCinematicRow, "history", effectiveLastFocusedKey, "-1_"
                                )
                                // Reset scroll position when focus was redirected (cleared item removed)
                                val savedPosition = if (historyFocusRedirected) Pair(0, 0)
                                    else rowScrollPositions["history"] ?: Pair(0, 0)
                                val historyRowState = rememberLazyListState(
                                    initialFirstVisibleItemIndex = savedPosition.first,
                                    initialFirstVisibleItemScrollOffset = savedPosition.second
                                )

                                PersistLazyListPosition(
                                    listState = historyRowState,
                                    key = "cinematic_history",
                                    minOffsetDeltaPx = 36,
                                    onPositionChanged = { onScrollPositionChange("history", it) }
                                )

                                InfiniteLoopRow(
                                    startPadding = startPadding,
                                    isTopNav = isTopNav,
                                    rowIndex = -1,
                                    title = "Continue Watching",
                                    items = historyItems,
                                    onMovieClick = onMovieClick,
                                    onViewMore = { onViewMore("Continue Watching", historyItems, "") },
                                    onFocused = { item: MetaItem?, key: String ->
                                        activeCinematicRow = "history"
                                        item?.let(onPreviewItemVisible)
                                        wrappedOnFocusChange(key)
                                    },
                                    entryRequester = entryRequester,
                                    drawerRequester = drawerRequester,
                                    locallyFocusedItemId = if (effectiveLastFocusedKey?.startsWith("-1_") == true) effectiveLastFocusedKey else null,
                                    isGlobalFocusPresent = effectiveLastFocusedKey != null,
                                    isFirstRow = heroItems.isEmpty(),
                                    pivotFocusRequester = if (heroItems.isNotEmpty()) firstRowPivotRequester else null,
                                    isInfiniteLoopEnabled = false,
                                    visibleItemCount = 15,
                                    isInfiniteScrollingEnabled = true,
                                    externalListState = historyRowState,
                                    upKeyDebouncer = upKeyDebouncer,
                                    repeatGate = dpadRepeatGate,
                                    // Inactive rows are 16:9 rails; the active row uses
                                    // portrait posters and one expanded inline preview.
                                    isLandscapeCards = !historyRowActive,
                                    enrichedItems = state.enrichedMeta,
                                    rowHeight = if (historyRowActive) 220.dp else 116.dp,
                                    inlineCinematicPreview = { poster ->
                                        InlineFocusedPosterPreview(
                                            poster, state, resolveTrailer, autoPlayPreviews, previewSound
                                        )
                                    },
                                    isInlineRowActive = historyRowActive,
                                    verticalFocusCoordinator = verticalFocusCoordinator,
                                    onRowFocusExited = {
                                        if (activeCinematicRow == "history") {
                                            activeCinematicRow = "__navigation__"
                                        }
                                    }
                                )
                                }
                            }
                        }

                        itemsIndexed(
                            items = visibleMixedRows,
                            key = { _, indexed ->
                                when (val item = indexed.value) {
                                    is HubGroupRow -> "hub_row_${item.id}"
                                    is CategoryRow -> "row_${item.id}"
                                    else -> "unknown_${item.id}"
                                }
                            },
                            contentType = { _, indexed ->
                                when (indexed.value) {
                                    is HubGroupRow -> "hub_row"
                                    is CategoryRow -> "row"
                                    else -> "unknown"
                                }
                            }
                        ) { _, indexed ->
                            val index = indexed.index
                            when (val item = indexed.value) {
                                is HubGroupRow -> {
                                    Column {
                                    // Create list state with saved scroll position for this hub row
                                    // Reset when focus was redirected from a removed history item to this first row
                                    val hubKey = "hub_${item.id}"
                                    val resetScroll = historyFocusRedirected && historyItems.isEmpty() && index == firstVisibleRowIndex
                                    val savedHubPosition = if (resetScroll) null else rowScrollPositions[hubKey]
                                    val hubListState = rememberLazyListState(
                                        initialFirstVisibleItemIndex = savedHubPosition?.first ?: 0,
                                        initialFirstVisibleItemScrollOffset = savedHubPosition?.second ?: 0
                                    )

                                    PersistLazyListPosition(
                                        listState = hubListState,
                                        key = "cinematic_$hubKey",
                                        minOffsetDeltaPx = 36,
                                        onPositionChanged = { onScrollPositionChange(hubKey, it) }
                                    )

                                    HubRow(
                                        hubGroup = item,
                                        startPadding = startPadding,
                                        onHubClick = onHubClick,
                                        onFocused = { _, focusKey ->
                                            activeCinematicRow = "__hub__"
                                             wrappedOnFocusChange("hub_$focusKey")
                                        },
                                        entryRequester = entryRequester,
                                        drawerRequester = drawerRequester,
                                        locallyFocusedItemId = if (effectiveLastFocusedKey?.startsWith("hub_") == true) effectiveLastFocusedKey else null,
                                        isTopNav = isTopNav,
                                        rowIndex = index,
                                        externalListState = hubListState,
                                        upKeyDebouncer = upKeyDebouncer,
                                        repeatGate = dpadRepeatGate,
                                        isLastRow = index == visibleMixedRows.lastOrNull()?.index,
                                        isFirstRow = heroItems.isEmpty() && historyItems.isEmpty() && index == firstVisibleRowIndex,
                                        pivotFocusRequester = if (heroItems.isNotEmpty() &&
                                            historyItems.isEmpty() && index == firstVisibleRowIndex
                                        ) firstRowPivotRequester else null,
                                        isGlobalFocusPresent = effectiveLastFocusedKey != null,
                                        forceLandscape = true
                                    )
                                    }
                                }
                                is CategoryRow -> {
                                    Column {
                                    // Create list state with saved scroll position for this row
                                    // Reset when focus was redirected from a removed history item to this first row
                                    val rowKey = item.id
                                    val categoryRowActive = isCinematicRowActive(
                                        activeCinematicRow, rowKey, effectiveLastFocusedKey, "${index}_"
                                    )
                                    val resetScroll = historyFocusRedirected && historyItems.isEmpty() && index == firstVisibleRowIndex
                                    val savedPosition = if (resetScroll) null else rowScrollPositions[rowKey]

                                    val initialIndex = savedPosition?.first ?: 0
                                    val initialOffset = savedPosition?.second ?: 0

                                    val rowListState = rememberLazyListState(
                                        initialFirstVisibleItemIndex = initialIndex,
                                        initialFirstVisibleItemScrollOffset = initialOffset
                                    )

                                    PersistLazyListPosition(
                                        listState = rowListState,
                                        key = "cinematic_$rowKey",
                                        minOffsetDeltaPx = 36,
                                        onPositionChanged = { onScrollPositionChange(rowKey, it) }
                                    )
                                    
                                    // A genre row is created without eagerly querying all
                                    // addon catalogs. Fetch when its LazyColumn item first
                                    // enters view, then keep pagination on horizontal scrolling.
                                    LaunchedEffect(item.id) {
                                        if ((item.id.startsWith("catalog-aggregate:") ||
                                            item.id.startsWith("catalog-home:")) &&
                                            item.items.isEmpty()
                                        ) onLoadMore(item.id)
                                    }

                                    // Lazy pagination: load more items when scrolling near the end
                                    // Use snapshotFlow to avoid reading layoutInfo during composition
                                    val itemId = item.id
                                    val itemSize = item.items.size
                                    LaunchedEffect(rowListState, itemId, itemSize) {
                                        snapshotFlow {
                                            rowListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                                        }.collect { lastVisibleIndex ->
                                            if (itemSize > 0 && lastVisibleIndex >= itemSize - 10) {
                                                onLoadMore(itemId)
                                            }
                                        }
                                    }

                                    InfiniteLoopRow(
                                        startPadding = startPadding,
                                        isTopNav = isTopNav,
                                        rowIndex = index,
                                        title = item.title,
                                        items = item.items,
                                        onMovieClick = onMovieClick,
                                        onViewMore = remember(item.title, item.items, item.id, onViewMore) {
                                            { onViewMore(item.title, item.items, item.id) }
                                        },
                                        onFocused = { focusItem: MetaItem?, key: String ->
                                            activeCinematicRow = rowKey
                                            focusItem?.let(onPreviewItemVisible)
                                            wrappedOnFocusChange(key)
                                        },
                                        entryRequester = entryRequester,
                                        drawerRequester = drawerRequester,
                                        locallyFocusedItemId = if (effectiveLastFocusedKey?.startsWith("${index}_") == true) effectiveLastFocusedKey else null,
                                        isGlobalFocusPresent = effectiveLastFocusedKey != null,
                                        isFirstRow = heroItems.isEmpty() && historyItems.isEmpty() && index == firstVisibleRowIndex,
                                        pivotFocusRequester = if (heroItems.isNotEmpty() && historyItems.isEmpty() && index == firstVisibleRowIndex) firstRowPivotRequester else null,
                                        isInfiniteLoopEnabled = item.isInfiniteLoopEnabled,
                                        visibleItemCount = item.visibleItemCount,
                                        isInfiniteScrollingEnabled = item.isInfiniteScrollingEnabled,
                                        externalListState = rowListState,
                                        upKeyDebouncer = upKeyDebouncer,
                                        repeatGate = dpadRepeatGate,
                                        isLandscapeCards = !categoryRowActive,
                                        enrichedItems = state.enrichedMeta,
                                        rowHeight = if (categoryRowActive) 220.dp else 116.dp,
                                        inlineCinematicPreview = { poster ->
                                            InlineFocusedPosterPreview(
                                            poster, state, resolveTrailer, autoPlayPreviews, previewSound
                                        )
                                        },
                                        isInlineRowActive = categoryRowActive,
                                        verticalFocusCoordinator = verticalFocusCoordinator,
                                        onRowFocusExited = {
                                            if (activeCinematicRow == rowKey) {
                                                activeCinematicRow = "__navigation__"
                                            }
                                        }
                                    )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Render preview *inside* the focused poster's existing Surface. The Surface
 * owns D-pad, touch and long-press; this content only paints moving artwork.
 */
@Composable
private fun InlineFocusedPosterPreview(
    item: MetaItem,
    state: HomeViewModel.HomeState,
    resolveTrailer: suspend (MetaItem) -> String?,
    autoPlayPreviews: Boolean,
    previewSound: Boolean
) {
    val current = state.enrichedMeta["${item.type}:${item.id}"] ?: item
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))) {
        AsyncImage(
            model = current.background?.takeIf { it.isNotBlank() }
                ?: current.poster?.takeIf { it.isNotBlank() },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        TrailerPreview(
            item = current,
            resolveTrailer = resolveTrailer,
            autoPlay = autoPlayPreviews,
            soundEnabled = previewSound,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.35f to Color.Black.copy(alpha = 0.15f),
                    1f to Color.Black.copy(alpha = 0.9f)
                )
            )
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = current.name,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold, fontSize = 19.sp
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val year = extractHomePrimaryYear(current.releaseInfo)
            Text(
                text = listOf(current.type.replaceFirstChar { it.uppercase() }, year)
                    .filterNot { it == "----" }.joinToString(" · "),
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall
            )
            current.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Redirect the saved continue-watching focus key when the row has changed since navigation:
 * 1. Item removed (progress cleared) → focus first remaining item, or first regular row
 * 2. Item reordered (e.g. resumed playback bumped it to front) → update to new index
 */
private fun resolveEffectiveFocusKey(
    lastFocusedKey: String?,
    historyItems: List<MetaItem>
): String? {
    if (lastFocusedKey == null || !lastFocusedKey.startsWith("-1_")) return lastFocusedKey

    val firstSep = lastFocusedKey.indexOf('_')
    if (firstSep < 0) return lastFocusedKey
    val remainder = lastFocusedKey.substring(firstSep + 1)
    val itemId = remainder.substringBeforeLast("_")
    val savedIndex = remainder.substringAfterLast("_").toIntOrNull()

    val currentIndex = historyItems.indexOfFirst { it.id == itemId }

    if (currentIndex < 0) {
        // Item was removed: redirect to its nearest remaining neighbour (same position, or
        // the new last item), or null (first regular row gets focus) if the row is gone.
        return if (historyItems.isNotEmpty()) {
            val neighbour = (savedIndex ?: 0).coerceIn(0, historyItems.lastIndex)
            "-1_${historyItems[neighbour].id}_$neighbour"
        } else {
            null
        }
    }

    // Item exists but moved position (e.g. became most-recently-watched after playback)
    if (savedIndex != null && currentIndex != savedIndex) {
        return "-1_${itemId}_${currentIndex}"
    }

    return lastFocusedKey
}

/**
 * Continue Watching holds only movies and series, one card per title. Episodes are saved
 * under their own ids and, depending on the catalog, typed "series", "tv" or "episode";
 * they all collapse into their series, shown at its most recently watched episode.
 * Any other type (channels, live TV, trailers) is left out.
 */
internal fun buildContinueWatchingItems(
    history: List<WatchHistoryEntity>,
    seriesNextUp: List<com.hereliesaz.illumera.data.model.SeriesNextUpEntity> = emptyList()
): List<MetaItem> {
    val result = mutableListOf<Pair<Long, MetaItem>>()
    val seriesIdsIncluded = mutableSetOf<String>()

    // 1. In-progress items (partially watched, not completed)
    val inProgress = history.filter { !it.watched }

    val seriesByCanonicalId = linkedMapOf<String, MutableList<WatchHistoryEntity>>()
    val movieById = linkedMapOf<String, WatchHistoryEntity>()

    inProgress.forEach { entry ->
        when {
            isSeriesEntry(entry) ->
                seriesByCanonicalId.getOrPut(seriesKey(entry)) { mutableListOf() }.add(entry)
            entry.type.equals("movie", ignoreCase = true) -> {
                val current = movieById[entry.id]
                if (current == null || entry.lastWatched > current.lastWatched) movieById[entry.id] = entry
            }
        }
    }

    seriesByCanonicalId.forEach { (canonicalId, entries) ->
        // The latest episode wins; a bare series entry only when no episode was saved.
        val chosen = entries.filter { isEpisodePlaybackId(it.id) }.maxByOrNull { it.lastWatched }
            ?: entries.maxByOrNull { it.lastWatched }
            ?: return@forEach
        seriesIdsIncluded.add(canonicalId)
        result.add(chosen.lastWatched to MetaItem(
            id = canonicalId,
            type = "series",
            name = chosen.title,
            poster = chosen.poster ?: entries.firstNotNullOfOrNull { it.poster },
            background = chosen.background ?: entries.firstNotNullOfOrNull { it.background },
            logo = chosen.logo ?: entries.firstNotNullOfOrNull { it.logo },
            progress = chosen.progress()
        ))
    }

    movieById.values.forEach { entry ->
        result.add(entry.lastWatched to MetaItem(
            id = entry.id,
            type = "movie",
            name = entry.title,
            poster = entry.poster,
            background = entry.background,
            logo = entry.logo,
            progress = entry.progress()
        ))
    }

    // 2. Next-up entries: series where all in-progress episodes are watched,
    //    but there's a next episode available. Only include if the episode has aired.
    val today = java.time.LocalDate.now().toString() // "2026-04-06"
    for (nextUp in seriesNextUp) {
        // Already shown as in-progress, or a duplicate next-up row
        if (!seriesIdsIncluded.add(nextUp.seriesId)) continue

        // Check if the next episode has aired (null = assume aired)
        val released = nextUp.nextReleased
        if (released != null && released > today) continue

        // Complete with no next episode date = truly done, skip
        if (nextUp.isComplete && released == null) continue

        // +1 badge: show was complete (user was caught up) and episode has now aired
        val isReturning = nextUp.isComplete || nextUp.isNewEpisode

        result.add(nextUp.updatedAt to MetaItem(
            id = nextUp.seriesId,
            type = "series",
            name = nextUp.title,
            poster = nextUp.poster,
            hasNewEpisode = isReturning
        ))
    }

    // Sort all items by most recent activity
    return result.sortedByDescending { it.first }.map { it.second }
}

/**
 * "series" and "episode" are always series. "tv" (and a mistyped "movie") count only when the
 * entry is an episode of something else; a live channel saved as "tv" is not a series.
 */
private fun isSeriesEntry(entry: WatchHistoryEntity): Boolean = when (entry.type.lowercase()) {
    "series", "episode" -> true
    "tv", "movie" -> !entry.seriesId.isNullOrBlank() && entry.seriesId != entry.id
    else -> false
}

/** The series an entry belongs to: its saved series id, else derived from an "id:season:episode" id. */
private fun seriesKey(entry: WatchHistoryEntity): String =
    entry.seriesId?.takeIf { it.isNotBlank() } ?: canonicalSeriesId(entry.id)

private fun canonicalSeriesId(playbackId: String): String {
    if (!isEpisodePlaybackId(playbackId)) return playbackId
    val parts = playbackId.split(":")
    return parts.dropLast(2).joinToString(":")
}

private fun isEpisodePlaybackId(playbackId: String): Boolean {
    val parts = playbackId.split(":")
    if (parts.size < 3) return false
    return parts[parts.lastIndex - 1].toIntOrNull() != null && parts.last().toIntOrNull() != null
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SimpleLayout(
    startPadding: androidx.compose.ui.unit.Dp,
    isTopNav: Boolean,
    state: HomeViewModel.HomeState,
    heroItems: List<MetaItem> = emptyList(),
    heroAutoScrollSeconds: Int = 0,
    onMovieClick: (MetaItem) -> Unit,
    onViewMore: (String, List<MetaItem>, String) -> Unit,
    onHubClick: (com.hereliesaz.illumera.domain.HubItem) -> Unit,
    onLoadMore: (String) -> Unit,
    entryRequester: FocusRequester,
    drawerRequester: FocusRequester,
    focusMemory: FocusMemory,
    lastFocusedKey: String?,
    rowScrollPositions: Map<String, Pair<Int, Int>>,
    verticalScrollPosition: Pair<Int, Int>,
    historyScrollAdjustment: Int,
    onFocusChange: (String) -> Unit,
    onScrollPositionChange: (String, Pair<Int, Int>) -> Unit,
    onVerticalScrollChange: (Pair<Int, Int>) -> Unit,
    onHeroItemVisible: (MetaItem) -> Unit,
    isLandscapeContinueWatching: Boolean = false
) {
    // Stable key: only rebuild when items/order/watched-state change, not when images update
    val historyKey = remember(state.history) {
        state.history.map { "${it.id}:${it.watched}:${it.lastWatched}:${it.poster != null}" }
    }
    val nextUpKey = remember(state.seriesNextUp) {
        state.seriesNextUp.map { "${it.seriesId}:${it.isComplete}:${it.nextSeason}:${it.nextEpisode}:${it.updatedAt}:${it.poster != null}" }
    }
    val historyItems = remember(historyKey, nextUpKey) {
        buildContinueWatchingItems(state.history, state.seriesNextUp)
    }

    // Proactive metadata fetch for continue watching cards (posters + landscape)
    LaunchedEffect(historyItems) {
        historyItems.take(15).forEach { item -> onHeroItemVisible(item) }
    }

    // Redirect stale continue-watching focus key after progress was cleared
    val effectiveLastFocusedKey = remember(lastFocusedKey, historyItems) {
        resolveEffectiveFocusKey(lastFocusedKey, historyItems)
    }
    val historyFocusRedirected = effectiveLastFocusedKey != lastFocusedKey

    // Global UP key debouncer to prevent accidental navbar navigation
    val upKeyDebouncer = remember { UpKeyDebouncer() }
    val dpadRepeatGate = remember {
        DpadRepeatGate(
            horizontalRepeatIntervalMs = DPAD_REPEAT_INTERVAL_HORIZONTAL_MS,
            verticalRepeatIntervalMs = DPAD_REPEAT_INTERVAL_VERTICAL_MS
        )
    }
    // Focus requester for the first row's pivot item (used by hero carousel DOWN key)
    val firstRowPivotRequester = remember { FocusRequester() }

    // Focus the remembered item (entryRequester follows it) once there is something to focus:
    // on resume, and again if a refresh takes the focused card away.
    RestoreFocusOnResume(
        memory = focusMemory,
        fallback = entryRequester,
        enabled = state.rows.isNotEmpty() || historyItems.isNotEmpty(),
        neighbour = { resolveHomeFocusNeighbour(it, focusMemory.registeredKeys) }
    )

    // Smooth vertical scrolling pivot for Simple layout
    val density = LocalDensity.current

    // Skip vertical scroll on restoration (when we have a saved focus key)
    val isVerticalRestoration = effectiveLastFocusedKey != null
    var skipVerticalScroll by remember { mutableStateOf(isVerticalRestoration) }

    // Reset skip flag after restoration is complete
    LaunchedEffect(isVerticalRestoration) {
        if (isVerticalRestoration) {
            delay(300)
            skipVerticalScroll = false
        }
    }

    val verticalPivotPx = remember(density, isTopNav) { with(density) { (if (isTopNav) 91.dp else 71.dp).toPx() } }

    val verticalPivot = remember(verticalPivotPx) {
        FocusPivotSpec(
            customOffset = verticalPivotPx,
            skipScrollProvider = { skipVerticalScroll },
            stiffnessProvider = { Spring.StiffnessLow }
        )
    }

    // Wrap onFocusChange to track timing for vertical dynamic stiffness
    val wrappedOnFocusChange: (String) -> Unit = remember(onFocusChange) {
        { key ->
            onFocusChange(key)
        }
    }
    
    CompositionLocalProvider(LocalBringIntoViewSpec provides verticalPivot) {
        // Use persisted vertical scroll position for instant restoration.
        // When the continue watching row first appears at index 0, all saved
        // indices are off by 1 — adjust the initial index to compensate.
        val verticalListState = rememberLazyListState(
            initialFirstVisibleItemIndex = verticalScrollPosition.first + historyScrollAdjustment,
            initialFirstVisibleItemScrollOffset = verticalScrollPosition.second
        )

        PersistLazyListPosition(
            listState = verticalListState,
            key = "simple_vertical",
            minOffsetDeltaPx = 36,
            onPositionChanged = onVerticalScrollChange
        )

        // Pre-scroll warmup: compose off-screen row to populate recycler + compile GPU shaders
        LaunchedEffect(Unit) {
            withFrameNanos { }
            val idx = verticalListState.firstVisibleItemIndex
            val off = verticalListState.firstVisibleItemScrollOffset
            verticalListState.scrollToItem(idx + 1)
            verticalListState.scrollToItem(idx, off)
        }

        LazyColumn(
            state = verticalListState,
            modifier = Modifier.fillMaxSize(), // Focus managed by items
            contentPadding = PaddingValues(top = if (heroItems.isNotEmpty()) 0.dp else if (isTopNav) 60.dp else 40.dp, bottom = 0.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            if (heroItems.isNotEmpty()) {
                item(key = "hero_carousel") {
                    HeroCarousel(
                        items = heroItems,
                        autoScrollSeconds = heroAutoScrollSeconds,
                        onItemClick = onMovieClick,
                        startPadding = startPadding,
                        onFocusChange = { wrappedOnFocusChange("hero_$it") },
                        onCurrentItemVisible = onHeroItemVisible,
                        entryRequester = if (effectiveLastFocusedKey == null || effectiveLastFocusedKey.startsWith("hero_")) entryRequester else null,
                        drawerRequester = drawerRequester,
                        isFirstItem = true,
                        isTopNav = isTopNav,
                        upKeyDebouncer = upKeyDebouncer,
                        repeatGate = dpadRepeatGate,
                        onNavigateDown = { firstRowPivotRequester.requestFocus() },
                        restoreItemId = if (effectiveLastFocusedKey?.startsWith("hero_") == true) effectiveLastFocusedKey.removePrefix("hero_") else null,
                        tmdbEnabled = state.tmdbEnabled,
                        tmdbEnrichedIds = state.tmdbEnrichedIds
                    )
                }
            }

            if (historyItems.isNotEmpty()) {
                item(key = "simple_history_header", contentType = "row") {
                    // Reset scroll position when focus was redirected (cleared item removed)
                    val savedPosition = if (historyFocusRedirected) Pair(0, 0)
                        else rowScrollPositions["history"] ?: Pair(0, 0)
                    val historyRowState = rememberLazyListState(
                        initialFirstVisibleItemIndex = savedPosition.first,
                        initialFirstVisibleItemScrollOffset = savedPosition.second
                    )

                    PersistLazyListPosition(
                        listState = historyRowState,
                        key = "simple_history",
                        minOffsetDeltaPx = 36,
                        onPositionChanged = { onScrollPositionChange("history", it) }
                    )

                    InfiniteLoopRow(
                        startPadding = startPadding,
                        isTopNav = isTopNav,
                        rowIndex = -1,
                        title = "Continue Watching",
                        items = historyItems,
                        onMovieClick = onMovieClick,
                        onViewMore = { onViewMore("Continue Watching", historyItems, "") },
                        onFocused = remember(wrappedOnFocusChange) {
                            { _: MetaItem?, key: String -> wrappedOnFocusChange(key) }
                        },
                        entryRequester = entryRequester,
                        drawerRequester = drawerRequester,
                        locallyFocusedItemId = if (effectiveLastFocusedKey?.startsWith("-1_") == true) effectiveLastFocusedKey else null,
                        isGlobalFocusPresent = effectiveLastFocusedKey != null,
                        isFirstRow = heroItems.isEmpty(),
                        isInfiniteLoopEnabled = false,
                        visibleItemCount = 15,
                        isInfiniteScrollingEnabled = true,
                        externalListState = historyRowState,
                        upKeyDebouncer = upKeyDebouncer,
                        repeatGate = dpadRepeatGate,
                        pivotFocusRequester = if (heroItems.isNotEmpty()) firstRowPivotRequester else null,
                        isLandscapeCards = isLandscapeContinueWatching,
                        enrichedItems = state.enrichedMeta,
                        rowHeight = if (isLandscapeContinueWatching) 140.dp else 210.dp
                    )
                }
            }

            itemsIndexed(
                items = state.mixedRows,
                key = { _, item -> 
                    when(item) {
                        is HubGroupRow -> "simple_hub_${item.id}"
                        is CategoryRow -> "simple_row_${item.id}"
                        else -> "simple_unknown_${item.id}"
                    }
                },
                contentType = { _, item -> 
                    when(item) {
                        is HubGroupRow -> "hub_row"
                        is CategoryRow -> "row"
                        else -> "unknown"
                    }
                }
            ) { rowIndex, item ->
                when (item) {
                    is HubGroupRow -> {

                        // Create list state with saved scroll position for this hub row
                        // Reset when focus was redirected from a removed history item to this first row
                        val hubKey = "hub_${item.id}"
                        val resetScroll = historyFocusRedirected && historyItems.isEmpty() && rowIndex == 0
                        val savedHubPosition = if (resetScroll) null else rowScrollPositions[hubKey]
                        val hubListState = rememberLazyListState(
                            initialFirstVisibleItemIndex = savedHubPosition?.first ?: 0,
                            initialFirstVisibleItemScrollOffset = savedHubPosition?.second ?: 0
                        )

                        PersistLazyListPosition(
                            listState = hubListState,
                            key = "simple_$hubKey",
                            minOffsetDeltaPx = 36,
                            onPositionChanged = { onScrollPositionChange(hubKey, it) }
                        )

                        HubRow(
                            hubGroup = item,
                            startPadding = startPadding,
                            onHubClick = onHubClick,
                            onFocused = { _, focusKey ->
                                wrappedOnFocusChange("hub_$focusKey")
                            },
                            entryRequester = entryRequester,
                            drawerRequester = drawerRequester,
                            locallyFocusedItemId = if (effectiveLastFocusedKey?.startsWith("hub_") == true) effectiveLastFocusedKey else null,
                            isTopNav = isTopNav,
                            rowIndex = rowIndex,
                            isFirstRow = heroItems.isEmpty() && historyItems.isEmpty() && rowIndex == 0, // Only steal focus if no hero/history row
                            externalListState = hubListState,
                            upKeyDebouncer = upKeyDebouncer,
                            repeatGate = dpadRepeatGate,
                            isLastRow = rowIndex == state.mixedRows.lastIndex,
                            pivotFocusRequester = if (heroItems.isNotEmpty() && historyItems.isEmpty() && rowIndex == 0) firstRowPivotRequester else null,
                            isGlobalFocusPresent = effectiveLastFocusedKey != null
                        )
                    }
                    is CategoryRow -> {
                        // Create list state with saved scroll position for this row
                        // Reset when focus was redirected from a removed history item to this first row
                        LaunchedEffect(item.id) {
                            if ((item.id.startsWith("catalog-home:") ||
                                    item.id.startsWith("catalog-aggregate:")) &&
                                item.items.isEmpty()
                            ) onLoadMore(item.id)
                        }
                        val rowKey = item.id
                        val resetScroll = historyFocusRedirected && historyItems.isEmpty() && rowIndex == 0
                        val savedPosition = if (resetScroll) null else rowScrollPositions[rowKey]

                        val initialIndex = savedPosition?.first ?: 0
                        val initialOffset = savedPosition?.second ?: 0

                        val rowListState = rememberLazyListState(
                            initialFirstVisibleItemIndex = initialIndex,
                            initialFirstVisibleItemScrollOffset = initialOffset
                        )

                        PersistLazyListPosition(
                            listState = rowListState,
                            key = "simple_$rowKey",
                            minOffsetDeltaPx = 36,
                            onPositionChanged = { onScrollPositionChange(rowKey, it) }
                        )
                        
                        // Lazy pagination: load more items when scrolling near the end
                        // Use snapshotFlow to avoid reading layoutInfo during composition
                        val itemId = item.id
                        val itemSize = item.items.size
                        LaunchedEffect(rowListState, itemId, itemSize) {
                            snapshotFlow {
                                rowListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                            }.collect { lastVisibleIndex ->
                                if (itemSize > 0 && lastVisibleIndex >= itemSize - 10) {
                                    onLoadMore(itemId)
                                }
                            }
                        }
                        
                        // Custom row height: 193dp for last row in Top Nav, 210dp standard
                        val rowHeight = if (isTopNav && rowIndex == state.mixedRows.lastIndex) 193.dp else 210.dp

                        // STABILIZE LAMBDAS:
                        // These specific lambda instances must remain referentially equal
                        // across recompositions to allow InfiniteLoopRow to skip logic.
                        val onRowViewMore = remember(item.items, item.title, item.id, onViewMore) {
                            { onViewMore(item.title, item.items, item.id) }
                        }
                        
                        val onRowFocused = remember(wrappedOnFocusChange) {
                            { _: MetaItem?, key: String -> wrappedOnFocusChange(key) }
                        }

                        InfiniteLoopRow(
                            startPadding = startPadding,
                            isTopNav = isTopNav,
                            rowIndex = rowIndex,
                            title = item.title,
                            items = item.items,
                            onMovieClick = onMovieClick,
                            onViewMore = onRowViewMore,
                            onFocused = onRowFocused,
                            entryRequester = entryRequester,
                            drawerRequester = drawerRequester,
                            locallyFocusedItemId = if (effectiveLastFocusedKey?.startsWith("${rowIndex}_") == true) effectiveLastFocusedKey else null,
                            isGlobalFocusPresent = effectiveLastFocusedKey != null,
                            isFirstRow = heroItems.isEmpty() && historyItems.isEmpty() && rowIndex == 0,
                            isInfiniteLoopEnabled = item.isInfiniteLoopEnabled,
                            visibleItemCount = item.visibleItemCount,
                            isInfiniteScrollingEnabled = item.isInfiniteScrollingEnabled,
                            externalListState = rowListState,
                            rowHeight = rowHeight,
                            upKeyDebouncer = upKeyDebouncer,
                            repeatGate = dpadRepeatGate,
                            pivotFocusRequester = if (heroItems.isNotEmpty() && historyItems.isEmpty() && rowIndex == 0) firstRowPivotRequester else null
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PersistLazyListPosition(
    listState: androidx.compose.foundation.lazy.LazyListState,
    key: String,
    minOffsetDeltaPx: Int = 32,
    onPositionChanged: (Pair<Int, Int>) -> Unit
) {
    var lastIndex by remember(key, listState) { mutableIntStateOf(Int.MIN_VALUE) }
    var lastOffset by remember(key, listState) { mutableIntStateOf(Int.MIN_VALUE) }

    LaunchedEffect(key, listState) {
        snapshotFlow {
            Pair(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }.collect { (index, offset) ->
            val shouldEmit =
                lastIndex == Int.MIN_VALUE ||
                    index != lastIndex ||
                    kotlin.math.abs(offset - lastOffset) >= minOffsetDeltaPx

            if (shouldEmit) {
                lastIndex = index
                lastOffset = offset
                onPositionChanged(Pair(index, offset))
            }
        }
    }

    DisposableEffect(key, listState) {
        onDispose {
            val finalIndex = listState.firstVisibleItemIndex
            val finalOffset = listState.firstVisibleItemScrollOffset
            if (finalIndex != lastIndex || finalOffset != lastOffset) {
                onPositionChanged(Pair(finalIndex, finalOffset))
            }
        }
    }
}

@Composable
fun CinematicBackground(item: MetaItem?) {
    // Use the theme's actual background color
    val backgroundColor = androidx.compose.material3.MaterialTheme.colorScheme.background
    // Brush.horizontalGradient's endX is raw pixels, not dp — convert so this fade
    // covers the same physical width regardless of screen density.
    val cinematicDensity = LocalDensity.current

    // Only fade LEFT and BOTTOM edges - top/right are at screen edge
    val leftFade = Brush.horizontalGradient(
        colorStops = arrayOf(
            0.0f to backgroundColor,
            0.1f to backgroundColor.copy(0.95f),
            0.2f to backgroundColor.copy(0.85f),
            0.3f to backgroundColor.copy(0.72f),
            0.4f to backgroundColor.copy(0.55f),
            0.55f to backgroundColor.copy(0.35f),
            0.7f to backgroundColor.copy(0.18f),
            0.85f to backgroundColor.copy(0.07f),
            1.0f to Color.Transparent
        ),
        startX = 0f,
        endX = with(cinematicDensity) { 500.dp.toPx() }
    )
    
    // Bottom fade: blend into the rows area
    val bottomFade = Brush.verticalGradient(
        colorStops = arrayOf(
            0.0f to Color.Transparent,
            0.2f to backgroundColor.copy(0.05f),
            0.35f to backgroundColor.copy(0.15f),
            0.45f to backgroundColor.copy(0.25f),
            0.55f to backgroundColor.copy(0.38f),
            0.65f to backgroundColor.copy(0.52f),
            0.75f to backgroundColor.copy(0.68f),
            0.85f to backgroundColor.copy(0.82f),
            0.92f to backgroundColor.copy(0.92f),
            1.0f to backgroundColor
        )
    )

    Box(modifier = Modifier.fillMaxSize().zIndex(0f)) {
        Box(modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.65f).fillMaxHeight(0.65f)) {
            Crossfade(
                targetState = item, 
                animationSpec = tween(700), 
                label = "HeroBg"
            ) { currentItem ->
                if (currentItem != null) {
                    val image = currentItem.background ?: currentItem.poster
                    val context = LocalContext.current
                    val imageRequest = remember(image) {
                        ImageRequest.Builder(context)
                            .data(image)
                            .crossfade(false)
                            .size(backdropDecodeSize(context))
                            .build()
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        // Left and bottom edge fades only
                        Spacer(
                            modifier = Modifier
                                .fillMaxSize()
                                .drawWithCache {
                                    onDrawWithContent {
                                        drawRect(brush = leftFade)
                                        drawRect(brush = bottomFade)
                                    }
                                }
                        )
                        com.hereliesaz.illumera.ui.components.NoiseOverlay()
                    }
                }
            }
        }
    }
}


@Composable
fun Badge(text: String, color: Color, textColor: Color) {
    Text(text = text, color = textColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.background(color, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
}

@Composable
private fun HomeMetaStrip(item: MetaItem) {
    val typeLabel = item.type.replaceFirstChar { it.uppercase() }
    val genreLabel = item.genres
        ?.firstOrNull()
        ?.replaceFirstChar { it.uppercase() }
        ?: "Unknown"
    val yearLabel = extractHomePrimaryYear(item.releaseInfo)
    val valueColor = Color.White.copy(alpha = 0.92f)
    val textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = typeLabel, style = textStyle, color = valueColor)
        HomeMetaDot()
        Text(text = genreLabel, style = textStyle, color = valueColor)
        HomeMetaDot()
        Text(text = yearLabel, style = textStyle, color = valueColor)

        item.runtime?.filter { it.isDigit() }?.toIntOrNull()?.let { mins ->
            HomeMetaDot()
            val hours = mins / 60
            val m = mins % 60
            val display = if (hours > 0) "${hours}h ${m}m" else "${m}m"
            Text(text = display, style = textStyle, color = valueColor)
        }

        item.imdbRating?.takeIf { it.isNotBlank() }?.let { rating ->
            HomeMetaDot()
            HomeImdbBadge()
            Text(text = rating, style = textStyle, color = valueColor)
        }
    }
}

@Composable
private fun HomeMetaDot() {
    Text(
        text = ".",
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        color = Color.White.copy(alpha = 0.55f)
    )
}

@Composable
private fun HomeImdbBadge() {
    Image(
        painter = painterResource(id = R.drawable.imdb_logo),
        contentDescription = "IMDb",
        modifier = Modifier.height(16.dp)
    )
}

private fun extractHomePrimaryYear(releaseInfo: String?): String {
    if (releaseInfo.isNullOrBlank()) return "----"
    return Regex("\\d{4}").find(releaseInfo)?.value ?: releaseInfo.take(4)
}

/**
 * Where focus goes when the card keyed [savedKey] ("<row>_<id>_<index>", or "hub_<group>_<index>")
 * is no longer on screen, among the tagged cards composed now ([keys]): the same title in the
 * same row (it moved), else the row's card nearest its old position. Like resolveRowRestoreIndex,
 * never "the first card" unless that is the neighbour. Null when the row has nothing composed.
 */
internal fun resolveHomeFocusNeighbour(savedKey: String, keys: Collection<String>): String? {
    val savedIndex = savedKey.substringAfterLast('_', "").toIntOrNull() ?: return null
    val group: String
    val savedId: String?
    if (savedKey.startsWith("hub_")) {
        group = savedKey.substringBeforeLast('_')
        savedId = null
    } else {
        group = savedKey.substringBefore('_')
        savedId = savedKey.removePrefix("${group}_").substringBeforeLast('_')
    }
    val candidates = keys.mapNotNull { key ->
        if (key == savedKey || key.startsWith("hub_") != savedKey.startsWith("hub_")) return@mapNotNull null
        val index = key.substringAfterLast('_', "").toIntOrNull() ?: return@mapNotNull null
        if (savedId == null) {
            if (key.substringBeforeLast('_') != group) return@mapNotNull null
            Triple(key, null as String?, index)
        } else {
            if (key.substringBefore('_') != group) return@mapNotNull null
            Triple(key, key.removePrefix("${group}_").substringBeforeLast('_'), index)
        }
    }
    val sameTitle = if (savedId != null) candidates.filter { it.second == savedId } else emptyList()
    return sameTitle.ifEmpty { candidates }
        .minWithOrNull(compareBy<Triple<String, String?, Int>> { kotlin.math.abs(it.third - savedIndex) }.thenBy { it.third })
        ?.first
}

/** Do not resolve media or start a player for a disabled/offscreen preview. */
internal fun shouldResolveCinematicPreview(autoPlay: Boolean, visible: Boolean): Boolean =
    autoPlay && visible

/**
 * The image underneath stays visible until ExoPlayer reports that the extracted
 * trailer video is ready. Switching titles cancels resolution and disposes the player.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun TrailerPreview(
    item: MetaItem?,
    resolveTrailer: suspend (MetaItem) -> String?,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = true,
    soundEnabled: Boolean = false,
    isVisible: Boolean = true
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val videoUrl by produceState<String?>(
        initialValue = null, item?.id, item?.type, autoPlay, isVisible
    ) {
        value = null
        if (item != null && shouldResolveCinematicPreview(autoPlay, isVisible)) {
            delay(500)
            value = resolveTrailer(item)
        }
    }
    var ready by remember(item?.id, item?.type, videoUrl) { mutableStateOf(false) }
    val player = remember(videoUrl, item?.id, item?.type) {
        videoUrl?.let { url ->
            ExoPlayer.Builder(context).build().apply {
                volume = if (soundEnabled) 1f else 0f
                repeatMode = Player.REPEAT_MODE_ONE
                addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        ready = true
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        ready = false
                    }
                })
                setMediaItem(PlayerMediaItem.fromUri(url))
                prepare()
                playWhenReady = autoPlay && isVisible
            }
        }
    }
    // Settings/viewport changes shouldn't dispose a player until its own URL
    // identity changes. The lifecycle observer reads the latest play condition.
    val shouldPlay by rememberUpdatedState(autoPlay && isVisible)
    SideEffect {
        player?.volume = if (soundEnabled) 1f else 0f
        player?.playWhenReady = shouldPlay &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }
    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_PAUSE -> {
                    // Pausing does not invalidate the already rendered first frame.
                    // Clearing ready here hid the video permanently on resume:
                    // onRenderedFirstFrame isn't guaranteed to fire a second time.
                    player?.pause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (player != null) player.playWhenReady = shouldPlay
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) player?.pause()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player?.release()
        }
    }
    if (player != null) {
        Box(modifier = modifier) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        // The preview is passive media *inside a focusable poster*.
                        // Never let its Android view steal D-pad or touch focus.
                        isFocusable = false
                        isFocusableInTouchMode = false
                        isClickable = false
                        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        useController = false
                        // Fill the available video box, cropping vertical excess
                        // rather than displaying letterbox bars on TV screens.
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        this.player = player
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize()
            )
            if (!ready) {
                // Don't hide the SurfaceView: alpha=0 can prevent a first-frame
                // callback on some Android TV devices. Cover it with the poster
                // until a decoded frame has really appeared.
                AsyncImage(
                    model = item?.background ?: item?.poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (ready) {
                // Keep the artwork's legibility gradients above moving video.
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)
                )))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                )))
            }
        }
    }
}
