package com.hereliesaz.illumera.ui.watchlist

import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.WatchlistEntity
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
