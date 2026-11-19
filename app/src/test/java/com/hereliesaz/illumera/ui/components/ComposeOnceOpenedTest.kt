package com.hereliesaz.illumera.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cards must not set up their long-press menu (ViewModel lookup, state, effects) until it is
 * opened; once opened it must stay composed so its soundtrack dialog survives the menu closing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeOnceOpenedTest {
    // v2 rules queue coroutines (StandardTestDispatcher); these tests rely on immediate dispatch.
    @Suppress("DEPRECATION")
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun contentIsNotComposedUntilOpened() {
        var composed = 0
        compose.setContent {
            ComposeOnceOpened(opened = false, key = "movie:tt1") { composed++ }
        }
        compose.waitForIdle()
        assertEquals(0, composed)
    }

    @Test
    fun contentIsComposedWhenOpenedAndStaysAfterClosing() {
        var opened by mutableStateOf(false)
        var enters = 0
        var disposals = 0
        compose.setContent {
            ComposeOnceOpened(opened = opened, key = "movie:tt1") {
                androidx.compose.runtime.DisposableEffect(Unit) {
                    enters++
                    onDispose { disposals++ }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(0, enters)

        opened = true
        compose.waitForIdle()
        assertEquals(1, enters)

        opened = false
        compose.waitForIdle()
        assertEquals("closing must not dispose the menu", 0, disposals)
        assertEquals(1, enters)
    }

    @Test
    fun anotherTitleInTheSameSlotStartsClosedAgain() {
        var key by mutableStateOf("movie:tt1")
        var composed = 0
        compose.setContent {
            ComposeOnceOpened(opened = key == "movie:tt1", key = key) { composed++ }
        }
        compose.waitForIdle()
        assertEquals(true, composed > 0)
        val before = composed

        key = "movie:tt2" // new key, not opened
        compose.waitForIdle()
        assertEquals(before, composed)
    }

    @Test
    fun soundtrackIdIsOnlyAnImdbId() {
        val movie = MediaActionTarget(id = "tt0111161", type = "movie", title = "A")
        assertEquals("tt0111161", soundtrackImdbIdOf(movie))
        assertNull(soundtrackImdbIdOf(movie.copy(id = "tmdb:123")))
        assertNull(soundtrackImdbIdOf(movie.copy(id = "tt")))
        assertNull(soundtrackImdbIdOf(movie.copy(id = "xtt12")))
    }

    @Test
    fun episodeSoundtrackUsesItsSeriesId() {
        val episode = MediaActionTarget(
            id = "tt999:1:2", type = "episode", title = "E",
            parentSeriesId = "tt0903747:1:2", season = 1, episode = 2
        )
        assertEquals("tt0903747", soundtrackImdbIdOf(episode))
        assertNull(soundtrackImdbIdOf(episode.copy(parentSeriesId = null)))
    }
}
