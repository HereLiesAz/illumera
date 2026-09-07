package com.hereliesaz.illumera.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hereliesaz.illumera.data.queue.QueueItem
import com.hereliesaz.illumera.data.queue.QueueManager
import com.hereliesaz.illumera.data.queue.QueueSuggestionSource

@Composable
fun QueueScreen(
    queueManager: QueueManager,
    entryRequester: FocusRequester,
    onOpenItem: (QueueItem) -> Unit
) {
    val state by queueManager.state.collectAsState()

    LaunchedEffect(state.preferences.enabled) {
        if (state.preferences.enabled && state.suggestions.size < 10) {
            queueManager.refreshSuggestions()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(start = 96.dp, end = 40.dp, top = 92.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().focusRequester(entryRequester),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Queue", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text("Your lineup, then ten suggestions.", color = MaterialTheme.colorScheme.onBackground.copy(alpha = .65f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.preferences.enabled) "Enabled" else "Disabled")
                    Switch(checked = state.preferences.enabled, onCheckedChange = queueManager::setEnabled)
                }
            }
        }

        item {
            Column(
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = .55f), RoundedCornerShape(14.dp)).padding(16.dp)
            ) {
                Text("Automatic lineup", fontWeight = FontWeight.Bold)
                QueueOption("Movies", state.preferences.includeMovies, queueManager::setIncludeMovies)
                QueueOption("Episodes", state.preferences.includeEpisodes, queueManager::setIncludeEpisodes)
                QueueOption("Whole shows — play straight through", state.preferences.includeWholeShows, queueManager::setIncludeWholeShows)
                Spacer(Modifier.height(8.dp))
                Text("Suggestion sources", fontWeight = FontWeight.SemiBold)
                QueueOption(
                    "Play history",
                    QueueSuggestionSource.PLAY_HISTORY in state.preferences.suggestionSources
                ) { queueManager.setSuggestionSource(QueueSuggestionSource.PLAY_HISTORY, it) }
                QueueOption(
                    "Trakt",
                    QueueSuggestionSource.TRAKT in state.preferences.suggestionSources
                ) { queueManager.setSuggestionSource(QueueSuggestionSource.TRAKT, it) }
                Button(
                    onClick = { kotlinx.coroutines.MainScope().launch { queueManager.refreshSuggestions() } },
                    enabled = state.preferences.enabled && !state.isRefreshingSuggestions
                ) {
                    Icon(Icons.Default.Refresh, null)
                    Text(if (state.isRefreshingSuggestions) " Refreshing…" else " Refresh suggestions")
                }
            }
        }

        item {
            Text("Your lineup", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        if (state.manualItems.isEmpty()) {
            item { Text("Nothing queued yet.", color = MaterialTheme.colorScheme.onBackground.copy(alpha = .55f)) }
        } else {
            itemsIndexed(state.manualItems, key = { _, item -> item.stableKey }) { index, item ->
                QueueRow(
                    item = item,
                    label = "${index + 1}",
                    onClick = { onOpenItem(item) },
                    actions = {
                        IconButton(onClick = { queueManager.move(item.stableKey, -1) }, enabled = index > 0) { Icon(Icons.Default.ArrowUpward, "Move up") }
                        IconButton(onClick = { queueManager.move(item.stableKey, 1) }, enabled = index < state.manualItems.lastIndex) { Icon(Icons.Default.ArrowDownward, "Move down") }
                        IconButton(onClick = { queueManager.remove(item.stableKey) }) { Icon(Icons.Default.Delete, "Remove") }
                    }
                )
            }
        }

        item {
            Text("Suggested next", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        itemsIndexed(state.suggestions, key = { _, item -> "suggestion_${item.stableKey}" }) { index, item ->
            QueueRow(
                item = item,
                label = "${index + 1}",
                onClick = { onOpenItem(item) },
                actions = {
                    IconButton(onClick = { queueManager.rateSuggestion(item.stableKey, -1) }) {
                        Icon(Icons.Default.ThumbDown, "Less like this", tint = if (item.rating < 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { queueManager.rateSuggestion(item.stableKey, 1) }) {
                        Icon(Icons.Default.ThumbUp, "More like this", tint = if (item.rating > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            )
        }
    }
}

@Composable
private fun QueueOption(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label)
    }
}

@Composable
private fun QueueRow(
    item: QueueItem,
    label: String,
    onClick: () -> Unit,
    actions: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = .42f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.size(36.dp), fontWeight = FontWeight.Bold)
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    item.wholeShow -> "Whole show"
                    item.type == "episode" && item.season != null && item.episode != null -> "S${item.season} E${item.episode}"
                    else -> item.type.replaceFirstChar { it.uppercase() }
                },
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f)
            )
        }
        actions()
    }
}
