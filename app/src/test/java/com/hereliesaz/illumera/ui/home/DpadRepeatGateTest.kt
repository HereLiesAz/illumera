package com.hereliesaz.illumera.ui.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Native remote auto-repeat must not be throttled like stray duplicate first presses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DpadRepeatGateTest {
    private fun dpadDown(repeatCount: Int): KeyEvent =
        KeyEvent(AndroidKeyEvent(
            0L, 0L, AndroidKeyEvent.ACTION_DOWN,
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT, repeatCount
        ))

    @Test fun heldDirectionRepeatsAreNotRateLimited() {
        val gate = DpadRepeatGate(horizontalRepeatIntervalMs = 200L)
        assertFalse(gate.shouldConsume(dpadDown(0)))
        assertFalse(gate.shouldConsume(dpadDown(1)))
        assertFalse(gate.shouldConsume(dpadDown(2)))
        assertFalse(gate.shouldConsume(dpadDown(12)))
    }

    @Test fun initialDuplicateStillDebouncesButHeldRepeatNeverDoes() {
        val gate = DpadRepeatGate(horizontalRepeatIntervalMs = 60L)
        assertFalse(gate.shouldConsume(dpadDown(0)))
        assertTrue(gate.shouldConsume(dpadDown(0)))
        assertFalse(gate.shouldConsume(dpadDown(5)))
    }
}
