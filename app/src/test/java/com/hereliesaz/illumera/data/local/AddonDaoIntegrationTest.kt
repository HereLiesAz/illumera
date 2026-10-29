package com.hereliesaz.illumera.data.local

import androidx.room.Room
import com.hereliesaz.illumera.data.model.AddonEntity
import com.hereliesaz.illumera.data.model.CatalogConfigEntity
import com.hereliesaz.illumera.data.model.HubRowItemEntity
import com.hereliesaz.illumera.data.model.ProfileEntity
import com.hereliesaz.illumera.data.model.RecentSearchEntity
import com.hereliesaz.illumera.data.model.SeriesNextUpEntity
import com.hereliesaz.illumera.data.model.WatchHistoryEntity
import com.hereliesaz.illumera.data.model.WatchlistEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AddonDaoIntegrationTest {
    private lateinit var db: LumeraDatabase
    private lateinit var dao: AddonDao

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, LumeraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.addonDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun addonsAreOrderedAndReplaceOnPrimaryKeyConflict() = runTest {
        dao.insertAddons(
            listOf(
                addon("https://b", "B", sortOrder = 20),
                addon("https://a", "A", sortOrder = 10)
            )
        )

        assertEquals(listOf("A", "B"), dao.getAllAddons().first().map { it.name })

        dao.insertAddon(addon("https://a", "A updated", sortOrder = 30))

        assertEquals("A updated", dao.getAddon("https://a")?.name)
        assertEquals(listOf("B", "A updated"), dao.getAllAddons().first().map { it.name })
    }

    @Test
    fun replaceCatalogConfigsForAddonDropsStaleConfigsAndTheirHubItems() = runTest {
        dao.saveCatalogConfigs(
            listOf(
                catalog("https://a", "keep"),
                catalog("https://a", "stale"),
                catalog("https://b", "other")
            )
        )
        dao.insertHubRowItems(
            listOf(
                HubRowItemEntity(hubRowId = "row", configUniqueId = "https://a/movie/keep", title = "keep"),
                HubRowItemEntity(hubRowId = "row", configUniqueId = "https://a/movie/stale", title = "stale")
            )
        )

        dao.replaceCatalogConfigsForAddon(
            transportUrl = "https://a",
            retained = listOf(catalog("https://a", "keep")),
            staleConfigIds = setOf("https://a/movie/stale")
        )

        assertEquals(
            setOf("https://a/movie/keep", "https://b/movie/other"),
            dao.getAllCatalogConfigs().first().map { it.uniqueId }.toSet()
        )
        assertEquals(
            listOf("https://a/movie/keep"),
            dao.getAllHubRowItems().first().map { it.configUniqueId }
        )
    }

    @Test
    fun watchlistIsProfileScopedAndOrderedNewestFirst() = runTest {
        dao.addToWatchlist(watchlist(profileId = 1, id = "older", addedAt = 10L))
        dao.addToWatchlist(watchlist(profileId = 1, id = "newer", addedAt = 20L))
        dao.addToWatchlist(watchlist(profileId = 2, id = "other", addedAt = 30L))

        assertEquals(listOf("newer", "older"), dao.getWatchlistOnce(1).map { it.id })
        assertTrue(dao.isInWatchlist(1, "older"))
        assertFalse(dao.isInWatchlist(1, "other"))

        dao.removeFromWatchlist(1, "older")
        assertFalse(dao.isInWatchlist(1, "older"))
        assertTrue(dao.isInWatchlist(2, "other"))
    }

    @Test
    fun deleteProfileCascadingRemovesOnlyThatProfilesDependentRows() = runTest {
        dao.insertProfile(ProfileEntity(id = 1, name = "One"))
        dao.insertProfile(ProfileEntity(id = 2, name = "Two"))
        dao.addToWatchlist(watchlist(1, "p1", 10L))
        dao.addToWatchlist(watchlist(2, "p2", 10L))
        dao.upsertSeriesNextUp(nextUp(1, "series-one"))
        dao.upsertSeriesNextUp(nextUp(2, "series-two"))
        dao.upsertRecentSearch(RecentSearchEntity(1, "one", 10L))
        dao.upsertRecentSearch(RecentSearchEntity(2, "two", 10L))

        dao.deleteProfileCascading(1)

        assertNull(dao.getProfileById(1))
        assertEquals("Two", dao.getProfileById(2)?.name)
        assertTrue(dao.getWatchlistOnce(1).isEmpty())
        assertEquals(listOf("p2"), dao.getWatchlistOnce(2).map { it.id })
        assertNull(dao.getSeriesNextUp(1, "series-one"))
        assertEquals("series-two", dao.getSeriesNextUp(2, "series-two")?.seriesId)
        assertTrue(dao.getRecentSearches(1).isEmpty())
        assertEquals(listOf("two"), dao.getRecentSearches(2).map { it.query })
    }

    @Test
    fun legacyUnplayedHistoryIsDeletedButBookmarksAndWatchedMarkersSurvive() = runTest {
        dao.upsertHistory(WatchHistoryEntity(
            id = "ghost", title = "Unplayed Bookmark", poster = null,
            position = 0L, duration = 0L, lastWatched = 500L, type = "movie"
        ))
        dao.upsertHistory(WatchHistoryEntity(
            id = "tiny", title = "One-second artifact", poster = null,
            position = 1_000L, duration = 300_000L, lastWatched = 400L, type = "movie"
        ))
        dao.upsertHistory(WatchHistoryEntity(
            id = "watched", title = "Explicit watched", poster = null,
            position = 0L, duration = 0L, lastWatched = 300L,
            type = "movie", watched = true
        ))
        dao.upsertHistory(history("started", lastWatched = 200L, position = 5_000L))
        dao.addToWatchlist(watchlist(profileId = 1, id = "ghost", addedAt = 500L))

        dao.pruneUnplayedHistory()

        assertEquals(null, dao.getHistoryItem("ghost"))
        assertEquals(null, dao.getHistoryItem("tiny"))
        assertTrue(dao.getHistoryItem("watched")?.watched == true)
        assertEquals(5_000L, dao.getHistoryItem("started")?.position)
        assertEquals(listOf("ghost"), dao.getWatchlistOnce(1).map { it.id })
    }

    @Test
    fun crashRecoveryNeverResurrectsZeroProgressHistoryFromLegacySnapshot() = runTest {
        val ghost = WatchHistoryEntity(
            id = "ghost", title = "Never played", poster = null,
            position = 0L, duration = 0L, lastWatched = 900L, type = "movie"
        )
        val real = history("real", lastWatched = 100L, position = 50_000L)

        dao.replaceRuntimeState(
            emptyList(), emptyList(), emptyList(), emptyList(),
            listOf(ghost, real), mergeWatchHistory = false, ownerProfileId = 1
        )
        assertEquals(null, dao.getHistoryItem("ghost"))
        assertEquals("real", dao.getHistoryItem("real")?.id)

        dao.upsertHistory(ghost)
        dao.replaceRuntimeState(
            emptyList(), emptyList(), emptyList(), emptyList(),
            listOf(ghost, real), mergeWatchHistory = true, ownerProfileId = 1
        )
        assertEquals(null, dao.getHistoryItem("ghost"))
        assertEquals("real", dao.getHistoryItem("real")?.id)
    }

    @Test
    fun replaceRuntimeStateWithoutMergeFullyReplacesWatchHistory() = runTest {
        dao.upsertHistory(history("old", lastWatched = 100L, position = 50000L))

        dao.replaceRuntimeState(
            addons = listOf(addon("https://new", "New", 1)),
            catalogConfigs = emptyList(),
            hubRows = emptyList(),
            hubRowItems = emptyList(),
            watchHistory = listOf(history("snapshot", lastWatched = 200L, position = 80000L)),
            mergeWatchHistory = false
        )

        assertNull(dao.getHistoryItem("old"))
        assertEquals(80000L, dao.getHistoryItem("snapshot")?.position)
        assertEquals(listOf("New"), dao.getAllAddons().first().map { it.name })
    }

    @Test
    fun replaceRuntimeStateMergeKeepsNewestVersionPerHistoryId() = runTest {
        dao.upsertHistory(history("same", lastWatched = 300L, position = 90000L))
        dao.upsertHistory(history("db-only", lastWatched = 150L, position = 20000L))

        dao.replaceRuntimeState(
            addons = emptyList(),
            catalogConfigs = emptyList(),
            hubRows = emptyList(),
            hubRowItems = emptyList(),
            watchHistory = listOf(
                history("same", lastWatched = 200L, position = 10000L),
                history("snapshot-only", lastWatched = 400L, position = 70000L)
            ),
            mergeWatchHistory = true
        )

        assertEquals(90000L, dao.getHistoryItem("same")?.position)
        assertEquals(20000L, dao.getHistoryItem("db-only")?.position)
        assertEquals(70000L, dao.getHistoryItem("snapshot-only")?.position)
    }

    @Test
    fun replaceRuntimeStateMergeUsesSnapshotOnTimestampTie() = runTest {
        dao.upsertHistory(history("same", lastWatched = 300L, position = 10000L))

        dao.replaceRuntimeState(
            addons = emptyList(),
            catalogConfigs = emptyList(),
            hubRows = emptyList(),
            hubRowItems = emptyList(),
            watchHistory = listOf(history("same", lastWatched = 300L, position = 99000L)),
            mergeWatchHistory = true
        )

        assertEquals(99000L, dao.getHistoryItem("same")?.position)
    }

    @Test
    fun replaceRuntimeStateRecordsOwnerInSameTransaction() = runTest {
        assertNull(dao.getRuntimeOwnerProfileId())

        dao.replaceRuntimeState(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), false, ownerProfileId = 7)

        assertEquals(7, dao.getRuntimeOwnerProfileId())
    }

    @Test
    fun mergeDoesNotResurrectTombstonedSnapshotOnlyRows() = runTest {
        dao.replaceRuntimeState(emptyList(), emptyList(), emptyList(), emptyList(),
            listOf(history("deleted", lastWatched = 100L), history("ep:1:1", lastWatched = 100L, type = "series")),
            mergeWatchHistory = false, ownerProfileId = 1)
        dao.deleteHistoryItem("deleted")
        dao.deleteSeriesHistory("ep:")

        // Stale snapshot (written before the deletes) still lists both rows.
        dao.replaceRuntimeState(emptyList(), emptyList(), emptyList(), emptyList(),
            listOf(history("deleted", lastWatched = 100L), history("ep:1:1", lastWatched = 100L, type = "series")),
            mergeWatchHistory = true, ownerProfileId = 1)

        assertNull(dao.getHistoryItem("deleted"))
        assertNull(dao.getHistoryItem("ep:1:1"))
    }

    @Test
    fun mergeKeepsSnapshotRowWatchedAgainAfterTombstone() = runTest {
        dao.replaceRuntimeState(emptyList(), emptyList(), emptyList(), emptyList(),
            listOf(history("item", lastWatched = 100L)), mergeWatchHistory = false, ownerProfileId = 1)
        dao.deleteHistoryItem("item")
        val rewatchedAt = System.currentTimeMillis() + 60_000L

        dao.replaceRuntimeState(emptyList(), emptyList(), emptyList(), emptyList(),
            listOf(history("item", lastWatched = rewatchedAt)), mergeWatchHistory = true, ownerProfileId = 1)

        assertEquals(rewatchedAt, dao.getHistoryItem("item")?.lastWatched)
    }

    @Test
    fun tombstonesArePerProfilePrunedAndRemovedWithProfile() = runTest {
        dao.insertProfile(ProfileEntity(id = 1, name = "One"))
        dao.replaceRuntimeState(emptyList(), emptyList(), emptyList(), emptyList(),
            listOf(history("item", lastWatched = 100L)), mergeWatchHistory = false, ownerProfileId = 1)
        dao.deleteHistoryItem("item")
        assertEquals(listOf("item"), dao.getHistoryTombstones(1).map { it.itemId })
        assertTrue(dao.getHistoryTombstones(2).isEmpty())

        // Profile 2's merge must not see profile 1's tombstones.
        dao.replaceRuntimeState(emptyList(), emptyList(), emptyList(), emptyList(),
            listOf(history("item", lastWatched = 100L)), mergeWatchHistory = true, ownerProfileId = 2)
        assertEquals(100L, dao.getHistoryItem("item")?.lastWatched)

        dao.deleteProfileCascading(1)
        assertTrue(dao.getHistoryTombstones(1).isEmpty())

        dao.deleteHistoryItem("item") // owner is now profile 2
        dao.replaceRuntimeState(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            mergeWatchHistory = true, ownerProfileId = 2,
            now = System.currentTimeMillis() + HISTORY_TOMBSTONE_TTL_MS + 1L)
        assertTrue(dao.getHistoryTombstones(2).isEmpty())
    }

    @Test
    fun recentSearchesReplaceSameQueryOrderNewestFirstAndTrimPerProfile() = runTest {
        for (index in 1L..6L) {
            dao.upsertRecentSearch(RecentSearchEntity(1, "q$index", index))
        }
        dao.upsertRecentSearch(RecentSearchEntity(2, "other", 100L))
        dao.upsertRecentSearch(RecentSearchEntity(1, "q2", 99L))

        assertEquals("q2", dao.getRecentSearches(1).first().query)

        dao.trimRecentSearches(profileId = 1, keep = 3)

        assertEquals(listOf("q2", "q6", "q5"), dao.getRecentSearches(1, 20).map { it.query })
        assertEquals(listOf("other"), dao.getRecentSearches(2, 20).map { it.query })
    }

    @Test
    fun seriesHistoryQueriesUseCanonicalPrefixAndLatestTimestamp() = runTest {
        dao.upsertHistory(history("show:1:1", lastWatched = 100L, type = "series", seriesId = "show"))
        dao.upsertHistory(history("show:1:2", lastWatched = 300L, type = "series", seriesId = "show"))
        dao.upsertHistory(history("other:1:1", lastWatched = 500L, type = "series", seriesId = "other"))

        assertEquals(
            listOf("show:1:2", "show:1:1"),
            dao.getSeriesEpisodeHistory("show:%").map { it.id }
        )
        assertEquals("show:1:2", dao.getLatestSeriesEpisodeHistory("show:%")?.id)
        assertEquals(2, dao.getHistoryItemsForSeries("show", "show:%").size)

        dao.deleteSeriesHistory("show:%")
        assertTrue(dao.getSeriesEpisodeHistory("show:%").isEmpty())
        assertEquals("other:1:1", dao.getHistoryItem("other:1:1")?.id)
    }

    private fun addon(url: String, name: String, sortOrder: Int) = AddonEntity(
        transportUrl = url,
        id = name.lowercase(),
        name = name,
        version = "1.0.0",
        description = null,
        iconUrl = null,
        sortOrder = sortOrder
    )

    private fun watchlist(profileId: Int, id: String, addedAt: Long) = WatchlistEntity(
        profileId = profileId,
        id = id,
        type = "movie",
        title = id,
        poster = null,
        addedAt = addedAt
    )

    private fun nextUp(profileId: Int, seriesId: String) = SeriesNextUpEntity(
        profileId = profileId,
        seriesId = seriesId,
        title = seriesId,
        poster = null,
        nextSeason = 1,
        nextEpisode = 2,
        nextEpisodeTitle = "Episode 2",
        updatedAt = 10L
    )

    private fun history(
        id: String,
        lastWatched: Long,
        position: Long = 10_000L,
        type: String = "movie",
        seriesId: String? = null
    ) = WatchHistoryEntity(
        id = id,
        title = id,
        poster = null,
        seriesId = seriesId,
        position = position,
        duration = 100_000L,
        lastWatched = lastWatched,
        type = type
    )

    private fun catalog(transportUrl: String, id: String) = CatalogConfigEntity(
        uniqueId = "$transportUrl/movie/$id",
        transportUrl = transportUrl,
        addonName = transportUrl,
        catalogType = "movie",
        catalogId = id
    )
}
