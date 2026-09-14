package com.hereliesaz.illumera.data.update

import android.content.Context
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppUpdateManagerSecurityTest {

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("lumera_update_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("lumera_update_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun downloadUrlRequiresHttpsAndExactTrustedHostBoundary() {
        assertTrue(callBoolean("isAllowedDownloadUrl", "https://github.com/HereLiesAz/illumera/releases/download/v1/app.apk"))
        assertTrue(callBoolean("isAllowedDownloadUrl", "https://objects.githubusercontent.com/path/app.apk"))
        assertTrue(callBoolean("isAllowedDownloadUrl", "https://subdomain.objects.githubusercontent.com/path/app.apk"))

        assertFalse(callBoolean("isAllowedDownloadUrl", "http://github.com/HereLiesAz/app.apk"))
        assertFalse(callBoolean("isAllowedDownloadUrl", "https://github.com.evil.example/app.apk"))
        assertFalse(callBoolean("isAllowedDownloadUrl", "https://evilgithub.com/app.apk"))
        assertFalse(callBoolean("isAllowedDownloadUrl", "not a url"))
    }

    @Test
    fun versionComparisonIsNumericNotLexicographic() {
        assertTrue(callBoolean("isNewerVersion", "1.10.0", "1.9.9"))
        assertTrue(callBoolean("isNewerVersion", "2.0", "1.99.99"))
        assertFalse(callBoolean("isNewerVersion", "1.9.9", "1.10.0"))
        assertFalse(callBoolean("isNewerVersion", "1.2.3", "1.2.3"))
        assertFalse(callBoolean("isNewerVersion", "1.2.3-beta", "1.2.3"))
        assertFalse(callBoolean("isNewerVersion", "1.2.3+build7", "1.2.3+build1"))
    }

    @Test
    fun versionComparisonPadsMissingNumericComponentsWithZero() {
        assertFalse(callBoolean("isNewerVersion", "1.2", "1.2.0"))
        assertTrue(callBoolean("isNewerVersion", "1.2.1", "1.2"))
        assertFalse(callBoolean("isNewerVersion", "1.2.0.0", "1.2"))
    }

    @Test
    fun sha256ExtractionRequiresExactly64HexCharacters() {
        val hash = "a".repeat(64)
        assertEquals(hash, callStringNullable("extractSha256FromBody", "Notes\nSHA-256: $hash\nDone"))
        assertEquals(null, callStringNullable("extractSha256FromBody", "SHA-256: ${"a".repeat(63)}"))
        assertEquals(null, callStringNullable("extractSha256FromBody", "SHA-256: ${"g".repeat(64)}"))
        assertEquals(null, callStringNullable("extractSha256FromBody", null))
    }

    @Test
    fun checksumLineIsRemovedFromDisplayedChangelog() {
        val hash = "B".repeat(64)
        assertEquals(
            "Feature one\nFeature two",
            callString("stripHashFromChangelog", "Feature one\nSHA-256: $hash\nFeature two")
        )
        assertEquals("No changelog provided.", callString("stripHashFromChangelog", null))
        assertEquals("No changelog provided.", callString("stripHashFromChangelog", "SHA-256: $hash"))
    }

    @Test
    fun popupPreferenceDefaultsOnAndRoundTrips() {
        val context = RuntimeEnvironment.getApplication()
        val manager = AppUpdateManager(mockk<OkHttpClient>(), context)

        assertTrue(manager.isPopupEnabled)
        manager.setPopupEnabled(false)
        assertFalse(manager.isPopupEnabled)
        manager.setPopupEnabled(true)
        assertTrue(manager.isPopupEnabled)
    }

    private fun callBoolean(name: String, vararg args: String): Boolean =
        callPrivate(name, *args) as Boolean

    private fun callString(name: String, value: String?): String =
        callPrivateNullableString(name, value) as String

    private fun callStringNullable(name: String, value: String?): String? =
        callPrivateNullableString(name, value) as String?

    private fun callPrivate(name: String, vararg args: String): Any? {
        val types = Array(args.size) { String::class.java }
        val method = AppUpdateManager.Companion.javaClass.getDeclaredMethod(name, *types)
        method.isAccessible = true
        return method.invoke(AppUpdateManager.Companion, *args)
    }

    private fun callPrivateNullableString(name: String, value: String?): Any? {
        val method = AppUpdateManager.Companion.javaClass.getDeclaredMethod(name, String::class.java)
        method.isAccessible = true
        return method.invoke(AppUpdateManager.Companion, value)
    }
}
