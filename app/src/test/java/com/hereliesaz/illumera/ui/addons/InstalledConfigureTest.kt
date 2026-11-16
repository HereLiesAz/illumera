package com.hereliesaz.illumera.ui.addons

import com.hereliesaz.illumera.data.model.stremio.AddonCatalogItem
import com.hereliesaz.illumera.data.model.stremio.Manifest
import com.hereliesaz.illumera.data.model.stremio.ManifestBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstalledConfigureTest {
    private fun catalogItem(configurable: Boolean) = AddonCatalogItem(
        transportUrl = "https://a.example/manifest.json",
        manifest = Manifest(id = "a", name = "A", behaviorHints = ManifestBehaviorHints(configurable = configurable))
    )

    @Test fun anAddonTheCatalogDoesNotKnowGetsAConfigureButton() {
        assertTrue(installedAddonCanBeConfigured("https://a.example/manifest.json", null))
    }

    @Test fun aConfigurableAddonGetsOneAndANonConfigurableOneDoesNot() {
        assertTrue(installedAddonCanBeConfigured("https://a.example/manifest.json", catalogItem(true)))
        assertFalse(installedAddonCanBeConfigured("https://a.example/manifest.json", catalogItem(false)))
    }

    @Test fun localAndNonHttpAddonsGetNone() {
        assertFalse(installedAddonCanBeConfigured("stremio://a.example/manifest.json", null))
        assertFalse(installedAddonCanBeConfigured("http://127.0.0.1:7000/manifest.json", null))
    }

    @Test fun theConfigurePageSitsNextToTheManifest() {
        assertEquals("https://a.example/cfg/configure", installedConfigureUrl("https://a.example/cfg/manifest.json"))
        assertEquals("https://a.example/configure", installedConfigureUrl("https://a.example"))
        assertEquals("https://a.example/configure?k=1", installedConfigureUrl("https://a.example/manifest.json?k=1"))
    }
}
