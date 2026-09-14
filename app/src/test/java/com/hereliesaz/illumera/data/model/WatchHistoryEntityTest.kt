package com.hereliesaz.illumera.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchHistoryEntityTest {

    @Test
    fun progressIsZeroWhenDurationIsZero() {
        assertEquals(0f, history(position = 500L, duration = 0L).progress(), 0f)
    }

    @Test
    fun progressReturnsPositionOverDurationWithoutClamping() {
        assertEquals(0.25f, history(position = 250L, duration = 1_000L).progress(), 0.0001f)
        assertEquals(1.5f, history(position = 1_500L, duration = 1_000L).progress(), 0.0001f)
    }

    private fun history(position: Long, duration: Long) = WatchHistoryEntity(
        id = "id",
        title = "Title",
        poster = null,
        position = position,
        duration = duration,
        lastWatched = 1L,
        type = "movie"
    )
}
