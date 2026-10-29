package com.hereliesaz.illumera.ui.home

import com.hereliesaz.illumera.ui.navigation.openNavDrawer
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlin.math.abs
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.ui.components.LumeraCard
import com.hereliesaz.illumera.ui.components.LocalWatchedIds
import com.hereliesaz.illumera.ui.components.LumeraLandscapeCard
import com.hereliesaz.illumera.ui.utils.ImagePrefetcher
import com.hereliesaz.illumera.ui.navigation.focus.LocalFocusMemory
import com.hereliesaz.illumera.ui.navigation.focus.restorableFocusIfTracked
import kotlinx.coroutines.delay

private fun GridRowItem.restoreId(): String = when (this) {
    is GridRowItem.MovieItem -> movie.id
    is GridRowItem.ViewMoreItem -> "viewmore"
}

/**
 * Resolves which position of a row focus should return to, from a saved focus key of the
 * form "rowIndex_itemId_index". The item is matched by id (so it is found even if items
 * before it were removed and it shifted); if it is gone, the nearest remaining neighbour at
 * its old position is used. Returns null when there is no saved key for this row or the
 * row is empty — never "jump to the first item" unless that is the neighbour.
 */
internal fun resolveRowRestoreIndex(savedKey: String?, rowIndex: Int, ids: List<String>): Int? {
    if (savedKey == null || ids.isEmpty()) return null
    val prefix = "${rowIndex}_"
    if (!savedKey.startsWith(prefix)) return null
    val rest = savedKey.removePrefix(prefix)
    val savedIndex = rest.substringAfterLast('_', missingDelimiterValue = "").toIntOrNull()
    val id = if (savedIndex != null) rest.substringBeforeLast('_') else rest
    if (savedIndex != null && ids.getOrNull(savedIndex) == id) return savedIndex
    val byId = ids.indexOf(id)
    if (byId >= 0) return byId
    return (savedIndex ?: 0).coerceIn(0, ids.lastIndex)
}

/**
 * ============================================================================
 * CONTENT ROW - THREE MODE SUPPORT
 * ============================================================================
 *
 * This composable supports three distinct modes:
 *
 * CASE A: Grid View OFF (isInfiniteLoopEnabled = false)
 * - Shows the FULL original list, no truncation
 * - Standard linear LazyRow with itemsIndexed
 * - No ViewMore card, no infinite logic, no modulo math
 *
 * CASE B: Grid View ON + Infinite Loop ON
 * - Truncates list to `visibleItemCount` items + ViewMore card
 * - Uses items(count = Int.MAX_VALUE) with modulo for infinite looping
 * - Unique keys: "${movie.id}_$scrollIndex" to prevent duplicate key crashes
 * - One-way loop: LEFT at loop start exits to Navbar
 * - ViewMore card has directional alpha fade
 *
 * CASE C: Grid View ON + Infinite Loop OFF
 * - Truncates list to `visibleItemCount` items + ViewMore card
 * - Finite list: count = visibleItemCount + 1
 * - Focus stops at ViewMore card, no looping
 * - No modulo math needed
 *
 * SAFETY: When switching modes while scrolled far out,
 * the list state is reset to prevent crashes from invalid indices.
 * ============================================================================
 */

private val ITEM_WIDTH = 120.dp
private val ITEM_SPACING = 20.dp

sealed class GridRowItem {
    data class MovieItem(val movie: MetaItem) : GridRowItem()
    data object ViewMoreItem : GridRowItem()
}

class UpKeyDebouncer {
    var lastTime: Long = 0L
}

private class RowKeyRepeatDebouncer {
    var lastTime: Long = 0L
}

/**
 * Pick the card with the closest LEFT edge, not the closest center. A wide
 * cinematic preview must not shift vertical D-pad focus one title to the right.
 */
internal fun closestCinematicCardLeft(
    sourceLeft: Float,
    candidates: List<Pair<String, Float>>
): String? = candidates.minWithOrNull(
    compareBy<Pair<String, Float>> { abs(it.second - sourceLeft) }.thenBy { it.second }
)?.first

/** Step through the actual visible row order, including non-media Hub rows. */
internal fun adjacentCinematicRowIndex(
    orderedRows: List<Int>,
    currentRow: Int,
    down: Boolean
): Int? {
    val current = orderedRows.indexOf(currentRow)
    if (current < 0) return null
    return orderedRows.getOrNull(current + if (down) 1 else -1)
}

/** Coordinates only the visible cards of adjacent cinematic media rows. */
class CinematicVerticalFocusCoordinator {
    var orderedRowIndices: List<Int> = emptyList()

    private data class Target(val left: Float, val requester: FocusRequester)
    private val targets = mutableMapOf<Int, MutableMap<String, Target>>()

    fun record(rowIndex: Int, itemKey: String, left: Float, requester: FocusRequester) {
        targets.getOrPut(rowIndex) { mutableMapOf() }[itemKey] = Target(left, requester)
    }

    fun unregister(rowIndex: Int, itemKey: String, requester: FocusRequester) {
        val registered = targets[rowIndex]?.get(itemKey)
        if (registered?.requester == requester) {
            targets[rowIndex]?.remove(itemKey)
            if (targets[rowIndex]?.isEmpty() == true) targets.remove(rowIndex)
        }
    }

    fun move(rowIndex: Int, itemKey: String, down: Boolean): Boolean {
        val sourceLeft = targets[rowIndex]?.get(itemKey)?.left ?: return false
        // Step past rows that have no cards (empty or not yet loaded) instead of stopping at them.
        var from = rowIndex
        while (true) {
            val destinationIndex = adjacentCinematicRowIndex(orderedRowIndices, from, down)
                ?: return false
            val destination = targets[destinationIndex].orEmpty()
            if (destination.isEmpty()) {
                from = destinationIndex
                continue
            }
            val closest = closestCinematicCardLeft(
                sourceLeft, destination.map { (key, target) -> key to target.left }
            ) ?: return false
            val requester = destination[closest]?.requester ?: return false
            return runCatching { requester.requestFocus() }.getOrDefault(false)
        }
    }
}

/** Register the actual card bounds and preserve normal navigation as fallback. */
@Composable
private fun rememberCinematicVerticalFocusModifier(
    coordinator: CinematicVerticalFocusCoordinator?,
    rowIndex: Int,
    itemKey: String
): Modifier {
    val requester = remember(rowIndex, itemKey) { FocusRequester() }
    DisposableEffect(coordinator, rowIndex, itemKey, requester) {
        onDispose { coordinator?.unregister(rowIndex, itemKey, requester) }
    }
    if (coordinator == null) return Modifier
    return Modifier
        .focusRequester(requester)
        .onGloballyPositioned { coordinates ->
            coordinator.record(rowIndex, itemKey, coordinates.positionInWindow().x, requester)
        }
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionUp -> coordinator.move(rowIndex, itemKey, down = false)
                Key.DirectionDown -> coordinator.move(rowIndex, itemKey, down = true)
                else -> false
            }
        }
}

