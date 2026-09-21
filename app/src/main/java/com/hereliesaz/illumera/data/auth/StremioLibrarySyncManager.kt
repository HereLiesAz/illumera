package com.hereliesaz.illumera.data.auth

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.WatchHistoryEntity
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.remote.StremioAuthService
import com.hereliesaz.illumera.data.remote.StremioLibraryItem
import com.hereliesaz.illumera.data.remote.StremioLibraryItemState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StremioLibrarySyncManager @Inject constructor(
    @ApplicationContext context: Context,
    private val stremioAuthManager: StremioAuthManager,
    private val stremioAuthService: StremioAuthService,
    private val dao: AddonDao,
    private val profileConfigurationManager: ProfileConfigurationManager
) {
    companion object {
        private const val TAG = "StremioLibrarySync"
        private const val MIN_SYNC_INTERVAL_MS = 60_000L
        private const val PREFS_FILE = "stremio_library_sync"
        private const val KEY_LOCAL_SNAPSHOT_PREFIX = "local_snapshot_"
    }

    private val syncMutex = Mutex()
    private val lastSyncAtMsByProfile = mutableMapOf<Int, Long>()
    private val gson = Gson()
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val libraryListType = object : TypeToken<List<StremioLibraryItem>>() {}.type

    suspend fun syncLibrary(force: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        val profileId = profileConfigurationManager.getLastActiveProfileId()
            ?: return@withContext Result.success(Unit)
        val authKey = stremioAuthManager.getStoredAuthKey()
            ?: return@withContext Result.success(Unit)

        val startedAt = System.currentTimeMillis()
        val shouldRun = syncMutex.withLock {
            val lastSyncAt = lastSyncAtMsByProfile[profileId] ?: 0L
            if (!force && startedAt - lastSyncAt < MIN_SYNC_INTERVAL_MS) {
                false
            } else {
                lastSyncAtMsByProfile[profileId] = startedAt
                true
            }
        }
        if (!shouldRun) return@withContext Result.success(Unit)

        try {
            // Shared watch-history tables belong to whichever profile runtime is active.
            // Capture the initiating profile's local view while ownership is guaranteed,
            // then release the runtime lock before any network call.
            val localItems = profileConfigurationManager.withActiveProfileRuntime(profileId) {
                buildLocalLibraryView()
            }
            val previousLocal = loadLocalSnapshot(profileId)
            val remoteMtimes = stremioAuthService.datastoreMeta(authKey)

            ensureProfileStillActive(profileId)

            val toPush = mutableListOf<StremioLibraryItem>()
            val idsToPull = mutableListOf<String>()
            val locallyDeletedIds = previousLocal.keys
                .filterTo(linkedSetOf()) { it !in localItems && it in remoteMtimes }

            // A row that existed in our last successful snapshot and is now absent is
            // a local deletion, not an invitation to resurrect the remote item. Persist
            // enough of the prior wire item to emit a real Stremio tombstone.
            val deletionTime = Instant.now().toString()
            locallyDeletedIds.forEach { id ->
                previousLocal[id]?.let { previous ->
                    toPush += previous.copy(
                        removed = true,
                        mtime = deletionTime,
                        state = previous.state.copy(lastWatched = deletionTime)
                    )
                }
            }

            for ((id, local) in localItems) {
                val remoteMtime = remoteMtimes[id]
                when {
                    remoteMtime == null || local.lastWatched > remoteMtime -> toPush += local.toLibraryItem(id)
                    remoteMtime > local.lastWatched -> idsToPull += id
                }
            }
            for (id in remoteMtimes.keys) {
                if (id !in localItems && id !in locallyDeletedIds) idsToPull += id
            }

            if (toPush.isNotEmpty()) {
                ensureProfileStillActive(profileId)
                stremioAuthService.datastorePut(authKey, toPush)
            }

            val remoteItems = if (idsToPull.isNotEmpty()) {
                ensureProfileStillActive(profileId)
                stremioAuthService.datastoreGet(authKey, idsToPull.distinct())
            } else {
                emptyList()
            }

            // Network work is finished. Reacquire ownership only for the short local
            // mutation phase, so profile switching is never blocked by remote latency.
            profileConfigurationManager.withActiveProfileRuntime(profileId) {
                for (item in remoteItems) applyRemoteItem(item)

                val refreshedLocal = buildLocalLibraryView()
                    .map { (id, history) -> history.toLibraryItem(id) }
                saveLocalSnapshot(profileId, refreshedLocal)
            }

            Log.i(TAG, "Library sync: pushed=${toPush.size}, pulled=${idsToPull.distinct().size}, deleted=${locallyDeletedIds.size}")
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            releaseThrottleAfterFailure(profileId, startedAt)
            throw cancelled
        } catch (e: Exception) {
            releaseThrottleAfterFailure(profileId, startedAt)
            Log.e(TAG, "Library sync failed", e)
            Result.failure(e)
        }
    }

    private fun ensureProfileStillActive(profileId: Int) {
        if (profileConfigurationManager.getLastActiveProfileId() != profileId) {
            throw CancellationException("Active profile changed during Stremio library sync")
        }
    }

    private suspend fun releaseThrottleAfterFailure(profileId: Int, startedAt: Long) {
        syncMutex.withLock {
            if (lastSyncAtMsByProfile[profileId] == startedAt) {
                lastSyncAtMsByProfile.remove(profileId)
            }
        }
    }

    /** One entry per movie, plus the latest episode for each canonical parent series. */
    private suspend fun buildLocalLibraryView(): Map<String, WatchHistoryEntity> {
        val result = LinkedHashMap<String, WatchHistoryEntity>()
        for (item in dao.getAllWatchHistoryOnce()) {
            if (item.type == "series") {
                val parentId = item.seriesId ?: parseSeriesId(item.id) ?: continue
                val current = result[parentId]
                if (current == null || item.lastWatched > current.lastWatched) result[parentId] = item
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
                dao.getHistoryItemsForSeries(item.id, "${item.id}:%")
                    .forEach { dao.deleteHistoryItem(it.id) }
            } else {
                dao.deleteHistoryItem(item.id)
            }
            return
        }

        val localId = if (item.type == "series") item.state.videoId ?: return else item.id
        val existing = dao.getHistoryItem(localId)
        if (existing != null && existing.lastWatched >= mtimeMs) return

        // Store duration as-is (including 0). Divide-by-zero protection belongs in the
        // display/progress layer, not here — removing the 1L floor avoids corrupting items
        // that genuinely have no duration recorded.
        val duration = item.state.duration.coerceAtLeast(item.state.timeOffset)
        dao.upsertHistory(
            WatchHistoryEntity(
                id = localId,
                title = existing?.title ?: item.name,
                poster = existing?.poster ?: item.poster,
                background = existing?.background,
                logo = existing?.logo,
                seriesId = if (item.type == "series") item.id else existing?.seriesId,
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

    private fun loadLocalSnapshot(profileId: Int): Map<String, StremioLibraryItem> = runCatching {
        val json = prefs.getString("$KEY_LOCAL_SNAPSHOT_PREFIX$profileId", "[]")
        val items = gson.fromJson<List<StremioLibraryItem>>(json, libraryListType).orEmpty()
        items.associateBy { it.id }
    }.getOrDefault(emptyMap())

    private fun saveLocalSnapshot(profileId: Int, items: List<StremioLibraryItem>) {
        val success = prefs.edit()
            .putString("$KEY_LOCAL_SNAPSHOT_PREFIX$profileId", gson.toJson(items))
            .commit()
        if (!success) {
            Log.e(TAG, "saveLocalSnapshot: commit() failed for profile $profileId — snapshot may be stale")
        }
    }

    private fun parseSeriesId(id: String): String? {
        val parts = id.split(":")
        if (parts.size < 3) return null
        val season = parts.getOrNull(parts.size - 2)?.toIntOrNull()
        val episode = parts.getOrNull(parts.size - 1)?.toIntOrNull()
        if (season != null && episode != null) return parts.dropLast(2).joinToString(":")
        if (parts.size >= 4) {
            val s = parts[parts.size - 3].toIntOrNull()
            val e = parts[parts.size - 2].toIntOrNull()
            if (s != null && e != null) return parts.dropLast(3).joinToString(":")
        }
        return null
    }
}
