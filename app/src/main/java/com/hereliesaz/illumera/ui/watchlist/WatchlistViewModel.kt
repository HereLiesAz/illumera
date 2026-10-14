package com.hereliesaz.illumera.ui.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.WatchlistEntity
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val dao: AddonDao,
    private val repository: AddonRepository,
    private val profileConfigurationManager: ProfileConfigurationManager
) : ViewModel() {

    // Touched from Main (resolvePosterIfNeeded) and IO (the launch's finally) — must be concurrent.
    private val resolveInFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    // Cools down retries for items that just failed to resolve, instead of retrying
    // every one of them on every watchlist mutation (the movieItems/seriesItems
    // flows re-emit the whole list on any add/remove, which otherwise re-triggers
    // resolvePosterIfNeeded for every previously-failed item every time).
    private val lastResolveFailureAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val resolveFailureCooldownMs = 5 * 60_000L

    var lastFocusedKey: String? = null
    var lastQueueFocusedKey: String? = null

    val movieRowState = androidx.compose.foundation.lazy.LazyListState()
    val seriesRowState = androidx.compose.foundation.lazy.LazyListState()

    data class LibraryEntry(
        val item: MetaItem,
        val addedAt: Long,
        val lastWatched: Long,
        val watched: Boolean,
        val timesWatched: Int
    )

    /**
     * Combines manually saved Watchlist entries with Stremio-synced history.
     * Entries are keyed by media type and canonical title ID so synced series
     * episodes resolve to a single series card rather than individual episodes.
     */
    val libraryEntries: StateFlow<List<LibraryEntry>> = profileConfigurationManager.activeProfileId
        .flatMapLatest { profileId ->
            if (profileId == null) flowOf(emptyList())
            else combine(dao.getWatchlist(profileId), dao.getWatchHistory()) { saved, history ->
                val historyEntries = history.map { entry ->
                    val parts = entry.id.split(':')
                    val episodeParent = if (
                        entry.type == "series" && parts.size >= 3 &&
                        parts.takeLast(2).all { it.toIntOrNull() != null }
                    ) parts.dropLast(2).joinToString(":") else entry.id
                    val canonicalId = entry.seriesId ?: episodeParent
                    val mediaType = if (entry.seriesId != null) "series" else entry.type
                    val item = MetaItem(
                        id = canonicalId, type = mediaType, name = entry.title,
                        poster = entry.poster, background = entry.background, logo = entry.logo
                    )
                    LibraryEntry(item, 0L, entry.lastWatched, entry.watched, if (entry.watched) 1 else 0)
                }
                val savedEntries = saved.map { item ->
                    LibraryEntry(item.toMetaItem(), item.addedAt, 0L, false, 0)
                }
                (savedEntries + historyEntries).groupBy { "${it.item.type}:${it.item.id}" }
                    .values.map { duplicates ->
                        val preferred = duplicates.firstOrNull { !it.item.poster.isNullOrBlank() }
                            ?: duplicates.first()
                        preferred.copy(
                            addedAt = duplicates.maxOf { it.addedAt },
                            lastWatched = duplicates.maxOf { it.lastWatched },
                            watched = duplicates.any { it.watched },
                            timesWatched = duplicates.sumOf { it.timesWatched }
                        )
                    }
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 0L, replayExpirationMillis = 0L),
            emptyList()
        )

    val movieItems: StateFlow<List<MetaItem>> = profileConfigurationManager.activeProfileId
        .flatMapLatest { profileId ->
            if (profileId == null) flowOf(emptyList<MetaItem>())
            else dao.getWatchlistByType(profileId, "movie")
                .map { list -> list.map { it.toMetaItem() } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0L, replayExpirationMillis = 0L), emptyList())

    val seriesItems: StateFlow<List<MetaItem>> = profileConfigurationManager.activeProfileId
        .flatMapLatest { profileId ->
            if (profileId == null) flowOf(emptyList<MetaItem>())
            else dao.getWatchlistByType(profileId, "series")
                .map { list -> list.map { it.toMetaItem() } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0L, replayExpirationMillis = 0L), emptyList())

    /**
     * Resolve poster from addons for items missing one (e.g., pulled from Trakt).
     * Updates the DB so the poster persists — the Flow will re-emit automatically.
     */
    fun resolvePosterIfNeeded(item: MetaItem) {
        if (!item.poster.isNullOrBlank()) return
        val ownerProfileId = profileConfigurationManager.activeProfileId.value ?: return
        val requestKey = "$ownerProfileId:${item.type}:${item.id}"
        val lastFailure = lastResolveFailureAt[requestKey]
        if (lastFailure != null && System.currentTimeMillis() - lastFailure < resolveFailureCooldownMs) return
        if (!resolveInFlight.add(requestKey)) return

        viewModelScope.launch(Dispatchers.IO) {
            var succeeded = false
            var cancelled = false
            try {
                // Network resolution must not hold the profile runtime lock. A slow addon
                // should never block loadRuntimeState() during a profile switch.
                val meta = repository.resolveMetaDetails(item.type, item.id)
                val poster = meta?.poster?.takeIf { it.isNotBlank() }
                if (poster != null) {
                    profileConfigurationManager.withActiveProfileRuntime(ownerProfileId) {
                        val existing = dao.getWatchlistItem(ownerProfileId, item.id)
                        if (existing != null) {
                            dao.addToWatchlist(existing.copy(poster = poster))
                            succeeded = true
                        }
                    }
                }
            } catch (error: CancellationException) {
                cancelled = true
                throw error
            } catch (_: Exception) {
            } finally {
                resolveInFlight.remove(requestKey)
                if (!succeeded && !cancelled) {
                    lastResolveFailureAt[requestKey] = System.currentTimeMillis()
                }
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
