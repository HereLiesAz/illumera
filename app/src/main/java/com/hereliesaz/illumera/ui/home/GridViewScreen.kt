package com.hereliesaz.illumera.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import com.hereliesaz.illumera.R
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.domain.ScreenGenreRows
import com.hereliesaz.illumera.domain.StreamingOriginals
import com.hereliesaz.illumera.ui.components.LumeraCard
import com.hereliesaz.illumera.ui.navigation.focus.FocusMemory
import com.hereliesaz.illumera.ui.navigation.focus.RestoreFocusOnResume
import com.hereliesaz.illumera.ui.navigation.focus.focusMemoryRoot
import com.hereliesaz.illumera.ui.navigation.focus.rememberFocusMemory
import com.hereliesaz.illumera.ui.navigation.focus.restorableFocus
import com.hereliesaz.illumera.ui.util.touchClick
import com.hereliesaz.illumera.ui.utils.ImagePrefetcher

private const val COLUMNS = 6
private const val ORIGINALS_OPTION = "ORIGINALS"
private const val DPAD_REPEAT_INTERVAL_HORIZONTAL_MS = 150L
private const val DPAD_REPEAT_INTERVAL_VERTICAL_MS = 200L

private class GridFocusPivotSpec(
    private val pivotOffset: Float,
    private val stiffnessProvider: (() -> Float)? = null
) : BringIntoViewSpec {
    
    @Deprecated("", level = DeprecationLevel.HIDDEN)
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float>
        get() = androidx.compose.animation.core.spring(
            stiffness = stiffnessProvider?.invoke() ?: androidx.compose.animation.core.Spring.StiffnessLow,
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            visibilityThreshold = 0.1f
        )
    
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // Calculate where we want the focused item to appear (at pivot point from top)
        val targetPosition = pivotOffset
        val currentPosition = offset
        return currentPosition - targetPosition
    }
}

/** A grid of titles; with [sortable] the header offers the sort orders (streaming service pages). */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GridViewScreen(
    title: String,
    items: List<MetaItem>,
    onMovieClick: (MetaItem) -> Unit,
    onBack: () -> Unit,
    onLoadMore: () -> Unit = {},
    watchedIds: Set<String> = emptySet(),
    externalEntryRequester: androidx.compose.ui.focus.FocusRequester? = null,
    focusMemory: FocusMemory = rememberFocusMemory(),
    sortable: Boolean = false,
    // The service's own productions (StreamingOriginals keys); null leaves "Originals" out.
    originalKeys: Set<String>? = null
) {
    var orderName by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf(ScreenGenreRows.TitleOrder.CATALOG.name)
    }
    val order = ScreenGenreRows.TitleOrder.entries.firstOrNull { it.name == orderName }
        ?: ScreenGenreRows.TitleOrder.CATALOG
    val originalsOnly = sortable && originalKeys != null && orderName == ORIGINALS_OPTION
    val shown = remember(items, order, sortable, originalsOnly, originalKeys) {
        when {
            !sortable -> items
            originalsOnly -> items.filter { StreamingOriginals.matches(it, originalKeys!!) }
            else -> ScreenGenreRows.sortTitles(items, order)
        }
    }
    GridViewContent(
        title = title,
        items = shown,
        onMovieClick = onMovieClick,
        onBack = onBack,
        onLoadMore = onLoadMore,
        watchedIds = watchedIds,
        externalEntryRequester = externalEntryRequester,
        focusMemory = focusMemory,
        headerExtra = if (sortable) {
            {
                GridSortChips(
                    selected = if (originalsOnly) null else order,
                    showOriginals = originalKeys != null,
                    originalsSelected = originalsOnly,
                    onSelect = { orderName = it.name },
                    onSelectOriginals = { orderName = ORIGINALS_OPTION }
                )
            }
        } else null
    )
}

