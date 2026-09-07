package com.hereliesaz.illumera.data.auth

import android.util.Log
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.WatchHistoryEntity
import com.hereliesaz.illumera.data.remote.StremioAuthService
import com.hereliesaz.illumera.data.remote.StremioLibraryItem
import com.hereliesaz.illumera.data.remote.StremioLibraryItemState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Two-way sync of Continue Watching / library state against a connected
 * Stremio account, using the same `datastoreMeta`/`datastoreGet`/`datastorePut`
 * API stremio-core itself uses (see StremioAuthService).
 *
 * Model mismatch this reconciles: locally, every episode gets its own
 * `watch_history` row (`seriesId:season:episode`), but Stremio's library has
 * exactly one item per *series*, with the current episode named by
 * `state.videoId`. So for series, only the most-recently-watched local
 * episode is treated as "the" item to push, and a pulled item is applied to
 * that one local episode row (by `state.videoId`) rather than the series as
 * a whole. Movies map 1:1 on their IMDb id.
 */
@Singleton
class StremioLibrarySyncManager @Inject constructor(
    private val stremioAuthManager: StremioAuthManager,
    private val stremioAuthService: StremioAuthService,
    private val dao: AddonDao
) {
    companion object {
        private const val TAG = "StremioLibrarySync"

        // Playback position saves fire every ~10s during active playback, but
        // stremio-core itself only calls the account API "when a sync is
        // planned" — never on a timer — so opportunistic callers (like
        // PlayerViewModel.saveProgress) are throttled to this interval rather
        // than hitting the network on every tick.
        private const val MIN_SYNC_INTERVAL_MS = 60_000L
    }

    private val syncMutex = Mutex()
    private var lastSyncAtMs = 0L

    /**
     * Syncs Continue Watching state. [force] bypasses the opportunistic
     * throttle — use it for explicit user actions (manual "Sync" button,
     * right after login), not for per-tick playback callers.
     *
     * Returns a [Result] so explicit foreground refreshes can accurately tell
     * the user when the network sync failed instead of always reporting success.
     */
    suspend fun syncLibrary(force: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        val authKey = stremioAuthManager.getStoredAuthKey()
            ?: return@withContext Result.success(Unit)

        syncMutex.withLock {
            val now = System.currentTimeMillis()
            if (!force && now - lastSyncAtMs < MIN_SYNC_INTERVAL_MS) {
                return@withContext Result.success(Unit)
            }
            lastSyncAtMs = now
        }

        try {
            val remoteMtimes = stremioAuthService.datastoreMeta(authKey)
            val localItems = buildLocalLibraryView()

            val toPush = mutableListOf<StremioLibraryItem>()
            val idsToPull = mutableListOf<String>()

            for ((id, local) in localItems) {
                val remoteMtime = remoteMtimes[id]
                when {
                    remoteMtime == null || local.lastWatched > remoteMtime -> toPush.add(local.toLibraryItem(id))
                    remoteMtime > local.lastWatched -> idsToPull.add(id)
                }
            }
            for (id in remoteMtimes.keys) {
                if (id !in localItems) idsToPull.add(id)
            }

            if (toPush.isNotEmpty()) {
                stremioAuthService.datastorePut(authKey, toPush)
            }
            if (idsToPull.isNotEmpty()) {
                val remoteItems = stremioAuthService.datastoreGet(authKey, idsToPull)
                for (item in remoteItems) applyRemoteItem(item)
            }
            Log.i(TAG, "Library sync: pushed=${toPush.size}, pulled=${idsToPull.size}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Library sync failed", e)
            Result.failure(e)
        }
    }

    /** One entry per movie, plus one per series keyed by series id (latest-watched episode). */
    private suspend fun buildLocalLibraryView(): Map<String, WatchHistoryEntity> {
        val all = dao.getAllWatchHistoryOnce()
        val result = LinkedHashMap<String, WatchHistoryEntity>()
        for (item in all) {
            if (item.type == "series") {
                val seriesId = parseSeriesId(item.id) ?: continue
                val current = result[seriesId]
                if (current == null || item.lastWatched > current.lastWatched) result[seriesId] = item
            } else {
                result[item.id] = item
            }
        }
        return result
    }

    private suspend fun applyRemoteItem(item: StremioLibraryItem) {
        val mtimeMs = runCatching { Instant.parse(item.mtime).toEpochMilli() }.getOrNull() ?: return

        if (item.removed) {
            if (item.type == "series") {
                dao.getHistoryItemsByPrefix("${item.id}:").forEach { dao.deleteHistoryItem(it.id) }
            } else {
                dao.deleteHistoryItem(item.id)
            }
            return
        }

        val localId = if (item.type == "series") {
            val videoId = item.state.videoId ?: return
            // Only apply if the addon's episode id matches this series' id scheme —
            // otherwise we have no local row to attach the progress to.
            if (!videoId.startsWith("${item.id}:")) return
            videoId
        } else {
            item.id
        }

        val existing = dao.getHistoryItem(localId)
        if (existing != null && existing.lastWatched >= mtimeMs) return // local is newer or equal, keep it

        val duration = item.state.duration.coerceAtLeast(item.state.timeOffset).coerceAtLeast(1L)
        dao.upsertHistory(
            WatchHistoryEntity(
                id = localId,
                title = existing?.title ?: item.name,
                poster = existing?.poster ?: item.poster,
                background = existing?.background,
                logo = existing?.logo,
                position = item.state.timeOffset,
                duration = duration,
                lastWatched = mtimeMs,
                type = item.type,
                watched = item.state.timesWatched > 0,
                scrobbled = existing?.scrobbled ?: false
            )
        )
    }

    private fun WatchHistoryEntity.toLibraryItem(libraryId: String): StremioLibraryItem {
        val nowIso = Instant.ofEpochMilli(lastWatched).toString()
        return StremioLibraryItem(
            id = libraryId,
            name = title,
            type = type,
            poster = poster,
            mtime = nowIso,
            state = StremioLibraryItemState(
                lastWatched = nowIso,
                timeOffset = position,
                duration = duration,
                overallTimeWatched = position,
                timesWatched = if (watched) 1 else 0,
                flaggedWatched = if (watched) 1 else 0,
                videoId = if (type == "series") id else null
            )
        )
    }

    /** Parses "seriesId:season:episode[:streamIndex]" back to "seriesId". Null if it doesn't fit that shape. */
    private fun parseSeriesId(id: String): String? {
        val parts = id.split(":")
        if (parts.size < 3) return null
        val season = parts.getOrNull(parts.size - 2)?.toIntOrNull()
        val episode = parts.getOrNull(parts.size - 1)?.toIntOrNull()
        if (season != null && episode != null) return parts.dropLast(2).joinToString(":")
        // Handle a trailing stream-index suffix ("...:season:episode:streamIdx")
        if (parts.size >= 4) {
            val s = parts[parts.size - 3].toIntOrNull()
            val e = parts[parts.size - 2].toIntOrNull()
            if (s != null && e != null) return parts.dropLast(3).joinToString(":")
        }
        return null
    }
}
