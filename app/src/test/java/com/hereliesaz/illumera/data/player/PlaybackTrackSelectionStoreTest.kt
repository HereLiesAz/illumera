package com.hereliesaz.illumera.data.player

import android.content.Context
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlaybackTrackSelectionStoreTest {
    private lateinit var context: Context
    private lateinit var profileManager: ProfileConfigurationManager
    private lateinit var store: PlaybackTrackSelectionStore
    private var activeProfileId: Int? = 1

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        profileManager = mockk()
        every { profileManager.getLastActiveProfileId() } answers { activeProfileId }
        store = PlaybackTrackSelectionStore(context, profileManager)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun updateAndReadSelectionRoundTripsAllFields() {
        store.updateSelection(
            playbackId = "movie:1",
            audioTrackId = " audio-en ",
            subtitleTrackId = " sub-es ",
            subtitleDelayMs = 750L,
            updateAudio = true,
            updateSubtitle = true,
            updateSubtitleDelay = true
        )

        assertEquals(
            PlaybackTrackSelectionStore.Selection("audio-en", "sub-es", 750L),
            store.getSelection(" movie:1 ")
        )
    }

    @Test
    fun updateFlagsLeaveUnselectedFieldsUntouched() {
        store.updateSelection(
            playbackId = "movie",
            audioTrackId = "a1",
            subtitleTrackId = "s1",
            subtitleDelayMs = 200L,
            updateAudio = true,
            updateSubtitle = true,
            updateSubtitleDelay = true
        )
        store.updateSelection(
            playbackId = "movie",
            audioTrackId = "a2",
            subtitleTrackId = "s2",
            subtitleDelayMs = 900L,
            updateAudio = true,
            updateSubtitle = false,
            updateSubtitleDelay = false
        )

        assertEquals(
            PlaybackTrackSelectionStore.Selection("a2", "s1", 200L),
            store.getSelection("movie")
        )
    }

    @Test
    fun blankTrackIdsAndZeroDelayRemoveStoredValues() {
        store.updateSelection(
            playbackId = "movie",
            audioTrackId = "audio",
            subtitleTrackId = "subtitle",
            subtitleDelayMs = 500L,
            updateAudio = true,
            updateSubtitle = true,
            updateSubtitleDelay = true
        )
        store.updateSelection(
            playbackId = "movie",
            audioTrackId = "   ",
            subtitleTrackId = null,
            subtitleDelayMs = 0L,
            updateAudio = true,
            updateSubtitle = true,
            updateSubtitleDelay = true
        )

        assertNull(store.getSelection("movie"))
    }

    @Test
    fun blankPlaybackIdIsIgnored() {
        store.updateSelection(
            playbackId = "   ",
            audioTrackId = "audio",
            subtitleTrackId = "subtitle",
            updateAudio = true,
            updateSubtitle = true
        )

        assertNull(store.getSelection("   "))
    }

    @Test
    fun clearSelectionRemovesAllFields() {
        store.updateSelection(
            playbackId = "movie",
            audioTrackId = "audio",
            subtitleTrackId = "subtitle",
            subtitleDelayMs = -250L,
            updateAudio = true,
            updateSubtitle = true,
            updateSubtitleDelay = true
        )

        store.clearSelection("movie")

        assertNull(store.getSelection("movie"))
    }

    @Test
    fun selectionsAreIsolatedByProfile() {
        activeProfileId = 1
        store.updateSelection(
            playbackId = "movie",
            audioTrackId = "profile-one",
            subtitleTrackId = null,
            updateAudio = true,
            updateSubtitle = false
        )

        activeProfileId = 2
        assertNull(store.getSelection("movie"))
        store.updateSelection(
            playbackId = "movie",
            audioTrackId = "profile-two",
            subtitleTrackId = null,
            updateAudio = true,
            updateSubtitle = false
        )

        assertEquals("profile-two", store.getSelection("movie")?.audioTrackId)
        activeProfileId = 1
        assertEquals("profile-one", store.getSelection("movie")?.audioTrackId)
    }

    @Test
    fun noActiveProfileDoesNotReadOrWriteProfileOneTrackMemory() {
        activeProfileId = null
        store.updateSelection(
            playbackId = "movie",
            audioTrackId = "default",
            subtitleTrackId = "subtitle",
            subtitleDelayMs = 500L,
            updateAudio = true,
            updateSubtitle = true,
            updateSubtitleDelay = true
        )

        assertNull(store.getSelection("movie"))

        activeProfileId = 1
        assertNull(store.getSelection("movie"))
    }

    @Test
    fun clearSelectionsForPrefixOnlyClearsMatchingIdsInCurrentProfile() {
        activeProfileId = 2
        fun remember(id: String, audio: String) {
            store.updateSelection(
                playbackId = id,
                audioTrackId = audio,
                subtitleTrackId = null,
                updateAudio = true,
                updateSubtitle = false
            )
        }

        remember("show:1:1", "one")
        remember("show:1:2", "two")
        remember("other:1:1", "other")
        activeProfileId = 1
        remember("show:1:1", "profile-one")

        activeProfileId = 2
        store.clearSelectionsForPrefix("show:")

        assertNull(store.getSelection("show:1:1"))
        assertNull(store.getSelection("show:1:2"))
        assertEquals("other", store.getSelection("other:1:1")?.audioTrackId)
        activeProfileId = 1
        assertEquals("profile-one", store.getSelection("show:1:1")?.audioTrackId)
    }

    companion object {
        private const val PREFS_FILE = "playback_track_selection_prefs"
    }
}
