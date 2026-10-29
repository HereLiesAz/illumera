package com.hereliesaz.illumera.ui.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties

/**
 * The active profile's "Navigation sounds" setting, provided at the app root.
 *
 * Compose plays the system navigation sound itself on every D-pad focus move, through the
 * root view of the window that has focus. That sound only honours that view's
 * `isSoundEffectsEnabled`, so the setting has to be applied to every window: the activity's
 * and each dialog's or menu's own. Before, it gated only an extra click the app played, and
 * the system sound went on regardless.
 */
val LocalNavigationSounds = compositionLocalOf { true }

/** Applies [LocalNavigationSounds] to the window this is composed in; live on every change. */
@Composable
fun FollowNavigationSounds() {
    val enabled = LocalNavigationSounds.current
    val view = LocalView.current
    SideEffect { view.isSoundEffectsEnabled = enabled }
}

/**
 * [androidx.compose.ui.window.Dialog] whose window follows the Navigation sounds setting.
 * Use this instead of the Compose one: a dialog is a separate window with its own root view.
 */
@Composable
fun Dialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit
) = androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest, properties = properties) {
    FollowNavigationSounds()
    content()
}
