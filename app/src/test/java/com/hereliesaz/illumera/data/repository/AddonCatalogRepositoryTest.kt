package com.hereliesaz.illumera.data.repository

import android.content.Context
import com.google.gson.JsonParser
import com.hereliesaz.illumera.data.model.stremio.AddonCatalogSource
import com.hereliesaz.illumera.data.model.stremio.SavedAddonCollection
import com.hereliesaz.illumera.data.remote.StremioApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Hard-coded repository fixtures and expected URLs, independent of production filtering. */
@RunWith(RobolectricTestRunner::class)
class AddonCatalogRepositoryTest {
    private val sourceA = "https://catalog-a.example/collection.json"
    private val sourceB = "https://catalog-b.example/collection.json"
    private lateinit var context: Context
    private lateinit var api: StremioApiService

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("illumera_addon_catalogs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("illumera_addon_catalogs", Context.MODE_PRIVATE)
            .edit().putString(
                "saved_collections",
                """[{"name":"Alpha","url":"https://catalog-a.example/collection.json"},{"name":"Beta","url":"https://catalog-b.example/collection.json"}]"""
            ).commit()
        api = mockk()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("illumera_addon_catalogs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun repository() = AddonCatalogRepository(context, api)

    @Test
    fun savedLegacyRepositoriesRemainEnabledWithoutMigration() {
        val repos = repository().repositories.value

        assertEquals(listOf(sourceA, sourceB), repos.enabledCollections.map { it.url })
        assertEquals(
            listOf(
                AddonCatalogSource.OFFICIAL,
                AddonCatalogSource.COMMUNITY,
                AddonCatalogSource.STREMIO_ADDONS_NET,
                AddonCatalogSource.AIOMETADATA,
                AddonCatalogSource.GITHUB_STREMIO_ADDONS
            ),
            repos.enabledBuiltIns
        )
    }

    @Test
    fun disablingPreservesSavedRepositoryAndSurvivesRecreation() {
        val first = repository()
        first.setCollectionEnabled(sourceB, false)

        assertEquals(listOf(sourceA, sourceB), first.repositories.value.collections.map { it.url })
        assertEquals(listOf(sourceA), first.repositories.value.enabledCollections.map { it.url })
        assertEquals(listOf(sourceA), repository().repositories.value.enabledCollections.map { it.url })

        first.setCollectionEnabled(sourceB, true)
        assertEquals(listOf(sourceA, sourceB), repository().repositories.value.enabledCollections.map { it.url })
    }

    @Test
    fun combinedCatalogNeverFetchesDisabledCustomRepository() = runTest {
        val catalog = repository()
        catalog.setBuiltInEnabled(AddonCatalogSource.OFFICIAL, false)
        catalog.setBuiltInEnabled(AddonCatalogSource.COMMUNITY, false)
        catalog.setBuiltInEnabled(AddonCatalogSource.STREMIO_ADDONS_NET, false)
        catalog.setBuiltInEnabled(AddonCatalogSource.AIOMETADATA, false)
        catalog.setBuiltInEnabled(AddonCatalogSource.GITHUB_STREMIO_ADDONS, false)
        catalog.setCollectionEnabled(sourceB, false)

        coEvery { api.getJson(sourceA) } returns JsonParser.parseString(
            """{"name":"Alpha","addons":[{"transportUrl":"https://addon-a.example/manifest.json","manifest":{"id":"addon-a","name":"Addon A","version":"1.0.0"}}]}"""
        )

        val items = catalog.fetch(AddonCatalogSource.ALL)

        assertEquals(listOf("https://addon-a.example/manifest.json"), items.map { it.transportUrl })
        coVerify(exactly = 1) { api.getJson(sourceA) }
        coVerify(exactly = 0) { api.getJson(sourceB) }
    }

    @Test
    fun disabledRepositoryCannotBeBrowsedDirectly() = runTest {
        val catalog = repository()
        catalog.setCollectionEnabled(sourceB, false)

        var rejected = false
        try {
            catalog.fetch(AddonCatalogSource.COLLECTION, SavedAddonCollection("Beta", sourceB))
        } catch (e: IllegalStateException) {
            rejected = e.message.orEmpty().contains("disabled")
        }

        assertTrue(rejected)
        coVerify(exactly = 0) { api.getJson(sourceB) }
    }

    @Test
    fun readdingDisabledRepositoryEnablesItAndKeepsItsPosition() = runTest {
        val catalog = repository()
        catalog.setCollectionEnabled(sourceB, false)
        coEvery { api.getJson(sourceB) } returns JsonParser.parseString(
            """{"name":"Beta refreshed","addons":[{"transportUrl":"https://addon-b.example/manifest.json","manifest":{"id":"addon-b","name":"Addon B","version":"1.0.0"}}]}"""
        )

        catalog.addCollection(sourceB)

        assertEquals(listOf(sourceA, sourceB), catalog.repositories.value.enabledCollections.map { it.url })
        assertEquals(listOf("Alpha", "Beta refreshed"), catalog.repositories.value.collections.map { it.name })
        assertEquals(listOf(sourceA, sourceB), repository().repositories.value.enabledCollections.map { it.url })
    }

    @Test
    fun movingCustomRepositoryPersistsOrderEvenWhenItIsDisabled() {
        val catalog = repository()
        catalog.setCollectionEnabled(sourceB, false)

        catalog.moveCollection(sourceB, -1)

        assertEquals(listOf(sourceB, sourceA), catalog.repositories.value.collections.map { it.url })
        assertEquals(listOf(sourceA), catalog.repositories.value.enabledCollections.map { it.url })
        assertEquals(listOf(sourceB, sourceA), repository().repositories.value.collections.map { it.url })
    }

    @Test
    fun aiometadataProvidesSetupLinkNotUnconfiguredInstall() = runTest {
        val item = repository().fetch(AddonCatalogSource.AIOMETADATA).single()

        assertEquals("AIOMetadata (ElfHosted)", item.manifest.name)
        assertEquals("https://aiometadata.elfhosted.com/configure", item.discoveryUrl)
        assertFalse(item.isModernManifestTransport)
    }

    @Test
    fun githubTopicListsValidProjectsAsLinksWithoutFabricatedManifests() = runTest {
        coEvery { api.getJson(match { it.startsWith("https://api.github.com/search/repositories") }) } returns
            JsonParser.parseString(
                """{"items":[{"full_name":"owner/addon","html_url":"https://github.com/owner/addon","description":"Source code","archived":false},{"full_name":"owner/old","html_url":"https://github.com/owner/old","archived":true},{"full_name":"owner/redirect","html_url":"https://not-github.example/redirect","archived":false}]}"""
            )

        val items = repository().fetch(AddonCatalogSource.GITHUB_STREMIO_ADDONS)

        assertEquals(2, items.size)
        assertEquals("https://github.com/topics/stremio-addon", items[0].discoveryUrl)
        assertEquals("owner/addon", items[1].manifest.name)
        assertEquals("https://github.com/owner/addon", items[1].discoveryUrl)
        assertFalse(items[1].isModernManifestTransport)
    }

    @Test
    fun githubTopicRemainsBrowsableWhenSearchApiFails() = runTest {
        coEvery { api.getJson(match { it.startsWith("https://api.github.com/search/repositories") }) } throws
            IllegalStateException("Rate limited")

        val items = repository().fetch(AddonCatalogSource.GITHUB_STREMIO_ADDONS)

        assertEquals(1, items.size)
        assertEquals("https://github.com/topics/stremio-addon", items.single().discoveryUrl)
    }

    @Test
    fun removingDisabledRepositoryClearsItsDisabledMarker() {
        val catalog = repository()
        catalog.setCollectionEnabled(sourceB, false)
        catalog.removeCollection(sourceB)

        assertEquals(listOf(sourceA), catalog.repositories.value.collections.map { it.url })
        assertFalse(sourceB in catalog.repositories.value.disabledCollectionUrls)
        assertTrue(sourceA in catalog.repositories.value.enabledCollections.map { it.url })
    }
}
