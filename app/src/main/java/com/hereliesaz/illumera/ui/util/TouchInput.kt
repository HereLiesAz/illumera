package com.hereliesaz.illumera.ui.util

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Adds pointer-tap activation without changing keyboard/D-pad behavior.
 *
 * Compose for TV interactive surfaces intentionally handle D-pad activation
 * themselves and do not install a pointer-click handler. Use this modifier on
 * TV Material click targets that must also work on phones and tablets.
 */
fun Modifier.touchClick(
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
): Modifier {
    if (!enabled) return this
    return pointerInput(onClick, onLongClick) {
        detectTapGestures(
            onTap = { onClick() },
            onLongPress = onLongClick?.let { longClick -> { longClick() } }
        )
    }
}
