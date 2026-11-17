package com.hereliesaz.illumera.ui.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import com.hereliesaz.illumera.ui.util.DeviceFormFactor
import com.hereliesaz.illumera.ui.util.detectDeviceFormFactor

/** Whatever shows the in-app browser (MainActivity). */
interface InAppBrowserHost {
    /** Opens [url]; [onAddonLink] receives an addon manifest address if the page offers one. */
    fun openBrowser(url: String, onAddonLink: ((String) -> Unit)? = null)
}

/**
 * Opens [url] in the app's own browser on a TV, where the device's is often missing and the
 * remote needs the pointer. Anywhere else, and with no host (a service context, a test), it goes
 * to the device's browser. Pages from addons and integrations go through here.
 */
fun Context.openInAppBrowser(url: String, onAddonLink: ((String) -> Unit)? = null) {
    if (!canOpenInBrowser(url)) {
        Toast.makeText(this, "Can't open this link: $url", Toast.LENGTH_LONG).show()
        return
    }
    if (detectDeviceFormFactor(this) == DeviceFormFactor.TV) {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is InAppBrowserHost) {
                current.openBrowser(url, onAddonLink)
                return
            }
            current = current.baseContext
        }
    }
    try {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "No browser to open $url", Toast.LENGTH_LONG).show()
    }
}
