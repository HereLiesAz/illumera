package com.hereliesaz.illumera.ui.navigation

import android.view.SoundEffectConstants
import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainShellNavigationSoundTest {
    @Test
    fun directionKeysUseMatchingPlatformNavigationSounds() {
        assertEquals(SoundEffectConstants.NAVIGATION_LEFT, soundForNavigationKey(Key.DirectionLeft))
        assertEquals(SoundEffectConstants.NAVIGATION_RIGHT, soundForNavigationKey(Key.DirectionRight))
        assertEquals(SoundEffectConstants.NAVIGATION_UP, soundForNavigationKey(Key.DirectionUp))
        assertEquals(SoundEffectConstants.NAVIGATION_DOWN, soundForNavigationKey(Key.DirectionDown))
    }

    @Test
    fun playbackAndConfirmationButtonsNeverProduceNavigationClicks() {
        assertNull(soundForNavigationKey(Key.Enter))
        assertNull(soundForNavigationKey(Key.DirectionCenter))
        assertNull(soundForNavigationKey(Key.Back))
    }
}
