package com.hereliesaz.illumera.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Clamps a dialog's preferred width to fit the screen (minus a margin) on phones,
 * instead of overflowing off-screen the way a bare fixed dp width does on a
 * ~360-412dp-wide phone. On screens wide enough for it (TV, tablet), the
 * preferred width is used as-is.
 */
@Composable
fun rememberDialogWidth(preferredDp: Int, marginDp: Int = 32): Dp {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    return minOf(preferredDp, screenWidthDp - marginDp).dp
}
