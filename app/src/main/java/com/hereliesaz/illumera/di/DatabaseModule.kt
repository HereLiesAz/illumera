package com.hereliesaz.illumera.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.local.LumeraDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN preferredAudioLanguage TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE profiles ADD COLUMN preferredAudioLanguageSecondary TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE profiles ADD COLUMN preferredSubtitleLanguage TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE profiles ADD COLUMN preferredSubtitleLanguageSecondary TEXT NOT NULL DEFAULT ''")
    }
}

private val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Version 28: no-op migration (schema unchanged, version was bumped without migration)
    }
}

private val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE addons ADD COLUMN supportsMeta INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE addons ADD COLUMN typesJson TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE addons ADD COLUMN idPrefixesJson TEXT NOT NULL DEFAULT '[]'")
    }
}

private val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE addons ADD COLUMN supportsStream INTEGER NOT NULL DEFAULT 1")
    }
}

private val MIGRATION_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN splashEnabled INTEGER NOT NULL DEFAULT 1")
    }
}

private val MIGRATION_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN subtitleSize INTEGER NOT NULL DEFAULT 100")
        db.execSQL("ALTER TABLE profiles ADD COLUMN subtitleOffset INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE profiles ADD COLUMN subtitleTextColor INTEGER NOT NULL DEFAULT -1")
        db.execSQL("ALTER TABLE profiles ADD COLUMN subtitleBackgroundColor INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceSortingEnabled INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceEnabledQualities TEXT NOT NULL DEFAULT '4k,1080p,720p,unknown'")
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceExcludePhrases TEXT NOT NULL DEFAULT ''")
    }
}

private val MIGRATION_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceSortPrimary TEXT NOT NULL DEFAULT 'quality'")
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceSortSecondary TEXT NOT NULL DEFAULT 'size'")
    }
}

private val MIGRATION_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceMaxSizeGb INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceExcludedFormats TEXT NOT NULL DEFAULT ''")
    }
}

private val MIGRATION_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN tmdbEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE profiles ADD COLUMN tmdbLanguage TEXT NOT NULL DEFAULT ''")
    }
}

private val MIGRATION_36_37 = object : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN assRendererEnabled INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_37_38 = object : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS watchlist (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "type TEXT NOT NULL, " +
                "title TEXT NOT NULL, " +
                "poster TEXT, " +
                "addedAt INTEGER NOT NULL)"
        )
    }
}

private val MIGRATION_38_39 = object : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN watchedThreshold INTEGER NOT NULL DEFAULT 85")
        db.execSQL("ALTER TABLE watch_history ADD COLUMN watched INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_39_40 = object : Migration(39, 40) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE watch_history ADD COLUMN background TEXT")
        db.execSQL("ALTER TABLE watch_history ADD COLUMN logo TEXT")
    }
}

private val MIGRATION_40_41 = object : Migration(40, 41) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE watch_history ADD COLUMN scrobbled INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_41_42 = object : Migration(41, 42) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS series_next_up (" +
                "seriesId TEXT NOT NULL PRIMARY KEY, " +
                "title TEXT NOT NULL, " +
                "poster TEXT, " +
                "nextSeason INTEGER NOT NULL, " +
                "nextEpisode INTEGER NOT NULL, " +
                "nextEpisodeTitle TEXT, " +
                "nextReleased TEXT, " +
                "isComplete INTEGER NOT NULL DEFAULT 0, " +
                "updatedAt INTEGER NOT NULL)"
        )
    }
}

private val MIGRATION_42_43 = object : Migration(42, 43) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE series_next_up ADD COLUMN isNewEpisode INTEGER NOT NULL DEFAULT 0")
    }
}

