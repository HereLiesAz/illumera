package com.hereliesaz.illumera.data.player

import android.content.Context
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EpisodeBrowseStoreTest {
    private lateinit var context: Context
    private lateinit var profileManager: ProfileConfigurationManager
    private lateinit var store: EpisodeBrowseStore
    private var activeProfileId: Int? = 1

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        profileManager = mockk()
        every { profileManager.getLastActiveProfileId() } answers { activeProfileId }
        store = EpisodeBrowseStore(context, profileManager)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun rememberAndRestoreEpisodeRoundTrips() {
        store.rememberEpisode("tt123", season = 3, episode = 7)

        assertEquals(":3:7", store.getRememberedEpisodeSuffix("tt123"))
    }

    @Test
    fun seriesIdIsTrimmedForStorageAndLookup() {
        store.rememberEpisode("  tt123  ", season = 2, episode = 4)

        assertEquals(":2:4", store.getRememberedEpisodeSuffix("tt123"))
        assertEquals(":2:4", store.getRememberedEpisodeSuffix("  tt123 "))
    }

    @Test
    fun blankSeriesIdAndNonPositiveSeasonAreIgnored() {
        store.rememberEpisode("   ", season = 1, episode = 1)
        store.rememberEpisode("show", season = 0, episode = 1)
        store.rememberEpisode("show2", season = -1, episode = 1)

        assertNull(store.getRememberedEpisodeSuffix(""))
        assertNull(store.getRememberedEpisodeSuffix("show"))
        assertNull(store.getRememberedEpisodeSuffix("show2"))
    }

    @Test
    fun malformedStoredValuesFailClosed() {
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

        listOf("bad", "1:2:3", "x:2", "1:x").forEach { malformed ->
            prefs.edit().putString("p1:series_show", malformed).commit()
            assertNull("Expected '$malformed' to be rejected", store.getRememberedEpisodeSuffix("show"))
        }
    }

    @Test
    fun rememberedEpisodeIsIsolatedByProfile() {
        activeProfileId = 1
        store.rememberEpisode("show", season = 1, episode = 2)

        activeProfileId = 2
        assertNull(store.getRememberedEpisodeSuffix("show"))
        store.rememberEpisode("show", season = 3, episode = 4)
        assertEquals(":3:4", store.getRememberedEpisodeSuffix("show"))

        activeProfileId = 1
        assertEquals(":1:2", store.getRememberedEpisodeSuffix("show"))
    }

    @Test
    fun noActiveProfileDoesNotReadOrWriteEpisodeMemory() {
        activeProfileId = null
        store.rememberEpisode("show", season = 5, episode = 6)
        assertNull(store.getRememberedEpisodeSuffix("show"))

        activeProfileId = 1
        assertNull(store.getRememberedEpisodeSuffix("show"))
    }

    @Test
    fun episodeNumberMayBeZeroBecauseOnlySeasonIsValidated() {
        store.rememberEpisode("show", season = 1, episode = 0)

        assertEquals(":1:0", store.getRememberedEpisodeSuffix("show"))
    }

    companion object {
        private const val PREFS_FILE = "episode_browse_prefs"
    }
}
