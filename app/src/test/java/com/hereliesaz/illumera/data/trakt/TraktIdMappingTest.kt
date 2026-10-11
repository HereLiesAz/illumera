package com.hereliesaz.illumera.data.trakt

import com.hereliesaz.illumera.data.model.WatchHistoryEntity
import com.hereliesaz.illumera.data.model.trakt.TraktIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktIdMappingTest {

    @Test
    fun mapsImdbAndTmdbIds() {
        assertEquals(TraktIds(imdb = "tt0111161"), traktIdsFor("tt0111161"))
        assertEquals(TraktIds(tmdb = 278), traktIdsFor("tmdb:278"))
    }

    @Test
    fun episodeIdsMapToTheirShow() {
        assertEquals(TraktIds(imdb = "tt0903747"), traktIdsFor("tt0903747:1:2"))
        assertEquals(TraktIds(tmdb = 1396), traktIdsFor("tmdb:1396:1:2:0"))
    }

    @Test
    fun unmappableIdsReturnNull() {
        assertNull(traktIdsFor("kitsu:123"))
        assertNull(traktIdsFor("tmdb:abc"))
        assertNull(traktIdsFor("ttabc"))
        assertNull(traktIdsFor(""))
    }

    @Test
    fun localIdPrefersImdbThenTmdb() {
        assertEquals("tt1", localIdFor(TraktIds(imdb = "tt1", tmdb = 5)))
        assertEquals("tmdb:5", localIdFor(TraktIds(tmdb = 5)))
        assertNull(localIdFor(TraktIds(slug = "x")))
        assertEquals(listOf("tt1", "tmdb:5"), localIdsFor(TraktIds(imdb = "tt1", tmdb = 5)))
    }

    @Test
    fun normalizePlaybackIdHandlesImdbIds() {
        assertEquals("tt1:1:3", normalizePlaybackId("tt1:1:3:0"))
        assertEquals("tt1:1:3", normalizePlaybackId("tt1:1:3"))
        assertEquals("tt1", normalizePlaybackId("tt1"))
    }

    @Test
    fun normalizePlaybackIdDoesNotTreatPrefixedEpisodeAsStreamIndexed() {
        assertEquals("tmdb:456:1:3", normalizePlaybackId("tmdb:456:1:3"))
        assertEquals("kitsu:789:1:3", normalizePlaybackId("kitsu:789:1:3"))
        assertEquals("tmdb:456:1:3", normalizePlaybackId("tmdb:456:1:3:0"))
        assertEquals("tmdb:456", normalizePlaybackId("tmdb:456"))
    }

    private fun row(id: String, watched: Boolean, lastWatched: Long, position: Long = 0L) =
        WatchHistoryEntity(
            id = id, title = id, poster = null, position = position, duration = 1_000L,
            lastWatched = lastWatched, type = "movie", watched = watched
        )

    private fun entry(vararg ids: String, lastWatched: Long = 100L) =
        TraktWatchedEntry(ids.toList(), row(ids.first(), watched = true, lastWatched = lastWatched).copy(scrobbled = true))

    @Test
    fun mergeInsertsTitlesMissingLocally() {
        val writes = mergeTraktWatchedHistory(emptyList(), listOf(entry("tt1")))
        assertEquals(listOf("tt1"), writes.map { it.id })
        assertTrue(writes.single().watched)
    }

    @Test
    fun mergeSkipsAlreadyWatchedAndNewerLocalProgress() {
        val local = listOf(
            row("tt1", watched = true, lastWatched = 10L),
            row("tt2", watched = false, lastWatched = 500L, position = 300L)
        )
        val writes = mergeTraktWatchedHistory(local, listOf(entry("tt1"), entry("tt2", lastWatched = 100L)))
        assertTrue(writes.isEmpty())
    }

    @Test
    fun mergeMarksOlderInProgressWatchedKeepingPosition() {
        val local = listOf(row("tt2", watched = false, lastWatched = 50L, position = 300L))
        val writes = mergeTraktWatchedHistory(local, listOf(entry("tt2", lastWatched = 100L)))
        val updated = writes.single()
        assertTrue(updated.watched)
        assertEquals(300L, updated.position)
        assertEquals(50L, updated.lastWatched)
    }

    @Test
    fun mergeMatchesTmdbAndStreamIndexVariants() {
        val local = listOf(
            row("tmdb:5", watched = true, lastWatched = 10L),
            row("tt9:1:2:0", watched = true, lastWatched = 10L)
        )
        val writes = mergeTraktWatchedHistory(
            local,
            listOf(entry("tt1", "tmdb:5"), entry("tt9:1:2"))
        )
        assertTrue(writes.isEmpty())
    }
}
