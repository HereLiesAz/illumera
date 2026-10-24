package com.hereliesaz.illumera.ui.watchlist

import com.hereliesaz.illumera.ui.navigation.openNavDrawer
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.hereliesaz.illumera.data.model.ProfileEntity
import com.hereliesaz.illumera.data.model.debrid.DebridItem
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.data.queue.QueueItem
import com.hereliesaz.illumera.ui.components.LumeraCard
import com.hereliesaz.illumera.ui.queue.QueueSection

/**
 * Number of fixed-width poster cards that fit in the available Watchlist viewport.
 * Keep the grid's non-scrolling rows within the parent LazyColumn's width.
 */
internal fun watchlistGridColumnCount(
    screenWidthDp: Int,
    leftPaddingDp: Int,
    posterWidthDp: Int,
    gapDp: Int = 12,
    rightPaddingDp: Int = 24
): Int = maxOf(
    1, (screenWidthDp - leftPaddingDp - rightPaddingDp + gapDp) /
        (posterWidthDp + gapDp)
)

/** One stable lazy-grid identity per media item; no row or sort index. */
internal fun watchlistGridMediaKey(item: MetaItem): String =
    "library_${item.type}_${item.id}"

@Composable
fun WatchlistScreen(
    currentProfile: ProfileEntity?,
    entryRequester: FocusRequester,
    drawerRequester: FocusRequester,
    onMovieClick: (MetaItem) -> Unit,
    watchedIds: Set<String> = emptySet(),
    onPlayResolvedStream: (id: String, url: String, title: String) -> Unit = { _, _, _ -> },
    viewModel: WatchlistViewModel = hiltViewModel(),
    debridViewModel: DebridLibraryViewModel = hiltViewModel()
) {
    val debridProviders by debridViewModel.connectedProviders.collectAsStateWithLifecycle()
    val debridState by debridViewModel.uiState.collectAsStateWithLifecycle()
    val latestOnPlay by androidx.compose.runtime.rememberUpdatedState(onPlayResolvedStream)
    val toastContext = LocalContext.current
    DisposableEffect(debridViewModel) {
        onDispose { debridViewModel.onScreenDisposed() }
    }
    LaunchedEffect(debridViewModel) {
        debridViewModel.events.collect { event ->
            when (event) {
                is DebridLibraryEvent.StreamResolved ->
                    latestOnPlay(event.id, event.url, event.name)
                is DebridLibraryEvent.Error ->
                    Toast.makeText(toastContext, event.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    val library by viewModel.libraryEntries.collectAsStateWithLifecycle()
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
    // Shared across all grid cells: a held Left key must stop at column zero,
    // not send the next auto-repeat immediately into the navigation drawer.
    var lastGridLeftPressAt by remember { mutableStateOf(0L) }

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

    // One actual lazy grid gives every poster its own stable item key, even
    // when reordering moves a card across row boundaries.
    val gridCardWidth = if (isCompactWatchlist) 122.dp else 140.dp
    val gridGap = 12.dp
    val gridColumnCount = watchlistGridColumnCount(
        configuration.screenWidthDp,
        startPadding.value.toInt(),
        gridCardWidth.value.toInt(),
        gridGap.value.toInt()
    )
    // Enrich only nearby visible posters, not an entire potentially huge library.
    LaunchedEffect(libraryItems, ambiguousIds, gridColumnCount) {
        libraryItems.take(gridColumnCount * 2).filter(::shouldEnrich)
            .forEach(viewModel::resolvePosterIfNeeded)
    }
    LaunchedEffect(viewModel.watchlistGridState, libraryItems, ambiguousIds) {
        snapshotFlow {
            viewModel.watchlistGridState.layoutInfo.visibleItemsInfo.map { it.index }
        }.collect { visible ->
            visible.flatMap { listIndex -> (listIndex - 3)..(listIndex - 1) }
                .distinct()
                .mapNotNull { libraryItems.getOrNull(it) }
                .filter(::shouldEnrich)
                .forEach(viewModel::resolvePosterIfNeeded)
        }
    }

    val libraryWatchedIds = remember(watchedIds, sortedLibrary) {
        watchedIds + sortedLibrary.filter { it.watched }.map { it.item.id }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        com.hereliesaz.illumera.ui.components.LocalWatchedIds provides libraryWatchedIds
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(gridColumnCount),
            state = viewModel.watchlistGridState,
            modifier = Modifier.fillMaxSize().focusMemoryRoot(focusMemory),
            contentPadding = PaddingValues(
                start = startPadding, end = 24.dp,
                top = topPadding + 20.dp, bottom = 48.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(gridGap),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "watchlist_queue", span = { GridItemSpan(maxLineSpan) }) {
                QueueSection(
                    entryRequester = entryRequester,
                    startPadding = 0.dp,
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
            if (debridProviders.isNotEmpty()) {
                item(key = "watchlist_cloud_storage", span = { GridItemSpan(maxLineSpan) }) {
                    DebridLibrarySection(
                        providers = debridProviders,
                        state = debridState,
                        startPadding = 0.dp,
                        onPlay = { debridViewModel.play(it) },
                        onDelete = { debridViewModel.delete(it) }
                    )
                }
            }
            item(key = "library_sort", span = { GridItemSpan(maxLineSpan) }) {
                Column(modifier = Modifier.padding(top = 12.dp, end = 12.dp)) {
                    Text("Library", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
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
                                color = if (selected || focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.27f)
                                        else if (selected) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.14f)
                                        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f))
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
            if (libraryItems.isEmpty()) {
                item(key = "empty_library", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "Your watchlist is empty",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                }
            }

            itemsIndexed(
                items = libraryItems,
                key = { _, media -> watchlistGridMediaKey(media) },
                contentType = { _, _ -> "library_poster" }
            ) { index, media ->
                val columnIndex = index % gridColumnCount
                val focusKey = "0_${media.id}_$index"
                Box(
                                modifier = Modifier.width(gridCardWidth)
                                    .onPreviewKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown &&
                                            event.key == Key.DirectionLeft
                                        ) {
                                            val now = System.currentTimeMillis()
                                            val deliberate = now - lastGridLeftPressAt > 350L
                                            lastGridLeftPressAt = now
                                            if (columnIndex == 0) {
                                                if (!isTopNav && deliberate) {
                                                    drawerRequester.openNavDrawer()
                                                }
                                                true
                                            } else false
                                        } else if (
                                            event.type == KeyEventType.KeyDown &&
                                            event.key == Key.DirectionRight &&
                                            isTopNav && index == libraryItems.lastIndex
                                        ) {
                                            // No next grid item: remain in Library instead
                                            // of jumping diagonally to top navigation.
                                            true
                                        } else false
                                    }
                            ) {
                                LumeraCard(
                                    title = media.name,
                                    posterUrl = media.poster,
                                    mediaItem = media,
                                    cardWidth = gridCardWidth,
                                    onClick = { onMovieClick(media) },
                                    isWatched = media.id in libraryWatchedIds,
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

@Composable
internal fun DebridLibrarySection(
    providers: List<com.hereliesaz.illumera.data.model.debrid.DebridProvider>,
    state: DebridLibraryUiState,
    startPadding: androidx.compose.ui.unit.Dp,
    onPlay: (DebridItem) -> Unit,
    onDelete: (DebridItem) -> Unit
) {
    // Deleting from the debrid cloud can't be undone, so confirm first.
    var pendingDelete by remember { mutableStateOf<DebridItem?>(null) }
    pendingDelete?.let { item ->
        com.hereliesaz.illumera.ui.settings.DisconnectConfirmDialog(
            title = "Remove from cloud storage?",
            message = "\"${item.name}\" will be deleted from your ${item.provider.displayName} account. This can't be undone.",
            confirmText = "Remove",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                onDelete(item)
            }
        )
    }
    // An empty library shows nothing: no header, no placeholder text.
    if (state.items.isEmpty() && !state.isLoading) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = startPadding, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Cloud Storage (${providers.joinToString(", ") { it.displayName }})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
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
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = startPadding)
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(start = startPadding, end = 24.dp)
            ) {
                items(state.items, key = { it.libraryKey }) { item ->
                    DebridItemCard(
                        item = item,
                        showProvider = providers.size > 1,
                        isResolving = state.resolvingItemId == item.libraryKey,
                        onPlay = { onPlay(item) },
                        onDelete = { pendingDelete = item }
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
    showProvider: Boolean,
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
            .background(MaterialTheme.colorScheme.onBackground.copy(0.06f))
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
                        color = if (isFocused) accentColor else MaterialTheme.colorScheme.onBackground.copy(0.7f)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = if (isFocused) accentColor else MaterialTheme.colorScheme.onBackground.copy(0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isResolving) "Resolving..." else "Play",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isFocused) accentColor else MaterialTheme.colorScheme.onBackground.copy(0.7f)
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
                    tint = MaterialTheme.colorScheme.onBackground.copy(0.5f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = item.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        if (item.sizeBytes != null || item.status != null || showProvider) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = listOfNotNull(
                    item.provider.displayName.takeIf { showProvider },
                    item.sizeBytes?.let { formatBytes(it) },
                    item.status
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(0.5f),
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
