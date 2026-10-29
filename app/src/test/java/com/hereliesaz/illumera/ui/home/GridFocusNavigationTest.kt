package com.hereliesaz.illumera.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** D-pad routing expectations independent of focus-requester implementation details. */
class GridFocusNavigationTest {
    @Test
    fun firstPosterRowMovesUpToSortChipsWhenTheyExist() {
        assertEquals(GridHeaderUpTarget.SORT, gridFirstRowUpTarget(true))
    }

    @Test
    fun ordinaryUnsortableGridMovesUpToBackButton() {
        assertEquals(GridHeaderUpTarget.BACK, gridFirstRowUpTarget(false))
    }

    @Test
    fun gridImagePrefetchIsBoundedToThreePostersPerDirection() {
        assertEquals(3, GRID_POSTER_PREFETCH_RADIUS)
    }
}
