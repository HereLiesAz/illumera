package com.hereliesaz.illumera.ui.search

/**
 * D-pad rules for the Search screen's on-screen keyboard, kept free of Compose so they can be
 * tested.
 *
 * - Left/Right step along a row. At the end of a row a tap wraps to the other end; holding for
 *   [SEARCH_LONG_PRESS_MS] leaves the keyboard instead (Left to the rail, Right to the content).
 * - Up/Down move between rows and wrap from the top row to the bottom one and back. Holding
 *   does nothing more than keep wrapping.
 */

/** How long Left/Right must be held at a row's end to leave the keyboard ("slightly under a second"). */
internal const val SEARCH_LONG_PRESS_MS = 800L

/** The keyboard is six columns wide; a key spans one or more of them. */
internal const val KEYBOARD_COLUMNS = 6

/** One key: where it sits in the grid. [id] is its position in reading order. */
internal data class KeySlot(val id: Int, val row: Int, val startCol: Int, val span: Int)

/** The keyboard's rows, each a list of key spans that add up to [KEYBOARD_COLUMNS]. */
internal class KeyGrid(rowSpans: List<List<Int>>) {
    val rows: List<List<KeySlot>>
    val slots: List<KeySlot>

    init {
        var id = 0
        rows = rowSpans.mapIndexed { row, spans ->
            require(spans.sum() == KEYBOARD_COLUMNS) { "row $row spans ${spans.sum()} of $KEYBOARD_COLUMNS columns" }
            var col = 0
            spans.map { span -> KeySlot(id++, row, col, span).also { col += span } }
        }
        slots = rows.flatten()
    }

    /** The key covering [col] in [row]. */
    fun slotAt(row: Int, col: Int): KeySlot =
        rows[row].first { col in it.startCol until it.startCol + it.span }

    /** The key to the left on the same row, or null at the row's left end. */
    fun left(slot: KeySlot): KeySlot? = rows[slot.row].getOrNull(rows[slot.row].indexOf(slot) - 1)

    /** The key to the right on the same row, or null at the row's right end. */
    fun right(slot: KeySlot): KeySlot? = rows[slot.row].getOrNull(rows[slot.row].indexOf(slot) + 1)

    /** Where a tap of Left at the row's left end lands: the row's last key. */
    fun wrapLeft(slot: KeySlot): KeySlot = rows[slot.row].last()

    /** Where a tap of Right at the row's right end lands: the row's first key. */
    fun wrapRight(slot: KeySlot): KeySlot = rows[slot.row].first()

    /** The key above in column [col]; the top row wraps to the bottom one. */
    fun up(slot: KeySlot, col: Int): KeySlot = slotAt((slot.row - 1 + rows.size) % rows.size, col)

    /** The key below in column [col]; the bottom row wraps to the top one. */
    fun down(slot: KeySlot, col: Int): KeySlot = slotAt((slot.row + 1) % rows.size, col)
}

internal enum class HoldResult { NONE, LONG, TAP }

/**
 * Tells a tap from a hold for one direction key on one key button, from the key events it sees.
 *
 * A press counts only from the moment this button first sees it: holding Left to run along a row
 * and arriving at the end must not leave the keyboard at once, nor wrap when the key is released.
 * Events of one press share a [downTime]; a repeat has `repeatCount > 0`.
 */
internal class HoldGesture(private val longPressMs: Long = SEARCH_LONG_PRESS_MS) {
    private var sequence = Long.MIN_VALUE
    private var firstSeenAt = 0L
    private var startedHere = false
    private var fired = false

    /** A key-down (or repeat). [HoldResult.LONG] once, when the hold reaches the threshold. */
    fun down(downTime: Long, eventTime: Long, repeatCount: Int): HoldResult {
        if (downTime != sequence) {
            sequence = downTime
            firstSeenAt = eventTime
            startedHere = repeatCount == 0
            fired = false
        }
        if (!fired && eventTime - firstSeenAt >= longPressMs) {
            fired = true
            return HoldResult.LONG
        }
        return HoldResult.NONE
    }

    /** The key-up. [HoldResult.TAP] when the press began here and never became a hold. */
    fun up(downTime: Long): HoldResult {
        val tap = downTime == sequence && startedHere && !fired
        sequence = Long.MIN_VALUE
        return if (tap) HoldResult.TAP else HoldResult.NONE
    }
}
