package com.hereliesaz.illumera.ui.queue

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.hereliesaz.illumera.data.queue.QueueItem
import com.hereliesaz.illumera.data.queue.QueueManager
import com.hereliesaz.illumera.data.queue.QueueSuggestionSource
import com.hereliesaz.illumera.ui.components.LumeraCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class QueueViewModel @Inject constructor(
    val queueManager: QueueManager
) : ViewModel()

@Composable
fun QueueScreen(
    queueManager: QueueManager,
    entryRequester: FocusRequester,
    onOpenItem: (QueueItem) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 84.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            QueueSection(
                queueManager = queueManager,
                entryRequester = entryRequester,
                startPadding = 96.dp,
                onOpenItem = onOpenItem,
                requestEntryFocus = true
            )
        }
    }
}

@Composable
fun QueueSection(
    entryRequester: FocusRequester,
    startPadding: Dp,
    onOpenItem: (QueueItem) -> Unit,
    queueManager: QueueManager = hiltViewModel<QueueViewModel>().queueManager,
    requestEntryFocus: Boolean = false,
    focusedQueueKey: String? = null,
    restoreEntryFocusWhenFocusedKeyMissing: Boolean = false,
    onQueueFocused: (String?) -> Unit = {}
) {
    val state by queueManager.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.preferences.enabled) {
        if (state.preferences.enabled) {
            if (state.preferences.onlyUnseenSuggestions) {
                queueManager.refreshSuggestions()
            } else {
                queueManager.ensureSuggestions()
            }
        }
    }

    LaunchedEffect(state.manualItems, state.suggestions) {
        queueManager.resolveMissingArtwork()
    }

    LaunchedEffect(
        state.manualItems,
        state.suggestions,
        focusedQueueKey,
        restoreEntryFocusWhenFocusedKeyMissing
    ) {
        val key = focusedQueueKey ?: return@LaunchedEffect
        val stillExists = state.manualItems.any { it.stableKey == key } ||
            state.suggestions.any { it.stableKey == key }
        if (!stillExists) {
            onQueueFocused(null)
            if (restoreEntryFocusWhenFocusedKeyMissing) {
                kotlinx.coroutines.delay(50)
                runCatching { entryRequester.requestFocus() }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(modifier = Modifier.padding(start = startPadding, end = 32.dp)) {
            Text(
                text = "Queue",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Your lineup, followed by suggestions.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = .62f)
            )
        }

        Column(
            modifier = Modifier
                .padding(start = startPadding, end = 32.dp)
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = .5f),
                    RoundedCornerShape(14.dp)
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            QueueOption(
                label = "Queue enabled",
                checked = state.preferences.enabled,
                onCheckedChange = queueManager::setEnabled,
                modifier = if (requestEntryFocus) Modifier.focusRequester(entryRequester) else Modifier
            )

            Text(
                text = "Automatic lineup",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 2.dp)
            )
            QueueOption(
                label = "Movies",
                checked = state.preferences.includeMovies,
                onCheckedChange = queueManager::setIncludeMovies
            )
            QueueOption(
                label = "Episodes",
                checked = state.preferences.includeEpisodes,
                onCheckedChange = queueManager::setIncludeEpisodes
            )
            QueueOption(
                label = "Whole shows — play straight through",
                checked = state.preferences.includeWholeShows,
                onCheckedChange = queueManager::setIncludeWholeShows
            )

            Text(
                text = "Suggestion sources",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 2.dp)
            )
            QueueOption(
                label = "Play history",
                checked = QueueSuggestionSource.PLAY_HISTORY in state.preferences.suggestionSources,
                onCheckedChange = { enabled ->
                    queueManager.setSuggestionSource(QueueSuggestionSource.PLAY_HISTORY, enabled)
                }
            )
            QueueOption(
                label = "Trakt",
                checked = QueueSuggestionSource.TRAKT in state.preferences.suggestionSources,
                onCheckedChange = { enabled ->
                    queueManager.setSuggestionSource(QueueSuggestionSource.TRAKT, enabled)
                }
            )
            QueueOption(
                label = "Only suggest things I haven't seen",
                checked = state.preferences.onlyUnseenSuggestions,
                onCheckedChange = { enabled ->
                    queueManager.setOnlyUnseenSuggestions(enabled)
                    scope.launch { queueManager.refreshSuggestions() }
                }
            )

            Button(
                onClick = { scope.launch { queueManager.refreshSuggestions(resetDismissed = true) } },
                enabled = state.preferences.enabled && !state.isRefreshingSuggestions,
                modifier = Modifier.padding(start = 8.dp, top = 6.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Text(if (state.isRefreshingSuggestions) " Refreshing…" else " Refresh suggestions")
            }
        }

        if (state.manualItems.isEmpty()) {
            Text(
                text = "Nothing queued yet.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = .55f),
                modifier = Modifier.padding(start = startPadding)
            )
        } else {
            QueueCardRow(
                title = "Your lineup",
                items = state.manualItems,
                startPadding = startPadding,
                onOpenItem = onOpenItem,
                focusedKey = focusedQueueKey,
                onFocused = onQueueFocused,
                actions = { item, index ->
                    IconButton(
                        onClick = { queueManager.move(item.stableKey, -1) },
                        enabled = index > 0
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Move up")
                    }
                    IconButton(
                        onClick = { queueManager.move(item.stableKey, 1) },
                        enabled = index < state.manualItems.lastIndex
                    ) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = "Move down")
                    }
                    IconButton(onClick = { queueManager.remove(item.stableKey) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove")
                    }
                }
            )
        }

        if (state.suggestions.isNotEmpty()) {
            QueueCardRow(
                title = "Suggested next",
                items = state.suggestions,
                startPadding = startPadding,
                onOpenItem = onOpenItem,
                focusedKey = focusedQueueKey,
                onFocused = onQueueFocused,
                onMoveSuggestion = { item, targetIndex ->
                    queueManager.moveSuggestion(item.stableKey, targetIndex)
                },
                onRemoveSuggestion = { item ->
                    scope.launch {
                        queueManager.removeSuggestion(item.stableKey)
                        queueManager.ensureSuggestions()
                    }
                },
                actions = { item, _ ->
                    IconButton(onClick = {
                        scope.launch {
                            queueManager.rateSuggestion(item.stableKey, -1)
                            queueManager.removeSuggestion(item.stableKey)
                            queueManager.ensureSuggestions()
                        }
                    }) {
                        Icon(
                            Icons.Default.ThumbDown,
                            contentDescription = "Less like this",
                            tint = if (item.rating < 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                    IconButton(onClick = { queueManager.rateSuggestion(item.stableKey, 1) }) {
                        Icon(
                            Icons.Default.ThumbUp,
                            contentDescription = "More like this",
                            tint = if (item.rating > 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun QueueOption(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    val accent = MaterialTheme.colorScheme.primary

    val backgroundColor = when {
        isFocused -> accent.copy(alpha = .24f)
        checked -> accent.copy(alpha = .11f)
        else -> Color.Transparent
    }
    val borderWidth = when {
        isFocused -> 2.dp
        checked -> 1.dp
        else -> 0.dp
    }
    val borderColor = when {
        isFocused -> accent
        checked -> accent.copy(alpha = .55f)
        else -> Color.Transparent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor, shape)
            .border(borderWidth, borderColor, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onCheckedChange(!checked) }
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(
            text = label,
            color = when {
                isFocused -> MaterialTheme.colorScheme.onSurface
                checked -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = .82f)
            },
            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun QueueCardRow(
    title: String,
    items: List<QueueItem>,
    startPadding: Dp,
    onOpenItem: (QueueItem) -> Unit,
    focusedKey: String? = null,
    onFocused: (String) -> Unit = {},
    onMoveSuggestion: ((QueueItem, Int) -> Unit)? = null,
    onRemoveSuggestion: ((QueueItem) -> Unit)? = null,
    actions: @Composable (QueueItem, Int) -> Unit
) {
    var movingKey by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = movingKey != null) { movingKey = null }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = startPadding, bottom = 12.dp)
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(start = startPadding, end = 40.dp)
        ) {
            itemsIndexed(items, key = { _, item -> item.stableKey }) { index, item ->
                val cardFocusRequester = remember(item.stableKey) { FocusRequester() }
                val isMoving = movingKey == item.stableKey
                LaunchedEffect(focusedKey, movingKey, item.stableKey) {
                    if (focusedKey == item.stableKey || isMoving) {
                        kotlinx.coroutines.delay(50)
                        runCatching { cardFocusRequester.requestFocus() }
                    }
                }

                Column(modifier = Modifier.width(140.dp)) {
                    var menuExpanded by remember(item.stableKey) { mutableStateOf(false) }
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .then(
                                if (isMoving) Modifier.border(
                                    3.dp,
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(12.dp)
                                ) else Modifier
                            )
                            .onPreviewKeyEvent { event ->
                                if (!isMoving || event.type != KeyEventType.KeyDown || onMoveSuggestion == null) {
                                    return@onPreviewKeyEvent false
                                }
                                when (event.key) {
                                    Key.DirectionLeft -> {
                                        if (index > 0) onMoveSuggestion(item, index - 1)
                                        true
                                    }
                                    Key.DirectionRight -> {
                                        if (index < items.lastIndex) onMoveSuggestion(item, index + 1)
                                        true
                                    }
                                    Key.Back, Key.Escape -> {
                                        movingKey = null
                                        true
                                    }
                                    else -> false
                                }
                            }
                    ) {
                        LumeraCard(
                            title = item.title,
                            posterUrl = item.poster,
                            onClick = {
                                if (isMoving) movingKey = null else onOpenItem(item)
                            },
                            modifier = Modifier.focusRequester(cardFocusRequester),
                            onFocused = { onFocused(item.stableKey) },
                            onLongClick = if (onMoveSuggestion != null || onRemoveSuggestion != null) {
                                { menuExpanded = true }
                            } else null
                        )

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            if (onMoveSuggestion != null) {
                                DropdownMenuItem(
                                    text = { Text("Move") },
                                    onClick = {
                                        menuExpanded = false
                                        movingKey = item.stableKey
                                    }
                                )
                            }
                            if (onRemoveSuggestion != null) {
                                DropdownMenuItem(
                                    text = { Text("Remove from queue") },
                                    onClick = {
                                        menuExpanded = false
                                        movingKey = null
                                        onRemoveSuggestion(item)
                                    },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (isMoving) "Move with ← → • select to place" else queueSubtitle(item),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isMoving) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .58f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        actions(item, index)
                    }
                }
            }
        }
    }
}

private fun queueSubtitle(item: QueueItem): String = when {
    item.wholeShow -> "Whole show"
    item.type == "episode" && item.season != null && item.episode != null ->
        "S${item.season} E${item.episode}"
    else -> item.type.replaceFirstChar { it.uppercase() }
}
