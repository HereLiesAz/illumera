package com.hereliesaz.illumera.ui.settings

import com.hereliesaz.illumera.data.model.CatalogConfigEntity
import com.hereliesaz.illumera.data.model.HubRowEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditorGroupingTest {
    private fun cat(addon: String, id: String, order: Int) = EditorListItem.CategoryItem(
        CatalogConfigEntity(
            uniqueId = "$addon/movie/$id", transportUrl = addon, addonName = "Addon $addon",
            catalogType = "movie", catalogId = id, showInHome = true, homeOrder = order
        ),
        order
    )

    private fun hub(id: String, order: Int) =
        EditorListItem.HubRowItem(HubRowEntity(id = id, shape = "wide", showInHome = true), emptyList(), order)

    // a: three rows, with b's two and a hub between them; c has a single row.
    private val items = listOf(
        cat("a", "1", 0), cat("b", "1", 1), cat("a", "2", 2), hub("h", 3),
        cat("c", "1", 4), cat("b", "2", 5), cat("a", "3", 6)
    )

    @Test fun ungroupedIsTheListAsItIs() {
        val display = groupEditorItems(items, grouped = false, expanded = emptySet())
        assertEquals(items.map { it.key }, display.map { it.key })
        assertEquals(true, display.all { it is EditorDisplayItem.Row && it.groupKey == null })
    }

    @Test fun groupedShutShowsOneLinePerAddonWithSeveralRows() {
        val display = groupEditorItems(items, grouped = true, expanded = emptySet())
        // a (3 rows) at the first a, b (2 rows) at the first b, the hub, and c's single row alone.
        assertEquals(listOf("group:a", "group:b", "hub:h", "cat:c/movie/1"), display.map { it.key })
        assertEquals(3, (display[0] as EditorDisplayItem.Group).rowCount)
        assertEquals("Addon a", (display[0] as EditorDisplayItem.Group).title)
    }

    @Test fun anOpenGroupPullsItsRowsUpInOrder() {
        val display = groupEditorItems(items, grouped = true, expanded = setOf("a"))
        assertEquals(
            listOf("group:a", "cat:a/movie/1", "cat:a/movie/2", "cat:a/movie/3", "group:b", "hub:h", "cat:c/movie/1"),
            display.map { it.key }
        )
        assertEquals(true, (display[0] as EditorDisplayItem.Group).expanded)
    }

    @Test fun anAddonWithOneRowIsNeverAGroup() {
        val display = groupEditorItems(listOf(cat("c", "1", 0)), grouped = true, expanded = emptySet())
        assertEquals(1, display.size)
        assertEquals(true, display[0] is EditorDisplayItem.Row)
    }

    @Test fun movesStayInsideTheGroup() {
        val display = groupEditorItems(items, grouped = true, expanded = setOf("a"))
        val a1 = items[0]; val a2 = items[2]; val a3 = items[6]
        assertEquals(a2.key, moveNeighbour(display, a1, 1)?.key)
        assertEquals(a1.key, moveNeighbour(display, a2, -1)?.key)
        // The group's last row doesn't move down into the next group or past the header above.
        assertNull(moveNeighbour(display, a3, 1))
        assertNull(moveNeighbour(display, a1, -1))
    }

    @Test fun ungroupedMovesGoToTheNextRowInTheList() {
        val display = groupEditorItems(items, grouped = false, expanded = emptySet())
        assertEquals(items[1].key, moveNeighbour(display, items[0], 1)?.key)
        assertNull(moveNeighbour(display, items[0], -1))
        assertNull(moveNeighbour(display, items.last(), 1))
    }

    // ---- moving several rows in a row

    private val flat = listOf(cat("a", "1", 0), cat("b", "1", 1), cat("c", "1", 2), cat("d", "1", 3), cat("e", "1", 4))

    @Test fun aPickedUpRowJumpsUnderTheOneMovedLast() {
        val anchor = pickupAnchor(grouped = false, lastMovedKey = flat[0].key, pickedKey = flat[3].key, items = flat)
        assertEquals(flat[0].key, anchor?.key)
    }

    @Test fun nothingJumpsWithoutALastMovedRowOrWhenItIsAlreadyThere() {
        assertNull(pickupAnchor(false, null, flat[3].key, flat))
        assertNull(pickupAnchor(false, flat[3].key, flat[3].key, flat))
        // Already right under it.
        assertNull(pickupAnchor(false, flat[1].key, flat[2].key, flat))
        // The last moved row is gone (hidden): stay put.
        assertNull(pickupAnchor(false, "cat:gone", flat[2].key, flat))
    }

    @Test fun nothingJumpsInTheGroupedView() {
        assertNull(pickupAnchor(grouped = true, lastMovedKey = flat[0].key, pickedKey = flat[3].key, items = flat))
    }

    @Test fun theJumpLandsJustUnderTheAnchorWhicheverWayItMoves() {
        // d (3) up under a (0): lands at 1. a (3) down under e (4): everything shifts up, lands at 4.
        assertEquals(1, jumpTargetIndex(from = 3, anchorIndex = 0))
        assertEquals(4, jumpTargetIndex(from = 0, anchorIndex = 4))
    }

    @Test fun focusReturnsToTheRowNowAtTheOriginalPlace() {
        val display = groupEditorItems(flat, grouped = false, expanded = emptySet())
        assertEquals(flat[2].key, returnFocusKey(display, 2))
        // Picked up from the last place and the list got shorter: the nearest row.
        assertEquals(flat[4].key, returnFocusKey(display, 9))
        assertNull(returnFocusKey(emptyList(), 0))
    }

    @Test fun focusSkipsAGroupHeaderToTheNearestRow() {
        val grouped = groupEditorItems(
            listOf(cat("a", "1", 0), cat("a", "2", 1), cat("c", "1", 2)), grouped = true, expanded = setOf("a")
        )
        // [group:a, a1, a2, c1]; origin 0 is the header, so the first row after it.
        assertEquals("cat:a/movie/1", returnFocusKey(grouped, 0))
    }
}
