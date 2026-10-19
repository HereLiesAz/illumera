package com.hereliesaz.illumera.ui.home

/** What the loader knows about one row. */
internal data class RowFacts(
    /** Titles the row shows. */
    val shown: Int,
    /** Titles already fetched but not shown. */
    val waiting: Int,
    /** Its catalog has nothing more. */
    val exhausted: Boolean,
    /** A load for it is running. */
    val loading: Boolean,
    /** Loads started for the current target without reaching it. */
    val attempts: Int
)

/**
 * The decisions behind demand-driven loading, with no state of their own: what is wanted in what
 * order, whether a row still needs work, and which running load to give up when something more
 * urgent is waiting. The ViewModel supplies the facts and carries the decisions out.
 */
internal object RowDemandPlanner {
    const val REVEAL_STEP = 5
    const val HERO_REVEAL = 10
    const val FOCUS_LOOKAHEAD = 3
    const val MAX_ATTEMPTS = 3

    /** Titles to keep shown for a viewer on card [itemIndex]: a little past it, in steps. */
    fun focusTarget(itemIndex: Int): Int =
        maxOf(REVEAL_STEP, ((itemIndex + FOCUS_LOOKAHEAD + REVEAL_STEP - 1) / REVEAL_STEP) * REVEAL_STEP)

    /**
     * What is wanted, most urgent first, as (row, titles to show): the row the viewer is on, the
     * first five of every visible row, the row behind the Hero, the first five of the rows just
     * below, then the next five of the visible rows.
     */
    fun demands(
        focusedRowId: String?,
        focusedTarget: Int,
        visible: List<String>,
        heroRowId: String?,
        lookahead: List<String>
    ): List<Pair<String, Int>> = buildList {
        focusedRowId?.let { add(it to focusedTarget) }
        visible.forEach { add(it to REVEAL_STEP) }
        heroRowId?.let { add(it to HERO_REVEAL) }
        lookahead.forEach { add(it to REVEAL_STEP) }
        visible.forEach { add(it to REVEAL_STEP * 2) }
    }

    /** Whether a row with [facts] still needs work to show [target] titles. */
    fun needsWork(target: Int, facts: RowFacts?): Boolean {
        if (facts == null) return false
        if (facts.shown >= target) return false
        if (facts.waiting > 0) return true
        return !facts.exhausted && !facts.loading && facts.attempts < MAX_ATTEMPTS
    }

    /** Position in [demands] of the first one that needs work, or null when all are met. */
    fun nextIndex(demands: List<Pair<String, Int>>, facts: (String) -> RowFacts?): Int? =
        demands.indexOfFirst { (rowId, target) -> needsWork(target, facts(rowId)) }
            .takeIf { it >= 0 }

    /**
     * The running load to give up so the demand at [demandIndex] can start: the one for the row
     * that matters least, and only if it matters less than that demand (rows nobody is asking
     * for matter least).
     */
    fun victim(demands: List<Pair<String, Int>>, demandIndex: Int, loading: Collection<String>): String? {
        fun rank(rowId: String) =
            demands.indexOfFirst { it.first == rowId }.let { if (it < 0) Int.MAX_VALUE else it }
        return loading.maxByOrNull(::rank)?.takeIf { rank(it) > demandIndex }
    }
}
