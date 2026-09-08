package com.hereliesaz.illumera.ui.util

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build

enum class DeviceFormFactor {
    PHONE,
    TABLET,
    CHROMEBOOK,
    TV
}

fun detectDeviceFormFactor(context: Context): DeviceFormFactor {
    val packageManager = context.packageManager
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager

    val isTv =
        packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    if (isTv) return DeviceFormFactor.TV

    val isChromebook = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        packageManager.hasSystemFeature(PackageManager.FEATURE_PC)
    } else {
        packageManager.hasSystemFeature("android.hardware.type.pc")
    }
    if (isChromebook) return DeviceFormFactor.CHROMEBOOK

    val smallestWidthDp = context.resources.configuration.smallestScreenWidthDp
    return if (smallestWidthDp >= 600) DeviceFormFactor.TABLET else DeviceFormFactor.PHONE
}
