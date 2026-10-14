package com.hereliesaz.illumera.ui.watchlist

import com.hereliesaz.illumera.ui.navigation.focus.RestoreFocusOnResume
import com.hereliesaz.illumera.ui.navigation.focus.focusMemoryRoot
import com.hereliesaz.illumera.ui.navigation.focus.rememberFocusMemory
import com.hereliesaz.illumera.ui.home.resolveRowRestoreIndex
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hereliesaz.illumera.data.model.ProfileEntity
import com.hereliesaz.illumera.data.model.debrid.DebridItem
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.data.queue.QueueItem
import com.hereliesaz.illumera.ui.home.DpadRepeatGate
import com.hereliesaz.illumera.ui.home.InfiniteLoopRow
import com.hereliesaz.illumera.ui.home.UpKeyDebouncer
import com.hereliesaz.illumera.ui.queue.QueueSection

@Composable
fun WatchlistScreen(
    currentProfile: ProfileEntity?,
    entryRequester: FocusRequester,
    drawerRequester: FocusRequester,
    onMovieClick: (MetaItem) -> Unit,
    watchedIds: Set<String> = emptySet(),
    viewModel: WatchlistViewModel = hiltViewModel()
) {

    val library by viewModel.libraryEntries.collectAsState()
    val context = LocalContext.current
    val watchlistPrefs = remember { context.getSharedPreferences("illumera_watchlist_ui", 0) }
    val remoteCounts = remember { context.getSharedPreferences("stremio_library_sync", 0) }
    var sortMode by remember(currentProfile?.id) {
        mutableStateOf(watchlistPrefs.getString("sort_${currentProfile?.id}", "recent") ?: "recent")
    }
    var mediaTypeFilter by remember { mutableStateOf("all") }
    val sortedLibrary = remember(library, sortMode, currentProfile?.id) {
        val recent = compareByDescending<WatchlistViewModel.LibraryEntry> {
            maxOf(it.addedAt, it.lastWatched)
        }
        when (sortMode) {
            "added" -> library.sortedByDescending { it.addedAt }
            "az" -> library.sortedBy { it.item.name.lowercase() }
            "za" -> library.sortedByDescending { it.item.name.lowercase() }
            "lastwatched" -> library.sortedByDescending { it.lastWatched }
            "watched" -> library.sortedWith(compareByDescending<WatchlistViewModel.LibraryEntry> { it.watched }.then(recent))
            "unwatched" -> library.sortedWith(compareBy<WatchlistViewModel.LibraryEntry> { it.watched }.then(recent))
            "mostwatched" -> library.sortedWith(
                compareByDescending<WatchlistViewModel.LibraryEntry> {
                    remoteCounts.getInt("watch_count_${currentProfile?.id}_${it.item.id}", it.timesWatched)
                }.then(recent)
            )
            else -> library.sortedWith(recent)
        }
    }
    val movies = remember(sortedLibrary) { sortedLibrary.filter { it.item.type == "movie" }.map { it.item } }
    val series = remember(sortedLibrary) { sortedLibrary.filter { it.item.type == "series" }.map { it.item } }

    // Main-screen Back is MainRootBackHandler's: every in-screen Back action (Queue reorder
    // mode, item menus) has its own handler composed later, so it runs first.

    // The remembered card lives in WatchlistViewModel (lastFocusedKey, row states), and the
    // rows (and the queue) attach entryRequester to it; this only triggers the restore on
    // resume, replacing the entry's old delay(200) request.
    val focusMemory = rememberFocusMemory()
    RestoreFocusOnResume(memory = focusMemory, fallback = entryRequester)

    val upKeyDebouncer = remember { UpKeyDebouncer() }
    val dpadRepeatGate = remember { DpadRepeatGate() }
    var lastFocusedKey by remember { mutableStateOf(viewModel.lastFocusedKey) }
    var lastQueueFocusedKey by remember { mutableStateOf(viewModel.lastQueueFocusedKey) }

    LaunchedEffect(movies, series) {
        val key = lastFocusedKey ?: return@LaunchedEffect
        val parts = key.split("_")
        val rowIndex = parts.getOrNull(0)?.toIntOrNull()
        val itemId = parts.getOrNull(1)
        if (itemId == null) return@LaunchedEffect

        val rowItems = if (rowIndex == 0) movies else series
        val stillExists = rowItems.any { it.id == itemId }
        if (!stillExists && rowItems.isNotEmpty()) {
            // Removed (e.g. taken off the watchlist from its details page): focus its nearest
            // remaining neighbour at the same position, not the end or start of the row.
            val fallbackIndex = resolveRowRestoreIndex(key, rowIndex ?: 0, rowItems.map { it.id })
                ?: rowItems.lastIndex
            val fallbackItem = rowItems[fallbackIndex]
            lastFocusedKey = "${rowIndex}_${fallbackItem.id}_$fallbackIndex"
            viewModel.lastFocusedKey = lastFocusedKey
        } else if (!stillExists && rowItems.isEmpty()) {
            val otherItems = if (rowIndex == 0) series else movies
            if (otherItems.isNotEmpty()) {
                val otherRow = if (rowIndex == 0) 1 else 0
                lastFocusedKey = "${otherRow}_${otherItems.first().id}_0"
                viewModel.lastFocusedKey = lastFocusedKey
            } else {
                lastFocusedKey = null
                viewModel.lastFocusedKey = null
            }
        }
    }

    LaunchedEffect(movies) { movies.forEach { viewModel.resolvePosterIfNeeded(it) } }
    LaunchedEffect(series) { series.forEach { viewModel.resolvePosterIfNeeded(it) } }

    val isTopNav = currentProfile?.navPosition == "top"
    val isCompactWatchlist = LocalConfiguration.current.screenWidthDp < 600
    val startPadding = when {
        isTopNav -> 50.dp
        isCompactWatchlist -> 96.dp
        else -> 120.dp
    }
    val topPadding = if (isTopNav) 24.dp else 16.dp
    val hasWatchlistMedia = movies.isNotEmpty() || series.isNotEmpty()
    val hasFilteredMedia = when (mediaTypeFilter) {
        "movie" -> movies.isNotEmpty()
        "series" -> series.isNotEmpty()
        else -> hasWatchlistMedia
    }

    androidx.compose.runtime.CompositionLocalProvider(
        com.hereliesaz.illumera.ui.components.LocalWatchedIds provides watchedIds
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().focusMemoryRoot(focusMemory),
            contentPadding = PaddingValues(top = topPadding + 20.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                QueueSection(
                    entryRequester = entryRequester,
                    startPadding = startPadding,
                    requestEntryFocus = lastFocusedKey == null && lastQueueFocusedKey == null,
                    focusedQueueKey = lastQueueFocusedKey,
                    restoreEntryFocusWhenFocusedKeyMissing = true,
                    onQueueFocused = { key ->
                        lastQueueFocusedKey = key
                        viewModel.lastQueueFocusedKey = key
                        if (key != null) {
                            lastFocusedKey = null
                            viewModel.lastFocusedKey = null
                        }
                    },
                    onOpenItem = { queueItem: QueueItem ->
                        onMovieClick(queueItem.toMetaItem())
                    }
                )
            }
            item(key = "library_sort") {
                Column(modifier = Modifier.padding(start = startPadding, top = 12.dp, end = 12.dp)) {
                    Text("Library", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Text("Saved movies and series, including synchronized Stremio history",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.65f))
                    val modes = listOf(
                        "recent" to "Recent", "added" to "Recently added",
                        "az" to "A–Z", "za" to "Z–A",
                        "lastwatched" to "Last watched", "watched" to "Watched",
                        "unwatched" to "Not watched", "mostwatched" to "Most watched"
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 10.dp)
                    ) {
                        items(listOf("all" to "All", "movie" to "Movies", "series" to "Series")) { type ->
                            val selected = mediaTypeFilter == type.first
                            val interaction = remember { MutableInteractionSource() }
                            val focused by interaction.collectIsFocusedAsState()
                            Text(
                                type.second,
                                color = if (selected || focused) MaterialTheme.colorScheme.primary else Color.White,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = if (selected || focused) 0.18f else 0.05f))
                                    .border(if (focused) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                                    .clickable { mediaTypeFilter = type.first }
                                    .focusable(interactionSource = interaction)
                                    .padding(horizontal = 14.dp, vertical = 11.dp)
                            )
                        }
                    }
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        items(modes, key = { it.first }) { mode ->
                            val selected = sortMode == mode.first
                            val interaction = remember { MutableInteractionSource() }
                            val focused by interaction.collectIsFocusedAsState()
                            Text(
                                mode.second,
                                color = if (selected || focused) MaterialTheme.colorScheme.primary else Color.White,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) Color.White.copy(alpha = 0.14f)
                                        else Color.White.copy(alpha = 0.05f))
                                    .clickable {
                                        sortMode = mode.first
                                        watchlistPrefs.edit().putString(
                                            "sort_${currentProfile?.id}", mode.first
                                        ).apply()
                                    }
                                    .focusable(interactionSource = interaction)
                                    .padding(horizontal = 14.dp, vertical = 11.dp)
                            )
                        }
                    }
                }
            }
            if (!hasFilteredMedia) {
                item {
                    Text(
                        text = if (hasWatchlistMedia) "No ${if (mediaTypeFilter == "movie") "movies" else "series"} in your Library" else "Your watchlist is empty",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = startPadding, top = 12.dp, bottom = 4.dp)
                    )
                }
            }

            if (movies.isNotEmpty() && mediaTypeFilter != "series") {
                item {
                    InfiniteLoopRow(
                        startPadding = startPadding,
                        isTopNav = isTopNav,
                        rowIndex = 0,
                        title = "Movies",
                        items = movies,
                        onMovieClick = onMovieClick,
                        onViewMore = {},
                        onFocused = { _: MetaItem?, key: String ->
                            lastFocusedKey = key
                            viewModel.lastFocusedKey = key
                            lastQueueFocusedKey = null
                            viewModel.lastQueueFocusedKey = null
                        },
                        entryRequester = entryRequester,
                        drawerRequester = drawerRequester,
                        locallyFocusedItemId = if (lastFocusedKey?.startsWith("0_") == true) lastFocusedKey else null,
                        isGlobalFocusPresent = lastFocusedKey != null,
                        isFirstRow = false, // Queue now precedes Library
                        isInfiniteLoopEnabled = false,
                        upKeyDebouncer = upKeyDebouncer,
                        repeatGate = dpadRepeatGate,
                        externalListState = viewModel.movieRowState
                    )
                }
            }

            if (series.isNotEmpty() && mediaTypeFilter != "movie") {
                item {
                    InfiniteLoopRow(
                        startPadding = startPadding,
                        isTopNav = isTopNav,
                        rowIndex = 1,
                        title = "Series",
                        items = series,
                        onMovieClick = onMovieClick,
                        onViewMore = {},
                        onFocused = { _: MetaItem?, key: String ->
                            lastFocusedKey = key
                            viewModel.lastFocusedKey = key
                            lastQueueFocusedKey = null
                            viewModel.lastQueueFocusedKey = null
                        },
                        entryRequester = entryRequester,
                        drawerRequester = drawerRequester,
                        locallyFocusedItemId = if (lastFocusedKey?.startsWith("1_") == true) lastFocusedKey else null,
                        isGlobalFocusPresent = lastFocusedKey != null,
                        isFirstRow = false, // Queue now precedes Library
                        isInfiniteLoopEnabled = false,
                        upKeyDebouncer = upKeyDebouncer,
                        repeatGate = dpadRepeatGate,
                        externalListState = viewModel.seriesRowState
                    )
                }
            }


        }
    }
}

