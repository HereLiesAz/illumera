package com.hereliesaz.illumera.data.auth

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.WatchHistoryEntity
import com.hereliesaz.illumera.data.model.MIN_CONFIRMED_PLAYBACK_MS
import com.hereliesaz.illumera.data.model.WatchlistEntity
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.remote.StremioAuthService
import com.hereliesaz.illumera.data.remote.StremioLibraryItem
import com.hereliesaz.illumera.data.remote.StremioLibraryItemState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stremio's datastore represents BOTH library bookmarks and playback. A new
 * bookmark has a valid mtime but no watch progress. Neither its creation time
 * nor its presence in Library is a playback event.
 */
internal fun stremioStateHasPlaybackEvidence(state: StremioLibraryItemState): Boolean =
    state.timesWatched > 0 || state.flaggedWatched > 0 ||
        state.timeOffset >= MIN_CONFIRMED_PLAYBACK_MS

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

    private val profileSyncMutexes = mutableMapOf<Int, Mutex>()

    private fun profileSyncMutex(profileId: Int): Mutex =
        synchronized(profileSyncMutexes) { profileSyncMutexes.getOrPut(profileId) { Mutex() } }

    suspend fun syncLibrary(force: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        val profileId = profileConfigurationManager.getLastActiveProfileId()
            ?: return@withContext Result.success(Unit)
        syncLibraryInternal(profileId, force, runtimeHeld = false)
    }

    /**
     * Variant for callers that already own the profile runtime through
     * [ProfileConfigurationManager.withActiveProfileRuntime]. The runtime mutex is not
     * reentrant, so this must never try to acquire it again.
     */
    suspend fun syncLibraryWithinActiveRuntime(profileId: Int, force: Boolean = false): Result<Unit> =
        withContext(Dispatchers.IO) {
            syncLibraryInternal(profileId, force, runtimeHeld = true)
        }

    private suspend fun <T> inProfileRuntime(profileId: Int, runtimeHeld: Boolean, block: suspend () -> T): T =
        if (runtimeHeld) {
            ensureProfileStillActive(profileId)
            block()
        } else {
            profileConfigurationManager.withActiveProfileRuntime(profileId, block)
        }

    // Serialize the whole sync per profile: two overlapping syncs could otherwise read the
    // same baseline and push/pull/save conflicting snapshots.
    private suspend fun syncLibraryInternal(
        profileId: Int,
        force: Boolean,
        runtimeHeld: Boolean
    ): Result<Unit> = profileSyncMutex(profileId).withLock {
        val authKey = stremioAuthManager.getStoredAuthKey()
            ?: return@withLock Result.success(Unit)

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
        if (!shouldRun) return@withLock Result.success(Unit)

        try {
            // Shared watch-history tables belong to whichever profile runtime is active.
            // Capture the initiating profile's local view while ownership is guaranteed,
            // then release the runtime lock before any network call.
            val localItems = inProfileRuntime(profileId, runtimeHeld) {
                buildLocalLibraryView(profileId)
            }
            // The baseline belongs to both the profile and the Stremio account. A profile
            // can sign out of account A and into B; sharing A's baseline with B can otherwise
            // turn A-only rows into deletion tombstones against B.
            val previousLocal = loadLocalSnapshot(profileId, authKey)
            val remoteMtimes = stremioAuthService.datastoreMeta(authKey)

            ensureProfileStillActive(profileId)

            val toPush = mutableListOf<StremioLibraryItem>()
            val idsToPull = mutableListOf<String>()
            val locallyDeletedIds = linkedSetOf<String>()

            // A missing local row is a deletion only relative to the last baseline. We do
            // not have a durable "deleted at" timestamp for legacy history rows, so never
            // let that inferred deletion beat a remote edit that happened after the baseline.
            // If the remote copy changed since we last saw it, pull it; if it did not, the
            // local absence wins and we can safely emit a fresh tombstone.
            val deletionTime = Instant.now().toString()
            previousLocal.forEach { (id, previous) ->
                if (id in localItems) return@forEach
                val remoteMtime = remoteMtimes[id] ?: return@forEach
                if (isAfter(remoteMtime, previous.mtime)) {
                    idsToPull += id
                } else {
                    locallyDeletedIds += id
                    toPush += previous.copy(
                        removed = true,
                        mtime = deletionTime,
                        state = previous.state.copy(lastWatched = deletionTime)
                    )
                }
            }

            for ((id, local) in localItems) {
                val remoteMtime = remoteMtimes[id]
                val localMtime = Instant.parse(local.mtime).toEpochMilli()
                when {
                    remoteMtime == null || localMtime > remoteMtime -> toPush += local
                    remoteMtime > localMtime -> idsToPull += id
                }
            }
            // Remote rows that never materialize locally (e.g. removed tombstones) would
            // otherwise be re-pulled on every sync; skip those unchanged since last sync.
            val previousRemoteMtimes = loadRemoteMtimes(profileId, authKey)
            for ((id, remoteMtime) in remoteMtimes) {
                if (id in localItems || id in locallyDeletedIds || id in previousLocal) continue
                val seen = previousRemoteMtimes[id]
                if (seen != null && remoteMtime <= seen) continue
                idsToPull += id
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
            inProfileRuntime(profileId, runtimeHeld) {
                for (item in remoteItems) {
                    applyRemoteItem(profileId, item, previousLocal[item.id])
                }

                val refreshedLocal = buildLocalLibraryView(profileId)
                saveLocalSnapshot(profileId, authKey, refreshedLocal.values.toList())
                saveRemoteMtimes(profileId, authKey, remoteMtimes)
            }

            Log.i(TAG, "Library sync: pushed=${toPush.size}, pulled=${idsToPull.distinct().size}, deleted=${locallyDeletedIds.size}")
            Result.success(Unit)
        } catch (changed: ProfileChangedException) {
            // Not a coroutine cancellation: the caller's scope is still live, so report it
            // as an ordinary failure instead of throwing CancellationException at it.
            releaseThrottleAfterFailure(profileId, startedAt)
            Log.i(TAG, "Library sync stopped: ${changed.message}")
            Result.failure(changed)
        } catch (cancelled: CancellationException) {
            releaseThrottleAfterFailure(profileId, startedAt)
            throw cancelled
        } catch (e: Exception) {
            releaseThrottleAfterFailure(profileId, startedAt)
            com.hereliesaz.illumera.crash.AppErrors.e(TAG, "Library sync failed", e)
            Result.failure(e)
        }
    }

    /** The active profile changed mid-sync; the sync stops before touching another profile. */
    class ProfileChangedException : Exception("Active profile changed during Stremio library sync")

    private fun ensureProfileStillActive(profileId: Int) {
        if (profileConfigurationManager.getLastActiveProfileId() != profileId) {
            throw ProfileChangedException()
        }
    }

    // NonCancellable: this also runs on the cancellation path, where a plain suspending
    // withLock would throw immediately and leave the throttle set.
    private suspend fun releaseThrottleAfterFailure(profileId: Int, startedAt: Long) {
        withContext(NonCancellable) {
            syncMutex.withLock {
                if (lastSyncAtMsByProfile[profileId] == startedAt) {
                    lastSyncAtMsByProfile.remove(profileId)
                }
            }
        }
    }

    /**
     * Stremio Library contains saved titles even before playback. Previously this returned
     * only watch history, silently excluding bookmarks from pushes and sync baselines.
     * Combining both sources also prevents the next sync from treating imported,
     * unplayed titles as local deletions.
     */
    private suspend fun buildLocalLibraryView(profileId: Int): Map<String, StremioLibraryItem> {
        val mostRecentHistory = LinkedHashMap<String, WatchHistoryEntity>()
        for (item in dao.getAllWatchHistoryOnce()) {
            if (!item.hasPlaybackEvidence()) continue
            val libraryId = if (item.type == "series") {
                item.seriesId ?: parseSeriesId(item.id) ?: continue
            } else item.id
            val previous = mostRecentHistory[libraryId]
            if (previous == null || item.lastWatched > previous.lastWatched) {
                mostRecentHistory[libraryId] = item
            }
        }
        val library = LinkedHashMap<String, StremioLibraryItem>()
        for ((id, history) in mostRecentHistory) library[id] = history.toLibraryItem(id)
        for (bookmark in dao.getWatchlistOnce(profileId)) {
            if (bookmark.type !in setOf("movie", "series")) continue
            val bookmarkedAt = Instant.ofEpochMilli(bookmark.addedAt).toString()
            val createdKey = "remote_bookmark_created_${profileId}_${bookmark.id}"
            val modifiedKey = "remote_bookmark_modified_${profileId}_${bookmark.id}"
            val remoteOwned = prefs.getBoolean("remote_bookmark_${profileId}_${bookmark.id}", false) &&
                prefs.getLong(createdKey, Long.MIN_VALUE) == bookmark.addedAt
            val remoteModified = if (remoteOwned)
                prefs.getLong(modifiedKey, bookmark.addedAt) else bookmark.addedAt
            val baselineModifiedAt = Instant.ofEpochMilli(maxOf(bookmark.addedAt, remoteModified)).toString()
            val watched = library[bookmark.id]
            library[bookmark.id] = if (watched == null) {
                StremioLibraryItem(
                    id = bookmark.id,
                    name = bookmark.title,
                    type = bookmark.type,
                    poster = bookmark.poster,
                    ctime = bookmarkedAt,
                    mtime = baselineModifiedAt
                )
            } else {
                val watchedAt = Instant.parse(watched.mtime).toEpochMilli()
                watched.copy(
                    name = bookmark.title.ifBlank { watched.name },
                    poster = bookmark.poster ?: watched.poster,
                    ctime = bookmarkedAt,
                    mtime = Instant.ofEpochMilli(maxOf(remoteModified, bookmark.addedAt, watchedAt)).toString()
                )
            }
        }
        return library
    }

    private suspend fun applyRemoteItem(
        profileId: Int, item: StremioLibraryItem,
        previousLocal: StremioLibraryItem? = null
    ) {
        val mtimeMs = runCatching { Instant.parse(item.mtime).toEpochMilli() }.getOrNull() ?: return

        val ownedBookmarkKey = "remote_bookmark_${profileId}_${item.id}"
        val ownedCreatedAtKey = "remote_bookmark_created_${profileId}_${item.id}"
        val ownedModifiedAtKey = "remote_bookmark_modified_${profileId}_${item.id}"
        val watchCountKey = "watch_count_${profileId}_${item.id}"
        if (item.removed) {
            val importedAt = prefs.getLong(ownedCreatedAtKey, Long.MIN_VALUE)
            val current = dao.getWatchlistItem(profileId, item.id)
            // Legacy syncs wrote the ownership flag but not the importedAt key.
            // Use the remote creation date to verify that this is still the exact
            // imported row; never delete an independently re-added local bookmark.
            val legacyCreatedAt = (item.ctime ?: previousLocal?.ctime)?.let {
                runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            }
            val matchesImportedRow = current != null && (
                current.addedAt == importedAt ||
                    (importedAt == Long.MIN_VALUE && legacyCreatedAt != null &&
                        current.addedAt == legacyCreatedAt)
                )
            if (prefs.getBoolean(ownedBookmarkKey, false) && matchesImportedRow) {
                dao.removeFromWatchlist(profileId, item.id)
            }
            prefs.edit().remove(ownedBookmarkKey).remove(ownedCreatedAtKey)
                .remove(ownedModifiedAtKey).remove(watchCountKey).apply()
            if (item.type == "series") {
                dao.getHistoryItemsForSeries(item.id, "${item.id}:%")
                    .forEach { dao.deleteHistoryItem(it.id) }
            } else {
                dao.deleteHistoryItem(item.id)
            }
            return
        }

        // A library bookmark is still part of Stremio Library even when its title
        // has never been played (and a series therefore has no videoId).
        prefs.edit().putInt(watchCountKey, item.state.timesWatched).apply()
        if (item.type == "movie" || item.type == "series") {
            val currentBookmark = dao.getWatchlistItem(profileId, item.id)
            if (currentBookmark == null) {
                val createdAt = item.ctime?.let {
                    runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
                } ?: mtimeMs
                dao.addToWatchlist(
                    WatchlistEntity(
                        profileId = profileId,
                        id = item.id,
                        type = item.type,
                        title = item.name,
                        poster = item.poster,
                        addedAt = createdAt
                    )
                )
                prefs.edit().putBoolean(ownedBookmarkKey, true)
                    .putLong(ownedCreatedAtKey, createdAt)
                    .putLong(ownedModifiedAtKey, mtimeMs).apply()
            } else if (prefs.getBoolean(ownedBookmarkKey, false) &&
                (prefs.getLong(ownedCreatedAtKey, Long.MIN_VALUE) == Long.MIN_VALUE ||
                    prefs.getLong(ownedCreatedAtKey, Long.MIN_VALUE) == currentBookmark.addedAt)
            ) {
                // Migrate pre-timestamp remote-owned rows when confirmed by a live
                // incoming library item; preserve the remote edit clock separately.
                prefs.edit().putLong(ownedCreatedAtKey, currentBookmark.addedAt)
                    .putLong(ownedModifiedAtKey, mtimeMs).apply()
            }
        }
        // Bookmarks must not become Continue Watching or preference seeds. In
        // particular, a newer bookmark mtime is not permission to overwrite a
        // real local play with zero-progress remote state.
        if (!stremioStateHasPlaybackEvidence(item.state)) return
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
                position = item.state.timeOffset.coerceAtLeast(0L),
                duration = duration,
                lastWatched = mtimeMs,
                type = item.type,
                watched = item.state.timesWatched > 0 || item.state.flaggedWatched > 0,
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

    /**
     * Forgets the sync baseline for a profile, so the next sync after signing in again
     * merges both sides instead of reading missing items as deletions to push.
     */
    suspend fun clearSyncState(profileId: Int) = syncMutex.withLock {
        lastSyncAtMsByProfile.remove(profileId)
        withContext(Dispatchers.IO) {
            val prefix = "$KEY_LOCAL_SNAPSHOT_PREFIX${profileId}_"
            val legacyKey = "$KEY_LOCAL_SNAPSHOT_PREFIX$profileId"
            val editor = prefs.edit().remove(legacyKey)
            val syncMetadataPrefixes = listOf(
                prefix,
                "remote_bookmark_${profileId}_",
                "remote_bookmark_created_${profileId}_",
                "remote_bookmark_modified_${profileId}_",
                "watch_count_${profileId}_"
            )
            prefs.all.keys.filter { key ->
                syncMetadataPrefixes.any { key.startsWith(it) }
            }.forEach(editor::remove)
            editor.commit()
        }
    }

    private fun snapshotKey(profileId: Int, authKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(authKey.toByteArray(StandardCharsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "$KEY_LOCAL_SNAPSHOT_PREFIX${profileId}_$digest"
    }

    private fun loadLocalSnapshot(
        profileId: Int,
        authKey: String
    ): Map<String, StremioLibraryItem> = runCatching {
        val json = prefs.getString(snapshotKey(profileId, authKey), "[]")
        val items = gson.fromJson<List<StremioLibraryItem>>(json, libraryListType).orEmpty()
        items.associateBy { it.id }
    }.getOrDefault(emptyMap())

    // Remote mtimes seen at the last completed sync. Stored under the snapshot key so
    // clearSyncState() forgets it together with the baseline.
    private val remoteMtimesType = object : TypeToken<Map<String, Long>>() {}.type

    private fun loadRemoteMtimes(profileId: Int, authKey: String): Map<String, Long> = runCatching {
        val json = prefs.getString(snapshotKey(profileId, authKey) + "_remote_mtimes", null) ?: return@runCatching emptyMap<String, Long>()
        gson.fromJson<Map<String, Long>>(json, remoteMtimesType).orEmpty()
    }.getOrDefault(emptyMap())

    private fun saveRemoteMtimes(profileId: Int, authKey: String, mtimes: Map<String, Long>) {
        prefs.edit().putString(snapshotKey(profileId, authKey) + "_remote_mtimes", gson.toJson(mtimes)).apply()
    }

    private fun saveLocalSnapshot(
        profileId: Int,
        authKey: String,
        items: List<StremioLibraryItem>
    ) {
        val success = prefs.edit()
            .putString(snapshotKey(profileId, authKey), gson.toJson(items))
            .commit()
        if (!success) {
            com.hereliesaz.illumera.crash.AppErrors.e(
                TAG,
                "saveLocalSnapshot: commit() failed for profile $profileId — snapshot may be stale"
            )
        }
    }

    /** [candidateMs] is a datastoreMeta epoch-millis mtime; [baseline] an ISO-8601 item mtime. */
    private fun isAfter(candidateMs: Long, baseline: String): Boolean {
        // An unparseable baseline is treated conservatively as remote-newer so it can
        // never authorize a destructive inferred tombstone.
        val baselineMs = runCatching { Instant.parse(baseline).toEpochMilli() }.getOrNull() ?: return true
        return candidateMs > baselineMs
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
