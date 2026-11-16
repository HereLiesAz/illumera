package com.hereliesaz.illumera.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RowDemandPlannerTest {
    private fun facts(
        shown: Int = 0,
        waiting: Int = 0,
        exhausted: Boolean = false,
        loading: Boolean = false,
        attempts: Int = 0
    ) = RowFacts(shown, waiting, exhausted, loading, attempts)

    @Test fun focusTargetKeepsALittleAheadInSteps() {
        assertEquals(5, RowDemandPlanner.focusTarget(0))
        assertEquals(5, RowDemandPlanner.focusTarget(2))
        assertEquals(10, RowDemandPlanner.focusTarget(3))
        assertEquals(10, RowDemandPlanner.focusTarget(7))
        assertEquals(15, RowDemandPlanner.focusTarget(8))
    }

    @Test fun demandsAreOrderedByUrgency() {
        val demands = RowDemandPlanner.demands(
            focusedRowId = "f", focusedTarget = 10,
            visible = listOf("a", "b"), heroRowId = "h", lookahead = listOf("x", "y")
        )
        assertEquals(
            listOf("f" to 10, "a" to 5, "b" to 5, "h" to 10, "x" to 5, "y" to 5, "a" to 10, "b" to 10),
            demands
        )
    }

    @Test fun noFocusedRowMeansNoFocusedDemand() {
        val demands = RowDemandPlanner.demands(null, 0, listOf("a"), null, emptyList())
        assertEquals(listOf("a" to 5, "a" to 10), demands)
    }

    @Test fun aRowThatHasEnoughNeedsNothing() {
        assertFalse(RowDemandPlanner.needsWork(5, facts(shown = 5)))
    }

    @Test fun waitingTitlesCountEvenWhenNothingMoreCanBeFetched() {
        assertTrue(RowDemandPlanner.needsWork(5, facts(shown = 2, waiting = 3, exhausted = true, attempts = 3)))
    }

    @Test fun aRowIsNotFetchedWhileLoadingExhaustedOrOutOfAttempts() {
        assertFalse(RowDemandPlanner.needsWork(5, facts(loading = true)))
        assertFalse(RowDemandPlanner.needsWork(5, facts(exhausted = true)))
        assertFalse(RowDemandPlanner.needsWork(5, facts(attempts = RowDemandPlanner.MAX_ATTEMPTS)))
        assertTrue(RowDemandPlanner.needsWork(5, facts()))
    }

    @Test fun aRowThatIsNotThereNeedsNothing() {
        assertFalse(RowDemandPlanner.needsWork(5, null))
    }

    @Test fun nextIndexSkipsMetDemandsAndMissingRows() {
        val demands = listOf("gone" to 5, "full" to 5, "empty" to 5)
        val facts = mapOf("full" to facts(shown = 5), "empty" to facts())
        assertEquals(2, RowDemandPlanner.nextIndex(demands) { facts[it] })
    }

    @Test fun nextIndexIsNullWhenEverythingIsMet() {
        val demands = listOf("a" to 5)
        assertNull(RowDemandPlanner.nextIndex(demands) { facts(shown = 5) })
    }

    @Test fun theLeastUrgentLoadIsGivenUpForAMoreUrgentOne() {
        val demands = listOf("focused" to 5, "a" to 5, "b" to 5)
        assertEquals("b", RowDemandPlanner.victim(demands, 0, listOf("a", "b")))
    }

    @Test fun aRowNobodyIsAskingForIsGivenUpFirst() {
        val demands = listOf("focused" to 5, "a" to 5)
        assertEquals("stray", RowDemandPlanner.victim(demands, 0, listOf("a", "stray")))
    }

    @Test fun nothingMoreUrgentIsEverGivenUp() {
        val demands = listOf("focused" to 5, "a" to 5, "b" to 5)
        assertNull(RowDemandPlanner.victim(demands, 2, listOf("focused", "a")))
        assertNull(RowDemandPlanner.victim(demands, 1, emptyList()))
    }

    @Test fun reachingTheEndOfLoadedRowsAsksForTheNextUnloadedOnes() {
        // The screen reports no loaded rows below the viewer: the unloaded ones fill the gap.
        assertEquals(
            listOf("r7", "r8", "r9"),
            RowDemandPlanner.padLookahead(emptyList(), listOf("r5", "r6"), listOf("r7", "r8", "r9", "r10"), 3)
        )
    }

    @Test fun loadedRowsBelowComeFirstAndOnlyTheShortfallIsPadded() {
        assertEquals(
            listOf("r4", "r7", "r8"),
            RowDemandPlanner.padLookahead(listOf("r4"), listOf("r2", "r3"), listOf("r7", "r8", "r9"), 3)
        )
        // Three loaded rows below already: nothing is added, so loading follows the viewer.
        assertEquals(
            listOf("a", "b", "c"),
            RowDemandPlanner.padLookahead(listOf("a", "b", "c"), listOf("v"), listOf("r7"), 3)
        )
    }

    @Test fun padLookaheadSkipsRowsAlreadyOnScreenOrListed() {
        assertEquals(
            listOf("a", "r7"),
            RowDemandPlanner.padLookahead(listOf("a"), listOf("r7x"), listOf("a", "r7x", "r7"), 2)
        )
    }
}
