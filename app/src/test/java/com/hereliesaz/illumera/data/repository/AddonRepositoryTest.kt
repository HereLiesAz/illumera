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
    fun searchQueryIsUrlEncodedWithPercent20Spaces() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>(relaxed = true)
        coEvery { api.getCatalog(any()) } returns CatalogResponse(emptyList())

        AddonRepository(api, dao).searchMovies("star wars/&?")

        coVerify { api.getCatalog("https://v3-cinemeta.strem.io/catalog/movie/top/search=star%20wars%2F%26%3F.json") }
    }

    @Test
    fun searchReturnsPartialResultsWhenOneRequestFails() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>(relaxed = true)
        coEvery { api.getCatalog("https://v3-cinemeta.strem.io/catalog/movie/top/search=matrix.json") } returns
            CatalogResponse(listOf(meta("movie", "movie")))
        coEvery { api.getCatalog("https://v3-cinemeta.strem.io/catalog/series/top/search=matrix.json") } throws
            java.io.IOException("down")

        val result = AddonRepository(api, dao).searchMovies("matrix")

        assertEquals(listOf("movie"), result.map { it.id })
    }

    @Test(expected = java.io.IOException::class)
    fun searchThrowsWhenEveryRequestFails() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>(relaxed = true)
        coEvery { api.getCatalog(any()) } throws java.io.IOException("offline")

        AddonRepository(api, dao).searchMovies("matrix")
    }

    @Test
    fun streamLookupQueriesOnlyEnabledStreamingAddonsAndLabelsOrigin() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        val enabled = addon("https://one.example", name = "One", nickname = "Favorite")
        val disabled = addon("https://disabled.example", enabled = false)
        val noStreams = addon("https://nostream.example", supportsStream = false)
        every { dao.getAllAddons() } returns flowOf(listOf(enabled, disabled, noStreams))
        coEvery { api.getJson("https://one.example/stream/movie/tt1.json") } returns
            JsonParser.parseString("""{"streams":[{"name":"1080p","url":"https://cdn.example/video"}]}""")

        val result = AddonRepository(api, dao).getStreams("movie", "tt1")

        assertEquals(1, result.size)
        assertEquals("1080p", result.single().name)
        assertEquals("Favorite", result.single().addonDisplayName)
        assertEquals("https://one.example", result.single().addonTransportUrl)
        assertEquals("movie", result.single().addonRequestType)
        assertEquals("tt1", result.single().addonRequestId)
        coVerify(exactly = 1) { api.getJson(any()) }
    }

    @Test
    fun streamFailureFromOneAddonDoesNotDiscardSuccessfulAddonResults() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        every { dao.getAllAddons() } returns flowOf(
            listOf(addon("https://bad.example", name = "Bad"), addon("https://good.example", name = "Good"))
        )
        coEvery { api.getJson("https://bad.example/stream/movie/tt1.json") } throws IllegalStateException("down")
        coEvery { api.getJson("https://good.example/stream/movie/tt1.json") } returns
            JsonParser.parseString("""{"streams":[{"name":"720p","infoHash":"hash"}]}""")

        val result = AddonRepository(api, dao).getStreams("movie", "tt1")

        assertEquals(1, result.size)
        assertEquals("720p", result.single().name)
        assertEquals("Good", result.single().addonDisplayName)
    }

    @Test
    fun malformedStreamEntryDropsOnlyThatEntryAndOutcomesReportEachAddon() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        every { dao.getAllAddons() } returns flowOf(
            listOf(addon("https://mixed.example", name = "Mixed"), addon("https://down.example", name = "Down"))
        )
        // fileIdx as an object can't become an Int: before, this one entry failed the whole response.
        coEvery { api.getJson("https://mixed.example/stream/movie/tt1.json") } returns JsonParser.parseString(
            """{"streams":[{"name":"good","infoHash":"a"},{"name":"bad","infoHash":"b","fileIdx":{"x":1}},{"name":"also good","url":"https://cdn/v"}]}"""
        )
        coEvery { api.getJson("https://down.example/stream/movie/tt1.json") } throws IllegalStateException("down")
        val repository = AddonRepository(api, dao)

        val result = repository.getStreams("movie", "tt1")

        assertEquals(listOf("good", "also good"), result.map { it.name })
        val outcomes = repository.lastStreamOutcomes.value.associateBy { it.addonName }
        assertEquals(2, outcomes.getValue("Mixed").count)
        assertEquals("1 unreadable", outcomes.getValue("Mixed").problem)
        assertEquals(0, outcomes.getValue("Down").count)
        assertEquals("failed", outcomes.getValue("Down").problem)
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
        val addonSlot = slot<AddonEntity>()
        val configsSlot = slot<List<CatalogConfigEntity>>()
        coEvery { dao.installAddonAtomically(capture(addonSlot), capture(configsSlot)) } returns Unit

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

    @Test
    fun fetchNextCatalogPageTimeoutReturnsEmptyInsteadOfCancellingCaller() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>(relaxed = true)
        coEvery { api.getCatalog(any()) } coAnswers {
            delay(10_001L)
            CatalogResponse(listOf(meta("late")))
        }

        val result = AddonRepository(api, dao).fetchNextCatalogPage("https://slow.example/catalog/movie/top.json", 0)

        assertTrue(result.isEmpty())
    }

    @Test
    fun dashboardRowTimeoutDropsOnlyThatRow() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        val slowUrl = "https://slow.example"
        val goodUrl = "https://good.example"
        every { dao.getAllAddons() } returns flowOf(listOf(addon(slowUrl), addon(goodUrl)))
        every { dao.getAllCatalogConfigs() } returns flowOf(
            listOf(
                CatalogConfigEntity(
                    uniqueId = "$slowUrl/movie/top", transportUrl = slowUrl, addonName = "Slow",
                    catalogType = "movie", catalogId = "top", catalogName = "Top", customTitle = null,
                    showInHome = true, homeOrder = 0
                ),
                CatalogConfigEntity(
                    uniqueId = "$goodUrl/movie/top", transportUrl = goodUrl, addonName = "Good",
                    catalogType = "movie", catalogId = "top", catalogName = "Top", customTitle = null,
                    showInHome = true, homeOrder = 1
                )
            )
        )
        coEvery { api.getCatalog("$slowUrl/catalog/movie/top.json") } coAnswers {
            delay(500L)
            CatalogResponse(listOf(meta("late")))
        }
        coEvery { api.getCatalog("$goodUrl/catalog/movie/top.json") } returns CatalogResponse(listOf(meta("m1")))

        val rows = AddonRepository(api, dao).getDashboardRows("home", catalogTimeoutMs = 100L)

        assertEquals(1, rows.size)
        assertEquals("$goodUrl/movie/top", rows.single().configId)
    }


    @Test
    fun aggregatedCatalogCombinesEnabledCatalogsAndDeduplicatesTitles() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        val catalog = """[{"type":"movie","id":"top","name":"Top"}]"""
        every { dao.getAllAddons() } returns flowOf(listOf(
            addon("https://first.example").copy(catalogsJson = catalog),
            addon("https://second.example").copy(catalogsJson = catalog),
            addon("https://disabled.example", enabled = false).copy(catalogsJson = catalog)
        ))
        coEvery { api.getCatalog("https://first.example/catalog/movie/top.json") } returns CatalogResponse(
            listOf(
                MetaItem(id = "tt0000001", type = "movie", name = "First Film"),
                MetaItem(id = "tt0000002", type = "movie", name = "Second Film")
            )
        )
        coEvery { api.getCatalog("https://second.example/catalog/movie/top.json") } returns CatalogResponse(
            listOf(
                MetaItem(id = "tt0000001", type = "movie", name = "Duplicate"),
                MetaItem(id = "tt0000003", type = "movie", name = "Third Film"),
                MetaItem(id = "tt0000004", type = "series", name = "Wrong Type")
            )
        )

        val result = AddonRepository(api, dao).getAggregatedCatalogPage("movie", sourcesPerPage = 8)

        assertEquals(listOf("tt0000001", "tt0000002", "tt0000003"), result.map { it.id })
        coVerify(exactly = 0) { api.getCatalog("https://disabled.example/catalog/movie/top.json") }
    }

    @Test
    fun homePairsMovieAndSeriesPerCategoryWithoutCollapsingCategories() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        val url = "https://catalog.example"
        val configs = listOf("latest", "trending", "netflix").flatMapIndexed { order, category ->
            listOf("movie", "series").mapIndexed { half, type ->
                CatalogConfigEntity(
                    uniqueId = "$url/$type/$category",
                    transportUrl = url,
                    addonName = "Catalog",
                    catalogType = type,
                    catalogId = category,
                    catalogName = category.replaceFirstChar { it.uppercase() },
                    showInHome = true,
                    homeOrder = order * 2 + half
                )
            }
        }
        every { dao.getAllAddons() } returns flowOf(listOf(addon(url)))
        every { dao.getAllCatalogConfigs() } returns flowOf(configs)
        for ((i, category) in listOf("latest", "trending", "netflix").withIndex()) {
            for ((j, type) in listOf("movie", "series").withIndex()) {
                coEvery {
                    api.getCatalog("$url/catalog/$type/$category.json")
                } returns CatalogResponse(listOf(
                    MetaItem(
                        id = "tt00000${i + 1}${j + 1}",
                        type = type,
                        name = "$category $type"
                    )
                ))
            }
        }
        val repository = AddonRepository(api, dao)
        val result = repository.getDashboardRows("home")
        assertEquals(listOf("Latest", "Trending", "Netflix"), result.map { it.title })
        assertEquals(3, result.size)
        assertTrue(result.all { row ->
            row.items.size == 2 &&
                row.items.map { it.type }.toSet() == setOf("movie", "series") &&
                row.secondCatalogUrl != null
        })
        // Pagination counts paired ROWS, not the six underlying catalogs.
        val second = repository.getDashboardRows("home", skipConfigs = 1, maxConfigs = 1)
        assertEquals(listOf("Trending"), second.map { it.title })
        assertEquals(2, second.single().items.size)
    }

    @Test
    fun homeDoesNotPairDifferentAddonProvidersOrUnrelatedCategories() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        val configs = listOf(
            CatalogConfigEntity(
                uniqueId = "a-m", transportUrl = "https://a.example",
                addonName = "A", catalogType = "movie",
                catalogId = "popular", showInHome = true
            ),
            CatalogConfigEntity(
                uniqueId = "b-s", transportUrl = "https://b.example",
                addonName = "B", catalogType = "series",
                catalogId = "popular", showInHome = true
            ),
            CatalogConfigEntity(
                uniqueId = "a-s", transportUrl = "https://a.example",
                addonName = "A", catalogType = "series",
                catalogId = "latest", showInHome = true
            )
        )
        every { dao.getAllAddons() } returns flowOf(listOf(
            addon("https://a.example"), addon("https://b.example")
        ))
        every { dao.getAllCatalogConfigs() } returns flowOf(configs)
        coEvery { api.getCatalog("https://a.example/catalog/movie/popular.json") } returns
            CatalogResponse(listOf(MetaItem(id = "tt1234561", type = "movie", name = "Film")))
        coEvery { api.getCatalog("https://b.example/catalog/series/popular.json") } returns
            CatalogResponse(listOf(MetaItem(id = "tt1234562", type = "series", name = "Show")))
        coEvery { api.getCatalog("https://a.example/catalog/series/latest.json") } returns
            CatalogResponse(listOf(MetaItem(id = "tt1234563", type = "series", name = "Latest Show")))
        val result = AddonRepository(api, dao).getDashboardRows("home")
        assertEquals(3, result.size)
        assertTrue(result.all { it.secondCatalogUrl == null })
    }

    @Test
    fun aggregatedCatalogPagesSourcesBeforeRequestingSkipLayers() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        val paginated = """[{"type":"movie","id":"top","extra":[{"name":"skip"}]}]"""
        val fixed = """[{"type":"movie","id":"latest"}]"""
        every { dao.getAllAddons() } returns flowOf(listOf(
            addon("https://paged.example").copy(catalogsJson = paginated),
            addon("https://fixed.example").copy(catalogsJson = fixed)
        ))
        coEvery { api.getCatalog(any()) } returns CatalogResponse(listOf(meta("tt0000005")))

        val repository = AddonRepository(api, dao)
        repository.getAggregatedCatalogPage("movie", page = 0, sourcesPerPage = 1)
        repository.getAggregatedCatalogPage("movie", page = 1, sourcesPerPage = 1)
        repository.getAggregatedCatalogPage("movie", page = 2, sourcesPerPage = 1)
        repository.getAggregatedCatalogPage("movie", page = 3, sourcesPerPage = 1)

        coVerify(exactly = 1) { api.getCatalog("https://paged.example/catalog/movie/top.json") }
        coVerify(exactly = 1) { api.getCatalog("https://fixed.example/catalog/movie/latest.json") }
        coVerify(exactly = 1) { api.getCatalog("https://paged.example/catalog/movie/top/skip=100.json") }
        coVerify(exactly = 3) { api.getCatalog(any()) }
    }

    @Test
    fun aggregatedGenreUsesCatalogExtraAndChecksGenresForOtherSources() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        every { dao.getAllAddons() } returns flowOf(listOf(
            addon("https://filtered.example").copy(
                catalogsJson = """[{"type":"series","id":"top","extra":[{"name":"genre","options":["Science Fiction"]}]}]"""
            ),
            addon("https://unfiltered.example").copy(
                catalogsJson = """[{"type":"series","id":"top"}]"""
            )
        ))
        coEvery {
            api.getCatalog("https://filtered.example/catalog/series/top/genre=Science%20Fiction.json")
        } returns CatalogResponse(listOf(
            MetaItem(id = "tt0000006", type = "series", name = "Sci-Fi One")
        ))
        coEvery { api.getCatalog("https://unfiltered.example/catalog/series/top.json") } returns CatalogResponse(
            listOf(
                MetaItem(id = "tt0000007", type = "series", name = "Sci-Fi Two", genres = listOf("Science Fiction")),
                MetaItem(id = "tt0000008", type = "series", name = "Drama", genres = listOf("Drama"))
            )
        )

        val result = AddonRepository(api, dao).getAggregatedCatalogPage(
            "series", genre = "Science Fiction", sourcesPerPage = 4
        )

        assertEquals(listOf("tt0000006", "tt0000007"), result.map { it.id })
    }

    @Test
    fun unsupportedOptionalGenreFetchesUnfilteredThenMatchesMetadataLocally() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        every { dao.getAllAddons() } returns flowOf(listOf(
            addon("https://optional.example").copy(
                catalogsJson = """[{"type":"movie","id":"top","extra":[{"name":"genre","options":["Drama"]}]}]"""
            )
        ))
        coEvery { api.getCatalog("https://optional.example/catalog/movie/top.json") } returns
            CatalogResponse(listOf(
                MetaItem(id = "tt0000011", type = "movie", name = "Action A", genres = listOf("Action")),
                MetaItem(id = "tt0000012", type = "movie", name = "Drama B", genres = listOf("Drama"))
            ))
        val result = AddonRepository(api, dao).getAggregatedCatalogPage("movie", genre = "Action")
        assertEquals(listOf("tt0000011"), result.map { it.id })
        coVerify(exactly = 0) {
            api.getCatalog("https://optional.example/catalog/movie/top/genre=Action.json")
        }
    }

    @Test
    fun officialScienceFictionAliasUsesAddonsDeclaredGenreValue() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        every { dao.getAllAddons() } returns flowOf(listOf(
            addon("https://scifi.example").copy(
                catalogsJson = """[{"type":"series","id":"top","extra":[{"name":"genre","options":["Science Fiction"]}]}]"""
            )
        ))
        coEvery {
            api.getCatalog("https://scifi.example/catalog/series/top/genre=Science%20Fiction.json")
        } returns CatalogResponse(listOf(
            MetaItem(id = "tt0000013", type = "series", name = "Space Series", genres = listOf("Science Fiction"))
        ))
        val result = AddonRepository(api, dao).getAggregatedCatalogPage("series", genre = "Sci-Fi")
        assertEquals(listOf("tt0000013"), result.map { it.id })
    }

    @Test
    fun countsAllSourceBatchesBeforeConcludingThatAGenreIsEmpty() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        val manyCatalogs = (1..11).joinToString(",", prefix = "[", postfix = "]") {
            """{"type":"movie","id":"catalog$it","extra":[{"name":"genre","options":["Action"]}]}"""
        }
        every { dao.getAllAddons() } returns flowOf(listOf(
            addon("https://many.example").copy(catalogsJson = manyCatalogs)
        ))
        assertEquals(3, AddonRepository(api, dao).aggregatedFirstLayerPageCount("movie", "Action"))
    }

    @Test
    fun aggregatedUnfilteredFeedIncludesEveryRequiredGenreVariant() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>()
        every { dao.getAllAddons() } returns flowOf(listOf(
            addon("https://genre.example").copy(
                catalogsJson = """[{"type":"movie","id":"top","extra":[{"name":"genre","isRequired":true,"options":["Action","Drama"]}]}]"""
            )
        ))
        coEvery { api.getCatalog("https://genre.example/catalog/movie/top/genre=Action.json") } returns
            CatalogResponse(listOf(MetaItem(id = "tt1234567", type = "movie", name = "Example A")))
        coEvery { api.getCatalog("https://genre.example/catalog/movie/top/genre=Drama.json") } returns
            CatalogResponse(listOf(MetaItem(id = "tt7654321", type = "movie", name = "Example B")))

        val repository = AddonRepository(api, dao)
        val first = repository.getAggregatedCatalogPage("movie", sourcesPerPage = 1)
        val second = repository.getAggregatedCatalogPage("movie", page = 1, sourcesPerPage = 1)
        assertEquals(setOf("tt1234567", "tt7654321"), (first + second).map { it.id }.toSet())
        coVerify(exactly = 0) { api.getCatalog("https://genre.example/catalog/movie/top.json") }
    }

    @Test
    fun overlappingGenresKeepDifferentCanonicalIdsWithSameTitleAndYear() {
        val first = MetaItem(
            id = "tt1230001", type = "movie", name = "The Return", releaseInfo = "2024"
        )
        val second = MetaItem(
            id = "tt1230002", type = "movie", name = "The Return", releaseInfo = "2024"
        )
        assertEquals(listOf(first, second), distinctAggregatedMetas(emptyList(), listOf(first, second)))
    }

    @Test
    fun duplicateWithoutImdbMatchesCanonicalTitleAndYear() {
        val imdb = MetaItem(
            id = "tt1230001", type = "movie", name = "The Return", releaseInfo = "2024"
        )
        val tmdb = imdb.copy(id = "tmdb:123")
        assertTrue(distinctAggregatedMetas(listOf(imdb), listOf(tmdb)).isEmpty())
        assertTrue(distinctAggregatedMetas(listOf(tmdb), listOf(imdb)).isEmpty())
    }

    @Test
    fun aggregatedIdentityMatchesImdbAndAlternateProviderByTitleAndYear() {
        val imdb = MetaItem(id = "tt1234567", type = "movie", name = "A Fine Film",
            releaseInfo = "2025")
        val tmdb = MetaItem(id = "tmdb:100", type = "movie", name = "A Fine Film",
            releaseInfo = "2025")
        val differentYear = tmdb.copy(releaseInfo = "2024")
        assertTrue(aggregatedMediaIdentityKeys(imdb)
            .intersect(aggregatedMediaIdentityKeys(tmdb)).isNotEmpty())
        assertTrue(aggregatedMediaIdentityKeys(imdb)
            .intersect(aggregatedMediaIdentityKeys(differentYear)).isEmpty())
    }

    @Test
    fun recoversEpisodeIndexWhenCatalogMetaHasNoVideos() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>(relaxed = true)
        val full = MetaItem(
            id = "tt1234567", type = "series", name = "Example Show",
            videos = listOf(
                MetaVideo(id = "tt1234567:1:2", title = "Second", season = 1, episode = 2),
                MetaVideo(id = "tt1234567:1:1", title = "First", season = 1, episode = 1)
            )
        )
        coEvery { api.getMeta("https://v3-cinemeta.strem.io/meta/series/tt1234567.json") } returns
            MetaResponse(full)

        val recovered = AddonRepository(api, dao).recoverSeriesEpisodes("tt1234567")
        assertEquals(listOf(1, 2), recovered.map { it.episode })
        assertEquals("First", recovered.first().title)
    }

    @Test
    fun unknownSeriesIdentityDoesNotLoadUnrelatedEpisodes() = runTest {
        val api = mockk<StremioApiService>()
        val dao = mockk<AddonDao>(relaxed = true)
        val recovered = AddonRepository(api, dao).recoverSeriesEpisodes("provider:unknown")
        assertTrue(recovered.isEmpty())
        coVerify(exactly = 0) { api.getMeta(any()) }
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
