package com.hereliesaz.illumera.ui.home

import android.os.SystemClock
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * Debounces duplicate initial DPAD presses but lets native repeat events
 * through unchanged, so holding a direction accelerates traversal.
 */
class DpadRepeatGate(
    private val horizontalRepeatIntervalMs: Long = 120L,
    private val verticalRepeatIntervalMs: Long = horizontalRepeatIntervalMs
) {
    private val lastAcceptedByKey = mutableMapOf<Key, Long>()

    fun shouldConsume(event: KeyEvent): Boolean {
        val key = event.key
        val repeatIntervalMs = when (key) {
            Key.DirectionLeft, Key.DirectionRight -> horizontalRepeatIntervalMs
            Key.DirectionUp, Key.DirectionDown -> verticalRepeatIntervalMs
            else -> return false
        }

        if (event.type != KeyEventType.KeyDown) return false

        val eventTimeMs = SystemClock.uptimeMillis()
        // Hardware remotes already emit paced key-repeat events. Let every
        // repeat through: throttling them makes long-press scrolling slower
        // than individual taps. Explicit drawer/first-row boundaries still
        // protect accidental focus escape.
        if (event.nativeKeyEvent.repeatCount > 0) {
            lastAcceptedByKey[key] = eventTimeMs
            return false
        }
        val lastAcceptedEventTimeMs = lastAcceptedByKey[key]
        if (lastAcceptedEventTimeMs == null) {
            lastAcceptedByKey[key] = eventTimeMs
            return false
        }

        val elapsed = eventTimeMs - lastAcceptedEventTimeMs
        return if (elapsed >= repeatIntervalMs) {
            lastAcceptedByKey[key] = eventTimeMs
            false
        } else {
            true
        }
    }
}
