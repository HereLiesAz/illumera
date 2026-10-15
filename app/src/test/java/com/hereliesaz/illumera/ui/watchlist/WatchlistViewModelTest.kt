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
    fun libraryGridKeysDoNotChangeWhenMoviesShiftAcrossRowBoundaries() {
        val first = com.hereliesaz.illumera.data.model.stremio.MetaItem(
            id = "tt0000001", type = "movie", name = "First"
        )
        val second = com.hereliesaz.illumera.data.model.stremio.MetaItem(
            id = "tt0000002", type = "movie", name = "Second"
        )
        val third = com.hereliesaz.illumera.data.model.stremio.MetaItem(
            id = "tt0000002", type = "series", name = "Second"
        )

        assertEquals(
            listOf("library_movie_tt0000001", "library_movie_tt0000002", "library_series_tt0000002"),
            listOf(first, second, third).map(::watchlistGridMediaKey)
        )
        assertEquals(
            listOf("library_series_tt0000002", "library_movie_tt0000001", "library_movie_tt0000002"),
            listOf(third, first, second).map(::watchlistGridMediaKey)
        )
    }

    @Test
    fun watchlistGridFitsTwoPortraitPostersOnCompactScreens() {
        assertEquals(2, watchlistGridColumnCount(393, 96, 122))
        assertEquals(1, watchlistGridColumnCount(260, 96, 122))
    }

    @Test
    fun watchlistGridUsesAvailableWidthWithoutOverflowingTheNav() {
        assertEquals(3, watchlistGridColumnCount(600, 96, 122))
        assertEquals(11, watchlistGridColumnCount(1920, 120, 140))
        assertEquals(1, watchlistGridColumnCount(120, 96, 122))
    }

    @Test
    fun differentProviderTitleWithUnknownYearIsNotAssumedDuplicate() {
        fun e(id: String, year: String? = null, poster: String? = null) =
            WatchlistViewModel.LibraryEntry(
                item = com.hereliesaz.illumera.data.model.stremio.MetaItem(
                    id = id, type = "movie", name = "The Return",
                    releaseInfo = year, poster = poster
                ),
                addedAt = 1L, lastWatched = 0L, watched = false, timesWatched = 0
            )
        assertEquals(2, aggregateLibraryEntries(listOf(e("tt0000001"), e("tmdb:2"))).size)
        val merged = aggregateLibraryEntries(
            listOf(e("tt0000001", "2024", ""), e("tmdb:2", "2024", "good-poster"))
        )
        assertEquals(1, merged.size)
        assertEquals("good-poster", merged.single().item.poster)
    }

    @Test
    fun mixedLibraryCollapsesDuplicateProvidersButPreservesMovieAndSeries() {
        fun entry(
            id: String, type: String, title: String, added: Long,
            watchedAt: Long, poster: String? = null, year: String? = null
        ) = WatchlistViewModel.LibraryEntry(
            item = com.hereliesaz.illumera.data.model.stremio.MetaItem(
                id = id, type = type, name = title, poster = poster, releaseInfo = year
            ),
            addedAt = added, lastWatched = watchedAt,
            watched = watchedAt > 0L, timesWatched = if (watchedAt > 0L) 1 else 0
        )
        val input = listOf(
            entry("tt0000033", "movie", "The Return", 100L, 0L, year = "2024"),
            entry("tmdb:9", "movie", "The Return", 0L, 400L,
                poster = "artwork", year = "2024"),
            entry("tt0000033", "series", "The Return", 200L, 0L, year = "2024"),
            entry("tt0000044", "movie", "Another Return", 300L, 0L, year = "2024")
        )
        val merged = aggregateLibraryEntries(input)
        assertEquals(3, merged.size)
        val film = merged.first { it.item.type == "movie" && it.item.id == "tt0000033" }
        assertEquals("artwork", film.item.poster)
        assertEquals(100L, film.addedAt)
        assertEquals(400L, film.lastWatched)
        assertTrue(film.watched)
        assertTrue(merged.any { it.item.type == "series" && it.item.id == "tt0000033" })
        assertTrue(merged.any { it.item.id == "tt0000044" })
    }

    @Test
    fun prefixedImdbLibraryEntriesMergeWithDifferentTitlesButKeepMediaTypesSeparate() {
        fun e(id: String, type: String, title: String) =
            WatchlistViewModel.LibraryEntry(
                item = com.hereliesaz.illumera.data.model.stremio.MetaItem(
                    id = id, type = type, name = title
                ),
                addedAt = 1L, lastWatched = 0L, watched = false, timesWatched = 0
            )
        val sameMovie = listOf(
            e("tt1234567", "movie", "Original title"),
            e("imdb:tt1234567", "movie", "Alternate title")
        )
        assertEquals(1, aggregateLibraryEntries(sameMovie).size)
        assertEquals(1, aggregateLibraryEntries(sameMovie.reversed()).size)
        val differentTypes = sameMovie + e("tt1234567", "series", "Series edition")
        assertEquals(2, aggregateLibraryEntries(differentTypes).size)
        assertEquals(2, aggregateLibraryEntries(listOf(
            e("tt1234567", "movie", "Same name"),
            e("tt7654321", "movie", "Same name")
        )).size)
    }

    @Test
    fun distinctCanonicalImdbTitlesWithSameNameAndYearStaySeparate() {
        val originals = listOf("tt0000091", "tt0000092").map { id ->
            WatchlistViewModel.LibraryEntry(
                item = com.hereliesaz.illumera.data.model.stremio.MetaItem(
                    id = id, type = "movie", name = "The Return", releaseInfo = "2024"
                ),
                addedAt = 1L, lastWatched = 0L, watched = false, timesWatched = 0
            )
        }
        assertEquals(2, aggregateLibraryEntries(originals).size)
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
