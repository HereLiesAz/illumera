package com.hereliesaz.illumera.ui.soundtrack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.hereliesaz.illumera.data.soundtrack.Soundtrack
import com.hereliesaz.illumera.data.soundtrack.SoundtrackSong
import com.hereliesaz.illumera.ui.util.rememberDialogWidth

/** One row of the dialog's list: a heading (episode) or a song with its position. */
internal sealed interface ListRow {
    data class Heading(val text: String) : ListRow
    data class Song(val number: Int, val song: SoundtrackSong) : ListRow
}

/** Episode headings for a series, then each group's songs numbered in order of appearance. */
internal fun soundtrackRows(soundtrack: Soundtrack): List<ListRow> {
    val grouped = soundtrack.groups.size > 1 || soundtrack.groups.firstOrNull()?.season != null
    return buildList {
        for (group in soundtrack.groups) {
            if (grouped && group.season != null) {
                add(ListRow.Heading("S${group.season} · E${group.episode}  ${group.title.orEmpty()}".trim()))
            }
            group.songs.forEachIndexed { i, song -> add(ListRow.Song(i + 1, song)) }
        }
    }
}

/**
 * The songs of a movie, a whole series (grouped by episode) or one episode, in order of
 * appearance. [nowPlaying], when known, is pinned above the list.
 */
@Composable
fun SoundtrackDialog(
    type: String,
    imdbId: String,
    season: Int? = null,
    episode: Int? = null,
    nowPlaying: SoundtrackSong? = null,
    onDismiss: () -> Unit,
    viewModel: SoundtrackViewModel = hiltViewModel(key = "soundtrack:$type:$imdbId:$season:$episode"),
) {
    LaunchedEffect(type, imdbId, season, episode) { viewModel.load(type, imdbId, season, episode) }
    val state by viewModel.state.collectAsState()
    val firstRow = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(rememberDialogWidth(560))
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val soundtrack = (state as? SoundtrackViewModel.State.Loaded)?.soundtrack
            Text(
                soundtrack?.title?.let { "$it · Soundtrack" } ?: "Soundtrack",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Light),
                color = Color.White
            )
            if (nowPlaying != null) {
                Text("Now playing", color = Color.White.copy(0.5f), style = MaterialTheme.typography.labelMedium)
                SongRow(null, nowPlaying, highlighted = true)
            }
            when (val s = state) {
                SoundtrackViewModel.State.Loading ->
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally))
                SoundtrackViewModel.State.Empty ->
                    Text("No songs listed for this title.", color = Color.White.copy(0.6f))
                is SoundtrackViewModel.State.Loaded -> {
                    val rows = remember(s.soundtrack) { soundtrackRows(s.soundtrack) }
                    LaunchedEffect(rows) { runCatching { firstRow.requestFocus() } }
                    LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                        // Keyed by position: song titles repeat across episodes.
                        itemsIndexed(rows, key = { index, _ -> index }) { index, row ->
                            val focus = if (index == 0) Modifier.focusRequester(firstRow) else Modifier
                            when (row) {
                                is ListRow.Heading -> Text(
                                    row.text,
                                    color = Color.White.copy(0.55f),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = focus.focusable().padding(top = 16.dp, bottom = 4.dp)
                                )
                                is ListRow.Song -> SongRow(row.number, row.song, modifier = focus)
                            }
                        }
                    }
                }
            }
            val loaded = state as? SoundtrackViewModel.State.Loaded
            Text(
                when {
                    loaded?.fromTunefind == true -> "Song data from Tunefind and IMDb."
                    loaded?.checking == true -> "Song data from IMDb · checking Tunefind…"
                    else -> "Song data from IMDb."
                },
                color = Color.White.copy(0.3f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun SongRow(number: Int?, song: SoundtrackSong, modifier: Modifier = Modifier, highlighted: Boolean = false) {
    // Rows are focusable so the D-pad can scroll the list.
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused || highlighted) Color.White.copy(0.1f) else Color.Transparent)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(number?.toString() ?: "♪", color = Color.White.copy(0.35f), modifier = Modifier.width(24.dp))
        Column {
            Text(song.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            song.artist?.let {
                Text(it, color = Color.White.copy(0.55f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
