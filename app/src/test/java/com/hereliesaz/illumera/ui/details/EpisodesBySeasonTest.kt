package com.hereliesaz.illumera.ui.details

import com.hereliesaz.illumera.data.model.stremio.MetaVideo
import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodesBySeasonTest {

    @Test
    fun `repeated episodes are listed once so list keys stay unique`() {
        val first = MetaVideo(id = "tt10986410:1:1", title = "Pilot", season = 1, episode = 1)
        val repeat = first.copy(title = "Pilot (again)")
        val next = MetaVideo(id = "tt10986410:1:2", season = 1, episode = 2)

        val seasons = episodesBySeason(listOf(first, repeat, next))

        assertEquals(listOf(first, next), seasons[1])
    }

    @Test
    fun `specials are dropped and seasons come back in order`() {
        val videos = listOf(
            MetaVideo(id = "s2e1", season = 2, episode = 1),
            MetaVideo(id = "special", season = 0, episode = 1),
            MetaVideo(id = "s1e1", season = 1, episode = 1),
        )

        assertEquals(listOf(1, 2), episodesBySeason(videos).keys.toList())
    }
}
