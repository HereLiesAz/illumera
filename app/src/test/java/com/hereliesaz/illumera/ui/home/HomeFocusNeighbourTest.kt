package com.hereliesaz.illumera.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
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
    fun authoritativeGenreTransferDoesNotCloneDestinationIntoOtherRows() {
        val action = MetaItem(id = "tt0000111", type = "movie", name = "Action Film")
        val drama = MetaItem(id = "tt0000222", type = "movie", name = "Drama Film")
        val comedy = MetaItem(id = "tt0000333", type = "movie", name = "Comedy Film")
        val rows = listOf(
            HomeRow(configId = "catalog-aggregate:movie:Action", title = "Action", items = emptyList()),
            HomeRow(configId = "catalog-aggregate:movie:Drama", title = "Drama", items = listOf(drama, action)),
            HomeRow(configId = "catalog-aggregate:movie:Comedy", title = "Comedy", items = listOf(comedy))
        )

        val updated = mergeClassifiedGenreRows(
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
        val updated = mergeClassifiedGenreRows(
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
        val updated = mergeClassifiedGenreRows(
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
