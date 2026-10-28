package com.hereliesaz.illumera.ui.audio

import android.content.SharedPreferences
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CinematicAudioPreferencesTest {
    @Test
    fun newProfilesAutoplayMutedPreviewsAndEnableNavigationFeedback() {
        val prefs = mockk<SharedPreferences>()
        every { prefs.getBoolean("17.autoplay", true) } returns true
        every { prefs.getBoolean("17.preview_sound", false) } returns false
        every { prefs.getBoolean("17.navigation_sounds", true) } returns true
        every { prefs.getBoolean("17.persistent_preview", false) } returns false

        assertEquals(
            CinematicAudioPreferences(
                autoPlayPreviews = true,
                previewSound = false,
                navigationSounds = true,
                persistentPreview = false
            ),
            readCinematicAudioPreferences(prefs, 17)
        )
    }

    @Test
    fun eachPreferenceUsesItsOwnProfileScopedKey() {
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { prefs.edit() } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.apply() } just Runs

        writeCinematicAudioPreference(prefs, 17, "autoplay", false)
        writeCinematicAudioPreference(prefs, 18, "preview_sound", true)
        writeCinematicAudioPreference(prefs, 17, "navigation_sounds", false)

        verify(exactly = 1) { editor.putBoolean("17.autoplay", false) }
        verify(exactly = 1) { editor.putBoolean("18.preview_sound", true) }
        verify(exactly = 1) { editor.putBoolean("17.navigation_sounds", false) }
        verify(exactly = 3) { editor.apply() }
    }
}
