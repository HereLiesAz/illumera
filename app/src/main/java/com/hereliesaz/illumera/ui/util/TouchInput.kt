package com.hereliesaz.illumera.ui.util

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Adds touch tap/long-press handling without changing keyboard/D-pad behavior.
 * A long press fires at the system long-press timeout (haptic tick), then swallows every
 * remaining event of that touch until release, so nothing it opened reacts to the held finger:
 * options are picked with a fresh tap, never by holding and sliding.
 */
fun Modifier.touchClick(
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
): Modifier = if (!enabled) this else composed {
    val haptics = LocalHapticFeedback.current
    pointerInput(onClick, onLongClick) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            if (onLongClick == null) {
                waitForUpOrCancellation()?.let { it.consume(); onClick() }
                return@awaitEachGesture
            }
            var up: PointerInputChange? = null
            val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                up = waitForUpOrCancellation()
                true
            }
            if (released == true) {
                up?.let { it.consume(); onClick() }
                return@awaitEachGesture
            }
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onLongClick()
            do {
                val event = awaitPointerEvent()
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
}
