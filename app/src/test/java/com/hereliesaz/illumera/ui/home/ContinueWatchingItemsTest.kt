package com.hereliesaz.illumera.ui.home

import com.hereliesaz.illumera.data.model.SeriesNextUpEntity
import com.hereliesaz.illumera.data.model.WatchHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingItemsTest {

    @Test
    fun episodesOfOneSeriesCollapseIntoOneCardAtTheLatestEpisode() {
        val items = buildContinueWatchingItems(listOf(
            entry("tt1:1:2", "series", lastWatched = 300, position = 30, seriesId = "tt1"),
            entry("tt1:1:1", "series", lastWatched = 100, position = 10, seriesId = "tt1"),
        ))
        assertEquals(listOf("tt1"), items.map { it.id })
        assertEquals("series", items.single().type)
        assertEquals(0.3f, items.single().progress, 0.0001f)
    }

    @Test
    fun episodesSavedAsTvOrEpisodeAreDedupedIntoTheirSeries() {
        val items = buildContinueWatchingItems(listOf(
            entry("tt1:1:3", "tv", lastWatched = 300, seriesId = "tt1"),
            entry("tt1:1:2", "episode", lastWatched = 200, seriesId = "tt1"),
            entry("tt1:1:1", "series", lastWatched = 100, seriesId = "tt1"),
        ))
        assertEquals(listOf("tt1" to "series"), items.map { it.id to it.type })
    }

    @Test
    fun seriesWithoutSeasonNumbersUseTheirSavedSeriesId() {
        val items = buildContinueWatchingItems(listOf(
            entry("kitsu:100:5", "series", lastWatched = 200, seriesId = "kitsu:100"),
            entry("kitsu:200:3", "series", lastWatched = 100, seriesId = "kitsu:200"),
        ))
        assertEquals(listOf("kitsu:100", "kitsu:200"), items.map { it.id })
    }

    @Test
    fun onlyMoviesAndSeriesAreShown() {
        val items = buildContinueWatchingItems(listOf(
            entry("tt9", "movie", lastWatched = 500),
            entry("channel-1", "channel", lastWatched = 400),
            entry("live-1", "tv", lastWatched = 300, seriesId = "live-1"),
            entry("other-1", "other", lastWatched = 200),
        ))
        assertEquals(listOf("tt9"), items.map { it.id })
    }

    @Test
    fun watchedEntriesAreLeftOutAndCardsSortByRecency() {
        val items = buildContinueWatchingItems(listOf(
            entry("tt9", "movie", lastWatched = 100),
            entry("tt8", "movie", lastWatched = 900, watched = true),
            entry("tt1:2:1", "series", lastWatched = 500, seriesId = "tt1"),
        ))
        assertEquals(listOf("tt1", "tt9"), items.map { it.id })
    }

    @Test
    fun nextUpNeverDuplicatesAnInProgressSeriesOrItself() {
        val items = buildContinueWatchingItems(
            history = listOf(entry("tt1:1:1", "series", lastWatched = 100, seriesId = "tt1")),
            seriesNextUp = listOf(nextUp("tt1", 500), nextUp("tt2", 400), nextUp("tt2", 300)),
        )
        assertEquals(listOf("tt2", "tt1"), items.map { it.id })
    }

    @Test
    fun unplayedBookmarkedMoviesAndSeriesNeverEnterContinueWatching() {
        val bogusMovie = entry("tt7654321", "movie", lastWatched = 900L, position = 0)
            .copy(duration = 0)
        val bogusSeries = entry("tt1234567:1:1", "series", lastWatched = 800L,
            position = 0, seriesId = "tt1234567").copy(duration = 0)
        val barelyStarted = entry("tt1111111", "movie", lastWatched = 700L,
            position = 1).copy(position = 999L)
        val realMovie = entry("tt9999999", "movie", lastWatched = 500L, position = 12)

        assertEquals(
            listOf("tt9999999"),
            buildContinueWatchingItems(listOf(bogusMovie, bogusSeries, barelyStarted, realMovie))
                .map { it.id }
        )
    }

    private fun entry(
        id: String,
        type: String,
        lastWatched: Long,
        position: Long = 10,
        seriesId: String? = null,
        watched: Boolean = false,
    ) = WatchHistoryEntity(
        id = id, title = id, poster = null, seriesId = seriesId,
        position = position * 1_000L, duration = 100_000L, lastWatched = lastWatched, type = type, watched = watched,
    )

    private fun nextUp(seriesId: String, updatedAt: Long) = SeriesNextUpEntity(
        profileId = 1, seriesId = seriesId, title = seriesId, poster = null,
        nextSeason = 1, nextEpisode = 2, nextEpisodeTitle = null, updatedAt = updatedAt,
    )
}
