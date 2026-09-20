package com.hereliesaz.illumera.data.repository

import com.google.gson.JsonParser
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.AddonEntity
import com.hereliesaz.illumera.data.model.CatalogConfigEntity
import com.hereliesaz.illumera.data.model.stremio.CatalogExtra
import com.hereliesaz.illumera.data.model.stremio.CatalogManifest
import com.hereliesaz.illumera.data.model.stremio.CatalogResponse
import com.hereliesaz.illumera.data.model.stremio.Manifest
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.data.model.stremio.MetaResponse
import com.hereliesaz.illumera.data.model.stremio.MetaVideo
import com.hereliesaz.illumera.data.model.stremio.Stream
import com.hereliesaz.illumera.data.model.stremio.StreamResponse
import com.hereliesaz.illumera.data.remote.StremioApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonRepositoryTest {

    @Test
    fun fetchNextCatalogPageUsesBaseUrlAtZeroAndSkipPathAfterward() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>(relaxed = true)
        coEvery { api.getCatalog(any()) } returns CatalogResponse(listOf(meta("m1")))
        val repository = AddonRepository(api, dao)
        val base = "https://addon.example/catalog/movie/top.json"

        assertEquals(listOf("m1"), repository.fetchNextCatalogPage(base, 0).map { it.id })
        assertEquals(listOf("m1"), repository.fetchNextCatalogPage(base, 25).map { it.id })

        coVerify(exactly = 1) { api.getCatalog(base) }
        coVerify(exactly = 1) { api.getCatalog("https://addon.example/catalog/movie/top/skip=25.json") }
    }

    @Test
    fun fetchNextCatalogPageFailsClosedOnNetworkError() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>(relaxed = true)
        coEvery { api.getCatalog(any()) } throws IllegalStateException("network")

        assertTrue(AddonRepository(api, dao).fetchNextCatalogPage("https://addon.example/top.json", 0).isEmpty())
    }

    @Test
    fun blankSearchDoesNotCallNetwork() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>(relaxed = true)

        assertTrue(AddonRepository(api, dao).searchMovies("   ").isEmpty())

        coVerify(exactly = 0) { api.getCatalog(any()) }
    }

    @Test
    fun searchCombinesMoviesAndSeriesAndMarksCinemetaOrigin() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>(relaxed = true)
        coEvery { api.getCatalog("https://v3-cinemeta.strem.io/catalog/movie/top/search=matrix.json") } returns
            CatalogResponse(listOf(meta("movie", "movie")))
        coEvery { api.getCatalog("https://v3-cinemeta.strem.io/catalog/series/top/search=matrix.json") } returns
            CatalogResponse(listOf(meta("series", "series")))

        val result = AddonRepository(api, dao).searchMovies("matrix")

        assertEquals(setOf("movie", "series"), result.map { it.id }.toSet())
        assertTrue(result.all { it.addonBaseUrl == "https://v3-cinemeta.strem.io" })
    }

    @Test
    fun streamLookupQueriesOnlyEnabledStreamingAddonsAndLabelsOrigin() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        val enabled = addon("https://one.example", name = "One", nickname = "Favorite")
        val disabled = addon("https://disabled.example", enabled = false)
        val noStreams = addon("https://nostream.example", supportsStream = false)
        every { dao.getAllAddons() } returns flowOf(listOf(enabled, disabled, noStreams))
        coEvery { api.getStreams("https://one.example/stream/movie/tt1.json") } returns
            StreamResponse(listOf(Stream(name = "1080p", url = "https://cdn.example/video")))

        val result = AddonRepository(api, dao).getStreams("movie", "tt1")

        assertEquals(1, result.size)
        assertEquals("1080p", result.single().name)
        assertEquals("Favorite", result.single().addonDisplayName)
        assertEquals("https://one.example", result.single().addonTransportUrl)
        assertEquals("movie", result.single().addonRequestType)
        assertEquals("tt1", result.single().addonRequestId)
        coVerify(exactly = 1) { api.getStreams(any()) }
    }

    @Test
    fun streamFailureFromOneAddonDoesNotDiscardSuccessfulAddonResults() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        every { dao.getAllAddons() } returns flowOf(
            listOf(addon("https://bad.example", name = "Bad"), addon("https://good.example", name = "Good"))
        )
        coEvery { api.getStreams("https://bad.example/stream/movie/tt1.json") } throws IllegalStateException("down")
        coEvery { api.getStreams("https://good.example/stream/movie/tt1.json") } returns
            StreamResponse(listOf(Stream(name = "720p", infoHash = "hash")))

        val result = AddonRepository(api, dao).getStreams("movie", "tt1")

        assertEquals(1, result.size)
        assertEquals("720p", result.single().name)
        assertEquals("Good", result.single().addonDisplayName)
    }

    @Test
    fun preferredAddonTimeoutFallsThroughToConfiguredMetaAddon() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        val fallback = AddonEntity(
            transportUrl = "https://fallback.example",
            id = "fallback",
            name = "Fallback",
            version = "1",
            description = null,
            iconUrl = null,
            supportsMeta = true,
            typesJson = "[\"movie\"]"
        )
        every { dao.getAllAddons() } returns flowOf(listOf(fallback))

        coEvery {
            api.getMeta("https://slow.example/meta/movie/tt1.json")
        } coAnswers {
            delay(5_001L)
            MetaResponse(MetaItem(id = "tt1", type = "movie", name = "Too Late"))
        }
        coEvery {
            api.getMeta("https://fallback.example/meta/movie/tt1.json")
        } returns MetaResponse(
            MetaItem(id = "tt1", type = "movie", name = "Fallback Movie")
        )

        val result = AddonRepository(api, dao).resolveMetaDetails(
            type = "movie",
            id = "tt1",
            preferredAddonBaseUrl = "https://slow.example"
        )

        assertEquals("Fallback Movie", result?.name)
        assertEquals("https://fallback.example", result?.addonBaseUrl)
        coVerify(exactly = 1) { api.getMeta("https://fallback.example/meta/movie/tt1.json") }
    }

    @Test
    fun slowFallbackAddonDoesNotPreventLaterCandidate() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        val slow = AddonEntity(
            transportUrl = "https://slow.example",
            id = "slow",
            name = "Slow",
            version = "1",
            description = null,
            iconUrl = null,
            supportsMeta = true,
            typesJson = "[\"movie\"]"
        )
        val good = AddonEntity(
            transportUrl = "https://good.example",
            id = "good",
            name = "Good",
            version = "1",
            description = null,
            iconUrl = null,
            supportsMeta = true,
            typesJson = "[\"movie\"]"
        )
        every { dao.getAllAddons() } returns flowOf(listOf(slow, good))

        coEvery {
            api.getMeta("https://slow.example/meta/movie/tt1.json")
        } coAnswers {
            delay(10_001L)
            MetaResponse(MetaItem(id = "tt1", type = "movie", name = "Too Late"))
        }
        coEvery {
            api.getMeta("https://good.example/meta/movie/tt1.json")
        } returns MetaResponse(
            MetaItem(id = "tt1", type = "movie", name = "Good Movie")
        )

        val result = AddonRepository(api, dao).resolveMetaDetails(
            type = "movie",
            id = "tt1"
        )

        assertEquals("Good Movie", result?.name)
        assertEquals("https://good.example", result?.addonBaseUrl)
        coVerify(exactly = 1) { api.getMeta("https://good.example/meta/movie/tt1.json") }
    }

    @Test
    fun externalMetaCancellationStillAbortsResolution() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        every { dao.getAllAddons() } returns flowOf(emptyList())
        coEvery {
            api.getMeta("https://preferred.example/meta/movie/tt1.json")
        } throws CancellationException("cancel parent request")

        var cancelled = false
        try {
            AddonRepository(api, dao).resolveMetaDetails(
                type = "movie",
                id = "tt1",
                preferredAddonBaseUrl = "https://preferred.example"
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
    }

    @Test
    fun preferredAddonMetaMayCanonicalizeReturnedId() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        every { dao.getAllAddons() } returns flowOf(emptyList())

        coEvery {
            api.getMeta("https://flix.example/meta/movie/tmdb:603.json")
        } returns MetaResponse(
            MetaItem(
                id = "tt0133093",
                type = "movie",
                name = "The Matrix"
            )
        )

        val result = AddonRepository(api, dao).resolveMetaDetails(
            type = "movie",
            id = "tmdb:603",
            preferredAddonBaseUrl = "https://flix.example"
        )

        assertEquals("tt0133093", result?.id)
        assertEquals("The Matrix", result?.name)
        assertEquals("https://flix.example", result?.addonBaseUrl)
    }

    @Test
    fun fallbackAddonStillRejectsMismatchedMetaId() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        val fallbackAddon = AddonEntity(
            transportUrl = "https://fallback.example",
            id = "fallback",
            name = "Fallback",
            version = "1",
            description = null,
            iconUrl = null,
            supportsMeta = true,
            typesJson = "[\"movie\"]"
        )
        every { dao.getAllAddons() } returns flowOf(listOf(fallbackAddon))

        coEvery {
            api.getMeta("https://fallback.example/meta/movie/tt-requested.json")
        } returns MetaResponse(
            MetaItem(id = "tt-unrelated", type = "movie", name = "Wrong Movie")
        )
        coEvery {
            api.getMeta("https://v3-cinemeta.strem.io/meta/movie/tt-requested.json")
        } throws IllegalStateException("no fallback")

        val result = AddonRepository(api, dao).resolveMetaDetails(
            type = "movie",
            id = "tt-requested"
        )

        assertEquals(null, result)
    }

    @Test
    fun getMetaDetailsPreservesAddonVideoListWithoutSeasonEpisodeDeduplication() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>(relaxed = true)
        val url = "https://addon.example/meta/series/custom-series.json"
        val videos = listOf(
            MetaVideo(id = "cut-a", title = "Cut A", season = 1, episode = 1),
            MetaVideo(id = "cut-b", title = "Cut B", season = 1, episode = 1),
            MetaVideo(id = "special-a", title = "Special A"),
            MetaVideo(id = "special-b", title = "Special B")
        )
        coEvery { api.getMeta(url) } returns MetaResponse(
            MetaItem(id = "custom-series", type = "series", name = "Custom", videos = videos)
        )

        val result = AddonRepository(api, dao).getMetaDetails(url)

        assertEquals(listOf("cut-a", "cut-b", "special-a", "special-b"), result?.videos?.map { it.id })
    }

    @Test
    fun sortOrderMapReflectsInstalledAddonOrder() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        every { dao.getAllAddons() } returns flowOf(
            listOf(addon("https://a", sortOrder = 3), addon("https://b", sortOrder = 7))
        )

        assertEquals(mapOf("https://a" to 3, "https://b" to 7), AddonRepository(api, dao).getAddonSortOrders())
    }

    @Test
    fun reinstallPreservesNicknameTrustSortOrderAndExistingCatalogCustomization() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        val manifestUrl = "https://addon.example/manifest.json"
        val transportUrl = "https://addon.example"
        val catalog = CatalogManifest(
            type = "movie",
            id = "popular",
            name = "Popular",
            extra = listOf(CatalogExtra(name = "skip"))
        )
        coEvery { api.getManifest(manifestUrl) } returns Manifest(
            id = "addon-id",
            name = "Fresh Name",
            version = "2.0",
            resources = listOf(JsonParser.parseString("\"meta\""), JsonParser.parseString("\"stream\"")),
            types = listOf("movie"),
            catalogs = listOf(catalog)
        )
        coEvery { dao.getAddon(transportUrl) } returns addon(
            transportUrl,
            name = "Old Name",
            nickname = "My Nickname",
            trusted = true,
            sortOrder = 4
        )
        val existingConfig = CatalogConfigEntity(
            uniqueId = "$transportUrl/movie/popular",
            transportUrl = transportUrl,
            addonName = "Old Name",
            catalogType = "movie",
            catalogId = "popular",
            catalogName = "Old Catalog",
            customTitle = "Keep Me",
            showInHome = false,
            showInMovies = true,
            homeOrder = 8,
            moviesOrder = 2
        )
        coEvery { dao.getCatalogConfig(existingConfig.uniqueId) } returns existingConfig
        coEvery { dao.insertAddon(any()) } returns Unit
        coEvery { dao.saveCatalogConfigs(any()) } returns Unit
        val addonSlot = slot<AddonEntity>()
        val configsSlot = slot<List<CatalogConfigEntity>>()
        coEvery { dao.insertAddon(capture(addonSlot)) } returns Unit
        coEvery { dao.saveCatalogConfigs(capture(configsSlot)) } returns Unit

        AddonRepository(api, dao).installAddonWithConfig(manifestUrl, home = true, movies = false, series = true)

        assertEquals("My Nickname", addonSlot.captured.nickname)
        assertTrue(addonSlot.captured.isTrusted)
        assertEquals(4, addonSlot.captured.sortOrder)
        assertTrue(addonSlot.captured.supportsMeta)
        assertTrue(addonSlot.captured.supportsStream)
        val saved = configsSlot.captured.single()
        assertEquals("Keep Me", saved.customTitle)
        assertFalse(saved.showInHome)
        assertTrue(saved.showInMovies)
        assertEquals("Fresh Name", saved.addonName)
        assertEquals("Popular", saved.catalogName)
    }

    private fun meta(id: String, type: String = "movie") = MetaItem(id = id, type = type, name = id)

    private fun addon(
        url: String,
        name: String = "Addon",
        nickname: String? = null,
        enabled: Boolean = true,
        supportsStream: Boolean = true,
        trusted: Boolean = false,
        sortOrder: Int = 999
    ) = AddonEntity(
        transportUrl = url,
        id = "id-$name",
        name = name,
        version = "1",
        description = null,
        iconUrl = null,
        isTrusted = trusted,
        isEnabled = enabled,
        nickname = nickname,
        supportsStream = supportsStream,
        sortOrder = sortOrder
    )
}