/** 16:9 preview width bounded by the actual space beside the navigation chrome. */
internal fun cinematicInlineWidth(screenWidth: Dp, startPadding: Dp): Dp =
    minOf(
        210.dp * (16f / 9f),
        (screenWidth - startPadding - 20.dp).coerceAtLeast(140.dp)
    )

/** Cinematic geometry has one owner: inactive rows cannot display portraits. */
internal fun useLandscapeMediaCard(
    hasInlinePreview: Boolean,
    isActiveCinematicRow: Boolean,
    configuredLandscape: Boolean
): Boolean = if (hasInlinePreview) !isActiveCinematicRow else configuredLandscape

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun InfiniteLoopRow(
    startPadding: Dp,
    isTopNav: Boolean,
    rowIndex: Int,
    title: String,
    items: List<MetaItem>,
    onMovieClick: (MetaItem) -> Unit,
    onViewMore: () -> Unit,
    onFocused: (MetaItem?, String) -> Unit,
    entryRequester: FocusRequester,
    drawerRequester: FocusRequester,
    locallyFocusedItemId: String?,  // Specific item ID to focus in THIS row (e.g. "movie123_g0"), or null if focus is elsewhere
    isGlobalFocusPresent: Boolean, // True if the app has a focused item tracked (lastFocusedKey != null)
    isFirstRow: Boolean,
    isInfiniteLoopEnabled: Boolean = false,
    visibleItemCount: Int = 15,
    isInfiniteScrollingEnabled: Boolean = true,
    externalListState: androidx.compose.foundation.lazy.LazyListState? = null,
    rowHeight: Dp = 210.dp,
    upKeyDebouncer: UpKeyDebouncer,
    repeatGate: DpadRepeatGate,
    pivotFocusRequester: FocusRequester? = null,
    isLandscapeCards: Boolean = false,
    enrichedItems: Map<String, MetaItem> = emptyMap(),
    inlineCinematicPreview: (@Composable (MetaItem) -> Unit)? = null,
    isInlineRowActive: Boolean = false,
    onRowFocusExited: (() -> Unit)? = null,
    verticalFocusCoordinator: CinematicVerticalFocusCoordinator? = null
) {
    // Only the focused cinematic row can use portraits. This rule is enforced
    // inside the renderer, independently of stale caller landscape flags.
    val renderLandscapeCards = useLandscapeMediaCard(
        inlineCinematicPreview != null, isInlineRowActive, isLandscapeCards
    )
    val density = LocalDensity.current
    val paddingPx = remember(density, startPadding) { with(density) { startPadding.toPx() } }
    
    // Detect if this is a restoration (coming back from details screen)
    // We check if we have a local focus target AND a saved scroll position
    val isRestoration = remember(locallyFocusedItemId, rowIndex, externalListState) {
        locallyFocusedItemId != null && externalListState != null
    }
    
    // Skip scroll flag - true during restoration, resets after focus is established
    var skipBringIntoViewScroll by remember { mutableStateOf(isRestoration) }
    
    // Reset skip flag after restoration is complete (focus is established)
    LaunchedEffect(isRestoration) {
        if (isRestoration) {
            skipBringIntoViewScroll = false
        }
    }
    
    // Create pivot spec with skip provider and dynamic stiffness
    val pivotSpec = remember(paddingPx) { 
        FocusPivotSpec(
            customOffset = paddingPx,
            skipScrollProvider = { skipBringIntoViewScroll },
            stiffnessProvider = { Spring.StiffnessLow }
        ) 
    }

    // Calculate end padding to allow last item to align to left (pivot position)
    // End padding = Screen Width - Start Padding - Item Width
    val effectiveItemWidth = when {
        renderLandscapeCards -> 190.dp
        inlineCinematicPreview != null -> 140.dp
        else -> ITEM_WIDTH
    }
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    // Native 16:9 previews need roughly 373dp at 210dp high, screen permitting.
    val inlineCinematicWidth = cinematicInlineWidth(screenWidth, startPadding)
    val endPadding = remember(
        screenWidth, startPadding, effectiveItemWidth, inlineCinematicWidth,
        isInfiniteLoopEnabled, renderLandscapeCards
    ) {
        // Finite/infinite grid modes end with a 120dp View More card (190dp
        // in landscape), never the widened cinematic poster. Use its REAL
        // width so the final item can reach the normal left focus pivot.
        val terminalWidth = if (isInfiniteLoopEnabled) {
            if (renderLandscapeCards) 190.dp else ITEM_WIDTH
        } else if (inlineCinematicPreview != null) {
            inlineCinematicWidth
        } else effectiveItemWidth
        (screenWidth - startPadding - terminalWidth).coerceAtLeast(20.dp)
    }

    // Use external state if provided, otherwise create local state
    val internalListState = rememberLazyListState()
    val listState = externalListState ?: internalListState

    // Calculate the max valid index based on current mode
    val maxValidIndex = when {
        !isInfiniteLoopEnabled -> (items.size - 1).coerceAtLeast(0)
        !isInfiniteScrollingEnabled -> visibleItemCount // visibleItemCount items + 1 ViewMore
        else -> Int.MAX_VALUE
    }

    // SAFETY: Reset scroll position when switching modes
    LaunchedEffect(renderLandscapeCards, isInfiniteLoopEnabled, isInfiniteScrollingEnabled, items.size) {
        if (listState.firstVisibleItemIndex > maxValidIndex) {
            listState.scrollToItem(0)
        }
    }

    // Transforming a focused 16:9 Surface into a portrait Surface detaches
    // its focus target for one frame. Don't confuse that handoff with focus
    // leaving the row for another row or the navigation drawer.
    var activeCardKey by remember(rowIndex, title) { mutableStateOf<String?>(null) }
    var rowPreviouslyHeldFocus by remember(rowIndex, title) { mutableStateOf(false) }
    var focusHandoffPending by remember(rowIndex, title) { mutableStateOf(false) }
    val inlineTransitionRequester = remember(rowIndex, title) { FocusRequester() }
    val focusedPreviewKey = activeCardKey.takeIf { isInlineRowActive }

    LaunchedEffect(isInlineRowActive, activeCardKey) {
        if (isInlineRowActive && focusHandoffPending && activeCardKey != null) {
            withFrameNanos { }
            val regained = runCatching { inlineTransitionRequester.requestFocus() }.isSuccess
            focusHandoffPending = false
            if (!regained) {
                activeCardKey = null
                onRowFocusExited?.invoke()
            }
        }
    }
    LaunchedEffect(isInlineRowActive) {
        if (!isInlineRowActive) {
            activeCardKey = null
            focusHandoffPending = false
        }
    }

    Column(
        modifier = Modifier
            .graphicsLayer { clip = false }
            .onFocusChanged { state ->
                if (state.hasFocus) {
                    rowPreviouslyHeldFocus = true
                } else if (rowPreviouslyHeldFocus && !focusHandoffPending) {
                    // Initial unattached/empty LazyRows also report hasFocus=false.
                    // Only an actual focused->unfocused transition can collapse
                    // the row; otherwise every remount toggles the active genre.
                    rowPreviouslyHeldFocus = false
                    activeCardKey = null
                    onRowFocusExited?.invoke()
                }
            }
    ) {
        Text(
            text = title,
            color = Color.White.copy(0.9f),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(
                start = startPadding,
                bottom = if (renderLandscapeCards) 4.dp else 12.dp
            )
        )

        // Stable reference to avoid recomposition from lambda re-allocation
        val wrappedOnFocused: (MetaItem?, String) -> Unit = { item, key ->
            if (item != null && inlineCinematicPreview != null && !isInlineRowActive) {
                focusHandoffPending = true
            }
            activeCardKey = if (item == null) null else key
            onFocused(item, key)
        }
        
        when {
            // CASE A: Grid View OFF - Standard linear list
            // Uses BringIntoViewSpec for automatic pivot-aligned scrolling
            // Cinematic landscape rails must not revert to portrait when a
            // catalog is configured as an infinite or finite grid.
            // A standard LazyRow also supports per-genre incremental paging.
            !isInfiniteLoopEnabled -> {
                CompositionLocalProvider(LocalBringIntoViewSpec provides pivotSpec) {
                    LinearContent(
                        listState = listState,
                        startPadding = startPadding,
                        endPadding = endPadding,
                        isTopNav = isTopNav,
                        rowIndex = rowIndex,
                        items = items,
                        onMovieClick = onMovieClick,
                        onFocused = wrappedOnFocused,
                        entryRequester = entryRequester,
                        drawerRequester = drawerRequester,
                        locallyFocusedItemId = locallyFocusedItemId,
                        isGlobalFocusPresent = isGlobalFocusPresent,
                        isFirstRow = isFirstRow,
                        rowHeight = rowHeight,
                        upKeyDebouncer = upKeyDebouncer,
                        repeatGate = repeatGate,
                        pivotFocusRequester = pivotFocusRequester,
                        isLandscapeCards = renderLandscapeCards,
                        enrichedItems = enrichedItems,
                        effectiveItemWidth = effectiveItemWidth,
                        inlineCinematicPreview = inlineCinematicPreview,
                        activeCardKey = focusedPreviewKey,
                        inlineCinematicWidth = inlineCinematicWidth,
                        isInlineRowActive = isInlineRowActive,
                        inlineTransitionRequester = inlineTransitionRequester,
                        verticalFocusCoordinator = verticalFocusCoordinator
                    )
                }
            }
            // CASE B: Grid View ON + Infinite Loop ON
            // BringIntoViewSpec handles alignment when returning from navbar
            // SmoothScrollEffect handles spring animation during normal navigation
            isInfiniteScrollingEnabled -> {
                CompositionLocalProvider(LocalBringIntoViewSpec provides pivotSpec) {
                    InfiniteGridContent(
                        listState = listState,
                        startPadding = startPadding,
                        endPadding = endPadding,
                        isTopNav = isTopNav,
                        rowIndex = rowIndex,
                        items = items,
                        visibleItemCount = visibleItemCount,
                        onMovieClick = onMovieClick,
                        onViewMore = onViewMore,
                        onFocused = wrappedOnFocused,
                        entryRequester = entryRequester,
                        drawerRequester = drawerRequester,
                        locallyFocusedItemId = locallyFocusedItemId,
                        isGlobalFocusPresent = isGlobalFocusPresent,
                        isFirstRow = isFirstRow,
                        isRestoredState = externalListState != null,
                        rowHeight = rowHeight,
                        upKeyDebouncer = upKeyDebouncer,
                        repeatGate = repeatGate,
                        pivotFocusRequester = pivotFocusRequester,
                        isLandscapeCards = renderLandscapeCards,
                        enrichedItems = enrichedItems,
                        inlineCinematicPreview = inlineCinematicPreview,
                        activeCardKey = focusedPreviewKey,
                        inlineCinematicWidth = inlineCinematicWidth,
                        isInlineRowActive = isInlineRowActive,
                        inlineTransitionRequester = inlineTransitionRequester,
                        verticalFocusCoordinator = verticalFocusCoordinator
                    )
                }
            }
            // CASE C: Grid View ON + Infinite Loop OFF
            // Uses BringIntoViewSpec for automatic pivot-aligned scrolling
            else -> {
                CompositionLocalProvider(LocalBringIntoViewSpec provides pivotSpec) {
                    FiniteGridContent(
                        listState = listState,
                        startPadding = startPadding,
                        endPadding = endPadding,
                        isTopNav = isTopNav,
                        rowIndex = rowIndex,
                        items = items,
                        visibleItemCount = visibleItemCount,
                        onMovieClick = onMovieClick,
                        onViewMore = onViewMore,
                        onFocused = wrappedOnFocused,
                        entryRequester = entryRequester,
                        drawerRequester = drawerRequester,
                        locallyFocusedItemId = locallyFocusedItemId,
                        isGlobalFocusPresent = isGlobalFocusPresent,
                        isFirstRow = isFirstRow,
                        rowHeight = rowHeight,
                        upKeyDebouncer = upKeyDebouncer,
                        repeatGate = repeatGate,
                        pivotFocusRequester = pivotFocusRequester,
                        isLandscapeCards = renderLandscapeCards,
                        enrichedItems = enrichedItems,
                        inlineCinematicPreview = inlineCinematicPreview,
                        activeCardKey = focusedPreviewKey,
                        inlineCinematicWidth = inlineCinematicWidth,
                        isInlineRowActive = isInlineRowActive,
                        inlineTransitionRequester = inlineTransitionRequester,
                        verticalFocusCoordinator = verticalFocusCoordinator
                    )
                }
            }
        }
    }
}

