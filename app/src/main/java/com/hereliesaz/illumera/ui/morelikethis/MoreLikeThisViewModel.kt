package com.hereliesaz.illumera.ui.morelikethis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.illumera.data.tmdb.TmdbMetaPreview
import com.hereliesaz.illumera.data.tmdb.TmdbMetadataService
import com.hereliesaz.illumera.data.tmdb.TmdbService
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface MoreLikeThisState {
    data object Loading : MoreLikeThisState
    data class Ready(val items: List<TmdbMetaPreview>) : MoreLikeThisState
    data class Empty(val message: String) : MoreLikeThisState
}

/** TMDB recommendations for one title, given by its back-stack entry (MoreLikeThisKey). */
@HiltViewModel(assistedFactory = MoreLikeThisViewModel.Factory::class)
class MoreLikeThisViewModel @AssistedInject constructor(
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    @Assisted("type") private val type: String,
    @Assisted("id") private val id: String
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(@Assisted("type") type: String, @Assisted("id") id: String): MoreLikeThisViewModel
    }

    private val _state = MutableStateFlow<MoreLikeThisState>(MoreLikeThisState.Loading)
    val state: StateFlow<MoreLikeThisState> = _state

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        _state.value = MoreLikeThisState.Loading
        viewModelScope.launch {
            val mediaType = tmdbService.normalizeMediaType(type)
            // An episode id (tt…:S:E) stands for its show.
            val baseId = if (id.startsWith("tmdb:")) id else id.substringBefore(':')
            val tmdbId = tmdbService.ensureTmdbId(baseId, mediaType)
            if (tmdbId == null) {
                _state.value = MoreLikeThisState.Empty("Couldn't find this title on TMDB.")
                return@launch
            }
            val items = tmdbMetadataService.fetchRecommendations(tmdbId, mediaType, maxItems = MAX_ITEMS)
            _state.value = if (items.isEmpty()) {
                MoreLikeThisState.Empty("No similar titles found.")
            } else {
                MoreLikeThisState.Ready(items)
            }
        }
    }

    private companion object {
        // TMDB returns one page (20 results) of recommendations; asking for more is a no-op.
        const val MAX_ITEMS = 20
    }
}
