package com.hereliesaz.illumera.data.queue

import android.content.Context
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.remote.TraktSyncApiService
import com.hereliesaz.illumera.data.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
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
class QueueManagerTest {
    private lateinit var context: Context
    private lateinit var profileManager: ProfileConfigurationManager
    private lateinit var manager: QueueManager
    private var activeProfileId: Int? = 1

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        profileManager = mockk()
        every { profileManager.getLastActiveProfileId() } answers { activeProfileId }
        manager = newManager()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun queueItemStableKeyDistinguishesEpisodeAndWholeShowState() {
        val episode = QueueItem(id = "show:1:2", type = "episode", title = "Episode", season = 1, episode = 2)
        val wholeShow = QueueItem(id = "show", type = "series", title = "Show", wholeShow = true)

        assertEquals("episode:show:1:2:1:2:false", episode.stableKey)
        assertEquals("series:show:::true", wholeShow.stableKey)
    }

    @Test
    fun queueItemConvertsEpisodeToCanonicalSeriesMeta() {
        val item = QueueItem(
            id = "tt123:4:7",
            type = "episode",
            title = "Episode 7",
            poster = "poster"
        )

        val meta = item.toMetaItem()

        assertEquals("tt123", meta.id)
        assertEquals("series", meta.type)
        assertEquals("Episode 7", meta.name)
        assertEquals("poster", meta.poster)
    }

    @Test
    fun explicitSeriesIdWinsWhenConvertingEpisodeToMeta() {
        val item = QueueItem(
            id = "addon-specific-episode-id",
            type = "episode",
            title = "Episode",
            seriesId = "canonical-series"
        )

        assertEquals("canonical-series", item.toMetaItem().id)
    }

    @Test
    fun addDeduplicatesByStableKeyAndForcesManualOrigin() {
        val item = QueueItem(id = "movie", type = "movie", title = "Movie", origin = QueueOrigin.SUGGESTED)

        manager.add(item)
        manager.add(item.copy(title = "Duplicate"))

        assertEquals(1, manager.state.value.manualItems.size)
        assertEquals(QueueOrigin.MANUAL, manager.state.value.manualItems.single().origin)
        assertEquals("Movie", manager.state.value.manualItems.single().title)
    }

    @Test
    fun moveClampsToBoundsAndRemoveUsesStableKey() {
        val one = QueueItem(id = "one", type = "movie", title = "One")
        val two = QueueItem(id = "two", type = "movie", title = "Two")
        val three = QueueItem(id = "three", type = "movie", title = "Three")
        manager.add(one)
        manager.add(two)
        manager.add(three)

        manager.move(three.stableKey, -99)
        assertEquals(listOf("three", "one", "two"), manager.state.value.manualItems.map { it.id })

        manager.move(three.stableKey, 99)
        assertEquals(listOf("one", "two", "three"), manager.state.value.manualItems.map { it.id })

        manager.remove(two.stableKey)
        assertEquals(listOf("one", "three"), manager.state.value.manualItems.map { it.id })
    }

    @Test
    fun preferencesPersistAcrossManagerRecreation() {
        manager.setEnabled(true)
        manager.setIncludeMovies(false)
        manager.setIncludeEpisodes(false)
        manager.setIncludeWholeShows(true)
        manager.setOnlyUnseenSuggestions(true)
        manager.setSuggestionSource(QueueSuggestionSource.TRAKT, false)

        val reloaded = newManager().state.value.preferences

        assertTrue(reloaded.enabled)
        assertFalse(reloaded.includeMovies)
        assertFalse(reloaded.includeEpisodes)
        assertTrue(reloaded.includeWholeShows)
        assertTrue(reloaded.onlyUnseenSuggestions)
        assertEquals(setOf(QueueSuggestionSource.PLAY_HISTORY), reloaded.suggestionSources)
    }

