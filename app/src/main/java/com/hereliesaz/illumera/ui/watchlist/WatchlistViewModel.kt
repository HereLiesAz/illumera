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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val metadataResolveSlots = Semaphore(3)
    // Cools down retries for items that just failed to resolve, instead of retrying
    // every one of them on every watchlist mutation (the movieItems/seriesItems
    // flows re-emit the whole list on any add/remove, which otherwise re-triggers
    // resolvePosterIfNeeded for every previously-failed item every time).
    private val lastResolveFailureAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val resolveFailureCooldownMs = 5 * 60_000L
    private val enrichedLibraryMetas = MutableStateFlow<Map<String, MetaItem>>(emptyMap())

    var lastFocusedKey: String? = null
    var lastFocusedMediaType: String? = null
    var lastQueueFocusedKey: String? = null

    val movieRowState = androidx.compose.foundation.lazy.LazyListState()
    val seriesRowState = androidx.compose.foundation.lazy.LazyListState()
    val libraryRowState = androidx.compose.foundation.lazy.LazyListState()

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
            else combine(
                dao.getWatchlist(profileId), dao.getWatchHistory(), enrichedLibraryMetas
            ) { saved, history, enrichment ->
                fun enriched(item: MetaItem): MetaItem {
                    val extra = enrichment["$profileId:${item.type}:${item.id}"] ?: return item
                    return item.copy(
                        releaseInfo = item.releaseInfo ?: extra.releaseInfo,
                        poster = item.poster?.takeIf { it.isNotBlank() }
                            ?: extra.poster?.takeIf { it.isNotBlank() },
                        background = item.background?.takeIf { it.isNotBlank() }
                            ?: extra.background?.takeIf { it.isNotBlank() },
                        logo = item.logo?.takeIf { it.isNotBlank() }
                            ?: extra.logo?.takeIf { it.isNotBlank() }
                    )
                }
                val historyEntries = history.mapNotNull { entry ->
                    if (entry.type !in setOf("movie", "series", "tv", "episode")) return@mapNotNull null
                    val parts = entry.id.split(':')
                    if (entry.type == "episode" && entry.seriesId.isNullOrBlank() &&
                        (parts.size < 3 ||
                            parts.takeLast(2).any { it.toIntOrNull() == null })
                    ) return@mapNotNull null
                    val episodeParent = if (
                        entry.type in setOf("series", "tv", "episode") && parts.size >= 3 &&
                        parts.takeLast(2).all { it.toIntOrNull() != null }
                    ) parts.dropLast(2).joinToString(":") else entry.id
                    val canonicalId = entry.seriesId?.takeIf { it.isNotBlank() } ?: episodeParent
                    val mediaType = if (entry.seriesId != null ||
                        entry.type in setOf("series", "tv", "episode")
                    ) "series" else entry.type
                    val item = MetaItem(
                        id = canonicalId, type = mediaType, name = entry.title,
                        poster = entry.poster, background = entry.background, logo = entry.logo
                    )
                    LibraryEntry(enriched(item), 0L, entry.lastWatched, entry.watched, if (entry.watched) 1 else 0)
                }
                val savedEntries = saved.map { item ->
                    LibraryEntry(enriched(item.toMetaItem()), item.addedAt, 0L, false, 0)
                }
                aggregateLibraryEntries(savedEntries + historyEntries)
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
        if (!item.poster.isNullOrBlank() && !item.releaseInfo.isNullOrBlank()) return
        val ownerProfileId = profileConfigurationManager.activeProfileId.value ?: return
        val requestKey = "$ownerProfileId:${item.type}:${item.id}"
        val lastFailure = lastResolveFailureAt[requestKey]
        if (lastFailure != null && System.currentTimeMillis() - lastFailure < resolveFailureCooldownMs) return
        if (!resolveInFlight.add(requestKey)) return

        viewModelScope.launch(Dispatchers.IO) {
            var succeeded = false
            var cancelled = false
            try {
                // Only three metadata lookups run at once even when a large Library
                // contains several ambiguous cross-provider identities.
                metadataResolveSlots.withPermit {
                // Network resolution must not hold the profile runtime lock. A slow addon
                // should never block loadRuntimeState() during a profile switch.
                val meta = repository.resolveMetaDetails(item.type, item.id)
                if (meta != null &&
                    profileConfigurationManager.activeProfileId.value == ownerProfileId
                ) {
                    enrichedLibraryMetas.update { current ->
                        // Release year and alternate-provider images become visible
                        // immediately; no schema migration or local bookmark is needed.
                        if (current.size > 400) emptyMap<String, MetaItem>() + (requestKey to meta)
                        else current + (requestKey to meta)
                    }
                    succeeded = true
                }
                val poster = meta?.poster?.takeIf { it.isNotBlank() }
                if (poster != null) {
                    profileConfigurationManager.withActiveProfileRuntime(ownerProfileId) {
                        val existing = dao.getWatchlistItem(ownerProfileId, item.id)
                        if (existing != null) {
                            dao.addToWatchlist(existing.copy(poster = poster))
                            succeeded = true
                        } else {
                            // History-only Library items have no bookmark row.
                            // Persist artwork against their underlying episode/title
                            // history IDs so the Flow re-emits the resolved card.
                            val history = dao.getAllWatchHistoryOnce().filter { entry ->
                                entry.id == item.id || entry.seriesId == item.id ||
                                    (entry.type in setOf("series", "tv", "episode") &&
                                        entry.id.substringBeforeLast(':').substringBeforeLast(':') == item.id)
                            }
                            history.forEach { entry ->
                                dao.updateHistoryImages(
                                    entry.id, poster,
                                    meta.background?.takeIf { it.isNotBlank() } ?: entry.background,
                                    meta.logo?.takeIf { it.isNotBlank() } ?: entry.logo
                                )
                            }
                            succeeded = history.isNotEmpty()
                        }
                    }
                }
                } // Metadata semaphore slot.
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

/**
 * Deduplicate saved, synchronized and locally watched entries in one Library.
 * Never merge movies with TV series or distinct canonical IMDb IDs, even if
 * two titles happen to share a name. Alternative provider IDs can match by
 * normalized title and compatible release year when one has a canonical ID.
 */
internal fun aggregateLibraryEntries(
    entries: List<WatchlistViewModel.LibraryEntry>
): List<WatchlistViewModel.LibraryEntry> {
    val result = mutableListOf<WatchlistViewModel.LibraryEntry>()
    val imdbPattern = Regex("^tt[0-9]+$")
    val yearPattern = Regex("(?:19|20)[0-9]{2}")
    fun norm(item: MetaItem) = item.name.lowercase().filter { it.isLetterOrDigit() }
    fun year(item: MetaItem) =
        yearPattern.find(item.releaseInfo ?: item.name)?.value

    entries.forEach { incoming ->
        val item = incoming.item
        if (item.type !in setOf("movie", "series")) return@forEach
        val matchingIndex = result.indexOfFirst { existing ->
            val old = existing.item
            if (old.type != item.type) return@indexOfFirst false
            if (old.id == item.id) return@indexOfFirst true
            val oldImdb = old.id.takeIf { imdbPattern.matches(it) }
            val newImdb = item.id.takeIf { imdbPattern.matches(it) }
            if (oldImdb != null && newImdb != null) return@indexOfFirst false
            val oldYear = year(old)
            val newYear = year(item)
            // Matching only a name when release years are unknown collapses
            // different adaptations/remakes. Never perform that unsafe merge.
            norm(old).isNotEmpty() && norm(old) == norm(item) &&
                oldYear != null && oldYear == newYear
        }
        if (matchingIndex < 0) {
            result.add(incoming)
        } else {
            val old = result[matchingIndex]
            // Preserve an actionable saved-bookmark ID when available; otherwise
            // prefer an IMDb identity for cross-addon metadata and playback.
            val preferred = when {
                old.addedAt > 0L && incoming.addedAt == 0L -> old
                incoming.addedAt > 0L && old.addedAt == 0L -> incoming
                imdbPattern.matches(incoming.item.id) && !imdbPattern.matches(old.item.id) -> incoming
                else -> old
            }
            val alternate = if (preferred === old) incoming else old
            result[matchingIndex] = preferred.copy(
                item = preferred.item.copy(
                    poster = preferred.item.poster?.takeIf { it.isNotBlank() }
                        ?: alternate.item.poster?.takeIf { it.isNotBlank() },
                    background = preferred.item.background?.takeIf { it.isNotBlank() }
                        ?: alternate.item.background?.takeIf { it.isNotBlank() },
                    logo = preferred.item.logo?.takeIf { it.isNotBlank() }
                        ?: alternate.item.logo?.takeIf { it.isNotBlank() },
                    releaseInfo = preferred.item.releaseInfo ?: alternate.item.releaseInfo
                ),
                addedAt = maxOf(old.addedAt, incoming.addedAt),
                lastWatched = maxOf(old.lastWatched, incoming.lastWatched),
                watched = old.watched || incoming.watched,
                timesWatched = old.timesWatched + incoming.timesWatched
            )
        }
    }
    return result
}

private fun WatchlistEntity.toMetaItem() = MetaItem(
    id = id,
    type = type,
    name = title,
    poster = poster
)