/**
 * CASE A: LINEAR SCROLLING (Grid View OFF - DEFAULT)
 * Full list, no truncation, no infinite logic, no ViewMore
 * 
 * Netflix-grade optimizations:
 * - Image prefetching ahead of focus
 * - beyondBoundsItemCount for precomposition
 * - Stable keys for proper recycling
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinearContent(
    listState: androidx.compose.foundation.lazy.LazyListState,
    startPadding: Dp,
    endPadding: Dp,
    isTopNav: Boolean,
    rowIndex: Int,
    items: List<MetaItem>,
    onMovieClick: (MetaItem) -> Unit,
    onFocused: (MetaItem?, String) -> Unit,
    entryRequester: FocusRequester,
    drawerRequester: FocusRequester,
    locallyFocusedItemId: String?,
    isGlobalFocusPresent: Boolean,
    isFirstRow: Boolean,
    rowHeight: Dp,
    upKeyDebouncer: UpKeyDebouncer,
    repeatGate: DpadRepeatGate,
    pivotFocusRequester: FocusRequester? = null,
    isLandscapeCards: Boolean = false,
    enrichedItems: Map<String, MetaItem> = emptyMap(),
    effectiveItemWidth: Dp = ITEM_WIDTH,
    inlineCinematicPreview: (@Composable (MetaItem) -> Unit)? = null,
    activeCardKey: String? = null,
    inlineCinematicWidth: Dp = 340.dp,
    isInlineRowActive: Boolean = false,
    inlineTransitionRequester: FocusRequester = FocusRequester(),
    verticalFocusCoordinator: CinematicVerticalFocusCoordinator? = null
) {
    val context = LocalContext.current

    // Prefetch image URLs list (cached to avoid allocation during scroll)
    val imageUrls = remember(items, isLandscapeCards, enrichedItems) {
        if (isLandscapeCards) {
            items.map { item ->
                val enriched = enrichedItems["${item.type}:${item.id}"]
                enriched?.background?.takeIf { it.isNotBlank() }
                    ?: item.background?.takeIf { it.isNotBlank() }
                    ?: enriched?.poster?.takeIf { it.isNotBlank() }
                    ?: item.poster?.takeIf { it.isNotBlank() }
            }
        } else {
            items.map { item ->
                enrichedItems["${item.type}:${item.id}"]?.poster
                    ?.takeIf { it.isNotBlank() }
                    ?: item.poster
            }
        }
    }

    // Debounce navbar escape: track last LEFT key time to prevent escape during long-press
    val leftKeyDebouncer = remember { RowKeyRepeatDebouncer() }
    val navbarEscapeDebounceMs = 300L // Only allow escape if 300ms since last LEFT press

    // Where focus returns to in this row: the saved item (found by id even if it shifted),
    // or its nearest remaining neighbour if it was removed (e.g. marked watched).
    val restoreIndex = remember(locallyFocusedItemId, items) {
        resolveRowRestoreIndex(locallyFocusedItemId, rowIndex, items.map { it.id })
    }

    // Pre-scroll warmup: compose off-screen items to populate recycler + compile GPU shaders
    // Scrolls forward 3 items and back in ~2 frames (invisible at 60fps)
    LaunchedEffect(Unit) {
        withFrameNanos { } // Wait for initial layout
        val idx = listState.firstVisibleItemIndex
        val off = listState.firstVisibleItemScrollOffset
        listState.scrollToItem(idx + 3)
        listState.scrollToItem(idx, off)
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .height(rowHeight)
            .graphicsLayer { clip = false },
        contentPadding = PaddingValues(start = startPadding, end = endPadding),
        horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING)
    ) {
        itemsIndexed(
            items = items,
            key = { index, item -> "${rowIndex}_${item.id}_$index" },
            contentType = { _, _ -> if (isLandscapeCards) "landscape" else "movie" }
        ) { index, item ->
            val isFirstItem = index == 0
            val isLastItem = index == items.lastIndex
            val uniqueKey = "${rowIndex}_${item.id}_$index"

            val shouldRequestFocus = when {
                index == restoreIndex -> true
                !isGlobalFocusPresent && isFirstRow && isFirstItem -> true
                else -> false
            }

            Box(
                modifier = Modifier
                    .width(if (inlineCinematicPreview != null && activeCardKey == uniqueKey) {
                        inlineCinematicWidth
                    } else effectiveItemWidth)
                    .then(if (isInlineRowActive && activeCardKey == uniqueKey)
                        Modifier.focusRequester(inlineTransitionRequester) else Modifier)
                    .graphicsLayer { clip = false }
                    .onPreviewKeyEvent { keyEvent ->
                        if (repeatGate.shouldConsume(keyEvent)) return@onPreviewKeyEvent true
                        if (keyEvent.type == KeyEventType.KeyDown) {
                            when {
                                keyEvent.key == Key.DirectionRight -> {
                                    if (isTopNav && isLastItem) {
                                        true // Block escape to top navbar from end of row
                                    } else {
                                        false
                                    }
                                }
                                keyEvent.key == Key.DirectionLeft -> {
                                    // Always update time on ANY left press for debounce tracking
                                    val now = System.currentTimeMillis()
                                    val timeSinceLastLeft = now - leftKeyDebouncer.lastTime
                                    leftKeyDebouncer.lastTime = now

                                    if (isFirstItem) {
                                        // Only escape to navbar if this is a deliberate press (not rapid long-press repeat)
                                        if (!isTopNav && timeSinceLastLeft > navbarEscapeDebounceMs) {
                                            drawerRequester.openNavDrawer()
                                        }
                                        true // Consume at first item to prevent focus escaping
                                    } else {
                                        false // Let normal navigation happen
                                    }
                                }
                                keyEvent.key == Key.DirectionUp -> {
                                    if (isTopNav) {
                                        // TRACK UP KEY on ALL rows to handle rapid scrolling from bottom
                                        val now = System.currentTimeMillis()
                                        val timeSinceLastUp = now - upKeyDebouncer.lastTime
                                        upKeyDebouncer.lastTime = now

                                        if (isFirstRow) {
                                            // Only escape to navbar if slow press
                                            if (timeSinceLastUp > 300L) {
                                                drawerRequester.requestFocus()
                                            }
                                            true // Block default
                                        } else {
                                            false // Allow up nav
                                        }
                                    } else false
                                }
                                else -> false
                            }
                        } else false
                    }
                    .then(rememberCinematicVerticalFocusModifier(
                        verticalFocusCoordinator, rowIndex, uniqueKey
                    ))
            ) {
                if (isLandscapeCards) {
                    val enriched = enrichedItems["${item.type}:${item.id}"]
                    LumeraLandscapeCard(
                        title = item.name,
                        backdropUrl = enriched?.background?.takeIf { it.isNotBlank() }
                            ?: item.background?.takeIf { it.isNotBlank() },
                        logoUrl = enriched?.logo?.takeIf { it.isNotBlank() }
                            ?: item.logo?.takeIf { it.isNotBlank() },
                        posterUrl = enriched?.poster?.takeIf { it.isNotBlank() }
                            ?: item.poster?.takeIf { it.isNotBlank() },
                        onClick = { onMovieClick(item) },
                        mediaItem = item,
                        progress = item.progress,
                        hasNewEpisode = item.hasNewEpisode,
                        isWatched = rowIndex != -1 && item.id in LocalWatchedIds.current,
                        onFocused = {
                            ImagePrefetcher.prefetchAroundLandscape(context, imageUrls, index)
                            onFocused(item, uniqueKey)
                        },
                        modifier = Modifier.then(
                            if (shouldRequestFocus) Modifier.focusRequester(entryRequester)
                            else if (pivotFocusRequester != null && index == listState.firstVisibleItemIndex) Modifier.focusRequester(pivotFocusRequester)
                            else Modifier
                        ).restorableFocusIfTracked(uniqueKey, LocalFocusMemory.current)
                    )
                } else {
                    val watchedIds = LocalWatchedIds.current
                    LumeraCard(
                        title = item.name,
                        posterUrl = enrichedItems["${item.type}:${item.id}"]?.poster
                            ?.takeIf { it.isNotBlank() } ?: item.poster,
                        onClick = { onMovieClick(item) },
                        mediaItem = item,
                        progress = item.progress,
                        isWatched = rowIndex != -1 && item.id in watchedIds,
                        hasNewEpisode = item.hasNewEpisode,
                        inlineCinematicPreview = inlineCinematicPreview,
                        inlineCinematicExpanded = inlineCinematicPreview != null && activeCardKey == uniqueKey,
                        inlineCinematicWidth = inlineCinematicWidth,
                        onFocused = {
                            ImagePrefetcher.prefetchAround(context, imageUrls, index)
                            onFocused(item, uniqueKey)
                        },
                        modifier = Modifier.then(
                            if (shouldRequestFocus) Modifier.focusRequester(entryRequester)
                            else if (pivotFocusRequester != null && index == listState.firstVisibleItemIndex) Modifier.focusRequester(pivotFocusRequester)
                            else Modifier
                        ).restorableFocusIfTracked(uniqueKey, LocalFocusMemory.current)
                    )
                }
            }
        }
    }
}

/**
 * ============================================================================
 * CASE B: INFINITE GRID (Grid View ON + Infinite Loop ON)
 * ============================================================================
 * 
 * Netflix-grade truly infinite scrolling implementation:
 * 
 * KEY INSIGHT: Use a VERY large bounded list (100+ repetitions) starting from
 * the middle. Users will NEVER reach the end in practice, making it feel
 * truly infinite without any visible jumps or recentering.
 * 
 * Optimizations:
 * 1. 100 repetitions = 1500+ items - practically infinite
 * 2. Start at section 50 (middle) - can scroll 50 sections in either direction
 * 3. Stable keys with generation suffix - bounded memory, proper recycling
 * 4. No recentering needed - eliminates all visible jumps
 * 5. Image prefetching for smooth loading
 * ============================================================================
 */