// watchlist and series_next_up were previously global tables shared by every
// profile on the device — bookmarking a title or tracking a show's next episode
// on one profile made it visible on all of them. Rebuild both with a profileId
// column folded into the primary key so profiles are isolated. Existing rows
// default to profileId = 1 (the device's first/only profile in the common case);
// SQLite's ALTER TABLE can't add a column into an existing PRIMARY KEY, so this
// recreates each table rather than a plain ADD COLUMN.
private val MIGRATION_43_44 = object : Migration(43, 44) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE watchlist_new (" +
                "profileId INTEGER NOT NULL, " +
                "id TEXT NOT NULL, " +
                "type TEXT NOT NULL, " +
                "title TEXT NOT NULL, " +
                "poster TEXT, " +
                "addedAt INTEGER NOT NULL, " +
                "PRIMARY KEY(profileId, id))"
        )
        db.execSQL(
            "INSERT INTO watchlist_new (profileId, id, type, title, poster, addedAt) " +
                "SELECT 1, id, type, title, poster, addedAt FROM watchlist"
        )
        db.execSQL("DROP TABLE watchlist")
        db.execSQL("ALTER TABLE watchlist_new RENAME TO watchlist")

        db.execSQL(
            "CREATE TABLE series_next_up_new (" +
                "profileId INTEGER NOT NULL, " +
                "seriesId TEXT NOT NULL, " +
                "title TEXT NOT NULL, " +
                "poster TEXT, " +
                "nextSeason INTEGER NOT NULL, " +
                "nextEpisode INTEGER NOT NULL, " +
                "nextEpisodeTitle TEXT, " +
                "nextReleased TEXT, " +
                "isComplete INTEGER NOT NULL DEFAULT 0, " +
                "isNewEpisode INTEGER NOT NULL DEFAULT 0, " +
                "updatedAt INTEGER NOT NULL, " +
                "PRIMARY KEY(profileId, seriesId))"
        )
        db.execSQL(
            "INSERT INTO series_next_up_new (profileId, seriesId, title, poster, nextSeason, " +
                "nextEpisode, nextEpisodeTitle, nextReleased, isComplete, isNewEpisode, updatedAt) " +
                "SELECT 1, seriesId, title, poster, nextSeason, nextEpisode, nextEpisodeTitle, " +
                "nextReleased, isComplete, isNewEpisode, updatedAt FROM series_next_up"
        )
        db.execSQL("DROP TABLE series_next_up")
        db.execSQL("ALTER TABLE series_next_up_new RENAME TO series_next_up")
    }
}

private val MIGRATION_44_45 = object : Migration(44, 45) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS recent_searches (" +
                "profileId INTEGER NOT NULL, " +
                "query TEXT NOT NULL, " +
                "searchedAt INTEGER NOT NULL, " +
                "PRIMARY KEY(profileId, query))"
        )
    }
}

private val MIGRATION_45_46 = object : Migration(45, 46) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceEpisodeTargetSizeMb INTEGER NOT NULL DEFAULT 750")
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceMovieTargetSizeMb INTEGER NOT NULL DEFAULT 3000")
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceMinimumSeeds INTEGER NOT NULL DEFAULT 5")
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceAutoFallback INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceDebridMaxWaitSeconds INTEGER NOT NULL DEFAULT 120")
    }
}

private val MIGRATION_46_47 = object : Migration(46, 47) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN menuMoviesEnabled INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE profiles ADD COLUMN menuSeriesEnabled INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE profiles ADD COLUMN menuWatchlistEnabled INTEGER NOT NULL DEFAULT 1")
    }
}

private val MIGRATION_47_48 = object : Migration(47, 48) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceSkipSeedless INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceAudioLanguageRequirement TEXT NOT NULL DEFAULT 'off'")
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceSubtitleLanguageRequirement TEXT NOT NULL DEFAULT 'off'")
    }
}

// Corrects the sourceSkipSeedless default: the 47→48 migration set DEFAULT 1 (enabled),
// which silently filtered out many torrent streams (any that report 0 seeds/peers at the
// moment of browsing). The correct default is disabled — users can opt in via Settings.
private val MIGRATION_48_49 = object : Migration(48, 49) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE profiles SET sourceSkipSeedless = 0")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LumeraDatabase {
        return Room.databaseBuilder(
            context,
            LumeraDatabase::class.java,
            "lumera_db"
        )
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.execSQL("PRAGMA synchronous = 2")
                }
            })
            .addMigrations(MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36, MIGRATION_36_37, MIGRATION_37_38, MIGRATION_38_39, MIGRATION_39_40, MIGRATION_40_41, MIGRATION_41_42, MIGRATION_42_43, MIGRATION_43_44, MIGRATION_44_45, MIGRATION_45_46, MIGRATION_46_47, MIGRATION_47_48, MIGRATION_48_49)
            // No explicit migrations exist below version 26 (an early, narrowly
            // distributed schema). Without this, any device still on one of those
            // versions hits Room's "no migration found" IllegalStateException and
            // can't open the database at all. Scoped to those specific starting
            // versions only — an actually-missing migration between two *current*
            // versions still fails loudly instead of silently wiping data.
            .fallbackToDestructiveMigrationFrom(*(1..25).toList().toIntArray())
            .build()
    }

    @Provides
    fun provideAddonDao(db: LumeraDatabase): AddonDao {
        return db.addonDao()
    }
}
