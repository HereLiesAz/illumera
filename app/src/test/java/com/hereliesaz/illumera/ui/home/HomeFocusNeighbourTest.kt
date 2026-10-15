package com.hereliesaz.illumera.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import androidx.compose.ui.unit.dp

/** Where Home's focus goes when a refresh removes or moves the focused card. */
class HomeFocusNeighbourTest {

    @Test
    fun theSameTitleIsFoundAtItsNewPosition() {
        // tt1 was removed, so tt3 shifted from index 2 to 1 and was re-keyed.
        val keys = listOf("3_tt2_0", "3_tt3_1", "3_tt4_2", "4_tt3_2")
        assertEquals("3_tt3_1", resolveHomeFocusNeighbour("3_tt3_2", keys))
    }

    @Test
    fun aRemovedTitleGivesWayToItsRowsNearestCard() {
        val keys = listOf("3_tt1_0", "3_tt2_1", "3_tt4_2", "5_tt9_2")
        assertEquals("3_tt4_2", resolveHomeFocusNeighbour("3_tt3_2", keys))
    }

    @Test
    fun continueWatchingIdsWithUnderscoresAndHubsResolve() {
        assertEquals("-1_kitsu_12_1", resolveHomeFocusNeighbour("-1_kitsu_12_3", listOf("-1_a_0", "-1_kitsu_12_1")))
        assertEquals("hub_g1_2", resolveHomeFocusNeighbour("hub_g1_3", listOf("hub_g1_2", "hub_g2_3", "1_x_3")))
    }

    @Test
    fun youtubeVideoIdAfterAnAddonSearchPageRemainsSelectable() {
        val ranked = prioritizedYouTubePreviewCandidates(
            listOf(
                "https://www.youtube.com/results?search_query=trailer",
                "dQw4w9WgXcQ",
                "https://www.youtube.com/watch?v=QH2-TGUlwu4"
            )
        )
        assertEquals(listOf("dQw4w9WgXcQ", "https://www.youtube.com/watch?v=QH2-TGUlwu4"), ranked)
    }

    @Test
    fun trailerCandidatesAreBoundedAndDirectIdsTakePriority() {
        val ranked = prioritizedYouTubePreviewCandidates(
            listOf(
                "https://youtu.be/ABCDEFGHIJK",
                "ABCDEFGHIJK",
                "MNOPQRSTUVW",
                "https://youtube.com/results?search_query=bad"
            )
        )
        assertEquals(listOf("ABCDEFGHIJK", "MNOPQRSTUVW"), ranked)
    }

    @Test
    fun onlyCurrentlyFocusedCinematicRowUsesPortraitPosters() {
        assertEquals(true, isCinematicRowActive("movies:action", "movies:action", "2_tt42_0", "2_"))
        assertEquals(false, isCinematicRowActive("movies:action", "movies:drama", "3_tt31_1", "3_"))
        assertEquals(false, isCinematicRowActive("__hero__", "movies:action", "2_tt42_0", "2_"))
        assertEquals(false, isCinematicRowActive("__hub__", "history", "-1_tt9_0", "-1_"))
    }

    @Test
    fun restoredFocusActivatesOnlyItsMatchingRowUntilNavigationBegins() {
        assertEquals(true, isCinematicRowActive(null, "history", "-1_tt9_0", "-1_"))
        assertEquals(false, isCinematicRowActive(null, "drama", "-1_tt9_0", "0_"))
        assertEquals(true, isCinematicRowActive(null, "drama", "0_tt31_2", "0_"))
        assertEquals(false, isCinematicRowActive(null, "drama", "10_tt31_2", "1_"))
    }

    @Test
    fun disabledOrOffscreenPreviewNeverResolvesTrailer() {
        assertEquals(false, shouldResolveCinematicPreview(false, true))
        assertEquals(false, shouldResolveCinematicPreview(true, false))
        assertEquals(false, shouldResolveCinematicPreview(false, false))
        assertEquals(true, shouldResolveCinematicPreview(true, true))
    }

    @Test
    fun cinematicPreviewMatchesWideVideoWhileRespectingNarrowViewport() {
        // Expected sizes are independent measurements, not calls to the production formula.
        assertEquals(373.3333f, cinematicInlineWidth(1920.dp, 120.dp).value, 0.02f)
        assertEquals(277f, cinematicInlineWidth(393.dp, 96.dp).value, 0.001f)
        assertEquals(140f, cinematicInlineWidth(250.dp, 96.dp).value, 0.001f)
    }

    @Test
    fun verticalFocusFollowsExpandedPreviewLeftEdgeNotItsCenter() {
        // A 373dp-wide active preview starts at 120dp. Center-based
        // focus would choose the second 190dp-wide landscape card.
        assertEquals(
            "first",
            closestCinematicCardLeft(
                120f,
                listOf("first" to 120f, "second" to 330f, "third" to 540f)
            )
        )
        assertEquals(
            "second",
            closestCinematicCardLeft(
                330f,
                listOf("first" to 120f, "second" to 330f, "third" to 540f)
            )
        )
    }

    @Test
    fun verticalFocusDoesNotSkipHubRowsOrHiddenGenreIndices() {
        // The visible order contains a Hub at index 3 and genre gaps where
        // unpopulated rails were omitted. Navigation must respect that order.
        val visibleRows = listOf(-1, 0, 3, 9)
        assertEquals(3, adjacentCinematicRowIndex(visibleRows, 0, down = true))
        assertEquals(3, adjacentCinematicRowIndex(visibleRows, 9, down = false))
        assertEquals(0, adjacentCinematicRowIndex(visibleRows, 3, down = false))
        assertEquals(null, adjacentCinematicRowIndex(visibleRows, -1, down = false))
        assertEquals(null, adjacentCinematicRowIndex(visibleRows, 9, down = true))
    }

    @Test
    fun verticalFocusUsesNearestLeftEdgeAcrossDifferentCardWidths() {
        // Source and destination can have different horizontal scroll offsets
        // and independently sized cards; never match by their list indices.
        assertEquals(
            "aligned",
            closestCinematicCardLeft(
                405f,
                listOf("left" to 181f, "aligned" to 410f, "right" to 612f)
            )
        )
        assertEquals(
            "left",
            closestCinematicCardLeft(120f, listOf("right" to 330f, "left" to 120f))
        )
        assertEquals(null, closestCinematicCardLeft(100f, emptyList()))
    }

    @Test
    fun nothingComposedInTheRowGivesNull() {
        assertNull(resolveHomeFocusNeighbour("3_tt3_2", listOf("4_tt1_0", "hero")))
        assertNull(resolveHomeFocusNeighbour("hero", listOf("3_tt1_0")))
    }
}
