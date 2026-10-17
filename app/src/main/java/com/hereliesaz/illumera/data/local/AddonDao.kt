package com.hereliesaz.illumera.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.hereliesaz.illumera.data.model.AddonEntity
import com.hereliesaz.illumera.data.model.CatalogConfigEntity
import com.hereliesaz.illumera.data.model.HistoryTombstoneEntity
import com.hereliesaz.illumera.data.model.HubRowEntity
import com.hereliesaz.illumera.data.model.HubRowWithItems
import com.hereliesaz.illumera.data.model.HubRowItemEntity
import com.hereliesaz.illumera.data.model.ProfileEntity
import com.hereliesaz.illumera.data.model.RecentSearchEntity
import com.hereliesaz.illumera.data.model.RuntimeOwnerEntity
import com.hereliesaz.illumera.data.model.ThemeEntity
import com.hereliesaz.illumera.data.model.SeriesNextUpEntity
import com.hereliesaz.illumera.data.model.WatchHistoryEntity
import com.hereliesaz.illumera.data.model.WatchlistEntity
import kotlinx.coroutines.flow.Flow
import androidx.room.Delete

/** Escapes `%`, `_`, and `\` in a LIKE operand so they are treated as literals. */
fun escapeLikeParam(value: String): String =
    value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/** Watch-history deletion tombstones older than this are pruned during a merge. */
const val HISTORY_TOMBSTONE_TTL_MS: Long = 90L * 24 * 60 * 60 * 1000

@Dao
interface AddonDao {

