package com.hereliesaz.illumera.ui.util

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Adds touch tap/long-press handling without changing keyboard/D-pad behavior.
 * A long press fires after one second (haptic tick), then swallows every
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
            val released = withTimeoutOrNull(1_000L) {
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

/** How long OK (or a finger) is held to count as a long press. */
const val LONG_PRESS_MS = 1_000L

/**
 * A tap or OK press runs [onClick]; holding it for [LONG_PRESS_MS] runs [onLongClick] instead, once,
 * with no click when it is let go. Works for touch and for the remote's OK / Enter / A button,
 * so a control can do its common thing on a plain press and keep the rarer one (a menu) behind
 * a hold. It replaces `clickable`; keep the control focusable separately.
 */
fun Modifier.longPressClick(
    onClick: () -> Unit,
    onLongClick: () -> Unit
): Modifier = composed {
    var held by remember { mutableStateOf(false) }
    var fired by remember { mutableStateOf(false) }
    LaunchedEffect(held) {
        if (held) {
            kotlinx.coroutines.delay(LONG_PRESS_MS)
            if (held && !fired) {
                fired = true
                onLongClick()
            }
        }
    }
    this
        .touchClick(onClick = onClick, onLongClick = onLongClick)
        .onPreviewKeyEvent { event ->
            val activation = event.key == Key.DirectionCenter || event.key == Key.Enter ||
                event.key == Key.NumPadEnter || event.key == Key.ButtonA
            if (!activation) return@onPreviewKeyEvent false
            when (event.type) {
                KeyEventType.KeyDown -> {
                    if (!held) { held = true; fired = false }
                    true
                }
                KeyEventType.KeyUp -> {
                    val wasLong = fired
                    held = false
                    fired = false
                    if (!wasLong) onClick()
                    true
                }
                else -> false
            }
        }
}
