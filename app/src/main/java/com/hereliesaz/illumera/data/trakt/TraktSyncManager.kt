package com.hereliesaz.illumera.data.trakt

import android.util.Log
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.SeriesNextUpEntity
import com.hereliesaz.illumera.data.model.WatchHistoryEntity
import com.hereliesaz.illumera.data.model.WatchlistEntity
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.model.trakt.TraktIds
import com.hereliesaz.illumera.data.model.trakt.TraktPlaybackItem
import com.hereliesaz.illumera.data.model.trakt.TraktSyncEpisode
import com.hereliesaz.illumera.data.model.trakt.TraktSyncItem
import com.hereliesaz.illumera.data.model.trakt.TraktSyncRequest
import com.hereliesaz.illumera.data.model.trakt.TraktSyncSeason
import com.hereliesaz.illumera.data.model.trakt.TraktWatchlistItem
import com.hereliesaz.illumera.data.remote.TraktSyncApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktSyncManager @Inject constructor(
    private val traktSyncApi: TraktSyncApiService,
    private val traktAuthManager: TraktAuthManager,
    private val dao: AddonDao,
    private val profileConfigurationManager: ProfileConfigurationManager
) {
    companion object {
        private const val TAG = "TraktSyncManager"
    }

    private val profileId: Int
        get() = profileConfigurationManager.getLastActiveProfileId() ?: 1

    private val syncMutex = Mutex()

    // Last known activity timestamps from Trakt (Fix #4/#10: volatile for thread safety)
    @Volatile private var lastWatchlistActivity: String? = null
    @Volatile private var lastPlaybackActivity: String? = null
    @Volatile private var lastWatchedActivity: String? = null

    // IDs currently being deleted from Trakt — sync skips these to prevent race conditions (Fix 5)
    // Fix #2: Thread-safe set for concurrent access from sync poll and delete operations
    private val pendingDeletes = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Lightweight check: queries /sync/last_activities and only runs
     * syncs for categories whose timestamps have changed.
     */
    suspend fun checkAndSync(): Boolean {
        if (traktAuthManager.getAccessToken() == null) return false

        return withContext(Dispatchers.IO) {
            try {
                val response = traktSyncApi.getLastActivities()
                if (!response.isSuccessful) {
                    Log.w(TAG, "last_activities failed: ${response.code()}")
                    return@withContext false
                }

                val activities = response.body() ?: return@withContext false
                var synced = false

                // Check watchlist changes
                val watchlistTimestamp = activities.watchlist?.updatedAt
                if (watchlistTimestamp != null && watchlistTimestamp != lastWatchlistActivity) {
                    Log.d(TAG, "Watchlist activity changed: $lastWatchlistActivity → $watchlistTimestamp")
                    lastWatchlistActivity = watchlistTimestamp
                    syncWatchlist()
                    synced = true
                }

                // Check playback progress changes (continue watching)
                val moviesPaused = activities.movies?.pausedAt
                val episodesPaused = activities.episodes?.pausedAt
                val playbackTimestamp = listOfNotNull(moviesPaused, episodesPaused).maxOrNull()
                if (playbackTimestamp != null && playbackTimestamp != lastPlaybackActivity) {
                    Log.d(TAG, "Playback activity changed: $lastPlaybackActivity → $playbackTimestamp")
                    lastPlaybackActivity = playbackTimestamp
                    syncPlaybackProgress()
                    synced = true
                }

                // Check watched history changes (movies or episodes)
                val moviesWatched = activities.movies?.watchedAt
                val episodesWatched = activities.episodes?.watchedAt
                val watchedTimestamp = listOfNotNull(moviesWatched, episodesWatched).maxOrNull()
                if (watchedTimestamp != null && watchedTimestamp != lastWatchedActivity) {
                    Log.d(TAG, "Watched activity changed: $lastWatchedActivity → $watchedTimestamp")
                    lastWatchedActivity = watchedTimestamp
                    syncSeriesNextUp()
                    synced = true
                }

                synced
            } catch (e: Exception) {
                Log.w(TAG, "Activity check failed", e)
                false
            }
        }
    }

    /**
     * Initial sync after first connecting Trakt.
     * Pushes all local items to Trakt, then does a normal sync.
     * Fix #11: 30-second timeout prevents blocking the mutex indefinitely.
     */
    suspend fun initialSync() {
        withTimeoutOrNull(30_000L) {
            syncMutex.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        val localItems = dao.getWatchlistOnce(profileId)
                        if (localItems.isNotEmpty()) {
                            pushToTrakt(localItems)
                            Log.d(TAG, "Initial push: ${localItems.size} items")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Initial push failed", e)
                    }
                }
            }
        } ?: Log.w(TAG, "Initial sync timed out")
        syncWatchlist()
    }

    /**
     * Periodic watchlist sync with diff.
     * Pulls new items and removes items deleted on Trakt.
     * Does NOT push — local adds are pushed instantly via pushAdd().
     */
    suspend fun syncWatchlist(): Result<Unit> = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                if (traktAuthManager.getAccessToken() == null) {
                    return@withContext Result.failure(Exception("Not connected to Trakt"))
                }

                // 1. Fetch Trakt watchlist
                val traktItems = fetchAllTraktWatchlist()
                    ?: return@withContext Result.failure(Exception("Failed to fetch Trakt watchlist"))

                // 2. Get local watchlist
                val localItems = dao.getWatchlistOnce(profileId)

                // 3. Build lookup sets
                val traktImdbIds = traktItems.mapNotNull { item ->
                    when (item.type) {
                        "movie" -> item.movie?.ids?.imdb
                        "show" -> item.show?.ids?.imdb
                        else -> null
                    }
                }.toSet()

                val localImdbIds = localItems.map { it.id }.toSet()

                // 4. Pull Trakt → local (items on Trakt but not local)
                val toPull = traktItems.filter { item ->
                    val imdbId = when (item.type) {
                        "movie" -> item.movie?.ids?.imdb
                        "show" -> item.show?.ids?.imdb
                        else -> null
                    }
                    imdbId != null && imdbId !in localImdbIds
                }
                if (toPull.isNotEmpty()) {
                    pullFromTrakt(toPull)
                    Log.d(TAG, "Pulled ${toPull.size} items from Trakt")
                }

                // 5. Remove local items no longer on Trakt (deleted externally).
                // Local adds are pushed instantly via pushAdd(), so anything
                // local but missing from Trakt was removed on Trakt's side.
                // Only consider items with IMDb-format IDs — items without them
                // can't be matched against Trakt's response. (Fix: audit #7)
                val toRemove = localItems.filter { it.id.startsWith("tt") && it.id !in traktImdbIds }
                for (item in toRemove) {
                    dao.removeFromWatchlist(profileId, item.id)
                    Log.d(TAG, "Removed ${item.title} (deleted on Trakt)")
                }

                Log.i(TAG, "Sync complete: pulled=${toPull.size}, removed=${toRemove.size}")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Watchlist sync failed: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Push a single item to Trakt watchlist (called when user adds locally).
     */
    suspend fun pushAdd(item: WatchlistEntity) {
        if (traktAuthManager.getAccessToken() == null) return
        withContext(Dispatchers.IO) {
            try {
                pushToTrakt(listOf(item))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to push watchlist add to Trakt", e)
            }
        }
    }

    /**
     * Push a removal to Trakt watchlist (called when user removes locally).
     */
    suspend fun pushRemove(itemId: String, type: String) {
        if (traktAuthManager.getAccessToken() == null) return
        withContext(Dispatchers.IO) {
            try {
                val item = listOf(TraktSyncItem(ids = TraktIds(imdb = itemId)))
                val body = if (type == "movie") TraktSyncRequest(movies = item)
                           else TraktSyncRequest(shows = item)
                val response = traktSyncApi.removeFromWatchlist(body)
                if (!response.isSuccessful) {
                    Log.w(TAG, "Trakt watchlist remove failed: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to push watchlist remove to Trakt", e)
            }
        }
    }

    /** Add a movie/show to the user's Trakt collection (Library). */
    suspend fun pushAddToCollection(imdbId: String, type: String) {
        if (traktAuthManager.getAccessToken() == null || !imdbId.startsWith("tt")) return
        withContext(Dispatchers.IO) {
            try {
                val item = listOf(TraktSyncItem(ids = TraktIds(imdb = imdbId)))
                val body = if (type == "movie") TraktSyncRequest(movies = item)
                    else TraktSyncRequest(shows = item)
                val response = traktSyncApi.addToCollection(body)
                if (!response.isSuccessful) {
                    Log.w(TAG, "Trakt collection add failed: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add item to Trakt collection", e)
            }
        }
    }

    /** Push a whole set of show episodes in one Trakt history request. */
    suspend fun pushSeriesEpisodesWatched(
        showImdbId: String,
        episodes: List<Pair<Int, Int>>,
        watched: Boolean
    ) {
        if (traktAuthManager.getAccessToken() == null || !showImdbId.startsWith("tt") || episodes.isEmpty()) return
        withContext(Dispatchers.IO) {
            try {
                val seasons = episodes
                    .groupBy { it.first }
                    .toSortedMap()
                    .map { (season, entries) ->
                        TraktSyncSeason(
                            number = season,
                            episodes = entries.map { TraktSyncEpisode(it.second) }.distinctBy { it.number }
                        )
                    }
                val body = TraktSyncRequest(
                    shows = listOf(
                        TraktSyncItem(
                            ids = TraktIds(imdb = showImdbId),
                            seasons = seasons
                        )
                    )
                )
                val response = if (watched) traktSyncApi.addToHistory(body) else traktSyncApi.removeFromHistory(body)
                if (!response.isSuccessful) {
                    Log.w(TAG, "Trakt bulk history update failed: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update series history on Trakt", e)
            }
        }
    }

    // ── Watch History (mark as watched) ──

    /**
     * Mark a movie as watched on Trakt.
     */
    suspend fun pushMovieWatched(imdbId: String) {
        if (traktAuthManager.getAccessToken() == null) return
        withContext(Dispatchers.IO) {
            try {
                val body = TraktSyncRequest(movies = listOf(TraktSyncItem(ids = TraktIds(imdb = imdbId))))
                val response = traktSyncApi.addToHistory(body)
                Log.d(TAG, "pushMovieWatched $imdbId: ${response.code()}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to push movie watched to Trakt", e)
            }
        }
    }

    /**
     * Remove a movie from watched history on Trakt.
     */
    suspend fun pushMovieUnwatched(imdbId: String) {
        if (traktAuthManager.getAccessToken() == null) return
        withContext(Dispatchers.IO) {
            try {
                val body = TraktSyncRequest(movies = listOf(TraktSyncItem(ids = TraktIds(imdb = imdbId))))
                val response = traktSyncApi.removeFromHistory(body)
                Log.d(TAG, "pushMovieUnwatched $imdbId: ${response.code()}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to push movie unwatched to Trakt", e)
            }
        }
    }

    /**
     * Mark an episode as watched on Trakt.
     */
    suspend fun pushEpisodeWatched(showImdbId: String, season: Int, episode: Int) {
        if (traktAuthManager.getAccessToken() == null) return
        withContext(Dispatchers.IO) {
            try {
                val body = TraktSyncRequest(
                    shows = listOf(
                        TraktSyncItem(
                            ids = TraktIds(imdb = showImdbId),
                            seasons = listOf(TraktSyncSeason(season, listOf(TraktSyncEpisode(episode))))
                        )
                    )
                )
                val response = traktSyncApi.addToHistory(body)
                Log.d(TAG, "pushEpisodeWatched S${season}E${episode}: ${response.code()}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to push episode watched to Trakt", e)
            }
        }
    }

    /**
     * Remove an episode from watched history on Trakt.
     */
    suspend fun pushEpisodeUnwatched(showImdbId: String, season: Int, episode: Int) {
        if (traktAuthManager.getAccessToken() == null) return
        withContext(Dispatchers.IO) {
            try {
                val body = TraktSyncRequest(
                    shows = listOf(
                        TraktSyncItem(
                            ids = TraktIds(imdb = showImdbId),
                            seasons = listOf(TraktSyncSeason(season, listOf(TraktSyncEpisode(episode))))
                        )
                    )
                )
                val response = traktSyncApi.removeFromHistory(body)
                Log.d(TAG, "pushEpisodeUnwatched S${season}E${episode}: ${response.code()}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to push episode unwatched to Trakt", e)
            }
        }
    }

    /**
     * Full playback progress sync with diff logic.
     *
     * For each LOCAL in-progress item where scrobbled == true:
     *   - On Trakt playback?        → keep local (more accurate position)
     *   - Not on playback, on watched? → mark as watched locally (finished on other app)
     *   - Not on either?            → user cleared it on Trakt → delete locally
     *
     * For each LOCAL in-progress item where scrobbled == false:
     *   → Never touched Trakt. Leave it alone.
     *
     * For each item on Trakt playback NOT in local:
     *   → Pull to Lumera (watched on other app)
     */
    suspend fun syncPlaybackProgress() {
        withContext(Dispatchers.IO) {
            try {
                // 1. Fetch Trakt playback progress
                val playbackResponse = traktSyncApi.getPlaybackProgress()
                if (!playbackResponse.isSuccessful) {
                    Log.w(TAG, "Playback progress fetch failed: ${playbackResponse.code()}")
                    return@withContext
                }
                val traktPlayback = playbackResponse.body() ?: emptyList()

                // 2. Build lookup: IMDb ID → TraktPlaybackItem
                val traktPlaybackIds = mutableMapOf<String, TraktPlaybackItem>()
                for (item in traktPlayback) {
                    val id = when (item.type) {
                        "movie" -> item.movie?.ids?.imdb
                        "episode" -> {
                            val showImdb = item.show?.ids?.imdb ?: continue
                            val ep = item.episode ?: continue
                            "$showImdb:${ep.season}:${ep.number}"
                        }
                        else -> null
                    }
                    if (id != null) traktPlaybackIds[id] = item
                }

                // 3. Fetch Trakt watched history with episode-level detail (Fix 2)
                // Fix #7: If this fails, we get null — skip removals entirely to prevent data loss
                val watchedData = fetchWatchedData()

                // 4. Process local scrobbled in-progress items
                val scrobbledItems = dao.getScrobbledInProgressItems()
                var removed = 0
                var markedWatched = 0

                for (local in scrobbledItems) {
                    // Fix 5: skip items currently being deleted to prevent race
                    if (local.id in pendingDeletes) continue

                    // Fix 1: strip stream index suffix for lookup
                    val normalizedId = normalizePlaybackId(local.id)

                    val onTraktPlayback = normalizedId in traktPlaybackIds

                    if (onTraktPlayback) {
                        // Still in progress on Trakt — keep local version (more accurate)
                        continue
                    }

                    // Fix #7: If watched data fetch failed, we can't determine if
                    // the item was cleared vs finished. Skip to avoid data loss.
                    if (watchedData == null) continue

                    // Not on Trakt playback. Check if the specific item was watched (Fix 2)
                    val onTraktWatched = if (local.type == "series") {
                        val parts = normalizedId.split(":")
                        if (parts.size >= 3) {
                            val showImdb = parts.dropLast(2).joinToString(":")
                            val season = parts[parts.size - 2].toIntOrNull()
                            val episode = parts[parts.size - 1].toIntOrNull()
                            season != null && episode != null && watchedData.isEpisodeWatched(showImdb, season, episode)
                        } else false
                    } else {
                        watchedData.isMovieWatched(local.id)
                    }

                    if (onTraktWatched) {
                        // Finished on another app → mark as watched locally
                        dao.upsertHistory(local.copy(watched = true))
                        markedWatched++
                        Log.d(TAG, "Marked watched (finished elsewhere): ${local.title}")
                    } else {
                        // Not on playback, not on watched → user cleared it on Trakt
                        dao.deleteHistoryItem(local.id)
                        removed++
                        Log.d(TAG, "Removed (cleared on Trakt): ${local.title}")
                    }
                }

                // 5. Pull new items from Trakt playback that aren't local
                var added = 0
                for ((id, item) in traktPlaybackIds) {
                    // Fix 5: skip items being deleted
                    if (id in pendingDeletes) continue

                    val type = if (item.type == "movie") "movie" else "series"

                    // Don't overwrite existing local progress (check both exact and with stream index)
                    val existing = dao.getHistoryItem(id)
                    if (existing != null) continue
                    if (type == "series") {
                        val hasLocal = dao.getLatestSeriesEpisodeHistory("$id:%") != null
                        if (hasLocal) continue
                    }

                    val title = when (item.type) {
                        "movie" -> item.movie?.title ?: "Unknown"
                        else -> {
                            val ep = item.episode
                            val showTitle = item.show?.title ?: "Unknown"
                            when {
                                ep?.title != null -> ep.title
                                ep?.season != null && ep.number != null -> "S${ep.season}:E${ep.number} - $showTitle"
                                else -> showTitle
                            }
                        }
                    }

                    // Fix #12: clamp progress to [0,100] and ensure position never exceeds duration
                    val estimatedDurationMs = 90 * 60 * 1000L
                    val clampedProgress = item.progress.coerceIn(0f, 100f)
                    val estimatedPositionMs = ((clampedProgress / 100f) * estimatedDurationMs).toLong()
                        .coerceIn(0L, estimatedDurationMs)

                    dao.upsertHistory(
                        WatchHistoryEntity(
                            id = id,
                            title = title,
                            poster = null,
                            position = estimatedPositionMs,
                            duration = estimatedDurationMs,
                            lastWatched = parseIsoTimestamp(item.pausedAt),
                            type = type,
                            watched = false,
                            scrobbled = true
                        )
                    )
                    added++
                }

                Log.i(TAG, "Playback sync: added=$added, removed=$removed, markedWatched=$markedWatched")
            } catch (e: Exception) {
                Log.e(TAG, "Playback progress sync failed", e)
            }
        }
    }

    /**
     * Sync series next-up entries from Trakt.
     * For each watched show on Trakt, fetches the show's watched progress
     * to get the next episode, and updates the local series_next_up table.
     */
    suspend fun syncSeriesNextUp() {
        withContext(Dispatchers.IO) {
            try {
                // Sync watched movies from Trakt
                val moviesResponse = traktSyncApi.getWatchedMovies()
                val traktWatchedMovieIds = mutableSetOf<String>()
                if (moviesResponse.isSuccessful) {
                    moviesResponse.body()?.forEach { watchedMovie ->
                        val imdbId = watchedMovie.movie.ids.imdb ?: return@forEach
                        traktWatchedMovieIds.add(imdbId)
                        val existing = dao.getHistoryItem(imdbId)
                        if (existing == null) {
                            dao.upsertHistory(
                                WatchHistoryEntity(
                                    id = imdbId,
                                    title = watchedMovie.movie.title ?: "Unknown",
                                    poster = null,
                                    position = 0L,
                                    duration = 0L,
                                    lastWatched = parseIsoTimestamp(watchedMovie.lastWatchedAt),
                                    type = "movie",
                                    watched = true,
                                    scrobbled = true
                                )
                            )
                        } else if (!existing.watched) {
                            dao.upsertHistory(existing.copy(watched = true))
                        }
                    }
                }

                // Get all watched shows from Trakt
                val showsResponse = traktSyncApi.getWatchedShows()
                if (!showsResponse.isSuccessful) {
                    Log.w(TAG, "Failed to fetch watched shows for next-up: ${showsResponse.code()}")
                    return@withContext
                }
                val watchedShows = showsResponse.body() ?: return@withContext

                val traktWatchedEpisodeIds = mutableSetOf<String>()
                var updated = 0
                for (show in watchedShows) {
                    val imdbId = show.show.ids.imdb ?: continue
                    val traktSlug = show.show.ids.slug ?: continue
                    val showTitle = show.show.title ?: "Unknown"

                    // Create watched history entries for episodes marked watched on Trakt
                    show.seasons?.forEach { season ->
                        season.episodes?.forEach { ep ->
                            val playbackId = "$imdbId:${season.number}:${ep.number}"
                            traktWatchedEpisodeIds.add(playbackId)
                            val existing = dao.getHistoryItem(playbackId)
                            if (existing == null) {
                                dao.upsertHistory(
                                    WatchHistoryEntity(
                                        id = playbackId,
                                        title = "S${season.number}:E${ep.number} - $showTitle",
                                        poster = null,
                                        position = 0L,
                                        duration = 0L,
                                        lastWatched = parseIsoTimestamp(ep.lastWatchedAt),
                                        type = "series",
                                        watched = true,
                                        scrobbled = true
                                    )
                                )
                            } else if (!existing.watched) {
                                dao.upsertHistory(existing.copy(watched = true))
                            }
                        }
                    }

                    try {
                        val progressResponse = traktSyncApi.getShowProgress(traktSlug)
                        if (!progressResponse.isSuccessful) continue
                        val progress = progressResponse.body() ?: continue

                        val nextEp = progress.nextEpisode
                        val existing = dao.getSeriesNextUp(profileId, imdbId)
                        if (nextEp != null) {
                            val airDate = nextEp.firstAired?.take(10)
                            val unchanged = existing != null &&
                                !existing.isComplete &&
                                existing.nextSeason == nextEp.season &&
                                existing.nextEpisode == nextEp.number
                            val revived = existing?.isComplete == true
                            val badgeState = when {
                                revived -> true
                                unchanged -> existing?.isNewEpisode ?: false
                                else -> false
                            }

                            dao.upsertSeriesNextUp(
                                SeriesNextUpEntity(
                                    profileId = profileId,
                                    seriesId = imdbId,
                                    title = show.show.title ?: "Unknown",
                                    poster = existing?.poster,
                                    nextSeason = nextEp.season,
                                    nextEpisode = nextEp.number,
                                    nextEpisodeTitle = nextEp.title,
                                    nextReleased = airDate,
                                    isComplete = false,
                                    isNewEpisode = badgeState,
                                    updatedAt = if (unchanged) existing.updatedAt else parseIsoTimestamp(show.lastWatchedAt)
                                )
                            )
                            if (!unchanged) updated++
                        } else {
                            if (existing != null && !existing.isComplete) {
                                dao.upsertSeriesNextUp(existing.copy(isComplete = true, updatedAt = System.currentTimeMillis()))
                                updated++
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fetch progress for $traktSlug", e)
                    }
                }

                // Reverse sync: unmark local items no longer watched on Trakt
                if (moviesResponse.isSuccessful && showsResponse.isSuccessful) {
                    val localWatched = dao.getScrobbledWatchedItems()
                    var unmarked = 0
                    for (item in localWatched) {
                        val normalizedId = normalizePlaybackId(item.id)
                        val stillWatched = if (item.type == "movie") {
                            item.id in traktWatchedMovieIds
                        } else {
                            normalizedId in traktWatchedEpisodeIds
                        }
                        if (!stillWatched) {
                            dao.deleteHistoryItem(item.id)
                            unmarked++
                            Log.d(TAG, "Unmarked (removed from Trakt): ${item.title}")
                        }
                    }
                    if (unmarked > 0) Log.i(TAG, "Reverse watched sync: unmarked $unmarked items")
                }

                Log.i(TAG, "Series next-up sync: updated $updated shows")
            } catch (e: Exception) {
                Log.e(TAG, "Series next-up sync failed", e)
            }
        }
    }

    /** Mark IDs as pending deletion so the sync poll doesn't re-add them. */
    fun markPendingDelete(ids: List<String>) {
        for (id in ids) {
            pendingDeletes.add(id)
            pendingDeletes.add(normalizePlaybackId(id))
        }
    }

    /** Remove IDs from pending deletion (e.g., user hit undo). */
    fun unmarkPendingDelete(ids: List<String>) {
        for (id in ids) {
            pendingDeletes.remove(id)
            pendingDeletes.remove(normalizePlaybackId(id))
        }
    }

    /** Reset stored activity timestamps (e.g., on profile switch). */
    fun resetActivityState() {
        lastWatchlistActivity = null
        lastPlaybackActivity = null
        lastWatchedActivity = null
    }

    // ── Internal ──

    /**
     * Fetch the complete Trakt watchlist. Returns null if ANY page fails,
     * so callers never act on incomplete data. (Fix #3)
     */
    private suspend fun fetchAllTraktWatchlist(): List<TraktWatchlistItem>? {
        val allItems = mutableListOf<TraktWatchlistItem>()
        var page = 1

        while (true) {
            val response = traktSyncApi.getWatchlist(page = page, limit = 100)
            if (!response.isSuccessful) {
                Log.w(TAG, "Trakt watchlist fetch failed on page $page: ${response.code()}")
                return null // Fail entirely — never return partial data
            }
            val items = response.body() ?: break
            if (items.isEmpty()) break
            allItems.addAll(items)
            if (items.size < 100) break
            page++
        }

        return allItems
    }

    private suspend fun pushToTrakt(items: List<WatchlistEntity>) {
        val movies = items.filter { it.type == "movie" }
            .map { TraktSyncItem(ids = TraktIds(imdb = it.id)) }
        val shows = items.filter { it.type == "series" }
            .map { TraktSyncItem(ids = TraktIds(imdb = it.id)) }

        val body = TraktSyncRequest(
            movies = movies.ifEmpty { null },
            shows = shows.ifEmpty { null }
        )

        Log.d(TAG, "pushToTrakt: movies=${movies.size}, shows=${shows.size}")
        if (movies.isNotEmpty() || shows.isNotEmpty()) {
            val response = traktSyncApi.addToWatchlist(body)
            Log.d(TAG, "pushToTrakt response: ${response.code()}")
            if (!response.isSuccessful) {
                Log.w(TAG, "Trakt watchlist push failed: ${response.code()} - ${response.errorBody()?.string()}")
            }
        }
    }

    /**
     * Strip stream index suffix from playback ID for Trakt lookup. (Fix 1)
     * "tt123:1:3:0" → "tt123:1:3"  (has stream index)
     * "tt123:1:3"   → "tt123:1:3"  (already normalized)
     * "tt123"       → "tt123"      (movie, no change)
     */
    private fun normalizePlaybackId(id: String): String {
        val parts = id.split(":")
        // Episode with stream index: 4+ parts where last is numeric (stream idx)
        // and second-to-last and third-to-last are also numeric (episode, season)
        if (parts.size >= 4 &&
            parts.last().toIntOrNull() != null &&
            parts[parts.size - 2].toIntOrNull() != null &&
            parts[parts.size - 3].toIntOrNull() != null
        ) {
            return parts.dropLast(1).joinToString(":")
        }
        return id
    }

    /**
     * Watched data from Trakt with episode-level granularity. (Fix 2)
     */
    private class WatchedData(
        val movieIds: Set<String>,
        val episodeMap: Map<String, Set<Pair<Int, Int>>> // showImdbId → set of (season, episode)
    ) {
        fun isMovieWatched(imdbId: String) = imdbId in movieIds
        fun isEpisodeWatched(showImdbId: String, season: Int, episode: Int): Boolean {
            return episodeMap[showImdbId]?.contains(Pair(season, episode)) ?: false
        }
    }

    /**
     * Fetch watched history with episode-level detail.
     * Returns null on failure so callers can skip removal logic. (Fix #7)
     */
    private suspend fun fetchWatchedData(): WatchedData? {
        val movieIds = mutableSetOf<String>()
        val episodeMap = mutableMapOf<String, MutableSet<Pair<Int, Int>>>()
        try {
            val moviesResponse = traktSyncApi.getWatchedMovies()
            if (!moviesResponse.isSuccessful) return null
            moviesResponse.body()?.forEach { it.movie.ids.imdb?.let { id -> movieIds.add(id) } }

            val showsResponse = traktSyncApi.getWatchedShows()
            if (!showsResponse.isSuccessful) return null
            showsResponse.body()?.forEach { show ->
                val showImdb = show.show.ids.imdb ?: return@forEach
                val episodes = episodeMap.getOrPut(showImdb) { mutableSetOf() }
                show.seasons?.forEach { season ->
                    season.episodes?.forEach { ep ->
                        episodes.add(Pair(season.number, ep.number))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch watched history", e)
            return null
        }
        return WatchedData(movieIds, episodeMap)
    }

    /**
     * Delete a playback item from Trakt by finding its Trakt playback ID.
     * Called when user clears progress in Lumera.
     */
    /**
     * Delete playback items from Trakt. Uses pendingDeletes to prevent
     * the sync poll from re-adding them during the delete operation. (Fix 5)
     */
    suspend fun deletePlaybackFromTrakt(localId: String) {
        if (traktAuthManager.getAccessToken() == null) return
        val normalizedId = normalizePlaybackId(localId)
        pendingDeletes.add(normalizedId)
        pendingDeletes.add(localId)
        withContext(Dispatchers.IO) {
            try {
                val response = traktSyncApi.getPlaybackProgress()
                if (!response.isSuccessful) return@withContext
                val items = response.body() ?: return@withContext

                for (item in items) {
                    val traktId = when (item.type) {
                        "movie" -> item.movie?.ids?.imdb
                        "episode" -> {
                            val showImdb = item.show?.ids?.imdb ?: continue
                            val ep = item.episode ?: continue
                            "$showImdb:${ep.season}:${ep.number}"
                        }
                        else -> null
                    } ?: continue

                    if (traktId == normalizedId) {
                        val playbackId = item.id ?: continue
                        val deleteResponse = traktSyncApi.deletePlaybackItem(playbackId)
                        Log.d(TAG, "Deleted playback $playbackId from Trakt: ${deleteResponse.code()}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete playback from Trakt", e)
            } finally {
                pendingDeletes.remove(normalizedId)
                pendingDeletes.remove(localId)
            }
        }
    }

    private suspend fun pullFromTrakt(items: List<TraktWatchlistItem>) {
        for (item in items) {
            val (id, title, type) = when (item.type) {
                "movie" -> Triple(
                    item.movie?.ids?.imdb ?: continue,
                    item.movie?.title ?: "Unknown",
                    "movie"
                )
                "show" -> Triple(
                    item.show?.ids?.imdb ?: continue,
                    item.show?.title ?: "Unknown",
                    "series"
                )
                else -> continue
            }

            dao.addToWatchlist(
                WatchlistEntity(
                    profileId = profileId,
                    id = id,
                    type = type,
                    title = title,
                    poster = null,
                    addedAt = System.currentTimeMillis()
                )
            )
        }
    }

    private fun parseIsoTimestamp(iso: String?): Long {
        if (iso == null) return System.currentTimeMillis()
        return try {
            java.time.Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }
}
