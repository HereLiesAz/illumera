package com.hereliesaz.illumera.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.RecentSearchEntity
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.repository.AddonRepository
import com.hereliesaz.illumera.data.model.debrid.DebridResult
import com.hereliesaz.illumera.data.torrentclaw.TorrentClawIntegration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    private val profileConfigurationManager: ProfileConfigurationManager,
    private val torrentClaw: TorrentClawIntegration
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
            var previousProfileId: Int? = null
            var first = true
            profileConfigurationManager.activeProfileId.collectLatest { profileId ->
                if (!first && profileId != previousProfileId) {
                    // Another profile's query/results must not leak across a profile switch.
                    searchJob?.cancel()
                    _state.value = _state.value.copy(
                        query = "", results = emptyList(), movies = emptyList(),
                        series = emptyList(), isLoading = false, searchFailed = false
                    )
                }
                first = false
                previousProfileId = profileId
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
            // A provider failure must not suppress results from the other provider.
            // TorrentClaw supplies canonical titles only; playable streams still come
            // from the installed addons when the title's Details screen opens.
            val addons = try {
                repository.searchMovies(query)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            // Do not block a successful installed-addon search on the optional provider.
            // SearchScreen hides results while isLoading, so publish these immediately
            // before TorrentClaw's separate network request finishes or times out.
            if (!addons.isNullOrEmpty()) {
                _state.value = _state.value.copy(
                    results = addons,
                    movies = addons.filter { it.type == "movie" },
                    series = addons.filter { it.type == "series" },
                    isLoading = false
                )
            }
            val claw = try {
                torrentClaw.search(query)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DebridResult.Failure("TorrentClaw unavailable")
            }
            val clawResults = (claw as? DebridResult.Success)?.value.orEmpty()
            if (addons == null && clawResults.isEmpty()) {
                throw IllegalStateException("Search providers unavailable")
            }
            val results = (addons.orEmpty() + clawResults).distinctBy { it.type to it.id }
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
