package com.hereliesaz.illumera.ui.watchlist

import com.hereliesaz.illumera.ui.navigation.focus.RestoreFocusOnResume
import com.hereliesaz.illumera.ui.navigation.focus.focusMemoryRoot
import com.hereliesaz.illumera.ui.navigation.focus.rememberFocusMemory
import com.hereliesaz.illumera.ui.navigation.focus.restorableFocusIfTracked
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hereliesaz.illumera.data.model.ProfileEntity
import com.hereliesaz.illumera.data.model.debrid.DebridItem
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.data.queue.QueueItem
import com.hereliesaz.illumera.ui.components.LumeraCard
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
    // A single sorted collection: movie/show type is retained for playback,
    // not used to segregate titles into separate Library poster rows.
    val libraryItems = remember(sortedLibrary) { sortedLibrary.map { it.item } }

    // Main-screen Back is MainRootBackHandler's: every in-screen Back action (Queue reorder
    // mode, item menus) has its own handler composed later, so it runs first.

    // The remembered card lives in WatchlistViewModel (lastFocusedKey, row states), and the
    // rows (and the queue) attach entryRequester to it; this only triggers the restore on
    // resume, replacing the entry's old delay(200) request.
    val focusMemory = rememberFocusMemory()
    RestoreFocusOnResume(memory = focusMemory, fallback = entryRequester)

    var lastFocusedKey by remember { mutableStateOf(viewModel.lastFocusedKey) }
    var lastQueueFocusedKey by remember { mutableStateOf(viewModel.lastQueueFocusedKey) }

    LaunchedEffect(libraryItems) {
        val key = lastFocusedKey ?: return@LaunchedEffect
        // Existing versions stored Series in row 1 and Movies in row 0.
        // Resolve by ID into the new single row so restored remote focus
        // continues to point to the same title after aggregation or sorting.
        val id = key.substringAfter('_', "").substringBeforeLast('_', "")
        val previousRow = key.substringBefore('_').toIntOrNull()
        val expectedType = when (previousRow) {
            1 -> "series" // legacy Series row
            else -> viewModel.lastFocusedMediaType
        }
        val typedIndex = libraryItems.indexOfFirst {
            it.id == id && it.type == expectedType
        }
        val index = if (typedIndex >= 0) typedIndex else libraryItems.indexOfFirst { it.id == id }
        if (index >= 0) {
            val updated = "0_${libraryItems[index].id}_$index"
            if (updated != key) {
                lastFocusedKey = updated
                viewModel.lastFocusedKey = updated
            }
        } else if (libraryItems.isNotEmpty()) {
            val previousIndex = key.substringAfterLast('_').toIntOrNull() ?: 0
            val replacementIndex = previousIndex.coerceIn(0, libraryItems.lastIndex)
            val updated = "0_${libraryItems[replacementIndex].id}_$replacementIndex"
            lastFocusedKey = updated
            viewModel.lastFocusedKey = updated
        } else {
            lastFocusedKey = null
            viewModel.lastFocusedKey = null
        }
    }

    // Do not start a metadata request for every item in a large Library.
    // Resolve blank artwork plus genuinely ambiguous same-title provider IDs;
    // release-year enrichment can then safely collapse true duplicates.
    val ambiguousIds = remember(libraryItems) {
        libraryItems.groupBy { item ->
            "${item.type}:${item.name.lowercase().filter { it.isLetterOrDigit() }}"
        }.values.filter { group ->
            group.map { it.id }.distinct().size > 1
        }.flatten().mapTo(mutableSetOf()) { "${it.type}:${it.id}" }
    }
    fun shouldEnrich(item: MetaItem) =
        item.poster.isNullOrBlank() || "${item.type}:${item.id}" in ambiguousIds

    val isTopNav = currentProfile?.navPosition == "top"
    val configuration = LocalConfiguration.current
    val isCompactWatchlist = configuration.screenWidthDp < 600
    val startPadding = when {
        isTopNav -> 50.dp
        isCompactWatchlist -> 96.dp
        else -> 120.dp
    }
    val topPadding = if (isTopNav) 24.dp else 16.dp

    // Avoid nested vertical scrolling: each poster grid row is a LazyColumn item.
    val gridCardWidth = if (isCompactWatchlist) 122.dp else 140.dp
    val gridGap = 12.dp
    val usableWidth = configuration.screenWidthDp.dp - startPadding - 24.dp
    val gridColumnCount = maxOf(
        1, ((usableWidth.value + gridGap.value) / (gridCardWidth.value + gridGap.value)).toInt()
    )
    val libraryGridRows = remember(libraryItems, gridColumnCount) {
        libraryItems.chunked(gridColumnCount)
    }

    // Enrich only nearby visible posters, not an entire potentially huge library.
    LaunchedEffect(libraryItems, ambiguousIds, gridColumnCount) {
        libraryItems.take(gridColumnCount * 2).filter(::shouldEnrich)
            .forEach(viewModel::resolvePosterIfNeeded)
    }
    LaunchedEffect(viewModel.watchlistColumnState, libraryGridRows, ambiguousIds) {
        snapshotFlow {
            viewModel.watchlistColumnState.layoutInfo.visibleItemsInfo.map { it.index }
        }.collect { visible ->
            visible.flatMap { listIndex ->
                ((listIndex - 2) - 1)..((listIndex - 2) + 1)
            }.distinct().forEach { gridRowIndex ->
                libraryGridRows.getOrNull(gridRowIndex).orEmpty()
                    .filter(::shouldEnrich)
                    .forEach(viewModel::resolvePosterIfNeeded)
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        com.hereliesaz.illumera.ui.components.LocalWatchedIds provides
            (watchedIds + sortedLibrary.filter { it.watched }.map { it.item.id })
    ) {
        LazyColumn(
            state = viewModel.watchlistColumnState,
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
                    val modes = listOf(
                        "recent" to "Recent", "added" to "Recently added",
                        "az" to "A–Z", "za" to "Z–A",
                        "lastwatched" to "Last watched", "watched" to "Watched",
                        "unwatched" to "Not watched", "mostwatched" to "Most watched"
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.padding(top = 5.dp)
                    ) {
                        items(modes, key = { it.first }) { mode ->
                            val selected = sortMode == mode.first
                            val interaction = remember { MutableInteractionSource() }
                            val focused by interaction.collectIsFocusedAsState()
                            Text(
                                mode.second,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = if (selected || focused) MaterialTheme.colorScheme.primary else Color.White,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.27f)
                                        else if (selected) Color.White.copy(alpha = 0.14f)
                                        else Color.White.copy(alpha = 0.05f))
                                    .border(
                                        if (focused) 2.dp else 0.dp,
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable {
                                        sortMode = mode.first
                                        watchlistPrefs.edit().putString(
                                            "sort_${currentProfile?.id}", mode.first
                                        ).apply()
                                    }
                                    .focusable(interactionSource = interaction)
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }
            if (!hasFilteredMedia) {
                item {
                    Text(
                        text = "Your watchlist is empty",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = startPadding, top = 12.dp, bottom = 4.dp)
                    )
                }
            }

            if (libraryGridRows.isNotEmpty()) {
                itemsIndexed(
                    items = libraryGridRows,
                    key = { _, row -> "library_grid_${row.first().type}_${row.first().id}" },
                    contentType = { _, _ -> "library_poster_grid_row" }
                ) { gridRowIndex, row ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(start = startPadding, end = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(gridGap)
                    ) {
                        row.forEachIndexed { columnIndex, media ->
                            val index = gridRowIndex * gridColumnCount + columnIndex
                            val focusKey = "0_${media.id}_$index"
                            Box(Modifier.width(gridCardWidth)) {
                                LumeraCard(
                                    title = media.name,
                                    posterUrl = media.poster,
                                    mediaItem = media,
                                    onClick = { onMovieClick(media) },
                                    isWatched = media.id in watchedIds,
                                    hasNewEpisode = media.hasNewEpisode,
                                    onFocused = {
                                        media.takeIf(::shouldEnrich)?.let(viewModel::resolvePosterIfNeeded)
                                        viewModel.lastFocusedMediaType = media.type
                                        lastFocusedKey = focusKey
                                        viewModel.lastFocusedKey = focusKey
                                        lastQueueFocusedKey = null
                                        viewModel.lastQueueFocusedKey = null
                                    },
                                    modifier = Modifier
                                        .width(gridCardWidth)
                                        .then(
                                            if (lastFocusedKey == focusKey) Modifier.focusRequester(entryRequester)
                                            else Modifier
                                        )
                                        .restorableFocusIfTracked(focusKey, focusMemory)
                                )
                            }
                        }
                    }
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
