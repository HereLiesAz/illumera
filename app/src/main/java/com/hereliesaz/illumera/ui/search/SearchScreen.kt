package com.hereliesaz.illumera.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextInputService
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.data.model.ProfileEntity
import com.hereliesaz.illumera.ui.components.LumeraBackground
import com.hereliesaz.illumera.ui.components.LumeraCard
import com.hereliesaz.illumera.ui.home.ViewMoreCard
import com.hereliesaz.illumera.ui.util.rememberIsTvDevice
import com.hereliesaz.illumera.ui.util.touchClick
import kotlinx.coroutines.delay

private const val PREVIEW_COUNT = 3
private val RESULT_POSTER_WIDTH = 118.dp
private val RESULT_POSTER_WIDTH_TOP_NAV = 116.dp
private val RESULT_POSTER_HEIGHT = 208.dp
private val RESULT_POSTER_HEIGHT_TOP_NAV = 150.dp

@Composable
fun SearchScreen(
    entryRequester: FocusRequester, // Drawer -> Keyboard
    drawerRequester: FocusRequester, // Left -> Drawer
    viewModel: SearchViewModel = hiltViewModel(),
    currentProfile: ProfileEntity?,
    onMovieClick: (MetaItem) -> Unit,
    onViewMore: (title: String, items: List<MetaItem>) -> Unit = { _, _ -> },
    moviesViewMoreRequester: FocusRequester = remember { FocusRequester() },
    seriesViewMoreRequester: FocusRequester = remember { FocusRequester() },
    resultsRequester: FocusRequester = remember { FocusRequester() },
    lastFocusedId: String? = null,
    onFocusedIdChange: (String?) -> Unit = {},
    watchedIds: Set<String> = emptySet()
) {
    val state by viewModel.state.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current

    // Internal Requesters
    val searchInputFocusRequester = remember { FocusRequester() }
    val recentSearchesEntryRequester = remember { FocusRequester() }

    // Track if we're actively using system keyboard
    var keepFocused by remember { mutableStateOf(false) }

    val isTopNav = currentProfile?.navPosition == "top"

    var isContentFocused by remember { mutableStateOf(false) }
    // Guards against double-back race: when returning from details, focus restoration
    // takes ~200ms. Until focus is established, keep BackHandler enabled so a quick
    // second back press doesn't exit the app. Resets on each fresh composition.
    var focusEverSet by remember { mutableStateOf(false) }

    // BACK: Go to Drawer
    BackHandler(enabled = !isTopNav || isContentFocused || !focusEverSet) {
        drawerRequester.requestFocus()
    }

    // Continuously maintain focus when system keyboard is active
    LaunchedEffect(keepFocused) {
        while (keepFocused) {
            delay(100)
            searchInputFocusRequester.requestFocus()
        }
    }

    val isTv = rememberIsTvDevice()
    val topPadding = if (isTopNav) 48.dp else 0.dp
    val startPadding = if (isTopNav) 50.dp else 90.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged {
                isContentFocused = it.hasFocus
                if (it.hasFocus) focusEverSet = true
            }
    ) {
        LumeraBackground {
        if (!isTv) {
            TouchSearchLayout(
                state = state,
                viewModel = viewModel,
                currentProfile = currentProfile,
                onMovieClick = onMovieClick,
                watchedIds = watchedIds
            )
            return@LumeraBackground
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = startPadding, end = 50.dp, bottom = 0.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp)
        ) {

            // --- LEFT PANE: KEYBOARD ---
            Column(
                modifier = Modifier
                    .width(240.dp)
                    .fillMaxHeight()
                    .padding(top = 20.dp + topPadding),
                verticalArrangement = Arrangement.Top
            ) {
                TvKeyboard(
                    onKeyPress = { char ->
                        viewModel.appendCharacter(char)
                        keepFocused = false
                    },
                    onBackspace = {
                        viewModel.removeCharacter()
                        keepFocused = false
                    },
                    onSpace = {
                        viewModel.appendCharacter(" ")
                        keepFocused = false
                    },
                    onOpenSystemKeyboard = {
                        keepFocused = true
                        searchInputFocusRequester.requestFocus()
                        try { keyboardController?.show() } catch (_: Exception) { }
                    },
                    entryRequester = entryRequester,
                    drawerRequester = drawerRequester,
                    isTopNav = isTopNav,
                    hasResults = state.results.isNotEmpty() || state.recentSearches.isNotEmpty(),
                    contentEntryRequester = if (state.query.isEmpty() && state.recentSearches.isNotEmpty()) {
                        recentSearchesEntryRequester
                    } else null
                )
            }

            // --- RIGHT PANE: RESULTS ---
            val searchBarHeight = 48.dp
            val density = LocalDensity.current

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(top = 20.dp + topPadding)
            ) {
                // CONTENT AREA (behind header)
                if (state.isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else if (state.results.isEmpty() && state.query.isNotEmpty() && state.searchFailed) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Search failed — check your connection", color = Color.White.copy(0.5f))
                    }
                } else if (state.results.isEmpty() && state.query.isNotEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No results for \"${state.query}\"", color = Color.White.copy(0.5f))
                    }
                } else if (state.query.isEmpty()) {
                    if (state.recentSearches.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Search for movies, series, and more", color = Color.White.copy(0.3f))
                        }
                    } else {
                        RecentSearchesList(
                            queries = state.recentSearches,
                            onSelect = { viewModel.selectRecentSearch(it) },
                            entryRequester = recentSearchesEntryRequester,
                            keyboardRequester = entryRequester,
                            headerHeight = searchBarHeight,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    val posterWidth = if (isTopNav) RESULT_POSTER_WIDTH_TOP_NAV else RESULT_POSTER_WIDTH
                    val posterHeight = if (isTopNav) RESULT_POSTER_HEIGHT_TOP_NAV else RESULT_POSTER_HEIGHT
                    // Results: static layout, two category rows that fill the space
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = searchBarHeight + 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // ═══════════════════════════════════════
                        // MOVIES SECTION
                        // ═══════════════════════════════════════
                        if (state.movies.isNotEmpty()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Movies",
                                    style = TvMaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = Color.White.copy(0.9f),
                                    modifier = Modifier.padding(bottom = if (isTopNav) 12.dp else 4.dp)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = if (isTopNav) 6.dp else 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    val blockUp = Modifier.onPreviewKeyEvent { event ->
                                        event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp
                                    }
                                    val moviePreview = state.movies.take(PREVIEW_COUNT)
                                    moviePreview.forEachIndexed { index, movie ->
                                        val isFirstItem = index == 0
                                        val isRemembered = movie.id == lastFocusedId
                                        val shouldAttachRequester = isRemembered || (lastFocusedId == null && isFirstItem)

                                        LumeraCard(
                                            title = movie.name,
                                            posterUrl = movie.poster,
                                            onClick = { onMovieClick(movie) },
                                            mediaItem = movie,
                                            isWatched = movie.id in watchedIds,
                                            modifier = Modifier
                                                .width(posterWidth)
                                                .height(posterHeight)
                                                .then(blockUp)
                                                .onFocusChanged { if (it.isFocused) onFocusedIdChange(movie.id) }
                                                .then(if (shouldAttachRequester) Modifier.focusRequester(resultsRequester) else Modifier)
                                        )
                                    }

                                    // ViewMore card for Movies
                                    ViewMoreCard(
                                        onClick = { onViewMore("Movies", state.movies) },
                                        modifier = Modifier
                                            .width(posterWidth)
                                            .height(posterHeight)
                                            .then(blockUp)
                                            .focusRequester(moviesViewMoreRequester)
                                            .onFocusChanged { if (it.isFocused) onFocusedIdChange("viewmore_movies") }
                                    )
                                }
                            }
                        }

                        // ═══════════════════════════════════════
                        // SERIES SECTION
                        // ═══════════════════════════════════════
                        if (state.series.isNotEmpty()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Series",
                                    style = TvMaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = Color.White.copy(0.9f),
                                    modifier = Modifier.padding(bottom = if (isTopNav) 12.dp else 4.dp)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = if (isTopNav) 6.dp else 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    val seriesPreview = state.series.take(PREVIEW_COUNT)
                                    seriesPreview.forEachIndexed { index, series ->
                                        val isRemembered = series.id == lastFocusedId

                                        LumeraCard(
                                            title = series.name,
                                            posterUrl = series.poster,
                                            onClick = { onMovieClick(series) },
                                            mediaItem = series,
                                            modifier = Modifier
                                                .width(posterWidth)
                                                .height(posterHeight)
                                                .onFocusChanged { if (it.isFocused) onFocusedIdChange(series.id) }
                                                .then(if (isRemembered) Modifier.focusRequester(resultsRequester) else Modifier)
                                        )
                                    }

                                    // ViewMore card for Series
                                    ViewMoreCard(
                                        onClick = { onViewMore("Series", state.series) },
                                        modifier = Modifier
                                            .width(posterWidth)
                                            .height(posterHeight)
                                            .focusRequester(seriesViewMoreRequester)
                                            .onFocusChanged { if (it.isFocused) onFocusedIdChange("viewmore_series") }
                                    )
                                }
                            }
                        }
                    }
                }

                // ══════════════════════════════════════════════════════════════
                // FIXED SEARCH BAR HEADER - Overlays content with gradient fade
                // ══════════════════════════════════════════════════════════════
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .zIndex(10f)
                        .layout { measurable, constraints ->
                            val extraPx = 20.dp.roundToPx()
                            val placeable = measurable.measure(
                                constraints.copy(maxWidth = constraints.maxWidth + extraPx * 2)
                            )
                            layout(constraints.maxWidth, placeable.height) {
                                placeable.place(-extraPx, 0)
                            }
                        }
                ) {
                    // Gradient background
                    val backgroundColor = MaterialTheme.colorScheme.background
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(searchBarHeight + 32.dp)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        backgroundColor,
                                        backgroundColor.copy(alpha = 0.95f),
                                        backgroundColor.copy(alpha = 0.7f),
                                        backgroundColor.copy(alpha = 0.3f),
                                        Color.Transparent
                                    ),
                                    startY = 0f,
                                    endY = with(density) { (searchBarHeight + 32.dp).toPx() }
                                )
                            )
                    )

                    // Search bar content
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(searchBarHeight)
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))

                        // Suppress automatic IME connection — Android TV may not have
                        // a system keyboard, which can crash the app. The custom
                        // on-screen keyboard handles input; the system keyboard is
                        // only shown explicitly via the keyboard button.
                        CompositionLocalProvider(LocalTextInputService provides null) {
                            BasicTextField(
                                value = state.query,
                                onValueChange = { viewModel.onQueryChange(it) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusProperties { canFocus = keepFocused }
                                    .focusRequester(searchInputFocusRequester)
                                    .onFocusChanged { if (!it.isFocused) keepFocused = false }
                                    .onPreviewKeyEvent {
                                        if (it.type == KeyEventType.KeyDown) {
                                            when (it.key) {
                                                Key.DirectionLeft -> {
                                                    entryRequester.requestFocus()
                                                    true
                                                }
                                                Key.DirectionUp -> {
                                                    if (isTopNav) {
                                                        drawerRequester.requestFocus()
                                                    }
                                                    true
                                                }
                                                else -> false
                                            }
                                        } else false
                                    },
                                textStyle = MaterialTheme.typography.headlineMedium.copy(
                                    color = Color.White,
                                    fontWeight = FontWeight.Normal
                                ),
                                cursorBrush = SolidColor(Color.White),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(
                                    onSearch = {
                                        keepFocused = false
                                        keyboardController?.hide()
                                    }
                                ),
                                decorationBox = { innerTextField ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (state.query.isEmpty()) {
                                            Text(
                                                text = "Type to search...",
                                                style = MaterialTheme.typography.headlineMedium.copy(
                                                    fontSize = 20.sp,
                                                    fontWeight = FontWeight.Normal
                                                ),
                                                color = Color.White.copy(alpha = 0.5f)
                                            )
                                        }
                                        innerTextField()
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

/**
 * Touch layout for phones/tablets. The TV layout's fixed 240dp keyboard column plus its
 * paddings only fits on TV-sized canvases — on a phone it leaves no room for results. Touch
 * devices already have a system keyboard, so this drops the on-screen keyboard column
 * entirely and stacks search bar / results vertically instead.
 */
@Composable
private fun TouchSearchLayout(
    state: SearchViewModel.SearchState,
    viewModel: SearchViewModel,
    currentProfile: ProfileEntity?,
    onMovieClick: (MetaItem) -> Unit,
    watchedIds: Set<String>
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val isTopNav = currentProfile?.navPosition == "top"
    val topPadding = if (isTopNav) 48.dp else 16.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 20.dp, end = 20.dp, top = topPadding)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            BasicTextField(
                value = state.query,
                onValueChange = { viewModel.onQueryChange(it) },
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.headlineSmall.copy(
                    color = Color.White,
                    fontWeight = FontWeight.Normal
                ),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { keyboardController?.hide() }
                ),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (state.query.isEmpty()) {
                            Text(
                                text = "Type to search...",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                state.query.isNotEmpty() && state.results.isEmpty() && state.searchFailed -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Search failed — check your connection", color = Color.White.copy(0.5f))
                    }
                }
                state.query.isNotEmpty() && state.results.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No results for \"${state.query}\"", color = Color.White.copy(0.5f))
                    }
                }
                state.query.isNotEmpty() -> {
                    TouchResultsGrid(
                        items = state.movies + state.series,
                        watchedIds = watchedIds,
                        onItemClick = onMovieClick
                    )
                }
                state.recentSearches.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Search for movies, series, and more", color = Color.White.copy(0.3f))
                    }
                }
                else -> {
                    RecentSearchesList(
                        queries = state.recentSearches,
                        onSelect = { viewModel.selectRecentSearch(it) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun TouchResultsGrid(
    items: List<MetaItem>,
    watchedIds: Set<String>,
    onItemClick: (MetaItem) -> Unit,
    onLoadMore: (() -> Unit)? = null
) {
    val gridState = rememberLazyGridState()
    val lastVisibleIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
    LaunchedEffect(lastVisibleIndex, items.size) {
        if (onLoadMore != null && items.isNotEmpty() && lastVisibleIndex >= items.size - 12) {
            onLoadMore()
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        state = gridState,
        contentPadding = PaddingValues(bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items, key = { it.id }) { item ->
            LumeraCard(
                title = item.name,
                posterUrl = item.poster,
                onClick = { onItemClick(item) },
                mediaItem = item,
                isWatched = item.id in watchedIds,
                modifier = Modifier.aspectRatio(2f / 3f)
            )
        }
    }
}

@Composable
private fun RecentSearchesList(
    queries: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    headerHeight: Dp = 0.dp,
    entryRequester: FocusRequester? = null,
    keyboardRequester: FocusRequester? = null
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = headerHeight + 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            Text(
                text = "Recent Searches",
                style = TvMaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White.copy(0.9f),
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        itemsIndexed(queries, key = { _, query -> query }) { index, query ->
            val leftInterceptor = if (keyboardRequester != null) {
                Modifier.onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                        keyboardRequester.requestFocus()
                        true
                    } else false
                }
            } else Modifier

            RecentSearchRow(
                query = query,
                onClick = { onSelect(query) },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(leftInterceptor)
                    .then(if (index == 0 && entryRequester != null) Modifier.focusRequester(entryRequester) else Modifier)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RecentSearchRow(
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier.touchClick(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(0.05f),
            focusedContainerColor = Color.White.copy(0.15f)
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Icon(
                Icons.Default.History,
                contentDescription = null,
                tint = Color.White.copy(if (isFocused) 0.8f else 0.5f),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = query,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isFocused) Color.White else Color.White.copy(0.8f)
            )
        }
    }
}

@Composable
fun TvKeyboard(
    onKeyPress: (String) -> Unit,
    onBackspace: () -> Unit,
    onSpace: () -> Unit,
    onOpenSystemKeyboard: () -> Unit,
    entryRequester: FocusRequester,
    drawerRequester: FocusRequester,
    isTopNav: Boolean,
    hasResults: Boolean,
    contentEntryRequester: FocusRequester? = null
) {
    val keys = remember {
        listOf(
            "a", "b", "c", "d", "e", "f",
            "g", "h", "i", "j", "k", "l",
            "m", "n", "o", "p", "q", "r",
            "s", "t", "u", "v", "w", "x",
            "y", "z", "1", "2", "3", "4",
            "5", "6", "7", "8", "9", "0"
        )
    }

    // MEMORY STATE: KEYBOARD
    var lastFocusedIndex by remember { mutableIntStateOf(0) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = 11.dp, start = 6.dp, end = 6.dp, bottom = 10.dp)
    ) {
        itemsIndexed(keys) { index, key ->
            val isLeftEdge = index % 6 == 0
            val isRightEdge = (index + 1) % 6 == 0
            val isTopRow = index < 6

            // MAGNET LOGIC: KEYBOARD
            val isRemembered = index == lastFocusedIndex

            KeyButton(
                text = key,
                onClick = { onKeyPress(key) },
                // Attach 'entryRequester' ONLY to the remembered item
                modifier = Modifier
                    .onFocusChanged { if (it.isFocused) lastFocusedIndex = index }
                    .then(if (isRemembered) Modifier.focusRequester(entryRequester) else Modifier)
                    .onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown) {
                            when {
                                // Left arrow on left edge -> go to drawer/topnav
                                isLeftEdge && it.key == Key.DirectionLeft -> {
                                    if (!isTopNav) drawerRequester.requestFocus()
                                    true
                                }
                                // Right arrow on right edge -> focus first visible poster or block
                                isRightEdge && it.key == Key.DirectionRight -> {
                                    if (!hasResults) return@onPreviewKeyEvent true
                                    if (contentEntryRequester != null) {
                                        contentEntryRequester.requestFocus()
                                        return@onPreviewKeyEvent true
                                    }
                                    false
                                }
                                // Up arrow on top row -> go to drawer/topnav
                                isTopRow && it.key == Key.DirectionUp -> {
                                    if (isTopNav) {
                                        drawerRequester.requestFocus()
                                    }
                                    true // Always consume to prevent default focus search finding sidebar
                                }
                                else -> false
                            }
                        } else false
                    }
            )
        }

        val spaceIndex = keys.size
        item(span = { GridItemSpan(2) }) {
            val isRemembered = spaceIndex == lastFocusedIndex
            KeyButton(
                icon = Icons.Default.SpaceBar,
                onClick = onSpace,
                label = "Space",
                modifier = Modifier
                    .onFocusChanged { if (it.isFocused) lastFocusedIndex = spaceIndex }
                    .then(if (isRemembered) Modifier.focusRequester(entryRequester) else Modifier)
                    .onPreviewKeyEvent {
                        if (it.key == Key.DirectionLeft && it.type == KeyEventType.KeyDown) {
                            if (!isTopNav) drawerRequester.requestFocus()
                            true
                        } else false
                    }
            )
        }

        val backIndex = keys.size + 1
        item(span = { GridItemSpan(2) }) {
            val isRemembered = backIndex == lastFocusedIndex
            KeyButton(
                icon = Icons.AutoMirrored.Filled.Backspace,
                onClick = onBackspace,
                label = "Back",
                modifier = Modifier
                    .onFocusChanged { if (it.isFocused) lastFocusedIndex = backIndex }
                    .then(if (isRemembered) Modifier.focusRequester(entryRequester) else Modifier)
            )
        }

        val hideIndex = keys.size + 2
        item(span = { GridItemSpan(2) }) {
            val isRemembered = hideIndex == lastFocusedIndex
            KeyButton(
                icon = Icons.Default.Keyboard,
                onClick = onOpenSystemKeyboard,
                label = "Keyboard",
                modifier = Modifier
                    .onFocusChanged { if (it.isFocused) lastFocusedIndex = hideIndex }
                    .then(if (isRemembered) Modifier.focusRequester(entryRequester) else Modifier)
                    .onPreviewKeyEvent {
                        if (it.key == Key.DirectionRight && it.type == KeyEventType.KeyDown) {
                            if (!hasResults) return@onPreviewKeyEvent true
                            if (contentEntryRequester != null) {
                                contentEntryRequester.requestFocus()
                                return@onPreviewKeyEvent true
                            }
                            false
                        } else false
                    }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun KeyButton(
    text: String? = null,
    icon: ImageVector? = null,
    label: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val dynamicContentColor = if (isFocused) Color.Black else Color.White

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier.height(35.dp).fillMaxWidth().touchClick(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(0.1f),
            focusedContainerColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (text != null) {
                Text(
                    text = text.uppercase(),
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = dynamicContentColor
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(18.dp),
                    tint = dynamicContentColor
                )
            }
        }
    }
}