private const val INFINITE_LOOP_GENERATIONS = 100000 // 100,000 repetitions = practically infinite

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InfiniteGridContent(
    listState: androidx.compose.foundation.lazy.LazyListState,
    startPadding: Dp,
    endPadding: Dp,
    isTopNav: Boolean,
    rowIndex: Int,
    items: List<MetaItem>,
    visibleItemCount: Int,
    onMovieClick: (MetaItem) -> Unit,
    onViewMore: () -> Unit,
    onFocused: (MetaItem?, String) -> Unit,
    entryRequester: FocusRequester,
    drawerRequester: FocusRequester,
    locallyFocusedItemId: String?,
    isGlobalFocusPresent: Boolean,
    isFirstRow: Boolean,
    isRestoredState: Boolean = false,
    rowHeight: Dp,
    upKeyDebouncer: UpKeyDebouncer,
    repeatGate: DpadRepeatGate,
    pivotFocusRequester: FocusRequester? = null,
    isLandscapeCards: Boolean = false,
    enrichedItems: Map<String, MetaItem> = emptyMap(),
    inlineCinematicPreview: (@Composable (MetaItem) -> Unit)? = null,
    activeCardKey: String? = null,
    inlineCinematicWidth: Dp = 340.dp,
    isInlineRowActive: Boolean = false,
    inlineTransitionRequester: FocusRequester = FocusRequester(),
    verticalFocusCoordinator: CinematicVerticalFocusCoordinator? = null
) {
    val context = LocalContext.current

    val truncatedMovies = remember(items, visibleItemCount) { 
        items.take(visibleItemCount.coerceIn(5, 50)) 
    }

    // Build base data list (one section) - movies + ViewMore card
    val baseDataList: List<GridRowItem> = remember(truncatedMovies) {
        truncatedMovies.map { GridRowItem.MovieItem(it) } + GridRowItem.ViewMoreItem
    }
    val sectionSize = baseDataList.size
    
    // Calculate total items and start position
    val totalItems = sectionSize * INFINITE_LOOP_GENERATIONS

    // Prefetch image URLs list
    val imageUrls = remember(truncatedMovies, isLandscapeCards) {
        truncatedMovies.map { item ->
            if (isLandscapeCards)
                item.background?.takeIf { it.isNotBlank() } ?: item.poster?.takeIf { it.isNotBlank() }
            else enrichedItems["${item.type}:${item.id}"]?.poster
                ?.takeIf { it.isNotBlank() } ?: item.poster?.takeIf { it.isNotBlank() }
        }
    }
    
    // Track focused index for ViewMore card's directional alpha
    var currentFocusedIndex by remember(listState) { 
        mutableIntStateOf(listState.firstVisibleItemIndex) 
    }
    
    // Debounce navbar escape: track last LEFT key time to prevent escape during long-press
    val leftKeyDebouncer = remember { RowKeyRepeatDebouncer() }
    val rightKeyDebouncer = remember { RowKeyRepeatDebouncer() }
    val navbarEscapeDebounceMs = 300L

    // Parse last focused key to find the item ID (not scroll index)
    // Key format: "rowIndex_movieId_scrollIndex" or "rowIndex_viewmore_scrollIndex"
    // If that item is no longer in the row (e.g. marked watched), fall back to its nearest
    // remaining neighbour by position rather than focusing nothing.
    val lastFocusedItemId = remember(locallyFocusedItemId, baseDataList) {
        val ids = baseDataList.map { it.restoreId() }
        locallyFocusedItemId?.let { key ->
            // Scroll indices run across generations; reduce to a position within one section.
            val withoutPrefix = key.removePrefix("${rowIndex}_")
            val savedScroll = withoutPrefix.substringAfterLast("_").toIntOrNull()
            val logicalKey = if (savedScroll != null && sectionSize > 0) {
                "${rowIndex}_${withoutPrefix.substringBeforeLast("_")}_${savedScroll % sectionSize}"
            } else key
            resolveRowRestoreIndex(logicalKey, rowIndex, ids)?.let { ids[it] }
        }
    }

    // Pre-scroll warmup: compose off-screen items to populate recycler + compile GPU shaders
    LaunchedEffect(Unit) {
        withFrameNanos { }
        val idx = listState.firstVisibleItemIndex
        val off = listState.firstVisibleItemScrollOffset
        listState.scrollToItem(idx + 3)
        listState.scrollToItem(idx, off)
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .height(rowHeight)
            .graphicsLayer { clip = false },
        contentPadding = PaddingValues(start = startPadding, end = endPadding),
        horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING)
    ) {
        items(
            count = totalItems,
            // Keys use generation (scrollIndex / sectionSize) for uniqueness
            // This bounds memory to INFINITE_LOOP_GENERATIONS unique keys per item
            key = { scrollIndex ->
                val logicalIndex = scrollIndex % sectionSize
                val generation = scrollIndex / sectionSize
                val item = baseDataList[logicalIndex]
                when (item) {
                    is GridRowItem.MovieItem -> "${rowIndex}_${item.movie.id}_g$generation"
                    is GridRowItem.ViewMoreItem -> "${rowIndex}_viewmore_g$generation"
                }
            },
            contentType = { scrollIndex ->
                when (baseDataList[scrollIndex % sectionSize]) {
                    is GridRowItem.MovieItem -> "movie"
                    is GridRowItem.ViewMoreItem -> "viewmore"
                }
            }
        ) { scrollIndex ->
            val logicalIndex = scrollIndex % sectionSize
            val item = baseDataList[logicalIndex]
            val isEntryItem = scrollIndex == 0
            val isLoopStart = logicalIndex == 0

            // For focus restoration: match by item ID, only in the current visible section
            // to avoid multiple items requesting focus across different generations
            val currentSectionStart = (listState.firstVisibleItemIndex / sectionSize) * sectionSize
            val isInCurrentSection = scrollIndex >= currentSectionStart && scrollIndex < currentSectionStart + sectionSize
            
            val shouldRequestFocus = when {
                // First row, no previous focus - focus the entry item
                !isGlobalFocusPresent && isFirstRow && isEntryItem -> true
                // Match by item ID in the current visible section only
                isInCurrentSection && item is GridRowItem.MovieItem && lastFocusedItemId == item.movie.id -> true
                isInCurrentSection && item is GridRowItem.ViewMoreItem && lastFocusedItemId == "viewmore" -> true
                else -> false
            }

            when (item) {
                is GridRowItem.MovieItem -> {
                    val uniqueKey = "${rowIndex}_${item.movie.id}_$scrollIndex"
                    Box(
                        modifier = Modifier
                            .width(if (inlineCinematicPreview != null && activeCardKey == uniqueKey) {
                                inlineCinematicWidth
                            } else if (isLandscapeCards) 190.dp else if (inlineCinematicPreview != null) 140.dp else ITEM_WIDTH)
                            .then(if (isInlineRowActive && activeCardKey == uniqueKey)
                                Modifier.focusRequester(inlineTransitionRequester) else Modifier)
                            .graphicsLayer { clip = false }
                            .onPreviewKeyEvent { keyEvent ->
                                if (repeatGate.shouldConsume(keyEvent)) return@onPreviewKeyEvent true
                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    when {
                                        keyEvent.key == Key.DirectionRight -> {
                                            // Prime RIGHT debounce while moving through posters so ViewMore can
                                            // block long-press escape on arrival.
                                            rightKeyDebouncer.lastTime = System.currentTimeMillis()
                                            false
                                        }
                                        keyEvent.key == Key.DirectionLeft -> {
                                            // Always update time on ANY left press for debounce tracking
                                            val now = System.currentTimeMillis()
                                            val timeSinceLastLeft = now - leftKeyDebouncer.lastTime
                                            leftKeyDebouncer.lastTime = now
                                            
                                            if (isLoopStart) {
                                                // Only escape to navbar if this is a deliberate press (not rapid long-press repeat)
                                                if (!isTopNav && timeSinceLastLeft > navbarEscapeDebounceMs) {
                                                    drawerRequester.openNavDrawer()
                                                }
                                                true // Consume at loop start to prevent focus escaping
                                            } else {
                                                false // Let normal navigation happen
                                            }
                                        }
                                        keyEvent.key == Key.DirectionUp -> {
                                            if (isTopNav) {
                                                // TRACK UP KEY on ALL rows
                                                val now = System.currentTimeMillis()
                                                val timeSinceLastUp = now - upKeyDebouncer.lastTime
                                                upKeyDebouncer.lastTime = now

                                                if (isFirstRow) {
                                                    if (timeSinceLastUp > 300L) {
                                                        drawerRequester.requestFocus()
                                                    }
                                                    true
                                                } else {
                                                    false
                                                }
                                            } else false
                                        }
                                        else -> false
                                    }
                                } else false
                            }
                            .then(rememberCinematicVerticalFocusModifier(
                                verticalFocusCoordinator, rowIndex, uniqueKey
                            ))
                    ) {
                        val watchedIds = LocalWatchedIds.current
                        GridMediaCard(
                            title = item.movie.name,
                            posterUrl = enrichedItems["${item.movie.type}:${item.movie.id}"]?.poster
                                ?.takeIf { it.isNotBlank() } ?: item.movie.poster,
                            onClick = { onMovieClick(item.movie) },
                            mediaItem = item.movie,
                            isLandscapeCards = isLandscapeCards,
                            enrichedItems = enrichedItems,
                            inlineCinematicPreview = inlineCinematicPreview,
                            inlineCinematicExpanded = inlineCinematicPreview != null && activeCardKey == uniqueKey,
                            inlineCinematicWidth = inlineCinematicWidth,
                            progress = item.movie.progress,
                            isWatched = rowIndex != -1 && item.movie.id in watchedIds,
                            onFocused = {
                                currentFocusedIndex = scrollIndex
                                val logicalIndex = scrollIndex % sectionSize
                                if (logicalIndex < imageUrls.size) {
                                    if (isLandscapeCards)
                                        ImagePrefetcher.prefetchAroundLandscape(context, imageUrls, logicalIndex)
                                    else ImagePrefetcher.prefetchAround(context, imageUrls, logicalIndex)
                                }
                                onFocused(item.movie, uniqueKey)
                            },
                            modifier = Modifier.then(
                                if (shouldRequestFocus) Modifier.focusRequester(entryRequester)
                                else if (pivotFocusRequester != null && scrollIndex == listState.firstVisibleItemIndex) Modifier.focusRequester(pivotFocusRequester)
                                else Modifier
                            ).restorableFocusIfTracked(uniqueKey, LocalFocusMemory.current)
                        )
                    }
                }

                is GridRowItem.ViewMoreItem -> {
                    val uniqueKey = "${rowIndex}_viewmore_$scrollIndex"
                    
                    InfiniteViewMoreCard(
                        isLandscapeCards = isLandscapeCards,
                        isTopNav = isTopNav,
                        isFirstRow = isFirstRow,
                        drawerRequester = drawerRequester,
                        upKeyDebouncer = upKeyDebouncer,
                        rightKeyDebouncer = rightKeyDebouncer,
                        navbarEscapeDebounceMs = navbarEscapeDebounceMs,
                        repeatGate = repeatGate,
                        scrollIndex = scrollIndex,
                        currentFocusedIndex = currentFocusedIndex,
                        onClick = onViewMore,
                        onFocused = {
                            currentFocusedIndex = scrollIndex
                            onFocused(null, uniqueKey)
                        },
                        modifier = Modifier
                            .then(if (shouldRequestFocus) Modifier.focusRequester(entryRequester) else Modifier)
                            .then(
                                if (pivotFocusRequester != null && scrollIndex == listState.firstVisibleItemIndex) {
                                    Modifier.focusRequester(pivotFocusRequester)
                                } else Modifier
                            )
                            .restorableFocusIfTracked(uniqueKey, LocalFocusMemory.current)
                    )
                }
            }
        }
    }
    
    // Initialize scroll position to middle on first composition
    // Skip initialization if this is a restored state (coming back to this row)
    LaunchedEffect(Unit) {
        if (!isRestoredState && listState.firstVisibleItemIndex == 0) {
            listState.scrollToItem(0)
        }
    }
}

