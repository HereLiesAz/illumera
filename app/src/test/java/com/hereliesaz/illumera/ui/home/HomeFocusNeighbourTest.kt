package com.hereliesaz.illumera.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import androidx.compose.ui.unit.dp
import com.hereliesaz.illumera.domain.HomeRow
import com.hereliesaz.illumera.data.model.stremio.MetaItem

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
    fun onlyActiveCinematicRailMayRenderPortraitCards() {
        assertEquals(true, useLandscapeMediaCard(true, false, false))
        assertEquals(true, useLandscapeMediaCard(true, false, true))
        assertEquals(false, useLandscapeMediaCard(true, true, true))
        assertEquals(false, useLandscapeMediaCard(false, false, false))
        assertEquals(true, useLandscapeMediaCard(false, true, true))
    }

    @Test
    fun expandedCinematicRowReservesSpaceOutsideThePoster() {
        assertEquals(116f, cinematicRailHeight(false).value, 0.001f)
        assertEquals(238f, cinematicRailHeight(true).value, 0.001f)
        assertEquals(0f, cinematicRailTrailingSpace(false).value, 0.001f)
        assertEquals(8f, cinematicRailTrailingSpace(true).value, 0.001f)
    }

    @Test
    fun heroVideoCoversWideViewportWithVerticallyCenteredOverflow() {
        val (width, height) = cinematicVideoCoverSize(1920.dp, 420.dp)
        assertEquals(1920f, width.value, 0.01f)
        assertEquals(1080f, height.value, 0.01f)
        assertEquals(330f, (height.value - 420f) / 2f, 0.01f)
    }

    @Test
    fun inlineVideoAlsoCoversItsViewportRatherThanLetterboxing() {
        val (portraitWidth, portraitHeight) = cinematicVideoCoverSize(280.dp, 210.dp)
        assertEquals(373.3333f, portraitWidth.value, 0.02f)
        assertEquals(210f, portraitHeight.value, 0.001f)
        val (wideWidth, wideHeight) = cinematicVideoCoverSize(373.3333f.dp, 210.dp)
        assertEquals(373.3333f, wideWidth.value, 0.02f)
        assertEquals(210f, wideHeight.value, 0.02f)
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
    fun heroReturnSkipsARevealedButNowEmptyGenre() {
        val formerAction = com.hereliesaz.illumera.domain.CategoryRow(
            id = "action", title = "Action", order = 0, items = emptyList()
        )
        val populatedDrama = com.hereliesaz.illumera.domain.CategoryRow(
            id = "drama", title = "Drama", order = 1,
            items = listOf(MetaItem(id = "tt111", type = "movie", name = "Drama"))
        )
        val rows: List<com.hereliesaz.illumera.domain.HomeRowItem> =
            listOf(formerAction, populatedDrama)

        assertEquals(1, firstFocusableCinematicRowIndex(rows.withIndex().toList()))
    }

    @Test
    fun heroReturnUsesFocusableHubAndPreservesOriginalRowIndices() {
        val empty = com.hereliesaz.illumera.domain.CategoryRow(
            id = "empty", title = "Empty", order = 0, items = emptyList()
        )
        val hub = com.hereliesaz.illumera.domain.HubGroupRow(
            id = "services", title = "Services", order = 1,
            items = listOf(com.hereliesaz.illumera.domain.HubItem(
                id = "stream", title = "Streaming", categoryId = "category"
            ))
        )
        val rows: List<com.hereliesaz.illumera.domain.HomeRowItem> = listOf(empty, hub)

        assertEquals(1, firstFocusableCinematicRowIndex(rows.withIndex().toList()))
        assertNull(firstFocusableCinematicRowIndex(listOf(empty).withIndex().toList()))
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
    fun authoritativeGenreTransferDoesNotCloneDestinationIntoOtherRows() {
        val action = MetaItem(id = "tt0000111", type = "movie", name = "Action Film")
        val drama = MetaItem(id = "tt0000222", type = "movie", name = "Drama Film")
        val comedy = MetaItem(id = "tt0000333", type = "movie", name = "Comedy Film")
        val rows = listOf(
            HomeRow(configId = "catalog-aggregate:movie:Action", title = "Action", items = emptyList()),
            HomeRow(configId = "catalog-aggregate:movie:Drama", title = "Drama", items = listOf(drama, action)),
            HomeRow(configId = "catalog-aggregate:movie:Comedy", title = "Comedy", items = listOf(comedy))
        )

        val updated = reconcileGenreRows(
            rows, "catalog-aggregate:movie:Action", listOf(action), authoritative = true
        )

        assertEquals(listOf("tt0000111"), updated[0].items.map { it.id })
        assertEquals(listOf("tt0000222"), updated[1].items.map { it.id })
        assertEquals(listOf("tt0000333"), updated[2].items.map { it.id })
    }

    @Test
    fun routingSameCanonicalTitleTwiceIsIdempotent() {
        val title = MetaItem(id = "tt0000111", type = "series", name = "Series")
        val rows = listOf(
            HomeRow("catalog-aggregate:series:Drama", "Drama", listOf(title)),
            HomeRow("catalog-aggregate:series:Comedy", "Comedy", emptyList())
        )
        val updated = reconcileGenreRows(
            rows, "catalog-aggregate:series:Drama", listOf(title), authoritative = true
        )

        assertEquals(rows, updated)
    }

    @Test
    fun noncanonicalDiscoveryNeverDeletesOtherRows() {
        val first = MetaItem(id = "tt0000111", type = "movie", name = "First")
        val second = MetaItem(id = "tt0000222", type = "movie", name = "Second")
        val rows = listOf(
            HomeRow("catalog-aggregate:movie:Drama", "Drama", listOf(first)),
            HomeRow("catalog-aggregate:movie:Comedy", "Comedy", emptyList())
        )
        val updated = reconcileGenreRows(
            rows, "catalog-aggregate:movie:Comedy", listOf(second), authoritative = false
        )

        assertEquals(listOf(first), updated[0].items)
        assertEquals(listOf(second), updated[1].items)
    }

    @Test
    fun nothingComposedInTheRowGivesNull() {
        assertNull(resolveHomeFocusNeighbour("3_tt3_2", listOf("4_tt1_0", "hero")))
        assertNull(resolveHomeFocusNeighbour("hero", listOf("3_tt1_0")))
    }
}