@Composable
internal fun DebridLibrarySection(
    provider: com.hereliesaz.illumera.data.model.debrid.DebridProvider?,
    state: DebridLibraryUiState,
    startPadding: androidx.compose.ui.unit.Dp,
    onPlay: (DebridItem) -> Unit,
    onDelete: (DebridItem) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = startPadding, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Cloud Storage (${provider?.displayName ?: ""})",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            if (state.isLoading) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(
                    modifier = Modifier.width(16.dp).height(16.dp),
                    strokeWidth = 2.dp
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (state.items.isEmpty() && !state.isLoading) {
            Text(
                text = "No items in your cloud storage",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = startPadding)
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(start = startPadding, end = 24.dp)
            ) {
                items(state.items, key = { it.id }) { item ->
                    DebridItemCard(
                        item = item,
                        isResolving = state.resolvingItemId == item.id,
                        onPlay = { onPlay(item) },
                        onDelete = { onDelete(item) }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DebridItemCard(
    item: DebridItem,
    isResolving: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val accentColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .width(180.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(0.06f))
            .border(
                if (isFocused) 2.dp else 0.dp,
                if (isFocused) accentColor else Color.Transparent,
                RoundedCornerShape(10.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null) {
                if (!isResolving) onPlay()
            }
            .focusable(interactionSource = interactionSource)
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isResolving) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(14.dp).height(14.dp),
                        strokeWidth = 2.dp,
                        color = if (isFocused) accentColor else Color.White.copy(0.7f)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = if (isFocused) accentColor else Color.White.copy(0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isResolving) "Resolving..." else "Play",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isFocused) accentColor else Color.White.copy(0.7f)
                )
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onDelete() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Remove from cloud storage",
                    tint = Color.White.copy(0.5f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = item.name,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        if (item.sizeBytes != null || item.status != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = listOfNotNull(
                    item.sizeBytes?.let { formatBytes(it) },
                    item.status
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return "%.1f %s".format(value, units[unitIndex])
}
