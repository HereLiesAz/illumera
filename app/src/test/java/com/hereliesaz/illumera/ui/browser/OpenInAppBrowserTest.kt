package com.hereliesaz.illumera.ui.browser

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The in-app browser is for TVs only; every other device gets its own browser. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenInAppBrowserTest {

    private class Host(base: Context) : ContextWrapper(base), InAppBrowserHost {
        var opened: String? = null
        override fun openBrowser(url: String, onAddonLink: ((String) -> Unit)?) {
            opened = url
        }
    }

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val url = "https://example.com/configure"

    @Test
    fun aTvUsesTheInAppBrowser() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        val host = Host(app)

        host.openInAppBrowser(url)

        assertEquals(url, host.opened)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun aPhoneUsesTheDevicesBrowser() {
        val host = Host(app)

        host.openInAppBrowser(url)

        assertNull(host.opened)
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(url, started.dataString)
    }
}
