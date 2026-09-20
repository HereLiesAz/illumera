package com.hereliesaz.illumera.data.player

import android.content.Context
import com.hereliesaz.illumera.data.model.stremio.Stream
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SourceSelectionStoreTest {
    private lateinit var context: Context
    private lateinit var profileManager: ProfileConfigurationManager
    private lateinit var store: SourceSelectionStore
    private var activeProfileId: Int? = 1

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        profileManager = mockk()
        every { profileManager.getLastActiveProfileId() } answers { activeProfileId }
        store = SourceSelectionStore(context, profileManager)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun exactRememberedStreamWinsWhenFingerprintMatches() {
        val remembered = Stream(
            name = "[Torrentio] 1080p",
            title = "Movie",
            url = "https://example.test/video",
            infoHash = "ABC123",
            fileIdx = 4
        )
        store.rememberSelection("movie", remembered)

        val equivalent = remembered.copy(
            name = " [TORRENTIO] 1080p ",
            url = " https://example.test/video ",
            infoHash = " abc123 "
        )
        val other = Stream(name = "[Torrentio] 720p", url = "https://example.test/other")

        assertEquals(equivalent, store.findPreferredStream("movie", listOf(other, equivalent)))
    }

    @Test
    fun addonTagProvidesFallbackWhenExactStreamDisappears() {
        store.rememberSelection(
            "movie",
            Stream(name = "[Torrentio] 1080p", url = "https://example.test/old")
        )

        val replacement = Stream(name = "[torrentio] 720p", url = "https://example.test/new")
        val unrelated = Stream(name = "[MediaFusion] 1080p", url = "https://example.test/other")

        assertEquals(replacement, store.findPreferredStream("movie", listOf(unrelated, replacement)))
    }

    @Test
    fun nonPlayableCandidatesAreNeverReturned() {
        store.rememberSelection(
            "movie",
            Stream(name = "[Torrentio] 1080p", url = "https://example.test/old")
        )

        val matchingButUnplayable = Stream(name = "[Torrentio] 720p")

        assertNull(store.findPreferredStream("movie", listOf(matchingButUnplayable)))
    }

    @Test
    fun completelyEmptyStreamIsNotRemembered() {
        store.rememberSelection("movie", Stream())

        assertFalse(store.hasRememberedSelection("movie"))
    }

    @Test
    fun clearSelectionRemovesFingerprintAndAddonFallback() {
        store.rememberSelection(
            "movie",
            Stream(name = "[Torrentio] 1080p", url = "https://example.test/old")
        )
        assertTrue(store.hasRememberedSelection("movie"))

        store.clearSelection("movie")

        assertFalse(store.hasRememberedSelection("movie"))
        assertNull(
            store.findPreferredStream(
                "movie",
                listOf(Stream(name = "[Torrentio] 720p", url = "https://example.test/new"))
            )
        )
    }

    @Test
    fun sourceListDisabledAndExcludedSourcesRoundTrip() {
        store.rememberSourceListDisabled("movie", true)
        store.rememberExcludedSources("movie", setOf("source-a", "source-b"))

        assertTrue(store.isSourceListDisabled("movie"))
        assertEquals(setOf("source-a", "source-b"), store.getExcludedSources("movie"))

        store.rememberSourceListDisabled("movie", false)
        store.rememberExcludedSources("movie", emptySet())

        assertFalse(store.isSourceListDisabled("movie"))
        assertTrue(store.getExcludedSources("movie").isEmpty())
    }

    @Test
    fun rememberedSelectionsAreProfileScoped() {
        activeProfileId = 1
        store.rememberSelection("movie", Stream(name = "[One] 1080p", url = "one"))

        activeProfileId = 2
        assertFalse(store.hasRememberedSelection("movie"))
        store.rememberSelection("movie", Stream(name = "[Two] 1080p", url = "two"))
        assertEquals("two", store.findPreferredStream("movie", listOf(Stream(name = "[Two] 1080p", url = "two")))?.url)

        activeProfileId = 1
        assertEquals("one", store.findPreferredStream("movie", listOf(Stream(name = "[One] 1080p", url = "one")))?.url)
    }

    @Test
    fun noActiveProfileDoesNotReadOrWriteProfileOneSourceMemory() {
        activeProfileId = null
        store.rememberSelection("movie", Stream(name = "[Default]", url = "default"))
        store.rememberSourceListDisabled("movie", true)
        store.rememberExcludedSources("movie", setOf("source-a"))

        assertFalse(store.hasRememberedSelection("movie"))
        assertFalse(store.isSourceListDisabled("movie"))
        assertTrue(store.getExcludedSources("movie").isEmpty())

        activeProfileId = 1
        assertFalse(store.hasRememberedSelection("movie"))
        assertFalse(store.isSourceListDisabled("movie"))
        assertTrue(store.getExcludedSources("movie").isEmpty())
    }

    @Test
    fun clearSelectionsForPrefixRemovesOnlyRememberedSourceIdentity() {
        store.rememberSelection("show:1:1", Stream(name = "[A]", url = "one"))
        store.rememberSelection("show:1:2", Stream(name = "[A]", url = "two"))
        store.rememberSelection("other:1:1", Stream(name = "[B]", url = "other"))
        store.rememberSourceListDisabled("show:1:1", true)
        store.rememberExcludedSources("show:1:1", setOf("x"))

        store.clearSelectionsForPrefix("show:")

        assertFalse(store.hasRememberedSelection("show:1:1"))
        assertFalse(store.hasRememberedSelection("show:1:2"))
        assertTrue(store.hasRememberedSelection("other:1:1"))
        assertTrue(store.isSourceListDisabled("show:1:1"))
        assertEquals(setOf("x"), store.getExcludedSources("show:1:1"))
    }


    @Test
    fun addonProvenanceProvidesFallbackWithoutMutatingStreamName() {
        store.rememberSelection(
            "movie",
            Stream(
                name = "1080p",
                url = "https://example.test/old",
                addonTransportUrl = "https://torrentio.example",
                addonDisplayName = "Torrentio"
            )
        )

        val replacement = Stream(
            name = "720p",
            url = "https://example.test/new",
            addonTransportUrl = "https://torrentio.example",
            addonDisplayName = "Torrentio"
        )
        val unrelated = Stream(
            name = "1080p",
            url = "https://example.test/other",
            addonTransportUrl = "https://mediafusion.example",
            addonDisplayName = "MediaFusion"
        )

        assertEquals(replacement, store.findPreferredStream("movie", listOf(unrelated, replacement)))
    }

    companion object {
        private const val PREFS_FILE = "source_selection_prefs"
    }
}
