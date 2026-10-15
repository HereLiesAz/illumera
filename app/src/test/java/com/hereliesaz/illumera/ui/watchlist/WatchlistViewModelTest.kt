package com.hereliesaz.illumera.ui.watchlist

import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.WatchlistEntity
import com.hereliesaz.illumera.data.model.WatchHistoryEntity
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.repository.AddonRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchlistViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var dao: AddonDao
    private lateinit var profileManager: ProfileConfigurationManager
    private lateinit var activeProfileId: MutableStateFlow<Int?>

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        dao = mockk()
        profileManager = mockk(relaxed = true)
        activeProfileId = MutableStateFlow(1)
        every { profileManager.activeProfileId } returns activeProfileId

        every { dao.getWatchlistByType(1, "movie") } returns flowOf(
            listOf(WatchlistEntity(profileId = 1, id = "m1", type = "movie", title = "One", poster = null, addedAt = 1L))
        )
        every { dao.getWatchlistByType(1, "series") } returns flowOf(
            listOf(WatchlistEntity(profileId = 1, id = "s1", type = "series", title = "Series One", poster = null, addedAt = 1L))
        )
        every { dao.getWatchlistByType(2, "movie") } returns flowOf(
            listOf(WatchlistEntity(profileId = 2, id = "m2", type = "movie", title = "Two", poster = null, addedAt = 2L))
        )
        every { dao.getWatchlistByType(2, "series") } returns flowOf(
            listOf(WatchlistEntity(profileId = 2, id = "s2", type = "series", title = "Series Two", poster = null, addedAt = 2L))
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun libraryMergesSavedAndSyncedTitlesWithoutDuplicatingSeriesEpisodes() = runTest(dispatcher) {
        every { dao.getWatchlist(1) } returns flowOf(listOf(
            WatchlistEntity(1, "tt123", "series", "Series Title", "poster", 1000L),
            WatchlistEntity(1, "tt456", "movie", "Movie Title", null, 2000L)
        ))
        every { dao.getWatchHistory() } returns flowOf(listOf(
            WatchHistoryEntity(
                id = "tt123:1:1", title = "Series Title", poster = "poster",
                seriesId = "tt123", position = 10L, duration = 100L,
                lastWatched = 3000L, type = "series", watched = false
            ),
            WatchHistoryEntity(
                id = "tt123:1:2", title = "Series Title", poster = "poster",
                seriesId = "tt123", position = 100L, duration = 100L,
                lastWatched = 4000L, type = "series", watched = true
            ),
            WatchHistoryEntity(
                id = "tt789", title = "Remote Only", poster = "remote",
                position = 15L, duration = 100L, lastWatched = 5000L,
                type = "movie", watched = false
            )
        ))
        val viewModel = WatchlistViewModel(
            dao = dao, repository = mockk(relaxed = true), profileConfigurationManager = profileManager
        )
        val collector = backgroundScope.launch { viewModel.libraryEntries.collect {} }
        runCurrent()
        advanceUntilIdle()
        val entries = viewModel.libraryEntries.value
        assertEquals(setOf("tt123", "tt456", "tt789"), entries.map { it.item.id }.toSet())
        assertEquals(4000L, entries.first { it.item.id == "tt123" }.lastWatched)
        assertTrue(entries.first { it.item.id == "tt123" }.watched)
        assertEquals("series", entries.first { it.item.id == "tt123" }.item.type)
        collector.cancel()
    }

    @Test
    fun combinedLibraryDeduplicatesSourcesAndKeepsMoviesAndSeriesTogether() {
        fun entry(id: String, type: String, name: String, added: Long, watched: Long = 0L) =
            WatchlistViewModel.LibraryEntry(
                item = com.hereliesaz.illumera.data.model.stremio.MetaItem(
                    id = id, type = type, name = name, releaseInfo = "2024"
                ),
                addedAt = added, lastWatched = watched,
                watched = watched > 0L, timesWatched = if (watched > 0L) 1 else 0
            )
        val merged = aggregateLibraryEntries(listOf(
            entry("tmdb:101", "movie", "Shared Title", 10L),
            entry("tt0000101", "movie", "Shared Title", 0L, 45L),
            entry("tt0000202", "series", "Shared Title", 40L),
            entry("tt0000202", "series", "Shared Title", 0L, 60L),
            entry("tt0000303", "movie", "Other Film", 30L)
        ))
        assertEquals(3, merged.size)
        assertEquals(setOf("movie", "series"), merged.map { it.item.type }.toSet())
        val sharedMovie = merged.single { it.item.type == "movie" && it.item.name == "Shared Title" }
        assertEquals("tmdb:101", sharedMovie.item.id) // saved bookmark stays actionable
        assertEquals(45L, sharedMovie.lastWatched)
        assertTrue(sharedMovie.watched)
        assertEquals(60L, merged.single { it.item.type == "series" }.lastWatched)
    }

    @Test
    fun matchingNamesWithDistinctCanonicalImdbIdsAreNotCollapsed() {
        val entries = listOf("tt1234561", "tt1234562").map { id ->
            WatchlistViewModel.LibraryEntry(
                com.hereliesaz.illumera.data.model.stremio.MetaItem(
                    id = id, type = "movie", name = "The Remake", releaseInfo = "2024"
                ), 1L, 0L, false, 0
            )
        }
        assertEquals(2, aggregateLibraryEntries(entries).size)
    }

    @Test
    fun stoppedWatchlistSubscriptionDoesNotReplayPreviousProfileRows() = runTest(dispatcher) {
        val viewModel = WatchlistViewModel(
            dao = dao,
            repository = mockk<AddonRepository>(relaxed = true),
            profileConfigurationManager = profileManager
        )

        val collector = backgroundScope.launch {
            viewModel.movieItems.collect {}
        }
        advanceUntilIdle()
        assertEquals(listOf("m1"), viewModel.movieItems.value.map { it.id })

        collector.cancel()
        advanceTimeBy(5_000L)
        advanceUntilIdle()
        assertTrue(viewModel.movieItems.value.isEmpty())

        activeProfileId.value = 2
        advanceUntilIdle()
        assertTrue(viewModel.movieItems.value.isEmpty())

        val replacementCollector = backgroundScope.launch {
            viewModel.movieItems.collect {}
        }
        // advanceUntilIdle() returns once only backgroundScope work is queued, and the
        // stopped share leaves nothing else scheduled, so start the collector explicitly.
        runCurrent()
        advanceUntilIdle()
        assertEquals(listOf("m2"), viewModel.movieItems.value.map { it.id })
        replacementCollector.cancel()
    }

    @Test
    fun watchlistSubscriptionsFollowActiveProfileChanges() = runTest(dispatcher) {
        val viewModel = WatchlistViewModel(
            dao = dao,
            repository = mockk<AddonRepository>(relaxed = true),
            profileConfigurationManager = profileManager
        )

        val movieCollector = backgroundScope.launch {
            viewModel.movieItems.collect {}
        }
        val seriesCollector = backgroundScope.launch {
            viewModel.seriesItems.collect {}
        }
        advanceUntilIdle()

        assertEquals(listOf("m1"), viewModel.movieItems.value.map { it.id })
        assertEquals(listOf("s1"), viewModel.seriesItems.value.map { it.id })

        activeProfileId.value = 2
        advanceUntilIdle()
        assertEquals(listOf("m2"), viewModel.movieItems.value.map { it.id })
        assertEquals(listOf("s2"), viewModel.seriesItems.value.map { it.id })

        activeProfileId.value = null
        advanceUntilIdle()
        assertTrue(viewModel.movieItems.value.isEmpty())
        assertTrue(viewModel.seriesItems.value.isEmpty())

        movieCollector.cancel()
        seriesCollector.cancel()
    }
}
