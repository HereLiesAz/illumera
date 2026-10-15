package com.hereliesaz.illumera.ui.home

import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.domain.HomeRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Fixed input/output fixtures: these expectations do not use the production deduper. */
class GenreRowReconciliationTest {
    private val movie = MetaItem(
        id = "tt1234567", type = "movie", name = "A Film", releaseInfo = "2024"
    )
    private val unrelated = MetaItem(
        id = "tt7654321", type = "movie", name = "Another Film", releaseInfo = "2022"
    )
    private val comedy = "catalog-aggregate:movie:Comedy"
    private val drama = "catalog-aggregate:movie:Drama"
    private val action = "catalog-aggregate:movie:Action"

    @Test
    fun canonicalClassificationMovesOnlyItsTitleAndRetainsEveryRowIdentity() {
        val rows = listOf(
            HomeRow(configId = drama, title = "Drama", items = listOf(movie)),
            HomeRow(configId = comedy, title = "Comedy", items = emptyList()),
            HomeRow(configId = action, title = "Action", items = listOf(unrelated))
        )
        val result = reconcileGenreRows(rows, comedy, listOf(movie), authoritative = true)

        assertEquals(listOf(drama, comedy, action), result.map { it.configId })
        assertEquals(listOf("Drama", "Comedy", "Action"), result.map { it.title })
        assertEquals(emptyList<MetaItem>(), result[0].items)
        assertEquals(listOf("tt1234567"), result[1].items.map { it.id })
        assertEquals(listOf("tt7654321"), result[2].items.map { it.id })
        assertSame(rows[2], result[2])
    }

    @Test
    fun mixedLazyColumnRowsRetainUniqueIdsAndUnchangedNeighbourReferences() {
        val original = listOf(
            HomeRow(configId = drama, title = "Drama", items = listOf(movie)),
            HomeRow(configId = comedy, title = "Comedy", items = emptyList()),
            HomeRow(configId = action, title = "Action", items = listOf(unrelated))
        )
        val mixed = original.map { com.hereliesaz.illumera.domain.CategoryRow.fromHomeRow(it) }
        val updatedRows = reconcileGenreRows(
            original, comedy, listOf(movie), authoritative = true
        )
        val result = reconcileGenreMixedRows(mixed, updatedRows)
            .map { it as com.hereliesaz.illumera.domain.CategoryRow }

        assertEquals(listOf(drama, comedy, action), result.map { it.id })
        assertEquals(listOf("Drama", "Comedy", "Action"), result.map { it.title })
        assertEquals(listOf(0, 1, 1), result.map { it.items.size })
        assertSame(mixed[2], result[2])
    }

    @Test
    fun laterFallbackPageCannotResurrectAClassifiedTitleInAnotherGenre() {
        val rows = listOf(
            HomeRow(configId = drama, title = "Drama", items = emptyList()),
            HomeRow(configId = comedy, title = "Comedy", items = listOf(movie))
        )
        val result = reconcileGenreRows(rows, drama, listOf(movie), authoritative = false)

        assertSame(rows[0], result[0])
        assertSame(rows[1], result[1])
    }

    @Test
    fun replayingCanonicalBatchDoesNotRecreateRowsOrRemountCards() {
        val rows = listOf(
            HomeRow(configId = drama, title = "Drama", items = emptyList()),
            HomeRow(configId = comedy, title = "Comedy", items = listOf(movie))
        )
        val result = reconcileGenreRows(rows, comedy, listOf(movie), authoritative = true)

        assertSame(rows[0], result[0])
        assertSame(rows[1], result[1])
        assertEquals(1, result[1].items.size)
    }

    @Test
    fun providerAliasDoesNotProduceDuplicateTitleOrEmptyAnUnrelatedRow() {
        val alias = movie.copy(id = "provider:18222")
        val rows = listOf(
            HomeRow(configId = drama, title = "Drama", items = listOf(alias, unrelated)),
            HomeRow(configId = comedy, title = "Comedy", items = emptyList())
        )
        val result = reconcileGenreRows(rows, comedy, listOf(movie), authoritative = true)

        assertEquals(listOf("tt7654321"), result[0].items.map { it.id })
        assertEquals(listOf("tt1234567"), result[1].items.map { it.id })
    }
}
