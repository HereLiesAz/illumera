package com.hereliesaz.illumera.ui.watchlist

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
    onPlayResolvedStream: (id: String, url: String, title: String) -> Unit,
    watchedIds: Set<String> = emptySet(),
    viewModel: WatchlistViewModel = hiltViewModel(),
    debridViewModel: DebridLibraryViewModel = hiltViewModel()
) {
    val movies by viewModel.movieItems.collectAsState()
    val series by viewModel.seriesItems.collectAsState()

    val debridProvider by debridViewModel.connectedProvider.collectAsState()
    val debridState by debridViewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        debridViewModel.events.collect { event ->
            when (event) {
                is DebridLibraryEvent.StreamResolved -> {
                    onPlayResolvedStream(event.id, event.url, event.name)
                }
                is DebridLibraryEvent.Error -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    androidx.activity.compose.BackHandler { drawerRequester.requestFocus() }

    val upKeyDebouncer = remember { UpKeyDebouncer() }
    val dpadRepeatGate = remember { DpadRepeatGate() }
    var lastFocusedKey by remember { mutableStateOf(viewModel.lastFocusedKey) }

    LaunchedEffect(movies, series) {
        val key = lastFocusedKey ?: return@LaunchedEffect
        val parts = key.split("_")
        val rowIndex = parts.getOrNull(0)?.toIntOrNull()
        val itemId = parts.getOrNull(1)
        if (itemId == null) return@LaunchedEffect

        val rowItems = if (rowIndex == 0) movies else series
        val stillExists = rowItems.any { it.id == itemId }
        if (!stillExists && rowItems.isNotEmpty()) {
            val fallbackItem = rowItems.last()
            val fallbackIndex = rowItems.lastIndex
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

    androidx.compose.runtime.CompositionLocalProvider(
        com.hereliesaz.illumera.ui.components.LocalWatchedIds provides watchedIds
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = topPadding + 20.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (!hasWatchlistMedia) {
                item {
                    Text(
                        text = "Your watchlist is empty",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = startPadding, top = 12.dp, bottom = 4.dp)
                    )
                }
            }

            if (movies.isNotEmpty()) {
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
                        },
                        entryRequester = entryRequester,
                        drawerRequester = drawerRequester,
                        locallyFocusedItemId = if (lastFocusedKey?.startsWith("0_") == true) lastFocusedKey else null,
                        isGlobalFocusPresent = lastFocusedKey != null,
                        isFirstRow = true,
                        isInfiniteLoopEnabled = false,
                        upKeyDebouncer = upKeyDebouncer,
                        repeatGate = dpadRepeatGate,
                        externalListState = viewModel.movieRowState
                    )
                }
            }

            if (series.isNotEmpty()) {
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
                        },
                        entryRequester = entryRequester,
                        drawerRequester = drawerRequester,
                        locallyFocusedItemId = if (lastFocusedKey?.startsWith("1_") == true) lastFocusedKey else null,
                        isGlobalFocusPresent = lastFocusedKey != null,
                        isFirstRow = movies.isEmpty(),
                        isInfiniteLoopEnabled = false,
                        upKeyDebouncer = upKeyDebouncer,
                        repeatGate = dpadRepeatGate,
                        externalListState = viewModel.seriesRowState
                    )
                }
            }

            item {
                QueueSection(
                    entryRequester = entryRequester,
                    startPadding = startPadding,
                    requestEntryFocus = !hasWatchlistMedia && viewModel.lastQueueFocusedKey == null,
                    focusedQueueKey = viewModel.lastQueueFocusedKey,
                    onQueueFocused = { key -> viewModel.lastQueueFocusedKey = key },
                    onOpenItem = { queueItem: QueueItem ->
                        onMovieClick(queueItem.toMetaItem())
                    }
                )
            }

            if (debridProvider != null) {
                item {
                    DebridLibrarySection(
                        provider = debridProvider,
                        state = debridState,
                        startPadding = startPadding,
                        onPlay = { debridViewModel.play(it) },
                        onDelete = { debridViewModel.delete(it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DebridLibrarySection(
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
