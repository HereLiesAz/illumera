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
    // One sorted rail contains both movies and series, including synced history.
    val libraryItems = remember(sortedLibrary) { sortedLibrary.map { it.item } }

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

    LaunchedEffect(libraryItems) {
        val key = lastFocusedKey ?: return@LaunchedEffect
        val rememberedId = key.substringAfter('_', "")
            .substringBeforeLast('_', "")
        val exists = libraryItems.any { it.id == rememberedId }
        if (libraryItems.isEmpty()) {
            lastFocusedKey = null
            viewModel.lastFocusedKey = null
        } else if (!exists || !key.startsWith("0_")) {
            // Older releases used a separate Series row (index 1). Redirect
            // that stored focus into the single mixed library row.
            val target = if (exists) libraryItems.indexOfFirst { it.id == rememberedId }
            else resolveRowRestoreIndex(key, 0, libraryItems.map { it.id })
                ?: libraryItems.lastIndex
            val item = libraryItems[target.coerceIn(0, libraryItems.lastIndex)]
            lastFocusedKey = "0_${item.id}_${target}"
            viewModel.lastFocusedKey = lastFocusedKey
        }
    }

    LaunchedEffect(libraryItems) {
        libraryItems.forEach { viewModel.resolvePosterIfNeeded(it) }
    }

    val isTopNav = currentProfile?.navPosition == "top"
    val isCompactWatchlist = LocalConfiguration.current.screenWidthDp < 600
    val startPadding = when {
        isTopNav -> 50.dp
        isCompactWatchlist -> 96.dp
        else -> 120.dp
    }
    val topPadding = if (isTopNav) 24.dp else 16.dp
    val hasWatchlistMedia = libraryItems.isNotEmpty()

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
                                    .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.27f)
                                        else if (selected) Color.White.copy(alpha = 0.14f)
                                        else Color.White.copy(alpha = 0.05f))
                                    .border(
                                        if (focused) 2.dp else 0.dp,
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(8.dp)
                                    )
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
            if (!hasWatchlistMedia) {
                item {
                    Text(
                        text = "Your Library is empty",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = startPadding, top = 12.dp, bottom = 4.dp)
                    )
                }
            }

            if (libraryItems.isNotEmpty()) {
                item(key = "mixed_library") {
                    InfiniteLoopRow(
                        startPadding = startPadding,
                        isTopNav = isTopNav,
                        rowIndex = 0,
                        title = "Movies & Series",
                        items = libraryItems,
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
                        locallyFocusedItemId = lastFocusedKey?.takeIf { it.startsWith("0_") },
                        isGlobalFocusPresent = lastFocusedKey != null,
                        isFirstRow = false, // Queue remains above Library
                        isInfiniteLoopEnabled = false,
                        upKeyDebouncer = upKeyDebouncer,
                        repeatGate = dpadRepeatGate,
                        externalListState = viewModel.libraryRowState
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
