package com.hereliesaz.illumera.data.queue

import android.content.Context
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.WatchHistoryEntity
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.remote.TraktSyncApiService
import com.hereliesaz.illumera.data.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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
    fun disablingASuggestionSourceInvalidatesSavedRecommendationsBeforeLeavingSettings() = runTest {
        val addonDao = mockk<AddonDao>(relaxed = true)
        coEvery { addonDao.getAllWatchHistoryOnce() } returns historyOf("tt-a", "tt-b")
        val queue = newManager(addonDao = addonDao)
        queue.setEnabled(true)
        queue.setSuggestionSource(QueueSuggestionSource.TRAKT, false)
        queue.refreshSuggestions()
        assertEquals(setOf("tt-a", "tt-b"), queue.state.value.suggestions.map { it.id }.toSet())

        // The queue may already be full, and the Settings composable may
        // disappear before its own refresh coroutine finishes.
        queue.setSuggestionSource(QueueSuggestionSource.PLAY_HISTORY, false)

        assertTrue(queue.state.value.suggestions.isEmpty())
        assertTrue(newManager(addonDao = addonDao).state.value.suggestions.isEmpty())
        assertTrue(newManager(addonDao = addonDao).state.value.preferences.suggestionSources.isEmpty())
    }

    @Test
    fun changingAllowedContentTypesInvalidatesPriorSuggestionsButNotManualQueue() = runTest {
        val addonDao = mockk<AddonDao>(relaxed = true)
        coEvery { addonDao.getAllWatchHistoryOnce() } returns historyOf("tt-a")
        val queue = newManager(addonDao = addonDao)
        queue.setEnabled(true)
        queue.setSuggestionSource(QueueSuggestionSource.TRAKT, false)
        queue.add(QueueItem(id = "tt-manual", type = "movie", title = "Manual"))
        queue.refreshSuggestions()
        assertEquals(listOf("tt-a"), queue.state.value.suggestions.map { it.id })

        queue.setIncludeMovies(false)

        assertTrue(queue.state.value.suggestions.isEmpty())
        assertEquals(listOf("tt-manual"), queue.state.value.manualItems.map { it.id })
        assertFalse(queue.state.value.preferences.includeMovies)
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
            seriesId = "canonical-show",
            season = 2,
            episode = 5
        )
        val next = QueueItem(id = "next", type = "movie", title = "Next")
        manager.add(episode)
        manager.add(next)

        // Same season/episode numbers in a different show must not consume the item.
        manager.advanceAfterPlayback("other-show:2:5")
        assertEquals(listOf("addon-episode-id", "next"), manager.state.value.manualItems.map { it.id })

        assertEquals("next", manager.advanceAfterPlayback("canonical-show:2:5")?.id)
        assertEquals(listOf("next"), manager.state.value.manualItems.map { it.id })
    }

    @Test
    fun advanceUsesUpAShowOnceOneOfItsEpisodesFinishes() {
        manager.setEnabled(true)
        manager.add(QueueItem(id = "tt1", type = "series", title = "Show"))
        manager.add(QueueItem(id = "tt10", type = "series", title = "Other show"))

        assertEquals("tt10", manager.advanceAfterPlayback("tt1:1:3")?.id)
        assertEquals(listOf("tt10"), manager.state.value.manualItems.map { it.id })
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
    fun newerSuggestionRefreshSupersedesTheOneStillRunning() = runTest {
        val addonDao = mockk<AddonDao>(relaxed = true)
        val firstGate = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { addonDao.getAllWatchHistoryOnce() } coAnswers {
            if (++calls == 1) {
                firstGate.await()
                listOf(WatchHistoryEntity(id = "tt-old", title = "Old", poster = null, position = 1, duration = 100, lastWatched = 1, type = "movie"))
            } else {
                listOf(WatchHistoryEntity(id = "tt-new", title = "New", poster = null, position = 1, duration = 100, lastWatched = 1, type = "movie"))
            }
        }
        val queue = newManager(addonDao = addonDao)
        queue.setEnabled(true)

        val first = launch { queue.refreshSuggestions() }
        runCurrent()
        assertTrue(queue.state.value.isRefreshingSuggestions)

        queue.refreshSuggestions()
        firstGate.complete(Unit)
        first.join()

        // The first call ended quietly (not cancelled from outside) and never wrote its result.
        assertFalse(first.isCancelled)
        assertEquals(listOf("tt-new"), queue.state.value.suggestions.map { it.id })
        assertFalse(queue.state.value.isRefreshingSuggestions)
    }

    @Test
    fun onlyUnseenSuggestionsDropsFinishedTitlesButKeepsUnfinishedOnes() = runTest {
        val addonDao = mockk<AddonDao>(relaxed = true)
        coEvery { addonDao.getAllWatchHistoryOnce() } returns listOf(
            WatchHistoryEntity(id = "tt-finished", title = "Finished", poster = null, position = 100, duration = 100, lastWatched = 3, type = "movie", watched = true),
            WatchHistoryEntity(id = "tt-started", title = "Started", poster = null, position = 10, duration = 100, lastWatched = 2, type = "movie"),
            WatchHistoryEntity(id = "tt-show:1:2", title = "Show", poster = null, position = 10, duration = 100, lastWatched = 1, type = "series"),
        )
        val queue = newManager(addonDao = addonDao)
        queue.setEnabled(true)
        queue.setOnlyUnseenSuggestions(true)

        queue.refreshSuggestions()

        assertEquals(setOf("tt-started", "tt-show:1:2"), queue.state.value.suggestions.map { it.id }.toSet())
    }

    @Test
    fun finishedEpisodeDoesNotExcludeAShowWithAnUnfinishedEpisode() = runTest {
        val addonDao = mockk<AddonDao>(relaxed = true)
        coEvery { addonDao.getAllWatchHistoryOnce() } returns listOf(
            WatchHistoryEntity(id = "tt-show:1:1", title = "Show", poster = null, seriesId = "tt-show", position = 100, duration = 100, lastWatched = 2, type = "series", watched = true),
            WatchHistoryEntity(id = "tt-show:1:2", title = "Show", poster = null, seriesId = "tt-show", position = 10, duration = 100, lastWatched = 1, type = "series"),
        )
        val queue = newManager(addonDao = addonDao)
        queue.setEnabled(true)

        queue.refreshSuggestions()

        assertTrue("tt-show:1:2" in queue.state.value.suggestions.map { it.id })
    }

    private fun historyOf(vararg ids: String): List<WatchHistoryEntity> = ids.mapIndexed { index, id ->
        WatchHistoryEntity(id = id, title = id, poster = null, position = 1, duration = 100, lastWatched = index.toLong(), type = "movie")
    }

    @Test
    fun removedSuggestionStaysGoneWhenTheViewerRefreshes() = runTest {
        val addonDao = mockk<AddonDao>(relaxed = true)
        coEvery { addonDao.getAllWatchHistoryOnce() } returns historyOf("tt-a", "tt-b")
        val queue = newManager(addonDao = addonDao)
        queue.setEnabled(true)
        queue.refreshSuggestions()
        val removed = queue.state.value.suggestions.first { it.id == "tt-a" }

        queue.removeSuggestion(removed.stableKey)
        queue.refreshSuggestions(rotate = true)

        assertEquals(listOf("tt-b"), queue.state.value.suggestions.map { it.id })
    }

    @Test
    fun removedManualItemDoesNotComeBackAsASuggestion() = runTest {
        val addonDao = mockk<AddonDao>(relaxed = true)
        coEvery { addonDao.getAllWatchHistoryOnce() } returns historyOf("tt-a", "tt-b")
        val queue = newManager(addonDao = addonDao)
        queue.setEnabled(true)
        val item = QueueItem(id = "tt-a", type = "movie", title = "tt-a")
        queue.add(item)

        queue.remove(item.stableKey)
        queue.refreshSuggestions(rotate = true)

        assertFalse(queue.state.value.suggestions.any { it.id == "tt-a" })
    }

    @Test
    fun viewerRefreshBringsInTitlesNotAlreadyShowing() = runTest {
        val addonDao = mockk<AddonDao>(relaxed = true)
        val ids = (1..14).map { "tt-$it" }.toTypedArray()
        coEvery { addonDao.getAllWatchHistoryOnce() } returns historyOf(*ids)
        val queue = newManager(addonDao = addonDao)
        queue.setEnabled(true)
        queue.refreshSuggestions()
        val before = queue.state.value.suggestions.map { it.id }.toSet()
        val notShown = ids.toSet() - before

        queue.refreshSuggestions(rotate = true)

        val after = queue.state.value.suggestions.map { it.id }.toSet()
        assertTrue("every title not shown before is shown now", after.containsAll(notShown))
        assertEquals(before.size, after.size)
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
