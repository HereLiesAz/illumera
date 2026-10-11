package com.hereliesaz.illumera.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * True on an actual Android TV device (Leanback launcher), false on phones/tablets —
 * even though this app's manifest makes it installable as either. Several TV-first
 * UI patterns (focus-driven expand/reveal in NavDrawer/TopNavigationBar, the custom
 * on-screen keyboard in Search) need to behave differently when there is no D-pad,
 * since touch produces no focus events to drive them.
 */
@Composable
fun rememberIsTvDevice(): Boolean {
    val context = LocalContext.current
    // Same test as detectDeviceFormFactor (leanback/television features or TV ui mode),
    // so the two never disagree about whether this is a TV.
    return remember(context) { detectDeviceFormFactor(context) == DeviceFormFactor.TV }
}
