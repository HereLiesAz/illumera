package com.hereliesaz.illumera.ui.home

import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.domain.CategoryRow
import com.hereliesaz.illumera.domain.ScreenGenreRows.TitleOrder
import com.hereliesaz.illumera.domain.StreamingServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RowSortChipsTest {
    private val b = MetaItem(id = "b", name = "Bravo", releaseInfo = "2010", imdbRating = "9.0")
    private val a = MetaItem(id = "a", name = "Alpha", releaseInfo = "2020", imdbRating = "5.0")
    private val c = MetaItem(id = "c", name = "Charlie", releaseInfo = "2000", imdbRating = "7.0")

    private fun row(id: String, title: String) = CategoryRow(id, title, order = 0, items = listOf(b, a, c))
    private fun ids(row: CategoryRow) = row.items.map { it.id }

    @Test
    fun `service rows are recognised by title`() {
        listOf("Netflix", "HBO Max", "Disney+", "Prime  Video", "Prime Video", "Apple TV+", "Hulu", "Peacock")
            .forEach { assertTrue(it, StreamingServices.isServiceRowTitle(it)) }
        listOf("Trending", "Because you watched Dune", "Netflix Originals Mix", "", "Action")
            .forEach { assertFalse(it, StreamingServices.isServiceRowTitle(it)) }
    }

    @Test
    fun `a service row is sorted by the order chosen for it`() {
        val netflix = row("catalog-home:netflix", "Netflix")
        val state = HomeViewModel.HomeState(mixedRows = listOf(netflix))

        fun sortedIds(order: TitleOrder) = ids(
            withRowSorts(state, mapOf(netflix.id to order)).mixedRows.single() as CategoryRow
        )

        assertEquals(listOf("a", "b", "c"), sortedIds(TitleOrder.TITLE_ASC))
        assertEquals(listOf("c", "b", "a"), sortedIds(TitleOrder.TITLE_DESC))
        assertEquals(listOf("b", "c", "a"), sortedIds(TitleOrder.RATING))
        assertEquals(listOf("a", "b", "c"), sortedIds(TitleOrder.NEWEST))
        assertEquals(listOf("c", "b", "a"), sortedIds(TitleOrder.OLDEST))
        assertEquals(listOf("b", "a", "c"), sortedIds(TitleOrder.CATALOG))
    }

    @Test
    fun `other rows keep their order even if an entry names them`() {
        val trending = row("catalog-home:trending", "Trending")
        val state = HomeViewModel.HomeState(mixedRows = listOf(trending))
        val sorted = withRowSorts(state, mapOf(trending.id to TitleOrder.TITLE_ASC))
        assertEquals(listOf("b", "a", "c"), ids(sorted.mixedRows.single() as CategoryRow))
    }

    @Test
    fun `rows are sorted independently`() {
        val netflix = row("catalog-home:netflix", "Netflix")
        val hulu = row("catalog-home:hulu", "Hulu")
        val state = HomeViewModel.HomeState(mixedRows = listOf(netflix, hulu))
        val sorted = withRowSorts(state, mapOf(netflix.id to TitleOrder.TITLE_ASC)).mixedRows
        assertEquals(listOf("a", "b", "c"), ids(sorted[0] as CategoryRow))
        assertEquals(listOf("b", "a", "c"), ids(sorted[1] as CategoryRow))
    }

    @Test
    fun `no chosen orders leaves the state untouched`() {
        val state = HomeViewModel.HomeState(mixedRows = listOf(row("catalog-home:netflix", "Netflix")))
        assertSame(state, withRowSorts(state, emptyMap()))
    }
}
