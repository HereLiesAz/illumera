package com.hereliesaz.illumera.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.hereliesaz.illumera.data.model.AddonEntity
import com.hereliesaz.illumera.data.model.CatalogConfigEntity
import com.hereliesaz.illumera.data.model.HubRowEntity
import com.hereliesaz.illumera.data.model.HubRowItemEntity
import com.hereliesaz.illumera.data.model.ProfileEntity
import com.hereliesaz.illumera.data.model.RecentSearchEntity
import com.hereliesaz.illumera.data.model.ThemeEntity
import com.hereliesaz.illumera.data.model.SeriesNextUpEntity
import com.hereliesaz.illumera.data.model.WatchHistoryEntity
import com.hereliesaz.illumera.data.model.WatchlistEntity


@Database(
    entities = [
        AddonEntity::class,
        ProfileEntity::class,
        WatchHistoryEntity::class,
        CatalogConfigEntity::class,
        ThemeEntity::class,
        HubRowEntity::class,
        HubRowItemEntity::class,
        WatchlistEntity::class,
        SeriesNextUpEntity::class,
        RecentSearchEntity::class
    ],
    version = 50,
    exportSchema = false
)
abstract class LumeraDatabase : RoomDatabase() {
    abstract fun addonDao(): AddonDao
}