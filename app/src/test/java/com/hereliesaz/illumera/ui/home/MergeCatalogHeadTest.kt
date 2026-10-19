package com.hereliesaz.illumera.ui.home

import com.hereliesaz.illumera.data.model.stremio.MetaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MergeCatalogHeadTest {
    private fun item(id: String) = MetaItem(id = id, type = "movie", name = "Title $id")
    private fun items(vararg ids: String) = ids.map(::item)
    private fun ids(list: List<MetaItem>?) = list?.map { it.id }

    @Test fun unchangedHeadLoadsNothingAndChangesNothing() {
        val current = items("a", "b", "c", "d")
        assertNull(mergeCatalogHead(current, emptyList(), items("a", "b", "c", "d")))
    }

    @Test fun newTitlesGoInFrontAndNothingIsRemoved() {
        val current = items("a", "b", "c")
        val merged = mergeCatalogHead(current, emptyList(), items("x", "a", "b"))
        assertEquals(listOf("x", "a", "b", "c"), ids(merged))
    }

    @Test fun titlesAlreadyElsewhereAreNotAdded() {
        val merged = mergeCatalogHead(items("a"), items("x"), items("x", "y", "a"))
        assertEquals(listOf("y", "a"), ids(merged))
    }

    @Test fun nothingNewMeansNoUpdate() {
        assertNull(mergeCatalogHead(items("a", "b"), emptyList(), items("b", "a")))
    }

    @Test fun rowIsCappedButKeepsItsHead() {
        val current = (1..5).map { item("o$it") }
        val merged = mergeCatalogHead(current, emptyList(), items("n1", "n2"), maxItems = 4)
        assertEquals(listOf("n1", "n2", "o1", "o2"), ids(merged))
    }
}
