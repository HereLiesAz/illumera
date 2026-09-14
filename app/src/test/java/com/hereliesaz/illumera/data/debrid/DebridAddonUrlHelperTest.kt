package com.hereliesaz.illumera.data.debrid

import com.hereliesaz.illumera.data.model.debrid.DebridProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class DebridAddonUrlHelperTest {

    @Test
    fun leavesUrlUnchangedWithoutProviderOrApiKey() {
        val url = "https://torrentio.strem.fun/manifest.json"

        assertEquals(url, DebridAddonUrlHelper.withDebridKeyIfKnown(url, null, "key"))
        assertEquals(url, DebridAddonUrlHelper.withDebridKeyIfKnown(url, DebridProvider.REAL_DEBRID, null))
        assertEquals(url, DebridAddonUrlHelper.withDebridKeyIfKnown(url, DebridProvider.REAL_DEBRID, "  "))
    }

    @Test
    fun leavesMalformedUnknownAndNonManifestUrlsUnchanged() {
        assertEquals(
            "not a url",
            DebridAddonUrlHelper.withDebridKeyIfKnown("not a url", DebridProvider.REAL_DEBRID, "key")
        )
        assertEquals(
            "https://example.com/manifest.json",
            DebridAddonUrlHelper.withDebridKeyIfKnown(
                "https://example.com/manifest.json",
                DebridProvider.REAL_DEBRID,
                "key"
            )
        )
        assertEquals(
            "https://torrentio.strem.fun/configure",
            DebridAddonUrlHelper.withDebridKeyIfKnown(
                "https://torrentio.strem.fun/configure",
                DebridProvider.REAL_DEBRID,
                "key"
            )
        )
    }

    @Test
    fun injectsRealDebridKeyIntoBareTorrentioManifest() {
        assertEquals(
            "https://torrentio.strem.fun/realdebrid=abc123/manifest.json",
            DebridAddonUrlHelper.withDebridKeyIfKnown(
                "https://torrentio.strem.fun/manifest.json",
                DebridProvider.REAL_DEBRID,
                "abc123"
            )
        )
    }

    @Test
    fun appendsProviderKeyToExistingTorrentioConfiguration() {
        assertEquals(
            "https://torrentio.strem.fun/sort=qualitysize|premiumize=secret/manifest.json",
            DebridAddonUrlHelper.withDebridKeyIfKnown(
                "https://torrentio.strem.fun/sort=qualitysize/manifest.json",
                DebridProvider.PREMIUMIZE,
                "secret"
            )
        )
    }

    @Test
    fun eachSupportedProviderUsesTorrentioExpectedConfigKey() {
        val expected = mapOf(
            DebridProvider.REAL_DEBRID to "realdebrid",
            DebridProvider.ALL_DEBRID to "alldebrid",
            DebridProvider.PREMIUMIZE to "premiumize",
            DebridProvider.TORBOX to "torbox",
            DebridProvider.DEBRID_LINK to "debridlink",
            DebridProvider.OFFCLOUD to "offcloud",
            DebridProvider.EASY_DEBRID to "easydebrid"
        )

        expected.forEach { (provider, key) ->
            assertEquals(
                "https://torrentio.strem.fun/$key=token/manifest.json",
                DebridAddonUrlHelper.withDebridKeyIfKnown(
                    "https://torrentio.strem.fun/manifest.json",
                    provider,
                    "token"
                )
            )
        }
    }

    @Test
    fun doesNotOverwriteAnyExistingDebridConfiguration() {
        val url = "https://torrentio.strem.fun/sort=quality|alldebrid=existing/manifest.json"

        assertEquals(
            url,
            DebridAddonUrlHelper.withDebridKeyIfKnown(url, DebridProvider.REAL_DEBRID, "replacement")
        )
    }

    @Test
    fun providerLookupUsesStableIds() {
        DebridProvider.entries.forEach { provider ->
            assertEquals(provider, DebridProvider.fromId(provider.id))
        }
        assertEquals(null, DebridProvider.fromId("unknown"))
        assertEquals(null, DebridProvider.fromId(null))
    }
}
