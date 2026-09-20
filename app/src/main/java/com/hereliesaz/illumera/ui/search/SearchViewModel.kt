package com.hereliesaz.illumera.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.RecentSearchEntity
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: AddonRepository,
    private val dao: AddonDao,
    private val profileConfigurationManager: ProfileConfigurationManager
) : ViewModel() {

    companion object {
        // Below this length a query is still likely a fragment of what the user meant to
        // type — don't clutter recent-search history with every half-typed word.
        private const val MIN_QUERY_LENGTH_TO_REMEMBER = 2
    }

    data class SearchState(
        val query: String = "",
        val results: List<MetaItem> = emptyList(),
        val movies: List<MetaItem> = emptyList(),
        val series: List<MetaItem> = emptyList(),
        val isLoading: Boolean = false,
        val searchFailed: Boolean = false,
        val recentSearches: List<String> = emptyList()
    )

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            profileConfigurationManager.activeProfileId.collectLatest { profileId ->
                val recent = if (profileId == null) {
                    emptyList()
                } else {
                    dao.getRecentSearches(profileId)
                }
                _state.value = _state.value.copy(recentSearches = recent.map { it.query })
            }
        }
    }

    // ═══════════════════════════════════════
    // SEARCH
    // ═══════════════════════════════════════

    fun appendCharacter(char: String) {
        val current = _state.value.query
        onQueryChange(current + char)
    }

    fun removeCharacter() {
        val current = _state.value.query
        if (current.isNotEmpty()) {
            onQueryChange(current.dropLast(1))
        }
    }

    fun onQueryChange(newQuery: String) {
        _state.value = _state.value.copy(query = newQuery)
        searchJob?.cancel()

        if (newQuery.isBlank()) {
            _state.value = _state.value.copy(
                results = emptyList(), movies = emptyList(),
                series = emptyList(), isLoading = false, searchFailed = false
            )
            return
        }

        // Debounced live suggestions, à la Stremio's own on-screen keyboard: results start
        // updating from the first character, not after some fixed length.
        searchJob = viewModelScope.launch {
            delay(350)
            performSearch(newQuery)
        }
    }

    private suspend fun performSearch(query: String) {
        val ownerProfileId = profileConfigurationManager.activeProfileId.value
        _state.value = _state.value.copy(isLoading = true, searchFailed = false)
        try {
            val results = repository.searchMovies(query)
            val movies = results.filter { it.type == "movie" }
            val series = results.filter { it.type == "series" }
            _state.value = _state.value.copy(
                results = results, movies = movies,
                series = series, isLoading = false
            )
            if (
                ownerProfileId != null &&
                ownerProfileId == profileConfigurationManager.activeProfileId.value &&
                query.trim().length >= MIN_QUERY_LENGTH_TO_REMEMBER
            ) {
                rememberSearch(ownerProfileId, query.trim())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            _state.value = _state.value.copy(isLoading = false, searchFailed = true)
        }
    }

    // ═══════════════════════════════════════
    // RECENT SEARCHES
    // ═══════════════════════════════════════

    private fun rememberSearch(profileId: Int, query: String) {
        viewModelScope.launch {
            if (profileConfigurationManager.activeProfileId.value != profileId) return@launch
            dao.upsertRecentSearch(RecentSearchEntity(profileId, query, System.currentTimeMillis()))
            dao.trimRecentSearches(profileId)
            if (profileConfigurationManager.activeProfileId.value == profileId) {
                val recent = dao.getRecentSearches(profileId)
                _state.value = _state.value.copy(recentSearches = recent.map { it.query })
            }
        }
    }

    /** Re-runs a past search immediately, bypassing the debounce. */
    fun selectRecentSearch(query: String) {
        _state.value = _state.value.copy(query = query)
        searchJob?.cancel()
        searchJob = viewModelScope.launch { performSearch(query) }
    }
}
