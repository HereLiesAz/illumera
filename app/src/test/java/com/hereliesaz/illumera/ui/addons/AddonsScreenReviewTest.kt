package com.hereliesaz.illumera.ui.addons

import com.hereliesaz.illumera.data.model.AddonEntity
import com.hereliesaz.illumera.data.model.stremio.AddonCatalogItem
import com.hereliesaz.illumera.data.model.stremio.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for remote-friendly Add-ons controls and safe removal. */
class AddonsScreenReviewTest {
    private fun installed(url: String, id: String = "com.example.addon") = AddonEntity(
        transportUrl = url,
        id = id,
        name = "Example",
        version = "1.0",
        description = null,
        iconUrl = null
    )

    @Test
    fun exactConfigurationMatchesStoredBaseWithoutManifestSuffix() {
        val alpha = installed("https://example.org/config?token=alpha")
        val beta = installed("https://example.org/config?token=beta")
        val catalog = AddonCatalogItem(
            transportUrl = "https://example.org/config/manifest.json?token=beta",
            manifest = Manifest(id = "com.example.addon")
        )
        assertEquals(listOf(beta), addonRemovalCandidates(catalog, listOf(alpha, beta)))
    }

    @Test
    fun manifestIdAloneNeverAuthorizesUninstallingAnUnrelatedConfiguration() {
        val a = installed("https://example.org/a?token=alpha")
        val b = installed("https://example.org/b?token=beta")
        val unrelated = AddonCatalogItem(
            transportUrl = "https://example.org/c/manifest.json",
            manifest = Manifest(id = "com.example.addon")
        )
        assertTrue(addonRemovalCandidates(unrelated, listOf(a, b)).isEmpty())
    }

    @Test
    fun queryParametersAndConfiguredPathsAreNotDiscarded() {
        assertEquals(
            "https://example.org/a?token=x",
            normalizeCatalogTransportUrl("https://example.org/a/manifest.json?token=x")
        )
        assertEquals(
            "https://example.org/b?token=y",
            normalizeCatalogTransportUrl("https://example.org/b/?token=y")
        )
        assertEquals(
            "https://example.org/b?token=y",
            normalizeCatalogTransportUrl("https://example.org/b/manifest.json?token=y")
        )
    }
}
