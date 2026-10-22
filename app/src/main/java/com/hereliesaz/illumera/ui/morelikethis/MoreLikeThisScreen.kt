package com.hereliesaz.illumera.ui.morelikethis

import com.hereliesaz.illumera.data.taste.dislikes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.hereliesaz.illumera.ui.addons.VoidButton
import com.hereliesaz.illumera.ui.studio.PosterCard

/** A grid of titles like [title]; a card opens its Details page. */
@Composable
fun MoreLikeThisScreen(
    type: String,
    id: String,
    title: String,
    onNavigateToDetails: (type: String, id: String) -> Unit,
    viewModel: MoreLikeThisViewModel = hiltViewModel<MoreLikeThisViewModel, MoreLikeThisViewModel.Factory>(
        creationCallback = { it.create(type, id) }
    )
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accent = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onBackground

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(
            text = if (title.isBlank()) "More like this" else "More like $title",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        Box(modifier = Modifier.fillMaxSize()) {
            when (val current = state) {
                MoreLikeThisState.Loading -> CircularProgressIndicator(
                    color = accent,
                    modifier = Modifier.align(Alignment.Center)
                )
                is MoreLikeThisState.Empty -> {
                    // fetchRecommendations reports failures as an empty list, so "empty" may
                    // be a network error: always offer Retry, focused for D-pad users.
                    val retryFocus = remember { FocusRequester() }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Text(
                            text = current.message,
                            color = textColor.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        VoidButton(
                            text = "Retry",
                            onClick = { viewModel.retry() },
                            modifier = Modifier.width(120.dp),
                            focusRequester = retryFocus,
                            isPrimary = true
                        )
                    }
                    LaunchedEffect(current) { runCatching { retryFocus.requestFocus() } }
                }
                is MoreLikeThisState.Ready -> {
                    val firstCard = remember { FocusRequester() }
                    val tastes by hiltViewModel<com.hereliesaz.illumera.ui.components.MediaActionsViewModel>()
                        .tastes.collectAsStateWithLifecycle()
                    val shown = remember(current.items, tastes) {
                        current.items.filterNot { tastes.dislikes("tmdb:${it.tmdbId}") }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 128.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        itemsIndexed(shown, key = { _, item -> "${item.type}:${item.tmdbId}" }) { index, item ->
                            PosterCard(
                                item = item,
                                accentColor = accent,
                                modifier = if (index == 0) Modifier.focusRequester(firstCard) else Modifier,
                                onClick = {
                                    val stremioType = if (item.type == "series" || item.type == "tv") "series" else "movie"
                                    onNavigateToDetails(stremioType, "tmdb:${item.tmdbId}")
                                }
                            )
                        }
                    }
                    // D-pad users land on the first title.
                    LaunchedEffect(current) { runCatching { firstCard.requestFocus() } }
                }
            }
        }
    }
}
