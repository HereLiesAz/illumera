package com.hereliesaz.illumera.data.cache

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScreenCacheStoreTest {
    private lateinit var store: ScreenCacheStore

    private fun titles(vararg ids: String) = ids.map { MetaItem(id = it, type = "movie", name = "Title $it") }

    @Before
    fun setUp() {
        store = ScreenCacheStore(ApplicationProvider.getApplicationContext())
    }

    @Test fun savedTitlesComeBackForTheSameAddons() {
        store.save(1, "movies", "sig-a", mapOf("row" to titles("a", "b")))
        val loaded = store.load(1, "movies", maxAgeMs = 60_000L, signature = "sig-a")
        assertEquals(listOf("a", "b"), loaded?.get("row")?.map { it.id })
    }

    @Test fun changedAddonsMeanNothingIsUsed() {
        store.save(1, "series", "sig-a", mapOf("row" to titles("a")))
        assertNull(store.load(1, "series", maxAgeMs = 60_000L, signature = "sig-b"))
    }

    @Test fun tooOldMeansNothingIsUsed() {
        store.save(1, "home", "sig-a", mapOf("row" to titles("a")))
        assertNull(store.load(1, "home", maxAgeMs = -1L, signature = "sig-a"))
    }

    @Test fun profilesAreKeptApart() {
        store.save(1, "movies", "sig-a", mapOf("row" to titles("a")))
        assertNull(store.load(2, "movies", maxAgeMs = 60_000L, signature = "sig-a"))
    }
}
