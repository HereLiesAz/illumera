package com.hereliesaz.illumera.ui.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.widget.Toast

/**
 * Opens an http(s) link in the device's browser. Many TV devices ship without one, so
 * failure is shown as a toast naming the link rather than thrown.
 */
fun Context.openExternalLink(url: String) {
    val uri = url.toUri()
    if (uri.scheme?.lowercase() !in setOf("http", "https")) {
        android.util.Log.w("ExternalLink", "Refusing non-http(s) link: $url")
        Toast.makeText(this, "Can't open this link: $url", Toast.LENGTH_LONG).show()
        return
    }
    try {
        startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "No browser to open $url", Toast.LENGTH_LONG).show()
    }
}
