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
    fun catalogUninstallKeepsConfiguredCredentialsByMatchingTheExactTransport() {
        val a = installed("https://example.org/a/manifest.json?token=alpha")
        val b = installed("https://example.org/b/manifest.json?token=beta")
        val catalog = AddonCatalogItem(
            transportUrl = b.transportUrl,
            manifest = Manifest(id = "com.example.addon")
        )

        assertEquals(listOf(b), addonRemovalCandidates(catalog, listOf(a, b)))
    }

    @Test
    fun ambiguousManifestIdReturnsEveryCandidateForExplicitUserSelection() {
        val a = installed("https://example.org/a/manifest.json?token=alpha")
        val b = installed("https://example.org/b/manifest.json?token=beta")
        val catalog = AddonCatalogItem(
            transportUrl = "https://example.org/manifest.json",
            manifest = Manifest(id = "com.example.addon")
        )

        assertEquals(listOf(a, b), addonRemovalCandidates(catalog, listOf(a, b)))
        assertTrue(addonRemovalCandidates(
            AddonCatalogItem(transportUrl = "https://missing.org/manifest.json",
                manifest = Manifest(id = "unknown")), listOf(a, b)
        ).isEmpty())
    }

    @Test
    fun compactHeaderUsesStackedControlsBeforeTheyWouldOverflow() {
        assertEquals(true, compactAddonHeader(230f))
        assertEquals(true, compactAddonHeader(300f))
        assertEquals(true, compactAddonHeader(371f))
        assertEquals(false, compactAddonHeader(372f))
        assertEquals(false, compactAddonHeader(640f))
    }
}
