package com.hereliesaz.illumera.ui.search

import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.RecentSearchEntity
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: AddonRepository
    private lateinit var dao: AddonDao
    private lateinit var profileManager: ProfileConfigurationManager

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mockk()
        dao = mockk(relaxed = true)
        profileManager = mockk()
        every { profileManager.getLastActiveProfileId() } returns 7
        coEvery { dao.getRecentSearches(7) } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initLoadsRecentSearchesForActiveProfile() = runTest(dispatcher) {
        coEvery { dao.getRecentSearches(7) } returns listOf(
            RecentSearchEntity(7, "alien", 20),
            RecentSearchEntity(7, "matrix", 10)
        )

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(listOf("alien", "matrix"), viewModel.state.value.recentSearches)
    }

    @Test
    fun liveSearchIsDebouncedAndPartitionsMovieAndSeriesResults() = runTest(dispatcher) {
        val movie = MetaItem(id = "m", type = "movie", name = "Movie")
        val series = MetaItem(id = "s", type = "series", name = "Series")
        coEvery { repository.searchMovies("matrix") } returns listOf(movie, series)
        coEvery { dao.getRecentSearches(7) } returnsMany listOf(
            emptyList(),
            listOf(RecentSearchEntity(7, "matrix", 1))
        )
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.onQueryChange("matrix")
        advanceTimeBy(349)
        coVerify(exactly = 0) { repository.searchMovies(any()) }

        advanceTimeBy(1)
        advanceUntilIdle()

        assertEquals(listOf(movie, series), viewModel.state.value.results)
        assertEquals(listOf(movie), viewModel.state.value.movies)
        assertEquals(listOf(series), viewModel.state.value.series)
        assertFalse(viewModel.state.value.isLoading)
        coVerify(exactly = 1) { dao.upsertRecentSearch(match { it.profileId == 7 && it.query == "matrix" }) }
        coVerify(exactly = 1) { dao.trimRecentSearches(7) }
    }

    @Test
    fun rapidQueryChangesCancelOlderDebouncedSearch() = runTest(dispatcher) {
        coEvery { repository.searchMovies(any()) } returns emptyList()
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.onQueryChange("ma")
        advanceTimeBy(200)
        viewModel.onQueryChange("matrix")
        advanceTimeBy(350)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.searchMovies("ma") }
        coVerify(exactly = 1) { repository.searchMovies("matrix") }
    }

    @Test
    fun blankQueryClearsResultsAndFailureStateWithoutNetworkCall() = runTest(dispatcher) {
        coEvery { repository.searchMovies("x") } throws IllegalStateException("network")
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.onQueryChange("x")
        advanceTimeBy(350)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.searchFailed)

        viewModel.onQueryChange("   ")

        assertTrue(viewModel.state.value.results.isEmpty())
        assertFalse(viewModel.state.value.searchFailed)
        assertFalse(viewModel.state.value.isLoading)
    }

    @Test
    fun singleCharacterSearchRunsButIsNotAddedToRecents() = runTest(dispatcher) {
        coEvery { repository.searchMovies("x") } returns emptyList()
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.onQueryChange("x")
        advanceTimeBy(350)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.searchMovies("x") }
        coVerify(exactly = 0) { dao.upsertRecentSearch(any()) }
    }

    @Test
    fun appendAndRemoveCharacterDelegateToQueryEditing() = runTest(dispatcher) {
        coEvery { repository.searchMovies(any()) } returns emptyList()
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.appendCharacter("a")
        viewModel.appendCharacter("b")
        assertEquals("ab", viewModel.state.value.query)

        viewModel.removeCharacter()
        assertEquals("a", viewModel.state.value.query)
        viewModel.removeCharacter()
        assertEquals("", viewModel.state.value.query)
        viewModel.removeCharacter()
        assertEquals("", viewModel.state.value.query)
    }

    @Test
    fun selectRecentSearchBypassesDebounce() = runTest(dispatcher) {
        coEvery { repository.searchMovies("alien") } returns emptyList()
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.selectRecentSearch("alien")
        advanceUntilIdle()

        assertEquals("alien", viewModel.state.value.query)
        coVerify(exactly = 1) { repository.searchMovies("alien") }
    }

    private fun newViewModel() = SearchViewModel(repository, dao, profileManager)
}
