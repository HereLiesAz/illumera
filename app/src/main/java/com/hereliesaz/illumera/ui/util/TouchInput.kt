package com.hereliesaz.illumera.ui.util

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Adds touch tap/long-press handling without changing keyboard/D-pad behavior.
 * Long-press actions are dispatched on release so context menus remain open.
 */
fun Modifier.touchClick(
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
): Modifier {
    if (!enabled) return this
    return pointerInput(onClick, onLongClick) {
        var longPressArmed = false
        detectTapGestures(
            onPress = {
                longPressArmed = false
                val released = tryAwaitRelease()
                if (released && longPressArmed) onLongClick?.invoke()
            },
            onTap = {
                if (!longPressArmed) onClick()
            },
            onLongPress = onLongClick?.let { { longPressArmed = true } }
        )
    }
}
