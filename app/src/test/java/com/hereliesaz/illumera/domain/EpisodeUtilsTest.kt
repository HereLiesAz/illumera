package com.hereliesaz.illumera.domain

import com.hereliesaz.illumera.data.model.stremio.MetaVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeUtilsTest {

    @Test
    fun playbackIdAlwaysUsesSeriesSeasonAndEpisode() {
        val episode = MetaVideo(id = "addon-specific-id", season = 2, episode = 7)

        assertEquals("series123:2:7", episodePlaybackId("series123", episode))
    }

    @Test
    fun streamIdUsesAddonIdWhenPresentAndFallsBackWhenBlank() {
        assertEquals(
            "addon-id",
            episodeStreamId("series", MetaVideo(id = "addon-id", season = 3, episode = 4))
        )
        assertEquals(
            "series:3:4",
            episodeStreamId("series", MetaVideo(id = "   ", season = 3, episode = 4))
        )
    }

    @Test
    fun displayTitleUsesSafeDefaultsForInvalidSeasonAndEpisodeNumbers() {
        assertEquals(
            "S1:E1 - Pilot",
            episodeDisplayTitle(MetaVideo(title = "Pilot", season = 0, episode = -1))
        )
        assertEquals(
            "S2:E9 - Finale",
            episodeDisplayTitle(MetaVideo(title = "Finale", season = 2, episode = 9))
        )
    }

    @Test
    fun hasAiredTreatsMissingAndPastDatesAsAired() {
        assertTrue(MetaVideo(released = null).hasAired())
        assertTrue(MetaVideo(released = "2000-01-01T12:00:00.000Z").hasAired())
    }

    @Test
    fun hasAiredRejectsFarFutureDates() {
        assertFalse(MetaVideo(released = "2999-01-01T00:00:00.000Z").hasAired())
    }

    @Test
    fun findNextEpisodeSortsInputAndSkipsSpecials() {
        val episodes = listOf(
            MetaVideo(id = "s2e1", title = "S2E1", season = 2, episode = 1, released = "2000-01-01"),
            MetaVideo(id = "special", title = "Special", season = 0, episode = 1, released = "2000-01-01"),
            MetaVideo(id = "s1e2", title = "S1E2", season = 1, episode = 2, released = "2000-01-01"),
            MetaVideo(id = "s1e1", title = "S1E1", season = 1, episode = 1, released = "2000-01-01"),
            MetaVideo(id = "invalid", title = "Invalid", season = 1, episode = 0, released = "2000-01-01")
        )

        val next = findNextEpisode("show", "show:1:1", episodes)

        assertEquals("s1e2", next?.id)
    }

    @Test
    fun findNextEpisodeSupportsLegacyIdsEndingInSeasonAndEpisode() {
        val episodes = listOf(
            MetaVideo(id = "one", season = 1, episode = 1, released = "2000-01-01"),
            MetaVideo(id = "two", season = 1, episode = 2, released = "2000-01-01")
        )

        val next = findNextEpisode("show", "legacy:anything:1:1", episodes)

        assertEquals("two", next?.id)
    }

    @Test
    fun findNextEpisodeReturnsNullForUnknownCurrentOrLastEpisode() {
        val episodes = listOf(
            MetaVideo(id = "one", season = 1, episode = 1, released = "2000-01-01"),
            MetaVideo(id = "two", season = 1, episode = 2, released = "2000-01-01")
        )

        assertNull(findNextEpisode("show", "show:9:9", episodes))
        assertNull(findNextEpisode("show", "show:1:2", episodes))
        assertNull(findNextEpisode("show", "show:1:1", emptyList()))
    }

    @Test
    fun findNextEpisodeNeverAutoplaysUnairedEpisode() {
        val episodes = listOf(
            MetaVideo(id = "one", season = 1, episode = 1, released = "2000-01-01"),
            MetaVideo(id = "two", season = 1, episode = 2, released = "2999-01-01")
        )

        assertNull(findNextEpisode("show", "show:1:1", episodes))
    }
}