@Composable
private fun GridSortChips(
    selected: ScreenGenreRows.TitleOrder?,
    showOriginals: Boolean,
    originalsSelected: Boolean,
    onSelect: (ScreenGenreRows.TitleOrder) -> Unit,
    onSelectOriginals: () -> Unit
) {
    val options = ScreenGenreRows.TitleOrder.entries.map { it.label to (it == selected) } +
        (if (showOriginals) listOf("Originals" to originalsSelected) else emptyList())
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { index, (label, active) ->
            val choose = {
                if (index < ScreenGenreRows.TitleOrder.entries.size)
                    onSelect(ScreenGenreRows.TitleOrder.entries[index])
                else onSelectOriginals()
            }
            var focused by remember { mutableStateOf(false) }
            Text(
                text = label,
                color = if (active || focused) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.key == Key.Enter || event.key == Key.NumPadEnter ||
                                event.key == Key.DirectionCenter)
                        ) {
                            choose()
                            true
                        } else false
                    }
                    .focusable()
                    .touchClick(onClick = { choose() })
                    .background(
                        when {
                            active -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.22f)
                            focused -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f)
                            else -> Color.Transparent
                        },
                        androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun GridViewContent(
    title: String,
    items: List<MetaItem>,
    onMovieClick: (MetaItem) -> Unit,
    onBack: () -> Unit,
    onLoadMore: () -> Unit = {},
    watchedIds: Set<String> = emptySet(),
    externalEntryRequester: androidx.compose.ui.focus.FocusRequester? = null,
    // The grid's place in its back-stack entry: the focused card ("item:<index>"). The scroll
    // comes back with the saveable grid state.
    focusMemory: FocusMemory = rememberFocusMemory(),
    headerExtra: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val isTv = com.hereliesaz.illumera.ui.util.rememberIsTvDevice()

    val startPadding = 50.dp
    
    // Header height for pivot calculation
    // Title row, plus a second row for the sort/filter chips when there are any.
    val headerHeight = if (headerExtra != null) 96.dp else 48.dp
    val headerHeightPx = with(density) { headerHeight.toPx() }
    
    val dpadRepeatGate = remember {
        DpadRepeatGate(
            horizontalRepeatIntervalMs = DPAD_REPEAT_INTERVAL_HORIZONTAL_MS,
            verticalRepeatIntervalMs = DPAD_REPEAT_INTERVAL_VERTICAL_MS
        )
    }
    
    // Create pivot spec - items scroll to just below the fixed header
    val pivotSpec = remember(headerHeightPx) { 
        GridFocusPivotSpec(
            pivotOffset = headerHeightPx + 16f,
            stiffnessProvider = { androidx.compose.animation.core.Spring.StiffnessLow }
        )
    }
    
    // Saveable: the entry's saved state brings the scroll back with Back from a Details page.
    val gridState = rememberLazyGridState()
    // The remembered card as the grid (re)enters the screen: where entryRequester goes. Read once,
    // so moving focus around the grid doesn't recompose it.
    val lastFocusedIndex = remember { gridItemIndex(focusMemory.savedKey) }
    
    // Lazy pagination: load more items when scrolling near the end
    val lastVisibleIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
    LaunchedEffect(lastVisibleIndex, items.size) {
        if (items.size > 0 && lastVisibleIndex >= items.size - 12) {
            onLoadMore()
        }
    }
    
    // Entry focus requester — external one allows the parent (e.g. NavDrawer onClose) to re-focus
    val internalEntryRequester = remember { FocusRequester() }
    val entryRequester = externalEntryRequester ?: internalEntryRequester
    val backIconRequester = remember { FocusRequester() }
    val cardRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    var pendingDirectionalTargetIndex by remember { mutableStateOf<Int?>(null) }
    var isBackIconFocused by remember { mutableStateOf(false) }
    val backIconScale by animateFloatAsState(
        targetValue = if (isBackIconFocused) 1.05f else 1f,
        label = "grid_back_icon_scale"
    )
    var lastFocusedPosterIndex by remember {
        mutableIntStateOf((lastFocusedIndex ?: 0).coerceIn(0, (items.lastIndex).coerceAtLeast(0)))
    }
    
    // Prefetch image URLs list
    val imageUrls = remember(items) { items.map { it.poster } }
    
    BackHandler {
        if (isBackIconFocused) {
            onBack()
        } else {
            backIconRequester.requestFocus()
        }
    }

    // The remembered card (scrolled into view if the grid moved), else the nearest card left
    // at its position, else the first.
    RestoreFocusOnResume(
        memory = focusMemory,
        fallback = entryRequester,
        enabled = items.isNotEmpty(),
        scrollTo = { key ->
            val index = gridItemIndex(key)
            if (index != null && index in items.indices &&
                gridState.layoutInfo.visibleItemsInfo.none { it.index == index }
            ) {
                gridState.scrollToItem(index)
            }
        },
        neighbour = { key ->
            gridItemIndex(key)?.takeIf { items.isNotEmpty() }?.coerceIn(0, items.lastIndex)?.let(::gridItemKey)
        }
    )

    // If UP/DOWN fallback used native scroll, re-apply focus as soon as the intended target is composed.
    LaunchedEffect(gridState) {
        snapshotFlow { pendingDirectionalTargetIndex to gridState.layoutInfo.visibleItemsInfo.map { it.index } }
            .collect { (pendingTarget, visibleIndexes) ->
                if (pendingTarget != null && visibleIndexes.contains(pendingTarget)) {
                    cardRequesters[pendingTarget]?.requestFocus()
                }
            }
    }

    // Use theme background color
    val backgroundColor = MaterialTheme.colorScheme.background
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .focusMemoryRoot(focusMemory)
    ) {
        // ══════════════════════════════════════════════════════════════
        // SCROLLABLE GRID - Positioned first so header overlays it
        // ══════════════════════════════════════════════════════════════
        CompositionLocalProvider(LocalBringIntoViewSpec provides pivotSpec) {
            LazyVerticalGrid(
                columns = if (isTv) GridCells.Fixed(COLUMNS) else GridCells.Adaptive(minSize = 110.dp),
                state = gridState,
                contentPadding = PaddingValues(
                    start = startPadding,
                    end = 50.dp,
                    top = headerHeight + 8.dp, // Start below header
                    bottom = 78.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(
                    items = items,
                    key = { index, item -> "${item.id}_$index" }
                ) { index, item ->
                    
                    val shouldRequestFocus = if (lastFocusedIndex != null && lastFocusedIndex in items.indices) {
                        index == lastFocusedIndex
                    } else {
                        index == 0
                    }
                    val itemRequester = remember(index) { FocusRequester() }
                    val effectiveRequester = if (shouldRequestFocus) entryRequester else itemRequester
                    DisposableEffect(index, effectiveRequester) {
                        cardRequesters[index] = effectiveRequester
                        onDispose {
                            if (cardRequesters[index] === effectiveRequester) {
                                cardRequesters.remove(index)
                            }
                        }
                    }
                    
                    LumeraCard(
                        title = item.name,
                        posterUrl = item.poster,
                        onClick = { onMovieClick(item) },
                        mediaItem = item,
                        isWatched = item.id in watchedIds,
                        modifier = Modifier
                            .aspectRatio(2f / 3f)
                            .onPreviewKeyEvent { keyEvent ->
                                if (dpadRepeatGate.shouldConsume(keyEvent)) return@onPreviewKeyEvent true
                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    when (keyEvent.key) {
                                        // Block UP navigation on first row
                                        Key.DirectionUp -> {
                                            if (index < COLUMNS) {
                                                pendingDirectionalTargetIndex = null
                                                backIconRequester.requestFocus()
                                                return@onPreviewKeyEvent true
                                            }
                                            val targetIndex = index - COLUMNS
                                            val targetRequester = cardRequesters[targetIndex]
                                            if (targetRequester != null) {
                                                pendingDirectionalTargetIndex = null
                                                targetRequester.requestFocus()
                                                return@onPreviewKeyEvent true
                                            } else {
                                                // Let native focus/scroll happen, then correct to the intended same-column target.
                                                pendingDirectionalTargetIndex = targetIndex
                                                return@onPreviewKeyEvent false
                                            }
                                        }
                                        Key.DirectionDown -> {
                                            val targetIndex = index + COLUMNS
                                            if (targetIndex >= items.size) {
                                                pendingDirectionalTargetIndex = null
                                                return@onPreviewKeyEvent true
                                            }
                                            val targetRequester = cardRequesters[targetIndex]
                                            if (targetRequester != null) {
                                                pendingDirectionalTargetIndex = null
                                                targetRequester.requestFocus()
                                                return@onPreviewKeyEvent true
                                            } else {
                                                // Let native focus/scroll happen, then correct to the intended same-column target.
                                                pendingDirectionalTargetIndex = targetIndex
                                                return@onPreviewKeyEvent false
                                            }
                                        }
                                        // Block LEFT navigation on first column (no exit on left key)
                                        Key.DirectionLeft -> {
                                            pendingDirectionalTargetIndex = null
                                            if (index % COLUMNS == 0) {
                                                return@onPreviewKeyEvent true
                                            }
                                        }
                                        Key.DirectionRight -> {
                                            pendingDirectionalTargetIndex = null
                                        }
                                    }
                                }
                                false
                            }
                            .onFocusChanged {
                                if (it.isFocused) {
                                    ImagePrefetcher.prefetchAround(context, imageUrls, index, count = 12)
                                    lastFocusedPosterIndex = index
                                    val pendingTarget = pendingDirectionalTargetIndex
                                    if (pendingTarget != null) {
                                        if (index == pendingTarget) {
                                            // Correction reached intended item.
                                            pendingDirectionalTargetIndex = null
                                        } else {
                                            // Keep pending until correction actually succeeds.
                                            cardRequesters[pendingTarget]?.requestFocus()
                                        }
                                    }
                                }
                            }
                            .focusRequester(effectiveRequester)
                            .restorableFocus(gridItemKey(index), focusMemory)
                    )
                }
            }
        }
        
        // ══════════════════════════════════════════════════════════════
        // FIXED HEADER - Overlays grid with gradient fade
        // ══════════════════════════════════════════════════════════════
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .zIndex(10f)
        ) {
            // Gradient background - extends beyond header for smooth fade
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(headerHeight + 48.dp) // Extended gradient
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                backgroundColor,
                                backgroundColor,
                                backgroundColor,
                                backgroundColor.copy(alpha = 0.95f),
                                backgroundColor.copy(alpha = 0.7f),
                                backgroundColor.copy(alpha = 0.3f),
                                Color.Transparent
                            ),
                            startY = 0f,
                            endY = with(density) { (headerHeight + 48.dp).toPx() }
                        )
                    )
            )
            
            // Header content - tight padding
            Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = startPadding,
                        top = 10.dp,
                        end = 50.dp,
                        bottom = 0.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back indicator (mirrored view more arrow)
                Box(
                    modifier = Modifier
                        .focusRequester(backIconRequester)
                        .onFocusChanged { isBackIconFocused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> {
                                    if (items.isNotEmpty()) {
                                        val targetIndex = lastFocusedPosterIndex.coerceIn(0, items.lastIndex)
                                        val targetRequester = cardRequesters[targetIndex]
                                        if (targetRequester != null) {
                                            pendingDirectionalTargetIndex = null
                                            targetRequester.requestFocus()
                                        } else {
                                            pendingDirectionalTargetIndex = targetIndex
                                        }
                                    }
                                    true
                                }
                                Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.Back -> {
                                    onBack()
                                    true
                                }
                                Key.DirectionUp, Key.DirectionLeft -> true
                                else -> false
                            }
                        }
                        .focusable()
                        .touchClick(onClick = onBack)
                        .size(30.dp)
                        .graphicsLayer {
                            scaleX = -backIconScale // Mirror horizontally + animated scale
                            scaleY = backIconScale
                        }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_view_more_arrow),
                        contentDescription = "Back",
                        tint = if (isBackIconFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxSize()
                    )
                }
                
                Spacer(modifier = Modifier.width(10.dp))
                
                // Category title
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                )
            }

            // Sort and filter chips on their own row; they scroll sideways when they don't fit.
            if (headerExtra != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = startPadding, top = 8.dp, end = 50.dp)
                        .horizontalScroll(rememberScrollState())
                ) {
                    headerExtra()
                }
            }
            }
        }
    }
}

private fun gridItemKey(index: Int) = "item:$index"

private fun gridItemIndex(key: String?): Int? = key?.removePrefix("item:")?.takeIf { key.startsWith("item:") }?.toIntOrNull()
