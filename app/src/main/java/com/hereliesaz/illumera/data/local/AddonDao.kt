package com.hereliesaz.illumera.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.hereliesaz.illumera.data.model.AddonEntity
import com.hereliesaz.illumera.data.model.CatalogConfigEntity
import com.hereliesaz.illumera.data.model.HubRowEntity
import com.hereliesaz.illumera.data.model.HubRowWithItems
import com.hereliesaz.illumera.data.model.HubRowItemEntity
import com.hereliesaz.illumera.data.model.ProfileEntity
import com.hereliesaz.illumera.data.model.RecentSearchEntity
import com.hereliesaz.illumera.data.model.ThemeEntity
import com.hereliesaz.illumera.data.model.SeriesNextUpEntity
import com.hereliesaz.illumera.data.model.WatchHistoryEntity
import com.hereliesaz.illumera.data.model.WatchlistEntity
import kotlinx.coroutines.flow.Flow
import androidx.room.Delete

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

    @Query("SELECT * FROM catalog_configs WHERE uniqueId = :uniqueId")
    suspend fun getCatalogConfig(uniqueId: String): CatalogConfigEntity?

    @Query("SELECT * FROM profiles")
    fun getProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getProfileById(id: Int): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE id = :id")
    fun getProfileFlow(id: Int): Flow<ProfileEntity>

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

    // A plain sequence of suspend calls has no atomicity of its own — a process death or a
    // later call failing partway through would leave a profile gone but its watchlist/next-up
    // rows orphaned, with no UI path left to retry cleanup. @Transaction runs all three deletes
    // as one unit so that can't happen.
    @Transaction
    suspend fun deleteProfileCascading(id: Int) {
        deleteProfile(id)
        deleteWatchlistForProfile(id)
        deleteSeriesNextUpForProfile(id)
        deleteRecentSearchesForProfile(id)
    }

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

    @Query("SELECT * FROM watch_history WHERE id LIKE :prefix || '%'")
    suspend fun getHistoryItemsByPrefix(prefix: String): List<WatchHistoryEntity>

    @Query(
        "SELECT * FROM watch_history " +
            "WHERE type = 'series' AND id LIKE :episodePrefix " +
            "ORDER BY lastWatched DESC LIMIT 1"
    )
    suspend fun getLatestSeriesEpisodeHistory(episodePrefix: String): WatchHistoryEntity?

    @Query("DELETE FROM watch_history WHERE id = :id")
    suspend fun deleteHistoryItem(id: String)

    @Query("SELECT * FROM watch_history WHERE type = 'series' AND id LIKE :episodePrefix ORDER BY lastWatched DESC")
    suspend fun getSeriesEpisodeHistory(episodePrefix: String): List<WatchHistoryEntity>

    @Query("DELETE FROM watch_history WHERE type = 'series' AND id LIKE :episodePrefix")
    suspend fun deleteSeriesHistory(episodePrefix: String)

    @Query("SELECT * FROM themes")
    fun getAllThemes(): Flow<List<ThemeEntity>>

    @Query("SELECT * FROM themes WHERE id = :id")
    suspend fun getThemeById(id: String): ThemeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTheme(theme: ThemeEntity)

    @Delete
    suspend fun deleteTheme(theme: ThemeEntity)

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
    @Query("DELETE FROM hub_row_items WHERE configUniqueId LIKE :transportUrl || '/%'")
    suspend fun deleteHubRowItemsForAddon(transportUrl: String)

    @Query("DELETE FROM hub_rows")
    suspend fun clearHubRows()

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
        mergeWatchHistory: Boolean
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
            val allIds = existing.keys + snapshotMap.keys
            allIds.mapNotNull { id ->
                val db = existing[id]
                val snap = snapshotMap[id]
                when {
                    db == null -> snap
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
    }
}
