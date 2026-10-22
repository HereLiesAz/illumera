package com.hereliesaz.illumera.ui.studio

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import com.hereliesaz.illumera.ui.theme.LocalRoundCorners
import com.hereliesaz.illumera.ui.addons.VoidButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.hereliesaz.illumera.ui.navigation.focus.FocusMemory
import com.hereliesaz.illumera.ui.navigation.focus.RestoreFocusOnResume
import com.hereliesaz.illumera.ui.navigation.focus.focusMemoryRoot
import com.hereliesaz.illumera.ui.navigation.focus.rememberFocusMemory
import com.hereliesaz.illumera.ui.navigation.focus.restorableFocus
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil.compose.AsyncImage
import com.hereliesaz.illumera.data.tmdb.TmdbDiscoverRail
import com.hereliesaz.illumera.data.tmdb.TmdbEntityDetail
import com.hereliesaz.illumera.data.tmdb.TmdbMetaPreview
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.hereliesaz.illumera.ui.home.DpadRepeatGate
import com.hereliesaz.illumera.ui.home.FocusPivotSpec

@Composable
fun StudioDetailScreen(
    entityId: Int,
    entityKind: String,
    entityName: String,
    sourceType: String = "movie",
    onNavigateToDetails: (type: String, id: String) -> Unit = { _, _ -> },
    viewModel: StudioDetailViewModel = hiltViewModel<StudioDetailViewModel, StudioDetailViewModel.Factory>(
        creationCallback = { it.create(entityId, entityKind, sourceType) }
    )
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val bg = MaterialTheme.colorScheme.background
    val accentColor = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onBackground

    // Back belongs to the app's back stack (NavDisplay pops this page).

    // This page's place in its back-stack entry: the rail card ("<rail>:<index>") that opened a
    // title, so Back returns to it (or the nearest card left in that rail).
    val focusMemory = rememberFocusMemory()

    Box(modifier = Modifier.fillMaxSize().background(bg).focusMemoryRoot(focusMemory)) {
        when (val state = uiState) {
            is StudioDetailState.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = accentColor
                )
            }
            is StudioDetailState.Error -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Text(
                        text = state.message,
                        color = textColor.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    VoidButton(
                        text = "Retry",
                        onClick = { viewModel.retry() },
                        modifier = Modifier.width(120.dp),
                        isPrimary = true
                    )
                }
            }
            is StudioDetailState.Success -> {
                val initialFocusRequester = remember { FocusRequester() }

                // With no rails nothing carries initialFocusRequester, so don't hand it out as the fallback.
                RestoreFocusOnResume(
                    memory = focusMemory,
                    fallback = initialFocusRequester.takeIf { state.rails.any { it.items.isNotEmpty() } }
                )

                StudioContent(
                    entity = state.entity,
                    rails = state.rails,
                    bg = bg,
                    accentColor = accentColor,
                    textColor = textColor,
                    onNavigateToDetails = { type, id, _, _ -> onNavigateToDetails(type, id) },
                    onLoadMore = { mediaType, railType ->
                        viewModel.loadMoreRail(mediaType, railType)
                    },
                    focusMemory = focusMemory,
                    initialFocusRequester = initialFocusRequester
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StudioContent(
    entity: TmdbEntityDetail,
    rails: List<TmdbDiscoverRail>,
    bg: Color,
    accentColor: Color,
    textColor: Color,
    onNavigateToDetails: (type: String, id: String, rowKey: String, index: Int) -> Unit,
    onLoadMore: (mediaType: String, railType: String) -> Unit,
    focusMemory: FocusMemory,
    initialFocusRequester: FocusRequester
) {
    val density = LocalDensity.current
    val verticalPivotPx = remember(density) { with(density) { 45.dp.toPx() } }
    val verticalPivot = remember(verticalPivotPx) {
        FocusPivotSpec(
            customOffset = verticalPivotPx,
            stiffnessProvider = { Spring.StiffnessLow }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Fixed hero section (40%)
        Box(
            modifier = Modifier
                .weight(0.40f)
                .fillMaxWidth()
                .padding(start = 48.dp, end = 48.dp, top = 48.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            HeroSection(entity, accentColor, textColor)
        }

        // Scrollable rails section (60%)
        Box(modifier = Modifier.weight(0.60f)) {
            if (rails.isEmpty()) {
                Text(
                    text = "No titles found",
                    color = textColor.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.Center)
                )
                return@Box
            }
            CompositionLocalProvider(LocalBringIntoViewSpec provides verticalPivot) {
                LazyColumn(
                    state = rememberLazyListState(),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 5.dp, bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    rails.filter { it.items.isNotEmpty() }.forEachIndexed { railIndex, rail ->
                        val rowKey = "${rail.mediaType}_${rail.railType}"
                        item(key = rowKey) {
                            DiscoverRailSection(
                                rail = rail,
                                accentColor = accentColor,
                                textColor = textColor,
                                onItemClick = { type, id, index ->
                                    onNavigateToDetails(type, id, rowKey, index)
                                },
                                onLoadMore = { onLoadMore(rail.mediaType, rail.railType) },
                                focusMemory = focusMemory,
                                rowKey = rowKey,
                                initialFocusRequester = if (railIndex == 0) initialFocusRequester else null
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroSection(entity: TmdbEntityDetail, accentColor: Color, textColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Logo or fallback name
        if (entity.logo != null) {
            Box(
                modifier = Modifier
                    .width(200.dp)
                    .height(100.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                AsyncImage(
                    model = entity.logo,
                    contentDescription = entity.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            Text(
                text = entity.name,
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = textColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Info column — weighted (matching the equivalent CastDetailScreen hero) so it's
        // constrained to the remaining row width instead of measuring unconstrained next
        // to the fixed-width logo box, which could push its text past the screen edge.
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val kindLabel = if (entity.kind == "network") "Network" else "Production Company"
            Text(
                text = kindLabel,
                style = MaterialTheme.typography.bodySmall,
                color = accentColor
            )

            val metaParts = listOfNotNull(
                entity.originCountry,
                entity.headquarters
            )
            if (metaParts.isNotEmpty()) {
                Text(
                    text = metaParts.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor.copy(alpha = 0.6f)
                )
            }

            entity.description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor.copy(alpha = 0.7f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiscoverRailSection(
    rail: TmdbDiscoverRail,
    accentColor: Color,
    textColor: Color,
    onItemClick: (type: String, id: String, index: Int) -> Unit,
    onLoadMore: () -> Unit,
    focusMemory: FocusMemory,
    rowKey: String,
    initialFocusRequester: FocusRequester? = null
) {
    // Nothing to show: render nothing, so the empty row neither shows nor traps focus.
    if (rail.items.isEmpty()) return
    val density = LocalDensity.current
    val repeatGate = remember { DpadRepeatGate(horizontalRepeatIntervalMs = 150L) }
    val startPad = 48.dp
    val paddingPx = remember(density) { with(density) { startPad.toPx() } }
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val endPadding = (screenWidth - startPad - 120.dp).coerceAtLeast(120.dp)
    val rowState = rememberLazyListState()

    val pivotSpec = remember(paddingPx) {
        FocusPivotSpec(
            customOffset = paddingPx,
            stiffnessProvider = { Spring.StiffnessLow }
        )
    }

    // Rail title
    val mediaLabel = if (rail.mediaType == "tv") "TV Shows" else "Movies"
    val typeLabel = when (rail.railType) {
        "top_rated" -> "Top Rated"
        "recent" -> "Recent"
        else -> "Popular"
    }

    // Auto-load more when near end
    val lastVisible = rowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
    if (lastVisible >= rail.items.size - 4 && rail.hasMore) {
        androidx.compose.runtime.LaunchedEffect(lastVisible) { onLoadMore() }
    }

    Column {
        Text(
            text = "$mediaLabel · $typeLabel",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = textColor.copy(alpha = 0.9f),
            modifier = Modifier.padding(start = 48.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        CompositionLocalProvider(LocalBringIntoViewSpec provides pivotSpec) {
            LazyRow(
                state = rowState,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(start = startPad, end = endPadding)
            ) {
                itemsIndexed(rail.items, key = { i, it -> "${it.tmdbId}_$i" }) { index, item ->
                    Box(modifier = Modifier.onPreviewKeyEvent {
                        if (repeatGate.shouldConsume(it)) return@onPreviewKeyEvent true
                        if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && index == 0) true else false
                    }) {
                        PosterCard(
                            item = item,
                            accentColor = accentColor,
                            modifier = (if (initialFocusRequester != null && index == 0) {
                                Modifier.focusRequester(initialFocusRequester)
                            } else {
                                Modifier
                            }).restorableFocus("$rowKey:$index", focusMemory)
                        ) {
                            val stremioType = if (item.type == "series") "series" else "movie"
                            onItemClick(stremioType, "tmdb:${item.tmdbId}", index)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PosterCard(
    item: TmdbMetaPreview,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val roundCorners = LocalRoundCorners.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val cardShape = if (roundCorners) RoundedCornerShape(if (isFocused) 16.dp else 12.dp) else RectangleShape
    val scale by animateFloatAsState(if (isFocused) 1.05f else 1f, label = "posterScale")

    Box(
        modifier = modifier
            .width(120.dp)
            .height(180.dp)
            .scale(scale)
            .clip(cardShape)
            .background(Color.White.copy(0.06f))
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) accentColor else Color.Transparent,
                shape = cardShape
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .focusable(interactionSource = interactionSource)
    ) {
        if (item.poster != null) {
            AsyncImage(
                model = item.poster,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(cardShape)
            )
        } else {
            Text(
                text = item.name,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(8.dp)
            )
        }
    }
}
