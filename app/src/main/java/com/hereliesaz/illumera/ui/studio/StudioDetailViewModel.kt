package com.hereliesaz.illumera.ui.studio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.illumera.data.tmdb.TmdbDiscoverRail
import com.hereliesaz.illumera.data.tmdb.TmdbEntityDetail
import com.hereliesaz.illumera.data.tmdb.TmdbMetadataService
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

sealed class StudioDetailState {
    data object Loading : StudioDetailState()
    data class Success(
        val entity: TmdbEntityDetail,
        val rails: List<TmdbDiscoverRail>
    ) : StudioDetailState()
    data class Error(val message: String) : StudioDetailState()
}

/** One studio page's entity, given by its back-stack entry (StudioKey) through [Factory]. */
@HiltViewModel(assistedFactory = StudioDetailViewModel.Factory::class)
class StudioDetailViewModel @AssistedInject constructor(
    private val tmdbMetadataService: TmdbMetadataService,
    @Assisted("entityId") val entityId: Int,
    @Assisted("entityKind") val entityKind: String,
    @Assisted("sourceType") val sourceType: String
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("entityId") entityId: Int,
            @Assisted("entityKind") entityKind: String,
            @Assisted("sourceType") sourceType: String
        ): StudioDetailViewModel
    }

    private val _state = MutableStateFlow<StudioDetailState>(StudioDetailState.Loading)
    val state: StateFlow<StudioDetailState> = _state

    private val inFlightLoads = mutableSetOf<String>()

    init {
        loadDetail()
    }

    fun retry() = loadDetail()

    fun loadMoreRail(mediaType: String, railType: String) {
        val currentState = _state.value as? StudioDetailState.Success ?: return
        val rail = currentState.rails.find { it.mediaType == mediaType && it.railType == railType } ?: return
        if (!rail.hasMore || rail.currentPage >= 3) return

        val loadKey = "$mediaType:$railType"
        if (!inFlightLoads.add(loadKey)) return

        viewModelScope.launch {
            try {
                val nextPage = rail.currentPage + 1
                val sortBy = when (railType) {
                    "top_rated" -> "vote_average.desc"
                    "recent" -> if (mediaType == "movie") "primary_release_date.desc" else "first_air_date.desc"
                    else -> "popularity.desc"
                }
                val (items, totalPages) = tmdbMetadataService.fetchDiscover(
                    entityId = entityId, kind = entityKind, mediaType = mediaType,
                    sortBy = sortBy, page = nextPage,
                    voteCountGte = if (railType == "top_rated") 200 else null,
                    dateLte = if (railType == "recent") LocalDate.now().toString() else null
                )
                // fetchDiscover reports failures as an empty page (totalPages = 1), which is
                // indistinguishable from a real end. Leave the rail untouched on an empty
                // result so a transient failure doesn't permanently end it; the next
                // scroll-to-end retries the same page.
                if (items.isEmpty()) return@launch

                // Merge into the latest state (not the snapshot taken before the fetch) so
                // concurrent loads of other rails aren't overwritten.
                _state.update { current ->
                    if (current !is StudioDetailState.Success) return@update current
                    current.copy(rails = current.rails.map { r ->
                        if (r.mediaType != mediaType || r.railType != railType) return@map r
                        val existingIds = r.items.map { it.tmdbId }.toSet()
                        r.copy(
                            items = r.items + items.filter { it.tmdbId !in existingIds },
                            currentPage = nextPage,
                            hasMore = nextPage < totalPages
                        )
                    })
                }
            } finally {
                inFlightLoads.remove(loadKey)
            }
        }
    }

    private fun loadDetail() {
        _state.value = StudioDetailState.Loading
        viewModelScope.launch {
            val entity = tmdbMetadataService.fetchEntityDetail(entityId, entityKind)
            if (entity == null) {
                _state.value = StudioDetailState.Error("Failed to load details")
                return@launch
            }

            val railConfigs = buildList {
                val primaryType = if (sourceType == "series") "tv" else "movie"
                val secondaryType = if (primaryType == "movie") "tv" else "movie"
                // Networks don't have movies
                val types = if (entityKind == "network") listOf("tv") else listOf(primaryType, secondaryType)
                for (type in types) {
                    add(Triple(type, "popular", "popularity.desc"))
                    add(Triple(type, "top_rated", if (type == "movie") "vote_average.desc" else "vote_average.desc"))
                    add(Triple(type, "recent", if (type == "movie") "primary_release_date.desc" else "first_air_date.desc"))
                }
            }

            val results = railConfigs.map { (mediaType, railType, sortBy) ->
                viewModelScope.async {
                    val (items, totalPages) = tmdbMetadataService.fetchDiscover(
                        entityId = entityId, kind = entityKind, mediaType = mediaType,
                        sortBy = sortBy, page = 1,
                        voteCountGte = if (railType == "top_rated") 200 else null,
                        dateLte = if (railType == "recent") LocalDate.now().toString() else null
                    )
                    TmdbDiscoverRail(
                        mediaType = mediaType, railType = railType,
                        items = items, currentPage = 1, hasMore = totalPages > 1
                    )
                }
            }.awaitAll()

            val rails = results.filter { it.items.isNotEmpty() }
            _state.value = StudioDetailState.Success(entity = entity, rails = rails)
        }
    }
}
