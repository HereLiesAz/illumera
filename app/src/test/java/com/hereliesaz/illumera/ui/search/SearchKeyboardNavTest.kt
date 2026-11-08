package com.hereliesaz.illumera.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchKeyboardNavTest {

    // mic row, six letter rows, space/back/keyboard row, search row
    private val grid = KeyGrid(
        buildList {
            add(listOf(6))
            repeat(6) { add(List(6) { 1 }) }
            add(listOf(2, 2, 2))
            add(listOf(6))
        }
    )

    private val mic = grid.slots.first()
    private val search = grid.slots.last()
    private fun letter(row: Int, col: Int) = grid.slotAt(row, col)

    @Test
    fun `slots number the keys in reading order`() {
        assertEquals(0, mic.id)
        assertEquals(1 + 36 + 3 + 1, grid.slots.size)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), grid.slots.take(7).map { it.id })
    }

    @Test
    fun `left and right step along a row and stop at its ends`() {
        val a = letter(1, 0)
        val f = letter(1, 5)
        assertNull(grid.left(a))
        assertEquals(letter(1, 1), grid.right(a))
        assertEquals(letter(1, 4), grid.left(f))
        assertNull(grid.right(f))
    }

    @Test
    fun `a tap at a row end wraps to the opposite end`() {
        assertEquals(letter(2, 5), grid.wrapLeft(letter(2, 0)))
        assertEquals(letter(2, 0), grid.wrapRight(letter(2, 5)))
        // the wide-key row wraps to its own ends
        val space = letter(7, 0)
        val keyboard = letter(7, 5)
        assertEquals(keyboard, grid.wrapLeft(space))
        assertEquals(space, grid.wrapRight(keyboard))
    }

    @Test
    fun `up from the top row wraps to the bottom and down from the bottom to the top`() {
        assertEquals(search, grid.up(mic, 3))
        assertEquals(mic, grid.down(search, 3))
    }

    @Test
    fun `vertical moves keep the column across wide keys`() {
        val lastLetterRow = letter(6, 1)
        assertEquals(letter(7, 0), grid.down(lastLetterRow, 1)) // Space covers columns 0-1
        assertEquals(letter(7, 2), grid.down(letter(6, 3), 3))
        assertEquals(letter(7, 4), grid.down(letter(6, 5), 5))
        assertEquals(letter(6, 5), grid.up(letter(7, 4), 5))
    }

    @Test
    fun `rows must fill six columns`() {
        val error = runCatching { KeyGrid(listOf(listOf(2, 2))) }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class.java, error?.javaClass)
    }

    // ---- hold gesture ----

    @Test
    fun `a press released before the threshold is a tap`() {
        val g = HoldGesture(800)
        assertEquals(HoldResult.NONE, g.down(downTime = 1_000, eventTime = 1_000, repeatCount = 0))
        assertEquals(HoldResult.NONE, g.down(1_000, 1_550, 1))
        assertEquals(HoldResult.TAP, g.up(1_000))
    }

    @Test
    fun `a hold past the threshold fires once and is not a tap`() {
        val g = HoldGesture(800)
        g.down(1_000, 1_000, 0)
        assertEquals(HoldResult.NONE, g.down(1_000, 1_790, 3))
        assertEquals(HoldResult.LONG, g.down(1_000, 1_800, 4))
        assertEquals(HoldResult.NONE, g.down(1_000, 1_850, 5))
        assertEquals(HoldResult.NONE, g.up(1_000))
    }

    @Test
    fun `arriving at the end mid-hold neither wraps nor leaves until held there`() {
        val g = HoldGesture(800)
        // the key was already repeating when this button first saw it
        assertEquals(HoldResult.NONE, g.down(downTime = 500, eventTime = 2_000, repeatCount = 20))
        assertEquals(HoldResult.NONE, g.down(500, 2_700, 34))
        assertEquals(HoldResult.LONG, g.down(500, 2_800, 36))
        // and released early it would have been neither
        val h = HoldGesture(800)
        h.down(500, 2_000, 20)
        assertEquals(HoldResult.NONE, h.up(500))
    }

    @Test
    fun `each press is judged on its own`() {
        val g = HoldGesture(800)
        g.down(1_000, 1_000, 0)
        g.down(1_000, 1_900, 9) // long
        g.up(1_000)
        assertEquals(HoldResult.NONE, g.down(5_000, 5_000, 0))
        assertEquals(HoldResult.TAP, g.up(5_000))
    }
}
