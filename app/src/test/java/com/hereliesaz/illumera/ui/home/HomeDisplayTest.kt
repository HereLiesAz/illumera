package com.hereliesaz.illumera.ui.home

import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.domain.CategoryRow
import com.hereliesaz.illumera.domain.DashboardTab
import com.hereliesaz.illumera.domain.HomeRow
import com.hereliesaz.illumera.domain.ScreenGenreRows.TitleOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeDisplayTest {
    private val a = MetaItem(id = "a", name = "Alpha", releaseInfo = "2020", imdbRating = "5.0")
    private val b = MetaItem(id = "b", name = "Bravo", releaseInfo = "2010", imdbRating = "9.0")
    private val c = MetaItem(id = "c", name = "Charlie", releaseInfo = "2000", imdbRating = "7.0")

    private fun homeRow(id: String, title: String, vararg items: MetaItem, order: Int = 0) =
        HomeRow(configId = id, title = title, items = items.toList(), order = order)

    private fun stateOf(vararg rows: HomeRow, watched: Set<String> = emptySet()): HomeViewModel.HomeState =
        HomeViewModel.HomeState(
            rows = rows.toList(),
            mixedRows = rows.map { CategoryRow.fromHomeRow(it) },
            heroRow = rows.firstOrNull(),
            isLoading = false,
            loadedScreen = "home",
            watchedIds = watched
        )

    private fun deriver(
        tab: DashboardTab = DashboardTab.HOME,
        profileId: Int? = 1,
        order: (String) -> TitleOrder = { TitleOrder.CATALOG }
    ) = HomeDisplayDeriver(tab, profileId, order)

    private fun ids(row: CategoryRow) = row.items.map { it.id }
    private fun ids(row: HomeRow) = row.items.map { it.id }

    // --- filtering -----------------------------------------------------------------------

    @Test
    fun `home hides watched titles from rows, mixed rows and the hero`() {
        val state = stateOf(homeRow("r", "Trending", a, b, c), watched = setOf("b"))
        val shown = deriver().derive(state, emptyMap())
        assertEquals(listOf("a", "c"), ids(shown.rows.single()))
        assertEquals(listOf("a", "c"), ids(shown.mixedRows.single() as CategoryRow))
        assertEquals(listOf("a", "c"), ids(shown.heroRow!!))
    }

    @Test
    fun `disliked titles are hidden on every tab`() {
        val state = stateOf(homeRow("r", "Action", a, b, c)).copy(dislikedIds = setOf("a"))
        DashboardTab.entries.forEach { tab ->
            val shown = deriver(tab).derive(state, emptyMap())
            assertEquals(tab.name, listOf("b", "c"), ids(shown.rows.single()))
            assertEquals(tab.name, listOf("b", "c"), ids(shown.mixedRows.single() as CategoryRow))
        }
    }

    @Test
    fun `watched titles are only hidden on home`() {
        val state = stateOf(homeRow("r", "Action", a, b, c), watched = setOf("b"))
        val shown = deriver(DashboardTab.MOVIES).derive(state, emptyMap())
        assertEquals(listOf("a", "b", "c"), ids(shown.rows.single()))
    }

    @Test
    fun `personal rows follow the catalog rows on home only`() {
        val personal = CategoryRow("personal:x", "More like X", order = 0, items = listOf(a))
        val state = stateOf(homeRow("r", "Trending", b)).copy(personalRows = listOf(personal))
        val home = deriver().derive(state, emptyMap())
        assertEquals(listOf("r", "personal:x"), home.mixedRows.map { it.id })
        val movies = deriver(DashboardTab.MOVIES).derive(state, emptyMap())
        assertEquals(listOf("r"), movies.mixedRows.map { it.id })
    }

    // --- sorting -------------------------------------------------------------------------

    @Test
    fun `movies and series sort each genre row by its own chosen order`() {
        val state = stateOf(homeRow("g1", "Action", b, a, c), homeRow("g2", "Drama", b, a, c))
        val shown = deriver(DashboardTab.MOVIES) { title ->
            if (title == "Action") TitleOrder.TITLE_ASC else TitleOrder.CATALOG
        }.derive(state, emptyMap())
        assertEquals(listOf("a", "b", "c"), ids(shown.rows[0]))
        assertEquals(listOf("b", "a", "c"), ids(shown.rows[1]))
        assertEquals(listOf("a", "b", "c"), ids(shown.mixedRows[0] as CategoryRow))
        assertEquals(listOf("b", "a", "c"), ids(shown.mixedRows[1] as CategoryRow))
    }

    @Test
    fun `genre sort covers every order`() {
        val state = stateOf(homeRow("g", "Action", b, a, c))
        fun sorted(order: TitleOrder) =
            ids(deriver(DashboardTab.SERIES) { order }.derive(state, emptyMap()).rows.single())
        assertEquals(listOf("a", "b", "c"), sorted(TitleOrder.TITLE_ASC))
        assertEquals(listOf("c", "b", "a"), sorted(TitleOrder.TITLE_DESC))
        assertEquals(listOf("b", "c", "a"), sorted(TitleOrder.RATING))
        assertEquals(listOf("a", "b", "c"), sorted(TitleOrder.NEWEST))
        assertEquals(listOf("c", "b", "a"), sorted(TitleOrder.OLDEST))
        assertEquals(listOf("b", "a", "c"), sorted(TitleOrder.CATALOG))
    }

    @Test
    fun `home never applies the genre sort`() {
        val state = stateOf(homeRow("g", "Action", b, a, c))
        val shown = deriver(DashboardTab.HOME) { TitleOrder.TITLE_ASC }.derive(state, emptyMap())
        assertEquals(listOf("b", "a", "c"), ids(shown.rows.single()))
    }

    @Test
    fun `without a profile nothing is sorted`() {
        val state = stateOf(homeRow("g", "Action", b, a, c))
        val shown = deriver(DashboardTab.MOVIES, profileId = null) { TitleOrder.TITLE_ASC }
            .derive(state, emptyMap())
        assertSame(state, shown)
    }

    @Test
    fun `a streaming service row follows the order chosen on its header`() {
        val state = stateOf(homeRow("catalog-home:netflix", "Netflix", b, a, c), homeRow("t", "Trending", b, a, c))
        val shown = deriver().derive(state, mapOf("catalog-home:netflix" to TitleOrder.TITLE_ASC, "t" to TitleOrder.TITLE_ASC))
        assertEquals(listOf("a", "b", "c"), ids(shown.mixedRows[0] as CategoryRow))
        assertEquals(listOf("b", "a", "c"), ids(shown.mixedRows[1] as CategoryRow))
    }

    @Test
    fun `the genre sort is read once per title per derivation, not per row item`() {
        var reads = 0
        val state = stateOf(homeRow("g1", "Action", b, a, c), homeRow("g2", "Action", c, b, a))
        deriver(DashboardTab.MOVIES) { reads++; TitleOrder.TITLE_ASC }.derive(state, emptyMap())
        assertEquals(1, reads)
    }

    // --- stable instances ----------------------------------------------------------------

    @Test
    fun `a change unrelated to the rows keeps the same row list instances`() {
        val deriver = deriver(DashboardTab.MOVIES) { TitleOrder.TITLE_ASC }
        val first = stateOf(homeRow("g", "Action", b, a, c))
        val shownFirst = deriver.derive(first, emptyMap())

        // Continue Watching progress, enrichment of another title, loading flags...
        val second = first.copy(
            enrichedMeta = mapOf("movie:z" to a),
            tmdbEnrichedIds = setOf("movie:z"),
            isLoading = false
        )
        val shownSecond = deriver.derive(second, emptyMap())

        assertEquals(1, deriver.derivations)
        assertSame(shownFirst.rows, shownSecond.rows)
        assertSame(shownFirst.mixedRows, shownSecond.mixedRows)
        assertSame(shownFirst.heroRow, shownSecond.heroRow)
        assertEquals(second.enrichedMeta, shownSecond.enrichedMeta)
    }

    @Test
    fun `equal rows rebuilt elsewhere keep the earlier derived instances`() {
        val deriver = deriver(DashboardTab.HOME)
        val first = stateOf(homeRow("g", "Action", b, a, c), watched = setOf("a"))
        val shownFirst = deriver.derive(first, emptyMap())
        val second = stateOf(homeRow("g", "Action", b, a, c), watched = setOf("a"))
        val shownSecond = deriver.derive(second, emptyMap())
        assertEquals(1, deriver.derivations)
        assertSame(shownFirst.mixedRows, shownSecond.mixedRows)
    }

    @Test
    fun `real data changes rebuild the rows`() {
        val deriver = deriver(DashboardTab.HOME)
        val first = stateOf(homeRow("g", "Action", a, b, c))
        val shownFirst = deriver.derive(first, emptyMap())

        val watched = deriver.derive(first.copy(watchedIds = setOf("a")), emptyMap())
        assertEquals(2, deriver.derivations)
        assertNotSame(shownFirst.rows, watched.rows)
        assertEquals(listOf("b", "c"), ids(watched.rows.single()))

        val extended = deriver.derive(
            stateOf(homeRow("g", "Action", a, b, c, MetaItem(id = "d", name = "Delta")), watched = setOf("a")),
            emptyMap()
        )
        assertEquals(3, deriver.derivations)
        assertEquals(listOf("b", "c", "d"), ids(extended.rows.single()))
    }

    @Test
    fun `a changed sort setting rebuilds the rows`() {
        var order = TitleOrder.CATALOG
        val deriver = deriver(DashboardTab.MOVIES) { order }
        val state = stateOf(homeRow("g", "Action", b, a, c))
        assertEquals(listOf("b", "a", "c"), ids(deriver.derive(state, emptyMap()).rows.single()))
        order = TitleOrder.TITLE_ASC
        assertEquals(listOf("a", "b", "c"), ids(deriver.derive(state, emptyMap()).rows.single()))
    }

    @Test
    fun `nothing to filter or sort passes the state through untouched`() {
        val state = stateOf(homeRow("g", "Action", a, b, c))
        assertSame(state, deriver().derive(state, emptyMap()))
    }

    // --- the flow ------------------------------------------------------------------------

    @Test
    fun `the display flow emits for data changes only and re-derives off the main thread`() = runTest {
        val source = MutableStateFlow(stateOf(homeRow("g", "Action", a, b, c)))
        val orders = MutableStateFlow<Map<String, TitleOrder>>(emptyMap())
        val emitted = mutableListOf<HomeViewModel.HomeState>()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val job = launch(dispatcher) {
            displayStates(source, orders, deriver(), dispatcher).toList(emitted)
        }
        advanceUntilIdle()
        assertEquals(1, emitted.size)

        // The same state again (what a no-op update produces) does not emit.
        source.value = source.value.copy()
        advanceUntilIdle()
        assertEquals(1, emitted.size)

        source.value = source.value.copy(watchedIds = setOf("a"))
        advanceUntilIdle()
        assertEquals(2, emitted.size)
        assertEquals(listOf("b", "c"), ids(emitted.last().rows.single()))
        job.cancel()
    }

    @Test
    fun `display flow reuses the row lists across state emissions that do not touch rows`() = runTest {
        val source = MutableStateFlow(stateOf(homeRow("g", "Action", a, b, c), watched = setOf("a")))
        val emitted = mutableListOf<HomeViewModel.HomeState>()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val job = launch(dispatcher) {
            displayStates(source, MutableStateFlow(emptyMap()), deriver(), dispatcher).toList(emitted)
        }
        advanceUntilIdle()
        source.value = source.value.copy(tmdbEnrichedIds = setOf("movie:q"))
        advanceUntilIdle()
        assertEquals(2, emitted.size)
        assertSame(emitted[0].rows, emitted[1].rows)
        assertSame(emitted[0].mixedRows, emitted[1].mixedRows)
        assertTrue(emitted[1].tmdbEnrichedIds.isNotEmpty())
        job.cancel()
    }
}