    @Test
    fun manualQueueIsIsolatedAcrossProfileSwitches() {
        manager.add(QueueItem(id = "profile-one", type = "movie", title = "One"))

        activeProfileId = 2
        manager.reloadForActiveProfile()
        assertTrue(manager.state.value.manualItems.isEmpty())
        manager.add(QueueItem(id = "profile-two", type = "movie", title = "Two"))

        activeProfileId = 1
        manager.reloadForActiveProfile()
        assertEquals(listOf("profile-one"), manager.state.value.manualItems.map { it.id })

        activeProfileId = 2
        manager.reloadForActiveProfile()
        assertEquals(listOf("profile-two"), manager.state.value.manualItems.map { it.id })
    }

    @Test
    fun mutationsAreIgnoredWhenNoProfileIsActive() {
        activeProfileId = null
        manager.reloadForActiveProfile()

        manager.setEnabled(true)
        manager.add(QueueItem(id = "movie", type = "movie", title = "Movie"))

        assertFalse(manager.state.value.preferences.enabled)
        assertTrue(manager.state.value.manualItems.isEmpty())
    }

    @Test
    fun advanceAfterPlaybackRequiresEnabledQueue() {
        val first = QueueItem(id = "one", type = "movie", title = "One")
        val second = QueueItem(id = "two", type = "movie", title = "Two")
        manager.add(first)
        manager.add(second)

        assertNull(manager.advanceAfterPlayback("one"))
        assertEquals(2, manager.state.value.manualItems.size)

        manager.setEnabled(true)
        assertEquals("two", manager.advanceAfterPlayback("one")?.id)
        assertEquals(listOf("two"), manager.state.value.manualItems.map { it.id })
    }

    @Test
    fun advanceMatchesEpisodeBySeasonEpisodeSuffix() {
        manager.setEnabled(true)
        val episode = QueueItem(
            id = "addon-episode-id",
            type = "episode",
            title = "Episode",
            season = 2,
            episode = 5
        )
        val next = QueueItem(id = "next", type = "movie", title = "Next")
        manager.add(episode)
        manager.add(next)

        assertEquals("next", manager.advanceAfterPlayback("canonical-show:2:5")?.id)
        assertEquals(listOf("next"), manager.state.value.manualItems.map { it.id })
    }

    @Test
    fun cancelledSuggestionRefreshPropagatesAndClearsRefreshingState() = runTest {
        val traktApi = mockk<TraktSyncApiService>(relaxed = true)
        coEvery { traktApi.getMovieRecommendations(any()) } throws
            CancellationException("cancel refresh")
        val queue = newManager(traktApi = traktApi)
        queue.setEnabled(true)

        try {
            queue.refreshSuggestions()
            throw AssertionError("Expected CancellationException")
        } catch (_: CancellationException) {
            // Expected: cancellation remains control flow, not a failed network request.
        }

        assertFalse(queue.state.value.isRefreshingSuggestions)
    }

    @Test
    fun clearForProfileRemovesPersistedQueueWithoutTouchingOtherProfile() {
        manager.add(QueueItem(id = "one", type = "movie", title = "One"))
        activeProfileId = 2
        manager.reloadForActiveProfile()
        manager.add(QueueItem(id = "two", type = "movie", title = "Two"))

        manager.clearForProfile(1)

        activeProfileId = 1
        manager.reloadForActiveProfile()
        assertTrue(manager.state.value.manualItems.isEmpty())
        activeProfileId = 2
        manager.reloadForActiveProfile()
        assertEquals(listOf("two"), manager.state.value.manualItems.map { it.id })
    }

    private fun newManager(
        addonDao: AddonDao = mockk(relaxed = true),
        traktApi: TraktSyncApiService = mockk(relaxed = true),
        repository: AddonRepository = mockk(relaxed = true)
    ) = QueueManager(
        context = context,
        addonDao = addonDao,
        traktApi = traktApi,
        repository = repository,
        profileConfigurationManager = profileManager
    )

    companion object {
        private const val PREFS_FILE = "illumera_queue"
    }
}
