package com.hereliesaz.illumera.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * What a button looks like before it is focused. There are only two highlights: the active
 * (focused) one, and the selected one for a tab, choice or toggle that is on. An action is
 * never lit at rest, however important it is.
 */
enum class ButtonEmphasis {
    /** Not selected: an ordinary action, or a choice that is off. */
    Normal,
    /** A tab, choice or toggle that is on. */
    Selected,
    /** Removes or resets something: red under the cursor only, never lit at rest. */
    Destructive
}

@Immutable
data class ButtonColors(
    val container: Color,
    val border: Color,
    val borderWidth: Dp,
    val content: Color
)

/**
 * The one highlighted/unhighlighted scheme every action button uses, so buttons that
 * sit side by side light up the same way. Shape and size stay each button's own.
 *
 * - At rest: faint fill, faint border, theme text label.
 * - Selected (a tab, choice or toggle that is on): a brighter neutral fill and border. It is
 *   deliberately not the accent, so it can never be mistaken for the cursor.
 * - Focused (the cursor): accent-tinted fill and 2dp accent border, with the label in the
 *   theme text color (an accent label on an accent-tinted fill is hard to read). Only focus
 *   uses the accent.
 * - Disabled: dimmed, never highlighted.
 */
@Composable
fun buttonColors(
    focused: Boolean,
    emphasis: ButtonEmphasis = ButtonEmphasis.Normal,
    enabled: Boolean = true
): ButtonColors {
    val accent = if (emphasis == ButtonEmphasis.Destructive) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.primary
    val target = when {
        !enabled -> ButtonColors(
            container = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f),
            border = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f),
            borderWidth = 1.dp,
            content = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.30f)
        )
        focused -> ButtonColors(
            container = accent.copy(alpha = if (emphasis == ButtonEmphasis.Normal) 0.15f else 0.35f),
            border = accent,
            borderWidth = 2.dp,
            content = MaterialTheme.colorScheme.onBackground
        )
        emphasis == ButtonEmphasis.Selected -> ButtonColors(
            container = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.22f),
            border = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.60f),
            borderWidth = 1.dp,
            content = MaterialTheme.colorScheme.onBackground
        )
        // Destructive is red only under the cursor; at rest it is an ordinary button.
        else -> ButtonColors(
            container = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f),
            border = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.20f),
            borderWidth = 1.dp,
            content = MaterialTheme.colorScheme.onBackground
        )
    }
    val container by animateColorAsState(target.container, tween(ANIMATION_MS), label = "buttonContainer")
    val border by animateColorAsState(target.border, tween(ANIMATION_MS), label = "buttonBorder")
    val borderWidth by animateDpAsState(target.borderWidth, tween(ANIMATION_MS), label = "buttonBorderWidth")
    val content by animateColorAsState(target.content, tween(ANIMATION_MS), label = "buttonContent")
    return ButtonColors(container, border, borderWidth, content)
}

private const val ANIMATION_MS = 150