/**
 * CASE C: FINITE GRID (Grid View ON + Infinite Loop OFF)
 * Truncated list that ends at ViewMore card, no looping
 * 
 * Netflix-grade optimizations:
 * - Image prefetching ahead of focus
 * - beyondBoundsItemCount for precomposition
 * - Stable keys for proper recycling
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FiniteGridContent(
    listState: androidx.compose.foundation.lazy.LazyListState,
    startPadding: Dp,
    endPadding: Dp,
    isTopNav: Boolean,
    rowIndex: Int,
    items: List<MetaItem>,
    visibleItemCount: Int,
    onMovieClick: (MetaItem) -> Unit,
    onViewMore: () -> Unit,
    onFocused: (MetaItem?, String) -> Unit,
    entryRequester: FocusRequester,
    drawerRequester: FocusRequester,
    locallyFocusedItemId: String?,
    isGlobalFocusPresent: Boolean,
    isFirstRow: Boolean,
    rowHeight: Dp,
    upKeyDebouncer: UpKeyDebouncer,
    repeatGate: DpadRepeatGate,
    pivotFocusRequester: FocusRequester? = null,
    isLandscapeCards: Boolean = false,
    enrichedItems: Map<String, MetaItem> = emptyMap(),
    inlineCinematicPreview: (@Composable (MetaItem) -> Unit)? = null,
    activeCardKey: String? = null,
    inlineCinematicWidth: Dp = 340.dp,
    isInlineRowActive: Boolean = false,
    inlineTransitionRequester: FocusRequester = FocusRequester(),
    verticalFocusCoordinator: CinematicVerticalFocusCoordinator? = null
) {
    val context = LocalContext.current
    
    val truncatedMovies = remember(items, visibleItemCount) { 
        items.take(visibleItemCount.coerceIn(5, 50)) 
    }
    
    // Prefetch image URLs list
    val imageUrls = remember(truncatedMovies, isLandscapeCards) {
        truncatedMovies.map { item ->
            if (isLandscapeCards)
                item.background?.takeIf { it.isNotBlank() } ?: item.poster?.takeIf { it.isNotBlank() }
            else enrichedItems["${item.type}:${item.id}"]?.poster
                ?.takeIf { it.isNotBlank() } ?: item.poster?.takeIf { it.isNotBlank() }
        }
    }
    
    // Debounce navbar escape: track last LEFT key time to prevent escape during long-press
    val leftKeyDebouncer = remember { RowKeyRepeatDebouncer() }
    val navbarEscapeDebounceMs = 300L

    // Build finite list: [Movie 0 ... Movie N, ViewMoreItem]
    val dataList: List<GridRowItem> = remember(truncatedMovies) {
        truncatedMovies.map { GridRowItem.MovieItem(it) } + GridRowItem.ViewMoreItem
    }
    val restoreIndex = remember(locallyFocusedItemId, dataList) {
        resolveRowRestoreIndex(locallyFocusedItemId, rowIndex, dataList.map { it.restoreId() })
    }

    // Pre-scroll warmup: compose off-screen items to populate recycler + compile GPU shaders
    LaunchedEffect(Unit) {
        withFrameNanos { }
        val idx = listState.firstVisibleItemIndex
        val off = listState.firstVisibleItemScrollOffset
        listState.scrollToItem(idx + 3)
        listState.scrollToItem(idx, off)
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .height(rowHeight)
            .graphicsLayer { clip = false },
        contentPadding = PaddingValues(start = startPadding, end = endPadding),
        horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING)
    ) {
        itemsIndexed(
            items = dataList,
            key = { index, item ->
                when (item) {
                    is GridRowItem.MovieItem -> "${rowIndex}_${item.movie.id}_$index"
                    is GridRowItem.ViewMoreItem -> "${rowIndex}_viewmore"
                }
            },
            contentType = { _, item ->
                when (item) {
                    is GridRowItem.MovieItem -> "movie"
                    is GridRowItem.ViewMoreItem -> "viewmore"
                }
            }
        ) { index, item ->
            val isFirstItem = index == 0
            val isLastItem = index == dataList.size - 1

            val uniqueKey = when (item) {
                is GridRowItem.MovieItem -> "${rowIndex}_${item.movie.id}_$index"
                is GridRowItem.ViewMoreItem -> "${rowIndex}_viewmore_$index"
            }

            val shouldRequestFocus = when {
                index == restoreIndex -> true
                !isGlobalFocusPresent && isFirstRow && isFirstItem -> true
                else -> false
            }

            when (item) {
                is GridRowItem.MovieItem -> {
                    Box(
                        modifier = Modifier
                            .width(if (inlineCinematicPreview != null && activeCardKey == uniqueKey) {
                                inlineCinematicWidth
                            } else if (isLandscapeCards) 190.dp else if (inlineCinematicPreview != null) 140.dp else ITEM_WIDTH)
                            .then(if (isInlineRowActive && activeCardKey == uniqueKey)
                                Modifier.focusRequester(inlineTransitionRequester) else Modifier)
                            .graphicsLayer { clip = false }
                            .onPreviewKeyEvent { keyEvent ->
                                if (repeatGate.shouldConsume(keyEvent)) return@onPreviewKeyEvent true
                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    when {
                                        keyEvent.key == Key.DirectionLeft -> {
                                            // Always update time on ANY left press for debounce tracking
                                            val now = System.currentTimeMillis()
                                            val timeSinceLastLeft = now - leftKeyDebouncer.lastTime
                                            leftKeyDebouncer.lastTime = now
                                            
                                            if (isFirstItem) {
                                                // Only escape to navbar if this is a deliberate press (not rapid long-press repeat)
                                                if (!isTopNav && timeSinceLastLeft > navbarEscapeDebounceMs) {
                                                    drawerRequester.openNavDrawer()
                                                }
                                                true // Consume at first item to prevent focus escaping
                                            } else {
                                                false // Let normal navigation happen
                                            }
                                        }
                                        keyEvent.key == Key.DirectionUp -> {
                                            if (isTopNav) {
                                                // TRACK UP KEY on ALL rows
                                                val now = System.currentTimeMillis()
                                                val timeSinceLastUp = now - upKeyDebouncer.lastTime
                                                upKeyDebouncer.lastTime = now

                                                if (isFirstRow) {
                                                    if (timeSinceLastUp > 300L) {
                                                        drawerRequester.requestFocus()
                                                    }
                                                    true
                                                } else {
                                                    false
                                                }
                                            } else false
                                        }
                                        else -> false
                                    }
                                } else false
                            }
                            .then(rememberCinematicVerticalFocusModifier(
                                verticalFocusCoordinator, rowIndex, uniqueKey
                            ))
                    ) {
                        val watchedIds = LocalWatchedIds.current
                        GridMediaCard(
                            title = item.movie.name,
                            posterUrl = enrichedItems["${item.movie.type}:${item.movie.id}"]?.poster
                                ?.takeIf { it.isNotBlank() } ?: item.movie.poster,
                            onClick = { onMovieClick(item.movie) },
                            mediaItem = item.movie,
                            isLandscapeCards = isLandscapeCards,
                            enrichedItems = enrichedItems,
                            inlineCinematicPreview = inlineCinematicPreview,
                            inlineCinematicExpanded = inlineCinematicPreview != null && activeCardKey == uniqueKey,
                            inlineCinematicWidth = inlineCinematicWidth,
                            progress = item.movie.progress,
                            isWatched = rowIndex != -1 && item.movie.id in watchedIds,
                            onFocused = {
                                if (isLandscapeCards)
                                    ImagePrefetcher.prefetchAroundLandscape(context, imageUrls, index)
                                else ImagePrefetcher.prefetchAround(context, imageUrls, index)
                                onFocused(item.movie, uniqueKey)
                            },
                            modifier = Modifier.then(
                                if (shouldRequestFocus) Modifier.focusRequester(entryRequester)
                                else if (pivotFocusRequester != null && index == listState.firstVisibleItemIndex) Modifier.focusRequester(pivotFocusRequester)
                                else Modifier
                            ).restorableFocusIfTracked(uniqueKey, LocalFocusMemory.current)
                        )
                    }
                }

                is GridRowItem.ViewMoreItem -> {
                    Box(
                        modifier = Modifier
                            .width(if (isLandscapeCards) 190.dp else ITEM_WIDTH)
                            .graphicsLayer { clip = false }
                            .onPreviewKeyEvent { keyEvent ->
                                if (repeatGate.shouldConsume(keyEvent)) return@onPreviewKeyEvent true
                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    when (keyEvent.key) {
                                        Key.DirectionRight -> true // Block RIGHT at ViewMore (end of list)
                                        Key.DirectionUp -> {
                                            if (isTopNav) {
                                                val now = System.currentTimeMillis()
                                                val timeSinceLastUp = now - upKeyDebouncer.lastTime
                                                upKeyDebouncer.lastTime = now

                                                if (isFirstRow) {
                                                    if (timeSinceLastUp > 300L) {
                                                        drawerRequester.requestFocus()
                                                    }
                                                    true
                                                } else {
                                                    false
                                                }
                                            } else false
                                        }
                                        else -> false
                                    }
                                } else false
                            }
                    ) {
                        ViewMoreCard(
                            isLandscape = isLandscapeCards,
                            onClick = onViewMore,
                            onFocused = { 
                                onFocused(null, uniqueKey) 
                            },
                            modifier = Modifier
                                .then(if (shouldRequestFocus) Modifier.focusRequester(entryRequester) else Modifier)
                                .then(
                                    if (pivotFocusRequester != null && index == listState.firstVisibleItemIndex) {
                                        Modifier.focusRequester(pivotFocusRequester)
                                    } else Modifier
                                )
                                .restorableFocusIfTracked(uniqueKey, LocalFocusMemory.current)
                        )
                    }
                }
            }
        }
    }
}

/**
 * ViewMore card with directional alpha animation (Infinite mode only)
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun InfiniteViewMoreCard(
    isLandscapeCards: Boolean,
    isTopNav: Boolean,
    isFirstRow: Boolean,
    drawerRequester: FocusRequester,
    upKeyDebouncer: UpKeyDebouncer,
    rightKeyDebouncer: RowKeyRepeatDebouncer,
    navbarEscapeDebounceMs: Long,
    repeatGate: DpadRepeatGate,
    scrollIndex: Int,
    currentFocusedIndex: Int,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    val isToTheLeft = scrollIndex < currentFocusedIndex
    val targetAlpha = if (isToTheLeft) 0f else 1f

    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioNoBouncy
        ),
        label = "viewMoreAlpha"
    )

    Box(
        modifier = modifier
            .width(if (isLandscapeCards) 190.dp else ITEM_WIDTH)
            .graphicsLayer {
                clip = false
                alpha = animatedAlpha
            }
            .onPreviewKeyEvent { keyEvent ->
                if (repeatGate.shouldConsume(keyEvent)) return@onPreviewKeyEvent true
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.key) {
                        Key.DirectionRight -> {
                            // Debounce RIGHT long-press repeats so focus does not escape unexpectedly.
                            val now = System.currentTimeMillis()
                            val timeSinceLastRight = now - rightKeyDebouncer.lastTime
                            rightKeyDebouncer.lastTime = now
                            timeSinceLastRight <= navbarEscapeDebounceMs
                        }
                        Key.DirectionUp -> {
                            if (isTopNav) {
                                val now = System.currentTimeMillis()
                                val timeSinceLastUp = now - upKeyDebouncer.lastTime
                                upKeyDebouncer.lastTime = now

                                if (isFirstRow) {
                                    if (timeSinceLastUp > 300L) {
                                        drawerRequester.requestFocus()
                                    }
                                    true
                                } else {
                                    false
                                }
                            } else false
                        }
                        else -> false
                    }
                } else false
            }
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
                if (focusState.isFocused) {
                    onFocused()
                }
            }
    ) {
        ViewMoreCard(
            onClick = onClick,
            onFocused = null,
            isLandscape = isLandscapeCards
        )
    }
}


// Shared poster renderer for finite/infinite grid modes. Grid behavior (including
// View More and looping) stays intact when cinematic mode switches the artwork
// itself to 16:9 landscape cards.
@Composable
private fun GridMediaCard(
    title: String,
    posterUrl: String?,
    onClick: () -> Unit,
    mediaItem: MetaItem,
    progress: Float,
    isWatched: Boolean,
    isLandscapeCards: Boolean,
    enrichedItems: Map<String, MetaItem>,
    inlineCinematicPreview: (@Composable (MetaItem) -> Unit)?,
    inlineCinematicExpanded: Boolean,
    inlineCinematicWidth: Dp,
    onFocused: () -> Unit,
    modifier: Modifier
) {
    if (isLandscapeCards) {
        val enriched = enrichedItems["${mediaItem.type}:${mediaItem.id}"]
        LumeraLandscapeCard(
            title = title,
            backdropUrl = enriched?.background?.takeIf { it.isNotBlank() }
                ?: mediaItem.background?.takeIf { it.isNotBlank() },
            posterUrl = enriched?.poster?.takeIf { it.isNotBlank() }
                ?: posterUrl?.takeIf { it.isNotBlank() },
            logoUrl = enriched?.logo?.takeIf { it.isNotBlank() }
                ?: mediaItem.logo?.takeIf { it.isNotBlank() },
            onClick = onClick,
            mediaItem = mediaItem,
            progress = progress,
            hasNewEpisode = mediaItem.hasNewEpisode,
            isWatched = isWatched,
            onFocused = onFocused,
            modifier = modifier
        )
    } else {
        LumeraCard(
            title = title,
            posterUrl = posterUrl,
            onClick = onClick,
            mediaItem = mediaItem,
            progress = progress,
            isWatched = isWatched,
            hasNewEpisode = mediaItem.hasNewEpisode,
            inlineCinematicPreview = inlineCinematicPreview,
            inlineCinematicExpanded = inlineCinematicExpanded,
            inlineCinematicWidth = inlineCinematicWidth,
            onFocused = onFocused,
            modifier = modifier
        )
    }
}
