package com.hereliesaz.illumera.ui.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.WatchlistEntity
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val dao: AddonDao,
    private val repository: AddonRepository,
    private val profileConfigurationManager: ProfileConfigurationManager
) : ViewModel() {

    private val profileId: Int
        get() = profileConfigurationManager.getLastActiveProfileId() ?: 1

    private val resolveInFlight = mutableSetOf<String>()
    // Cools down retries for items that just failed to resolve, instead of retrying
    // every one of them on every watchlist mutation (the movieItems/seriesItems
    // flows re-emit the whole list on any add/remove, which otherwise re-triggers
    // resolvePosterIfNeeded for every previously-failed item every time).
    private val lastResolveFailureAt = mutableMapOf<String, Long>()
    private val resolveFailureCooldownMs = 5 * 60_000L

    var lastFocusedKey: String? = null
    var lastQueueFocusedKey: String? = null

    val movieRowState = androidx.compose.foundation.lazy.LazyListState()
    val seriesRowState = androidx.compose.foundation.lazy.LazyListState()

    val movieItems: StateFlow<List<MetaItem>> = dao.getWatchlistByType(profileId, "movie")
        .map { list -> list.map { it.toMetaItem() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val seriesItems: StateFlow<List<MetaItem>> = dao.getWatchlistByType(profileId, "series")
        .map { list -> list.map { it.toMetaItem() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Resolve poster from addons for items missing one (e.g., pulled from Trakt).
     * Updates the DB so the poster persists — the Flow will re-emit automatically.
     */
    fun resolvePosterIfNeeded(item: MetaItem) {
        if (!item.poster.isNullOrBlank()) return
        val lastFailure = lastResolveFailureAt[item.id]
        if (lastFailure != null && System.currentTimeMillis() - lastFailure < resolveFailureCooldownMs) return
        if (!resolveInFlight.add(item.id)) return

        viewModelScope.launch(Dispatchers.IO) {
            var succeeded = false
            try {
                val meta = repository.resolveMetaDetails(item.type, item.id)
                if (!meta?.poster.isNullOrBlank()) {
                    val existing = dao.getWatchlistItem(profileId, item.id)
                    if (existing != null) {
                        dao.addToWatchlist(existing.copy(poster = meta?.poster))
                        succeeded = true
                    }
                }
            } catch (_: Exception) {
            } finally {
                resolveInFlight.remove(item.id)
                if (!succeeded) lastResolveFailureAt[item.id] = System.currentTimeMillis()
            }
        }
    }
}

private fun WatchlistEntity.toMetaItem() = MetaItem(
    id = id,
    type = type,
    name = title,
    poster = poster
)