    @Query("SELECT * FROM addons ORDER BY sortOrder ASC")
    fun getAllAddons(): Flow<List<AddonEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAddon(addon: AddonEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAddons(addons: List<AddonEntity>)

    @Query("DELETE FROM addons WHERE transportUrl = :url")
    suspend fun deleteAddonByUrl(url: String)

    @Query("DELETE FROM addons")
    suspend fun clearAddons()

    @Query("SELECT * FROM addons WHERE transportUrl = :transportUrl")
    suspend fun getAddon(transportUrl: String): AddonEntity?

    @Query("SELECT * FROM catalog_configs")
    fun getAllCatalogConfigs(): Flow<List<CatalogConfigEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCatalogConfig(config: CatalogConfigEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCatalogConfigs(configs: List<CatalogConfigEntity>)

    @Query("DELETE FROM catalog_configs WHERE transportUrl = :url")
    suspend fun deleteCatalogConfigs(url: String)

    @Query("DELETE FROM catalog_configs")
    suspend fun clearCatalogConfigs()

    @Query("DELETE FROM catalog_configs WHERE transportUrl = :url AND uniqueId NOT IN (:keepIds)")
    suspend fun deleteCatalogConfigsExcept(url: String, keepIds: List<String>)

    // Installing/re-installing an addon writes the addon row and its catalog configs as one
    // unit, and prunes configs for catalogs the new manifest no longer lists.
    @Transaction
    suspend fun installAddonAtomically(addon: AddonEntity, configs: List<CatalogConfigEntity>) {
        insertAddon(addon)
        if (configs.isEmpty()) {
            deleteCatalogConfigs(addon.transportUrl)
        } else {
            deleteCatalogConfigsExcept(addon.transportUrl, configs.map { it.uniqueId })
            saveCatalogConfigs(configs)
        }
    }

    @Query("SELECT * FROM catalog_configs WHERE uniqueId = :uniqueId")
    suspend fun getCatalogConfig(uniqueId: String): CatalogConfigEntity?

    @Query("SELECT * FROM profiles")
    fun getProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getProfileById(id: Int): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE id = :id")
    fun getProfileFlow(id: Int): Flow<ProfileEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: ProfileEntity): Long

    @Update
    suspend fun updateProfile(profile: ProfileEntity)

    @Query("UPDATE profiles SET menuMoviesEnabled = :enabled WHERE id = :profileId")
    suspend fun updateMenuMoviesEnabled(profileId: Int, enabled: Boolean)

    @Query("UPDATE profiles SET menuSeriesEnabled = :enabled WHERE id = :profileId")
    suspend fun updateMenuSeriesEnabled(profileId: Int, enabled: Boolean)

    @Query("UPDATE profiles SET menuWatchlistEnabled = :enabled WHERE id = :profileId")
    suspend fun updateMenuWatchlistEnabled(profileId: Int, enabled: Boolean)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteProfile(id: Int)

    @Query("DELETE FROM watchlist WHERE profileId = :profileId")
    suspend fun deleteWatchlistForProfile(profileId: Int)

    @Query("DELETE FROM series_next_up WHERE profileId = :profileId")
    suspend fun deleteSeriesNextUpForProfile(profileId: Int)

    @Query("DELETE FROM recent_searches WHERE profileId = :profileId")
    suspend fun deleteRecentSearchesForProfile(profileId: Int)

    @Query("DELETE FROM history_tombstones WHERE profileId = :profileId")
    suspend fun deleteHistoryTombstonesForProfile(profileId: Int)

    @Query("DELETE FROM runtime_owner WHERE profileId = :profileId")
    suspend fun clearRuntimeOwnerIfProfile(profileId: Int)

    // A plain sequence of suspend calls has no atomicity of its own — a process death or a
    // later call failing partway through would leave a profile gone but its watchlist/next-up
    // rows orphaned, with no UI path left to retry cleanup. @Transaction runs all six deletes
    // as one unit so that can't happen.
    @Transaction
    suspend fun deleteProfileCascading(id: Int) {
        deleteProfile(id)
        deleteWatchlistForProfile(id)
        deleteSeriesNextUpForProfile(id)
        deleteRecentSearchesForProfile(id)
        deleteHistoryTombstonesForProfile(id)
        clearRuntimeOwnerIfProfile(id)
    }

    // ---- Runtime owner marker -------------------------------------------------------
    // Names the profile whose data is in the shared runtime tables. Written inside
    // replaceRuntimeState's transaction so it always agrees with watch_history, unlike the
    // SharedPreferences "last active" id, which is written after the transaction commits.

    @Query("SELECT profileId FROM runtime_owner WHERE id = 0")
    suspend fun getRuntimeOwnerProfileId(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setRuntimeOwner(owner: RuntimeOwnerEntity)

    // ---- History deletion tombstones -----------------------------------------------
    // Every user/sync deletion of watch history records a tombstone for the runtime owner
    // profile in the same transaction, so a crash-recovery merge with a stale snapshot
    // can't resurrect it. No owner recorded yet (legacy DB) → no tombstone, same as before.

    @Query(
        "INSERT OR REPLACE INTO history_tombstones (profileId, itemId, deletedAt) " +
            "SELECT o.profileId, h.id, :deletedAt FROM watch_history h, runtime_owner o " +
            "WHERE o.id = 0 AND h.id = :id"
    )
    suspend fun recordHistoryTombstone(id: String, deletedAt: Long)

    @Query(
        "INSERT OR REPLACE INTO history_tombstones (profileId, itemId, deletedAt) " +
            "SELECT o.profileId, h.id, :deletedAt FROM watch_history h, runtime_owner o " +
            "WHERE o.id = 0 AND h.type = 'series' AND h.id LIKE :episodePrefix ESCAPE '\\'"
    )
    suspend fun recordSeriesHistoryTombstonesRaw(episodePrefix: String, deletedAt: Long)

    @Query("SELECT * FROM history_tombstones WHERE profileId = :profileId")
    suspend fun getHistoryTombstones(profileId: Int): List<HistoryTombstoneEntity>

    @Query("DELETE FROM history_tombstones WHERE deletedAt < :cutoff")
    suspend fun pruneHistoryTombstones(cutoff: Long)

    @Query("SELECT * FROM watch_history ORDER BY lastWatched DESC")
    fun getWatchHistory(): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history")
    suspend fun getAllWatchHistoryOnce(): List<WatchHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(item: WatchHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistoryItems(items: List<WatchHistoryEntity>)

    @Query("UPDATE watch_history SET scrobbled = 1 WHERE id = :id")
    suspend fun markHistoryScrobbled(id: String)

    @Query("DELETE FROM watch_history")
    suspend fun clearWatchHistory()

    @Query("SELECT * FROM watch_history WHERE id = :id")
    suspend fun getHistoryItem(id: String): WatchHistoryEntity?

    /**
     * Returns history items whose id starts with [prefix].
     * The SQL appends `%` itself — callers must NOT append `%` to [prefix].
     * Special LIKE characters (`%`, `_`, `\`) in [prefix] are escaped automatically
     * by the default method; call [getHistoryItemsByPrefixRaw] only with a pre-escaped value.
     */
    @Query("SELECT * FROM watch_history WHERE id LIKE :escapedPrefix || '%' ESCAPE '\\'")
    suspend fun getHistoryItemsByPrefixRaw(escapedPrefix: String): List<WatchHistoryEntity>

    suspend fun getHistoryItemsByPrefix(prefix: String): List<WatchHistoryEntity> =
        getHistoryItemsByPrefixRaw(escapeLikeParam(prefix))

    /**
     * Returns all history items for a series.
     * The `*Raw` query takes a fully escaped LIKE pattern (including the trailing `%`).
     * Callers should use the non-Raw wrapper, which takes the plain prefix (a trailing `%`
     * is tolerated and stripped), escapes `%`, `_`, `\` itself, and appends `%`.
     */
    @Query("SELECT * FROM watch_history WHERE type = 'series' AND (seriesId = :seriesId OR id LIKE :episodePrefix ESCAPE '\\')")
    suspend fun getHistoryItemsForSeriesRaw(seriesId: String, episodePrefix: String): List<WatchHistoryEntity>

    suspend fun getHistoryItemsForSeries(seriesId: String, episodePrefix: String): List<WatchHistoryEntity> =
        getHistoryItemsForSeriesRaw(seriesId, escapeLikeParam(episodePrefix.removeSuffix("%")) + "%")

    /**
     * Returns the most-recently-watched episode history for the given series.
     * The `*Raw` query takes a fully escaped LIKE pattern (including the trailing `%`).
     * Callers should use the non-Raw wrapper, which takes the plain prefix (a trailing `%`
     * is tolerated and stripped), escapes `%`, `_`, `\` itself, and appends `%`.
     */
    @Query(
        "SELECT * FROM watch_history " +
            "WHERE type = 'series' AND id LIKE :episodePrefix ESCAPE '\\' " +
            "ORDER BY lastWatched DESC LIMIT 1"
    )
    suspend fun getLatestSeriesEpisodeHistoryRaw(episodePrefix: String): WatchHistoryEntity?

    suspend fun getLatestSeriesEpisodeHistory(episodePrefix: String): WatchHistoryEntity? =
        getLatestSeriesEpisodeHistoryRaw(escapeLikeParam(episodePrefix.removeSuffix("%")) + "%")

    @Query("DELETE FROM watch_history WHERE id = :id")
    suspend fun deleteHistoryItemRaw(id: String)

    /** Deletes a history row and tombstones it for the runtime owner profile, atomically. */
    @Transaction
    suspend fun deleteHistoryItem(id: String) {
        recordHistoryTombstone(id, System.currentTimeMillis())
        deleteHistoryItemRaw(id)
    }

    /**
     * Returns episode history for a series, newest first.
     * The `*Raw` query takes a fully escaped LIKE pattern (including the trailing `%`).
     * Callers should use the non-Raw wrapper, which takes the plain prefix (a trailing `%`
     * is tolerated and stripped), escapes `%`, `_`, `\` itself, and appends `%`.
     */
    @Query("SELECT * FROM watch_history WHERE type = 'series' AND id LIKE :episodePrefix ESCAPE '\\' ORDER BY lastWatched DESC")
    suspend fun getSeriesEpisodeHistoryRaw(episodePrefix: String): List<WatchHistoryEntity>

    suspend fun getSeriesEpisodeHistory(episodePrefix: String): List<WatchHistoryEntity> =
        getSeriesEpisodeHistoryRaw(escapeLikeParam(episodePrefix.removeSuffix("%")) + "%")

    /**
     * Deletes all episode history rows for a series.
     * The `*Raw` query takes a fully escaped LIKE pattern (including the trailing `%`).
     * Callers should use the non-Raw wrapper, which takes the plain prefix (a trailing `%`
     * is tolerated and stripped), escapes `%`, `_`, `\` itself, and appends `%`.
     */
    @Query("DELETE FROM watch_history WHERE type = 'series' AND id LIKE :episodePrefix ESCAPE '\\'")
    suspend fun deleteSeriesHistoryRaw(episodePrefix: String)

    @Transaction
    suspend fun deleteSeriesHistory(episodePrefix: String) {
        val pattern = escapeLikeParam(episodePrefix.removeSuffix("%")) + "%"
        recordSeriesHistoryTombstonesRaw(pattern, System.currentTimeMillis())
        deleteSeriesHistoryRaw(pattern)
    }

    @Query("SELECT * FROM themes")
    fun getAllThemes(): Flow<List<ThemeEntity>>

    @Query("SELECT * FROM themes WHERE id = :id")
    suspend fun getThemeById(id: String): ThemeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTheme(theme: ThemeEntity)

    @Delete
    suspend fun deleteTheme(theme: ThemeEntity)

    @Query("UPDATE profiles SET themeId = :defaultThemeId WHERE themeId = :deletedThemeId")
    suspend fun resetThemeForProfiles(deletedThemeId: String, defaultThemeId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHubRow(row: HubRowEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHubRows(rows: List<HubRowEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHubRowItems(items: List<HubRowItemEntity>)

    @Transaction
    suspend fun insertHubRowWithItems(row: HubRowEntity, items: List<HubRowItemEntity>) {
        insertHubRow(row)
        insertHubRowItems(items)
    }

    @Query("SELECT * FROM hub_rows ORDER BY homeOrder ASC, createdAt ASC")
    fun getAllHubRows(): Flow<List<HubRowEntity>>

    @Query("SELECT * FROM hub_row_items ORDER BY itemOrder ASC")
    fun getAllHubRowItems(): Flow<List<HubRowItemEntity>>

    @Query("DELETE FROM hub_rows WHERE id = :hubRowId")
    suspend fun deleteHubRow(hubRowId: String)

    @Query("DELETE FROM hub_row_items WHERE hubRowId = :hubRowId")
    suspend fun deleteHubRowItems(hubRowId: String)

    @Query("DELETE FROM hub_row_items")
    suspend fun clearHubRowItems()

    // configUniqueId is "<transportUrl>/<type>/<id>" (see CatalogConfigEntity.uniqueId) —
    // deleting an addon must also drop any custom Hub row tile built from one of its
    // catalogs, or the tile is left permanently dead with nothing to render.
    // [escapedTransportUrl] must have `%`, `_`, `\` escaped (via [escapeLikeParam]) by the
    // caller before being passed here; the query appends `/%` and uses `ESCAPE '\'`.
    @Query("DELETE FROM hub_row_items WHERE configUniqueId LIKE :escapedTransportUrl || '/%' ESCAPE '\\'")
    suspend fun deleteHubRowItemsForAddon(escapedTransportUrl: String)

    @Query("DELETE FROM hub_rows")
    suspend fun clearHubRows()

    // Deleting an addon must atomically remove its catalog configs and hub row tiles
    // alongside the addon row itself — partial deletion would leave dead catalog configs
    // or orphaned hub tiles with no way to render them.
    @Transaction
    suspend fun deleteAddonCascading(transportUrl: String) {
        deleteCatalogConfigs(transportUrl)
        deleteHubRowItemsForAddon(escapeLikeParam(transportUrl))
        deleteAddonByUrl(transportUrl)
    }

    @Transaction
    suspend fun deleteHubRowWithItems(hubRowId: String) {
        deleteHubRowItems(hubRowId)
        deleteHubRow(hubRowId)
    }

    @Query("UPDATE hub_row_items SET customImageUrl = :imageUrl WHERE hubRowId = :hubRowId AND configUniqueId = :configUniqueId")
    suspend fun updateHubItemImage(hubRowId: String, configUniqueId: String, imageUrl: String?)

    @Transaction
    @Query("SELECT * FROM hub_rows ORDER BY homeOrder ASC, createdAt ASC")
    fun getHubRowsWithItems(): Flow<List<HubRowWithItems>>



    @Query("SELECT MAX(homeOrder) FROM hub_rows")
    suspend fun getMaxHubHomeOrder(): Int?

    @Query("SELECT MAX(moviesOrder) FROM hub_rows")
    suspend fun getMaxHubMoviesOrder(): Int?

    @Query("SELECT MAX(seriesOrder) FROM hub_rows")
    suspend fun getMaxHubSeriesOrder(): Int?



    @Update
    suspend fun updateHubRow(row: HubRowEntity)

    @Update
    suspend fun updateHubRows(rows: List<HubRowEntity>)

    @Query("DELETE FROM hub_row_items WHERE hubRowId = :hubRowId AND configUniqueId = :configUniqueId")
    suspend fun deleteHubRowItem(hubRowId: String, configUniqueId: String)

    @Query("DELETE FROM hub_row_items WHERE configUniqueId IN (:configUniqueIds)")
    suspend fun deleteHubRowItemsForConfigs(configUniqueIds: List<String>)

    // Manifest refresh: drop hub items pointing at catalogs the addon no longer lists and
    // replace the addon's config set with [retained], as one unit so a crash or failure
    // partway through cannot leave the addon with no catalog configs.
    @Transaction
    suspend fun replaceCatalogConfigsForAddon(
        transportUrl: String,
        retained: List<CatalogConfigEntity>,
        staleConfigIds: Collection<String>
    ) {
        if (staleConfigIds.isNotEmpty()) deleteHubRowItemsForConfigs(staleConfigIds.toList())
        deleteCatalogConfigs(transportUrl)
        if (retained.isNotEmpty()) saveCatalogConfigs(retained)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHubRowItem(item: HubRowItemEntity)

    @Query("SELECT MAX(itemOrder) FROM hub_row_items WHERE hubRowId = :hubRowId")
    suspend fun getMaxHubItemOrder(hubRowId: String): Int?

    @Update
    suspend fun updateHubRowItem(item: HubRowItemEntity)

    @Update
    suspend fun updateHubRowItems(items: List<HubRowItemEntity>)

    // ── Watchlist ──

    @Query("SELECT * FROM watchlist WHERE profileId = :profileId ORDER BY addedAt DESC")
    fun getWatchlist(profileId: Int): Flow<List<WatchlistEntity>>

    @Query("SELECT * FROM watchlist WHERE profileId = :profileId AND type = :type ORDER BY addedAt DESC")
    fun getWatchlistByType(profileId: Int, type: String): Flow<List<WatchlistEntity>>

    @Query("SELECT * FROM watchlist WHERE profileId = :profileId ORDER BY addedAt DESC")
    suspend fun getWatchlistOnce(profileId: Int): List<WatchlistEntity>

    @Query("SELECT * FROM watchlist WHERE profileId = :profileId AND id = :id")
    suspend fun getWatchlistItem(profileId: Int, id: String): WatchlistEntity?

    @Query("SELECT * FROM watch_history WHERE scrobbled = 1 AND watched = 0")
    suspend fun getScrobbledInProgressItems(): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history WHERE scrobbled = 1 AND watched = 1")
    suspend fun getScrobbledWatchedItems(): List<WatchHistoryEntity>

    @Query("SELECT id FROM watch_history WHERE watched = 1")
    fun getWatchedIds(): Flow<List<String>>

    @Query("UPDATE watch_history SET poster = :poster, background = :background, logo = :logo WHERE id = :id")
    suspend fun updateHistoryImages(id: String, poster: String?, background: String?, logo: String?)

    // ── Series Next Up ──

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeriesNextUp(entry: SeriesNextUpEntity)

    @Query("SELECT * FROM series_next_up WHERE profileId = :profileId AND isComplete = 0 ORDER BY updatedAt DESC")
    fun getActiveSeriesNextUp(profileId: Int): Flow<List<SeriesNextUpEntity>>

    @Query("SELECT * FROM series_next_up WHERE profileId = :profileId AND seriesId = :seriesId")
    suspend fun getSeriesNextUp(profileId: Int, seriesId: String): SeriesNextUpEntity?

    @Query("DELETE FROM series_next_up WHERE profileId = :profileId AND seriesId = :seriesId")
    suspend fun deleteSeriesNextUp(profileId: Int, seriesId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM watchlist WHERE profileId = :profileId AND id = :id)")
    suspend fun isInWatchlist(profileId: Int, id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM watchlist WHERE profileId = :profileId AND id = :id)")
    fun isInWatchlistFlow(profileId: Int, id: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addToWatchlist(item: WatchlistEntity)

    @Query("DELETE FROM watchlist WHERE profileId = :profileId AND id = :id")
    suspend fun removeFromWatchlist(profileId: Int, id: String)

    // ── Recent searches ──

    @Query("SELECT * FROM recent_searches WHERE profileId = :profileId ORDER BY searchedAt DESC LIMIT :limit")
    suspend fun getRecentSearches(profileId: Int, limit: Int = 12): List<RecentSearchEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecentSearch(entry: RecentSearchEntity)

    // Keeps the table from growing unbounded — run alongside every upsert.
    @Query(
        "DELETE FROM recent_searches WHERE profileId = :profileId AND query NOT IN " +
            "(SELECT query FROM recent_searches WHERE profileId = :profileId ORDER BY searchedAt DESC LIMIT :keep)"
    )
    suspend fun trimRecentSearches(profileId: Int, keep: Int = 20)

    @Transaction
    suspend fun replaceRuntimeState(
        addons: List<AddonEntity>,
        catalogConfigs: List<CatalogConfigEntity>,
        hubRows: List<HubRowEntity>,
        hubRowItems: List<HubRowItemEntity>,
        watchHistory: List<WatchHistoryEntity>,
        // true only when reloading the SAME profile already active in the DB (crash
        // recovery). On a genuine profile switch this must be false: the DB's watch
        // history belongs to the outgoing profile and merging it in by id would leak
        // that profile's progress/watched-state into the incoming one.
        mergeWatchHistory: Boolean,
        // Profile the snapshot belongs to. Recorded as the runtime owner in this same
        // transaction, and used to look up its deletion tombstones during a merge.
        ownerProfileId: Int? = null,
        now: Long = System.currentTimeMillis()
    ) {
        // Replace addon/catalog/hub state from snapshot
        clearHubRowItems()
        clearHubRows()
        clearCatalogConfigs()
        clearAddons()

        if (addons.isNotEmpty()) insertAddons(addons)
        if (catalogConfigs.isNotEmpty()) saveCatalogConfigs(catalogConfigs)
        if (hubRows.isNotEmpty()) insertHubRows(hubRows)
        if (hubRowItems.isNotEmpty()) insertHubRowItems(hubRowItems)

        val finalHistory = if (mergeWatchHistory) {
            // Merge watch history: keep whichever entry is newer (DB or snapshot).
            // Prevents a stale snapshot from overwriting progress saved during playback
            // (e.g., power failure before onStop snapshot could be written).
            val existing = getAllWatchHistoryOnce().associateBy { it.id }
            val snapshotMap = watchHistory.associateBy { it.id }
            // A snapshot-only row whose id was deleted after the snapshot was written must
            // stay deleted, unless it was watched again after the deletion.
            val tombstones = if (ownerProfileId != null) {
                pruneHistoryTombstones(now - HISTORY_TOMBSTONE_TTL_MS)
                getHistoryTombstones(ownerProfileId).associate { it.itemId to it.deletedAt }
            } else {
                emptyMap()
            }
            val allIds = existing.keys + snapshotMap.keys
            allIds.mapNotNull { id ->
                val db = existing[id]
                val snap = snapshotMap[id]
                when {
                    db == null -> snap?.takeIf { s ->
                        tombstones[id]?.let { deletedAt -> s.lastWatched > deletedAt } ?: true
                    }
                    snap == null -> db
                    snap.lastWatched >= db.lastWatched -> snap
                    else -> db
                }
            }
        } else {
            watchHistory
        }
        clearWatchHistory()
        if (finalHistory.isNotEmpty()) upsertHistoryItems(finalHistory)
        if (ownerProfileId != null) setRuntimeOwner(RuntimeOwnerEntity(profileId = ownerProfileId))
    }
}
