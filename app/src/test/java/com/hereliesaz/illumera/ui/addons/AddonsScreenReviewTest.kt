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

    private fun item(url: String, name: String) = AddonCatalogItem(
        transportUrl = url,
        manifest = Manifest(id = name.lowercase(), name = name)
    )

    @Test
    fun notInstalledFilterHidesOnlyExactlyInstalledEndpoints() {
        val a = item("https://example.org/a/manifest.json", "Alpha")
        val b = item("https://example.org/b/manifest.json", "Beta")
        val bConfigured = item("https://example.org/b/manifest.json?token=x", "Beta configured")
        val installedUrls = listOf("https://example.org/b")

        assertEquals(listOf(a, b, bConfigured), filterCatalogItems(listOf(a, b, bConfigured), installedUrls, "", false))
        // A different configured query is a different installation, so it stays listed.
        assertEquals(listOf(a, bConfigured), filterCatalogItems(listOf(a, b, bConfigured), installedUrls, "", true))
    }

    @Test
    fun notInstalledFilterComposesWithSearch() {
        val a = item("https://example.org/a/manifest.json", "Alpha")
        val b = item("https://example.org/b/manifest.json", "Beta")
        assertEquals(listOf(b), filterCatalogItems(listOf(a, b), listOf("https://example.org/a"), "bet", true))
        assertTrue(filterCatalogItems(listOf(a, b), listOf("https://example.org/a"), "alp", true).isEmpty())
    }
}
