package com.hereliesaz.illumera.ui.details

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.data.model.stremio.MetaVideo
import com.hereliesaz.illumera.data.model.stremio.Stream
import com.hereliesaz.illumera.data.model.StreamQuality
import com.hereliesaz.illumera.data.player.EpisodeBrowseStore
import com.hereliesaz.illumera.data.player.PlaybackTrackSelectionStore
import com.hereliesaz.illumera.data.player.SourceSelectionStore
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.repository.AddonRepository
import com.hereliesaz.illumera.data.repository.SubtitleRepository
import com.hereliesaz.illumera.data.stream.StreamSortingService
import com.hereliesaz.illumera.data.tmdb.TmdbEnrichment
import com.hereliesaz.illumera.data.tmdb.TmdbEpisodeEnrichment
import com.hereliesaz.illumera.data.tmdb.TmdbMetaPreview
import com.hereliesaz.illumera.data.tmdb.TmdbMetadataService
import com.hereliesaz.illumera.data.tmdb.TmdbService
import com.hereliesaz.illumera.data.tmdb.TmdbVideoInfo
import com.hereliesaz.illumera.domain.AddonSubtitle
import com.hereliesaz.illumera.domain.canonicalEpisodeStreamId
import com.hereliesaz.illumera.domain.episodeStreamId
import com.hereliesaz.illumera.domain.hasAired
import com.hereliesaz.illumera.data.trakt.TraktSyncManager
import com.hereliesaz.illumera.data.wutch.WutchManager
import dagger.hilt.android.lifecycle.HiltViewModel
import com.hereliesaz.illumera.data.model.SeriesNextUpEntity
import com.hereliesaz.illumera.data.model.WatchHistoryEntity
import com.hereliesaz.illumera.data.model.WatchlistEntity
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// How long a fetched stream list is served instantly (with a background refresh kicked off
// alongside it) before a re-open of the sources sidebar has to wait on a fresh fetch again.
private const val STREAMS_CACHE_TTL_MS = 3 * 60_000L
private const val KEY_QUEUE_AUTOPLAY_CONSUMED = "queueAutoPlayConsumed"

@HiltViewModel
class DetailsViewModel @Inject constructor(
    private val dao: AddonDao,
    private val sourceSelectionStore: SourceSelectionStore,
    private val episodeBrowseStore: EpisodeBrowseStore,
    private val playbackTrackSelectionStore: PlaybackTrackSelectionStore,
    private val repository: AddonRepository,
    private val subtitleRepository: SubtitleRepository,
    private val profileConfigurationManager: ProfileConfigurationManager,
    private val streamSortingService: StreamSortingService,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val traktSyncManager: TraktSyncManager,
    private val wutchManager: WutchManager,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    /**
     * The queue auto-play id this page already started. Saved with the page's entry, so
     * coming back to it (from the player, or after process death) doesn't start it again.
     */
    val consumedQueueAutoPlayId: StateFlow<String?> =
        savedStateHandle.getStateFlow(KEY_QUEUE_AUTOPLAY_CONSUMED, null)

    fun consumeQueueAutoPlay(id: String) {
        savedStateHandle[KEY_QUEUE_AUTOPLAY_CONSUMED] = id
    }

    /** Per-episode watch progress for the episodes sidebar. */
    data class EpisodeProgress(
        val progress: Float,  // 0.0–1.0
        val watched: Boolean
    )

    data class DetailsState(
        val meta: MetaItem? = null,
        val resolvedId: String? = null, // IMDb ID resolved from tmdb: prefixes, used for stream/subtitle fetching
        val contentKey: String? = null, // Tracks which item this state belongs to
        val isLoading: Boolean = true,
        val isLoadingStreams: Boolean = false,
        val resumePlaybackId: String? = null,
        val isResumeStateReady: Boolean = false,
        val isMovieWatched: Boolean = false,
        val autoPlayStream: Stream? = null,
        val addonSubtitles: List<AddonSubtitle> = emptyList(),
        val availableStreams: List<Stream> = emptyList(),
        val activeStreamMediaType: String? = null,
        val activeStreamRequestId: String? = null,
        val sidebarState: SidebarState = SidebarState.Closed,
        val episodeProgressMap: Map<String, EpisodeProgress> = emptyMap(), // "S1:E3" → progress
        val episodeEnrichmentMap: Map<String, TmdbEpisodeEnrichment> = emptyMap(), // "S1:E3" → TMDB data
        // Per-video source picker state — scoped to the video currently shown in the sidebar.
        val activeSourceSelectionId: String? = null,
        val sourceListDisabled: Boolean = false,
        val excludedSourceIds: Set<String> = emptySet(),
        // TMDB enrichment
        val tmdbEnabled: Boolean = false,
        val tmdbLoading: Boolean = false,
        val tmdbEnrichment: TmdbEnrichment? = null,
        val tmdbRecommendations: List<TmdbMetaPreview> = emptyList(),
        val tmdbTrailer: TmdbVideoInfo? = null,
        val tmdbCollection: List<TmdbMetaPreview> = emptyList(),
        val tmdbCollectionName: String? = null
    ) {
        /**
         * The id this page builds stream and episode playback ids from: the resolved IMDb id
         * behind a tmdb: id, else the loaded title's id, else [pageId] (the page's own key id).
         * Playback started here uses it as the show id, so next-episode and progress ids match.
         */
        fun streamId(pageId: String): String = resolvedId ?: meta?.id ?: pageId
    }

    /** Per-addon results of the latest stream request, shown in the source panel. */
    val streamOutcomes = repository.lastStreamOutcomes

    private val _state = MutableStateFlow(DetailsState())
    val state: StateFlow<DetailsState> = _state

    /** Reactive watchlist status — emits true/false as the current item's watchlist state changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val isInWatchlist: StateFlow<Boolean> = _state
        .map { it.resolvedId ?: it.meta?.id }
        .flatMapLatest { id -> if (id != null) dao.isInWatchlistFlow(profileId, id) else flowOf(false) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), false)

    private val profileId: Int
        get() = profileConfigurationManager.getLastActiveProfileId() ?: 1

    private var episodeMetadataRetryJob: Job? = null
    private var loadDetailsJob: Job? = null
    private var loadStreamsJob: Job? = null
    private var sourceSelectionJob: Job? = null
    private var tmdbEnrichmentJob: Job? = null
    private var loadRequestVersion: Long = 0L
    private var loadedContentKey: String? = null
    // Set only after every stage of loadDetails finished. loadedContentKey is set as soon as meta
    // arrives, so a load cut short (backing out mid-load) must not count as complete on reopen.
    private var fullyLoadedKey: String? = null

    private data class StreamsCacheEntry(
        val streams: List<Stream>,
        val subtitles: List<AddonSubtitle>,
        val fetchedAt: Long
    )

    // Streams cache, keyed by "$type:$id" — serves an open of the sources sidebar instantly
    // while STREAMS_CACHE_TTL_MS is still fresh, and is always refreshed in the background
    // whenever the sidebar is opened so the served list doesn't go stale.
    private val streamsCache = mutableMapOf<String, StreamsCacheEntry>()
    private var prefetchStreamsJob: Job? = null
    private var prefetchedStreamKey: String? = null
    private var backgroundRefreshJob: Job? = null


    fun loadDetails(type: String, id: String, addonBaseUrl: String? = null) {
        val requestKey = "$type:$id:${addonBaseUrl.orEmpty()}"

        // Keep current details when reopening the same item (e.g., returning from player).
        if (
            loadedContentKey == requestKey &&
            fullyLoadedKey == requestKey &&
            tmdbEnrichmentJob?.isCancelled != true &&
            _state.value.meta != null &&
            !_state.value.isLoading
        ) {
            refreshResumeStateIfNeeded(_state.value.meta)
            if (_state.value.sidebarState !is SidebarState.Closed) {
                _state.update { it.copy(sidebarState = SidebarState.Closed) }
            }
            return
        }

        loadDetailsJob?.cancel()
        episodeMetadataRetryJob?.cancel()
        // A different item is loading: stop all in-flight work for the previous one so it
        // cannot patch the new item's state.
        loadStreamsJob?.cancel()
        loadStreamsJob = null
        sourceSelectionJob?.cancel()
        sourceSelectionJob = null
        backgroundRefreshJob?.cancel()
        backgroundRefreshJob = null
        prefetchStreamsJob?.cancel()
        prefetchStreamsJob = null
        tmdbEnrichmentJob?.cancel()
        tmdbEnrichmentJob = null
        fullyLoadedKey = null
        loadRequestVersion += 1
        val requestVersion = loadRequestVersion

        // Reset immediately so previous movie details never flash for a new item.
        _state.value = DetailsState(
            isLoading = true,
            resumePlaybackId = null,
            autoPlayStream = null,
            addonSubtitles = emptyList(),
            availableStreams = emptyList(),
            sidebarState = SidebarState.Closed
        )

        loadDetailsJob = viewModelScope.launch {
            try {
                // Preserve the ID an addon actually returned. Cross-provider canonicalization is
                // only a fallback for items without an addon origin; rewriting a configured
                // addon's ID before calling its meta/stream handlers breaks custom idPrefixes.
                val isTmdbEnabled = profileConfigurationManager.getLastActiveProfileId()
                    ?.let { dao.getProfileById(it) }?.tmdbEnabled == true
                val canonicalFallbackId = if (isTmdbEnabled && id.startsWith("tmdb:", ignoreCase = true)) {
                    val tmdbNumericId = id.substringAfter(':').substringBefore(':').toIntOrNull()
                    val mediaType = tmdbService.normalizeMediaType(type)
                    tmdbNumericId?.let { tmdbService.tmdbToImdb(it, mediaType) } ?: id
                } else id
                val preferredId = if (!addonBaseUrl.isNullOrBlank()) id else canonicalFallbackId

                var details = if (!addonBaseUrl.isNullOrBlank() && preferredId != canonicalFallbackId) {
                    // The origin addon gets one fast chance with its private catalog ID.
                    // If that misses, fan out immediately with the canonical ID instead of
                    // spending every addon timeout on an ID they cannot understand.
                    repository.resolvePreferredMetaDetails(type, preferredId, addonBaseUrl)
                        ?: repository.resolveMetaDetails(type, canonicalFallbackId, null)
                } else {
                    repository.resolveMetaDetails(type, preferredId, addonBaseUrl)
                } ?: throw Exception("No meta found")
                if (requestVersion != loadRequestVersion) return@launch
                // A meta response can contain a poster/title yet no episode index.
                // Fill it before publishing so the Details Episodes sidebar, Play,
                // stream prefetch and the player all receive the same complete list.
                var recoveredCanonicalId: String? = null
                if (details.type == "series" &&
                    details.videos.orEmpty().none { it.season > 0 && it.episode > 0 }
                ) {
                    val recovered = repository.recoverSeriesEpisodes(
                        canonicalFallbackId, details.name, details.releaseInfo
                    )
                    if (requestVersion != loadRequestVersion) return@launch
                    if (recovered.isNotEmpty()) {
                        details = details.copy(videos = recovered)
                        recoveredCanonicalId = recovered.firstOrNull()?.id?.substringBefore(':')
                            ?.takeIf { Regex("^tt[0-9]+$").matches(it) }
                    }
                }
                loadedContentKey = requestKey

                // Canonical identity owns history/watchlist/Trakt and cross-addon lookup.
                // details.id remains the origin addon's private request ID when it differs.
                val streamFetchId = recoveredCanonicalId ?: canonicalFallbackId
                // Publish usable details before any watch-history or enrichment work.
                _state.update { it.copy(
                    meta = details,
                    resolvedId = streamFetchId,
                    contentKey = requestKey,
                    isLoading = false,
                    tmdbEnabled = isTmdbEnabled,
                    tmdbLoading = isTmdbEnabled
                ) }
                loadTmdbEnrichment(details.type, streamFetchId, requestKey)

                // History lookups are auxiliary: if they fail after meta is published, still
                // mark resume state ready (Play must not stay on "Loading…") and keep going.
                var resumePlaybackId: String? = null
                var isMovieWatched = false
                var episodeProgressMap: Map<String, EpisodeProgress> = emptyMap()
                try {
                    resumePlaybackId = if (details.type == "series") {
                        val latest = dao.getLatestSeriesEpisodeHistory("${streamFetchId}:%")
                        if (latest != null && !latest.watched) {
                            latest.id // In-progress episode — resume it
                        } else {
                            // All episodes watched or no history — use next-up if aired
                            val nextUp = dao.getSeriesNextUp(profileId, streamFetchId)
                            val hasAired = nextUp != null && !nextUp.isComplete &&
                                com.hereliesaz.illumera.data.model.isReleasedBy(nextUp.nextReleased)
                            if (hasAired) {
                                "${streamFetchId}:${nextUp.nextSeason}:${nextUp.nextEpisode}"
                            } else null
                        }
                    } else {
                        val movieHistory = dao.getHistoryItem(streamFetchId)
                        if (movieHistory?.watched == true) null else movieHistory?.id
                    }
                    isMovieWatched = if (details.type != "series") {
                        dao.getHistoryItem(streamFetchId)?.watched == true
                    } else false
                    // Build per-episode progress map for the episodes sidebar
                    episodeProgressMap = if (details.type == "series") {
                        buildEpisodeProgressMap(streamFetchId)
                    } else emptyMap()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    com.hereliesaz.illumera.crash.AppErrors.w("DetailsViewModel", "Resume-state lookup failed; falling back to normal playback", e)
                    resumePlaybackId = null
                    isMovieWatched = false
                    episodeProgressMap = emptyMap()
                }

                if (requestVersion != loadRequestVersion) return@launch
                // Preserve interactions and enrichment that arrived during the lookups.
                _state.update { it.copy(
                    resumePlaybackId = resumePlaybackId,
                    isResumeStateReady = true,
                    isMovieWatched = isMovieWatched,
                    episodeProgressMap = episodeProgressMap
                ) }
                // Update next-up entry when details load
                if (details.type == "series") {
                    try {
                        computeAndStoreNextUp(streamFetchId, details.name, details.poster, details.videos)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        // Next-up is auxiliary persistence; don't skip prefetch / fullyLoadedKey.
                        com.hereliesaz.illumera.crash.AppErrors.w("DetailsViewModel", "Next-up persistence failed", e)
                    }
                }

                // Prefetch streams so they're ready when the user hits Play
                val prefetchEpisode = if (details.type == "series") {
                    val numbered = details.videos
                        ?.filter { it.season > 0 && it.episode > 0 }
                        .orEmpty()
                    val aired = numbered.filter { it.hasAired() }
                    val pool = if (aired.isNotEmpty()) aired else numbered
                    val resumeParts = resumePlaybackId?.let { parseSeasonEpisode(streamFetchId, it) }
                    resumeParts?.let { (season, episode) ->
                        pool.firstOrNull { it.season == season && it.episode == episode }
                    } ?: pool.minWithOrNull(
                        compareBy<com.hereliesaz.illumera.data.model.stremio.MetaVideo> { it.season }
                            .thenBy { it.episode }
                    )
                } else null
                val prefetchId = prefetchEpisode?.let {
                    canonicalEpisodeStreamId(streamFetchId, it)
                } ?: streamFetchId
                val preferredPrefetchId = prefetchEpisode?.let {
                    episodeStreamId(streamFetchId, it)
                } ?: details.id
                prefetchStreams(
                    type = details.type,
                    id = prefetchId,
                    preferredAddonBaseUrl = details.addonBaseUrl,
                    preferredAddonRequestId = preferredPrefetchId
                )
                if (requestVersion == loadRequestVersion) fullyLoadedKey = requestKey
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (requestVersion != loadRequestVersion) return@launch
                if (_state.value.contentKey == requestKey && _state.value.meta != null) {
                    com.hereliesaz.illumera.crash.AppErrors.w("DetailsViewModel", "Optional detail work failed", e)
                    return@launch
                }
                loadedContentKey = null
                _state.update { it.copy(
                    meta = null,
                    isLoading = false,
                    resumePlaybackId = null,
                    autoPlayStream = null,
                    addonSubtitles = emptyList(),
                    availableStreams = emptyList()
                ) }
            }
        }
    }

    private fun refreshResumeStateIfNeeded(meta: MetaItem?) {
        if (meta == null) {
            if (_state.value.resumePlaybackId != null) {
                _state.update { it.copy(resumePlaybackId = null) }
            }
            return
        }

        _state.update { it.copy(isResumeStateReady = false) }
        // History and next-up are keyed by the canonical id loadDetails uses, not the addon's id.
        val historyId = _state.value.resolvedId ?: meta.id
        viewModelScope.launch {
            try {
                val resumePlaybackId = if (meta.type == "series") {
                    resolveSeriesResumePlaybackId(historyId, meta.videos)
                } else {
                    val movieHistory = dao.getHistoryItem(historyId)
                    if (movieHistory?.watched == true) null else movieHistory?.id
                }
                val isMovieWatched = if (meta.type != "series") {
                    dao.getHistoryItem(historyId)?.watched == true
                } else false
                if (_state.value.meta?.id == meta.id && _state.value.meta?.type == meta.type) {
                    val episodeProgressMap = if (meta.type == "series") {
                        buildEpisodeProgressMap(historyId)
                    } else emptyMap()
                    _state.update { it.copy(
                        resumePlaybackId = resumePlaybackId,
                        isResumeStateReady = true,
                        isMovieWatched = isMovieWatched,
                        autoPlayStream = null,
                        episodeProgressMap = episodeProgressMap
                    ) }
                    if (meta.type == "series") {
                        try {
                            computeAndStoreNextUp(historyId, meta.name, meta.poster, meta.videos)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            // Next-up is auxiliary persistence. A write failure must not
                            // erase the resume/progress state that was just loaded successfully.
                            com.hereliesaz.illumera.crash.AppErrors.w(
                                "DetailsViewModel",
                                "Next-up persistence failed",
                                e
                            )
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (_state.value.meta?.id == meta.id && _state.value.meta?.type == meta.type) {
                    com.hereliesaz.illumera.crash.AppErrors.w("DetailsViewModel", "Resume-state lookup failed; falling back to normal playback", e)
                    _state.update { it.copy(
                        resumePlaybackId = null,
                        isResumeStateReady = true,
                        isMovieWatched = false,
                        autoPlayStream = null,
                        episodeProgressMap = emptyMap()
                    ) }
                }
            }
        }
    }
    private suspend fun resolveSeriesResumePlaybackId(
        seriesId: String,
        videos: List<MetaVideo>?
    ): String? {
        val latest = dao.getLatestSeriesEpisodeHistory("$seriesId:%")
        if (latest != null && !latest.watched) {
            val parsed = parseSeasonEpisode(seriesId, latest.id)
            return parsed?.let { (season, episode) -> "$seriesId:$season:$episode" } ?: latest.id
        }

        val sortedEpisodes = videos.orEmpty()
            .filter { it.season > 0 && it.episode > 0 }
            .sortedWith(compareBy<MetaVideo> { it.season }.thenBy { it.episode })
        if (sortedEpisodes.isEmpty()) return null

        val next = if (latest == null) {
            sortedEpisodes.firstOrNull()
        } else {
            val latestKey = parseSeasonEpisode(seriesId, latest.id) ?: return null
            sortedEpisodes.firstOrNull { episode ->
                episode.season > latestKey.first ||
                    (episode.season == latestKey.first && episode.episode > latestKey.second)
            }
        } ?: return null

        val today = java.time.LocalDate.now().toString()
        val releaseDate = next.released?.take(10)
        if (releaseDate != null && releaseDate > today) return null
        return "$seriesId:${next.season}:${next.episode}"
    }

    fun refreshResumeState() {
        refreshResumeStateIfNeeded(_state.value.meta)
    }

    /**
     * Extracts (season, episode) from a watch-history playback ID of the form
     * "$seriesId:season:episode" or the legacy "$seriesId:season:episode:streamIndex".
     * Strips the known seriesId prefix first so this is unambiguous even when
     * seriesId itself contains colons (e.g. "kitsu:12345").
     */
    private fun parseSeasonEpisode(seriesId: String, playbackId: String): Pair<Int, Int>? {
        val prefix = "$seriesId:"
        if (!playbackId.startsWith(prefix)) return null
        val remainder = playbackId.removePrefix(prefix).split(":")
        if (remainder.size < 2) return null
        val season = remainder[0].toIntOrNull() ?: return null
        val episode = remainder[1].toIntOrNull() ?: return null
        return season to episode
    }

    /**
     * Build a map of "S{season}:E{episode}" → EpisodeProgress from watch history.
     * Checks both with and without stream index suffix.
     */
    private suspend fun buildEpisodeProgressMap(seriesId: String): Map<String, EpisodeProgress> {
        // Ordered newest-first (getSeriesEpisodeHistory sorts by lastWatched DESC), so the
        // first entry seen per season/episode key below is already the most recent one.
        val historyItems = dao.getSeriesEpisodeHistory("$seriesId:%")
        if (historyItems.isEmpty()) return emptyMap()

        val map = mutableMapOf<String, EpisodeProgress>()
        for (item in historyItems) {
            val (season, episode) = parseSeasonEpisode(seriesId, item.id) ?: continue
            val key = "S${season}:E${episode}"
            if (map.containsKey(key)) continue
            map[key] = EpisodeProgress(
                progress = item.progress(),
                watched = item.watched
            )
        }
        return map
    }

    /**
     * Compute and store the next unwatched episode for a series.
     * Called when an episode is watched (auto or manual) and when details load.
     */
    suspend fun computeAndStoreNextUp(
        seriesId: String,
        title: String,
        poster: String?,
        videos: List<MetaVideo>?
    ) {
        if (videos.isNullOrEmpty()) return

        // Missing local history is not evidence the series was finished: next-up rows imported
        // from Trakt or Wutch have none. Only explicit clear/unwatch actions delete next-up.
        val latest = dao.getLatestSeriesEpisodeHistory("$seriesId:%") ?: return

        val sortedEpisodes = videos
            .filter { it.season > 0 && it.episode > 0 }
            .sortedWith(compareBy<MetaVideo> { it.season }.thenBy { it.episode })
        if (sortedEpisodes.isEmpty()) return

        val latestKey = parseSeasonEpisode(seriesId, latest.id)
        val nextEpisode = when {
            !latest.watched && latestKey != null -> sortedEpisodes.firstOrNull {
                it.season == latestKey.first && it.episode == latestKey.second
            }
            latestKey != null -> sortedEpisodes.firstOrNull { ep ->
                ep.season > latestKey.first ||
                    (ep.season == latestKey.first && ep.episode > latestKey.second)
            }
            else -> null
        }

        val existing = dao.getSeriesNextUp(profileId, seriesId)
        if (nextEpisode != null) {
            val unchanged = existing != null &&
                !existing.isComplete &&
                existing.nextSeason == nextEpisode.season &&
                existing.nextEpisode == nextEpisode.episode
            val revived = existing?.isComplete == true
            val badgeState = when {
                revived -> true
                unchanged -> existing.isNewEpisode
                else -> false
            }
            dao.upsertSeriesNextUp(
                SeriesNextUpEntity(
                    profileId = profileId,
                    seriesId = seriesId,
                    title = title,
                    poster = poster ?: existing?.poster,
                    nextSeason = nextEpisode.season,
                    nextEpisode = nextEpisode.episode,
                    nextEpisodeTitle = nextEpisode.title.takeIf { it.isNotBlank() && it != "Episode" },
                    nextReleased = nextEpisode.released?.take(10),
                    isComplete = false,
                    isNewEpisode = badgeState,
                    updatedAt = if (unchanged) existing.updatedAt else System.currentTimeMillis()
                )
            )
        } else {
            // This addon's episode list may be partial or numbered differently; never downgrade
            // a pending next-up (e.g. from an import) to complete just because it lacks the episode.
            if (existing != null && !existing.isComplete) return
            val alreadyComplete = existing?.isComplete == true
            dao.upsertSeriesNextUp(
                SeriesNextUpEntity(
                    profileId = profileId,
                    seriesId = seriesId,
                    title = title,
                    poster = poster ?: existing?.poster,
                    nextSeason = 0,
                    nextEpisode = 0,
                    nextEpisodeTitle = null,
                    nextReleased = null,
                    isComplete = true,
                    isNewEpisode = false,
                    updatedAt = if (alreadyComplete) existing.updatedAt else System.currentTimeMillis()
                )
            )
        }
    }

    private fun loadTmdbEnrichment(type: String, videoId: String, contentKey: String) {
        tmdbEnrichmentJob?.cancel()
        tmdbEnrichmentJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Check if TMDB is enabled for the active profile
                val profileId = profileConfigurationManager.getLastActiveProfileId()
                val profile = profileId?.let { dao.getProfileById(it) }
                if (profile?.tmdbEnabled != true) {
                    _state.update { it.copy(tmdbEnabled = false, tmdbLoading = false) }
                    return@launch
                }

                _state.update { it.copy(tmdbEnabled = true, tmdbLoading = true) }

                val language = profile.tmdbLanguage.ifBlank { java.util.Locale.getDefault().toLanguageTag() } // blank = device language
                val mediaType = tmdbService.normalizeMediaType(type)

                // Resolve TMDB ID — if unresolvable (e.g. Kitsu IDs), stop loading and show addon data
                val tmdbId = tmdbService.ensureTmdbId(videoId, mediaType)
                if (tmdbId == null) {
                    _state.update { it.copy(tmdbLoading = false) }
                    return@launch
                }

                // Fetch enrichment, recommendations, and videos in parallel
                val enrichmentDeferred = async { tmdbMetadataService.fetchEnrichment(tmdbId, mediaType, language) }
                val recommendationsDeferred = async { tmdbMetadataService.fetchRecommendations(tmdbId, mediaType, language) }
                val trailerDeferred = async { tmdbMetadataService.fetchBestTrailerKey(tmdbId, mediaType, language) }

                val enrichment = enrichmentDeferred.await()
                val recommendations = recommendationsDeferred.await()
                val trailer = trailerDeferred.await()

                // Fetch collection if available (movies only)
                val collection = if (enrichment?.collectionId != null) {
                    tmdbMetadataService.fetchCollection(enrichment.collectionId, language)
                } else emptyList()

                // Only update if we're still showing the same content
                if (_state.value.contentKey != contentKey) return@launch

                // Apply enrichment — overlay TMDB data onto existing metadata where it adds value
                val currentMeta = _state.value.meta
                val enrichedMeta = if (currentMeta != null && enrichment != null) {
                    currentMeta.copy(
                        // Localized title
                        name = enrichment.localizedTitle ?: currentMeta.name,
                        // Localized description
                        description = enrichment.description ?: currentMeta.description,
                        // Better images
                        logo = enrichment.logo ?: currentMeta.logo,
                        background = enrichment.backdrop ?: currentMeta.background,
                        poster = enrichment.poster ?: currentMeta.poster,
                        // Localized genres
                        genres = enrichment.genres.ifEmpty { currentMeta.genres },
                        // Release info
                        releaseInfo = enrichment.releaseInfo ?: currentMeta.releaseInfo,
                        // Rating from TMDB
                        imdbRating = enrichment.rating?.let {
                            String.format(java.util.Locale.US, "%.1f", it)
                        } ?: currentMeta.imdbRating,
                        // Runtime
                        runtime = enrichment.runtimeMinutes?.let { "${it}m" } ?: currentMeta.runtime
                    )
                } else currentMeta

                // Fetch per-episode enrichment for series (synopsis, runtime, thumbnails)
                val episodeEnrichmentMap = if (mediaType == "tv") {
                    val seasons = enrichedMeta?.videos
                        ?.filter { it.season > 0 }
                        ?.map { it.season }
                        ?.distinct() ?: emptyList()
                    if (seasons.isNotEmpty()) {
                        val raw = tmdbMetadataService.fetchEpisodeEnrichment(tmdbId, seasons, language)
                        raw.mapKeys { (key, _) -> "S${key.first}:E${key.second}" }
                    } else emptyMap()
                } else emptyMap()

                _state.update { it.copy(
                    meta = enrichedMeta,
                    tmdbLoading = false,
                    tmdbEnrichment = enrichment,
                    tmdbRecommendations = recommendations,
                    tmdbTrailer = trailer,
                    tmdbCollection = collection,
                    tmdbCollectionName = enrichment?.collectionName,
                    episodeEnrichmentMap = episodeEnrichmentMap
                ) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("DetailsViewModel", "TMDB enrichment failed: ${e.message}")
                _state.update { it.copy(tmdbLoading = false) }
            }
        }
    }

    // ── Mark episode watched/unwatched ──

    fun toggleMovieWatched() {
        val meta = _state.value.meta ?: return
        if (meta.type == "series") return
        val itemId = _state.value.resolvedId ?: meta.id
        val isCurrentlyWatched = _state.value.isMovieWatched

        viewModelScope.launch(Dispatchers.IO) {
            if (isCurrentlyWatched) {
                dao.deleteHistoryItem(itemId)
                traktSyncManager.pushMovieUnwatched(itemId)
            } else {
                // Preserve any real resume position/duration already on record —
                // marking watched shouldn't destroy it (e.g. an accidental toggle
                // shouldn't force a full re-download of progress from Trakt).
                val existing = dao.getHistoryItem(itemId)
                dao.upsertHistory(
                    WatchHistoryEntity(
                        id = itemId,
                        title = meta.name,
                        poster = meta.poster,
                        position = existing?.position ?: 0L,
                        duration = existing?.duration ?: 0L,
                        lastWatched = System.currentTimeMillis(),
                        type = "movie",
                        watched = true,
                        scrobbled = true
                    )
                )
                traktSyncManager.pushMovieWatched(itemId)
                wutchManager.onMarkedWatched(itemId)
            }
            _state.update { it.copy(
                isMovieWatched = !isCurrentlyWatched,
                resumePlaybackId = null
            ) }
        }
    }

    fun toggleEpisodeWatched(episode: MetaVideo) {
        val meta = _state.value.meta ?: return
        val streamId = _state.value.resolvedId ?: meta.id
        val key = "S${episode.season}:E${episode.episode}"
        val currentProgress = _state.value.episodeProgressMap[key]
        val isCurrentlyWatched = currentProgress?.watched ?: false

        viewModelScope.launch(Dispatchers.IO) {
            if (isCurrentlyWatched) {
                // Unmark: remove watched entry from history
                val playbackId = "$streamId:${episode.season}:${episode.episode}"
                dao.deleteHistoryItem(playbackId)
                // Also try with stream index variants
                dao.getSeriesEpisodeHistory("$playbackId:%").forEach {
                    dao.deleteHistoryItem(it.id)
                }
                traktSyncManager.pushEpisodeUnwatched(streamId, episode.season, episode.episode)
            } else {
                // Mark as watched: create a watched history entry, preserving any
                // real resume position/duration already on record.
                val playbackId = "$streamId:${episode.season}:${episode.episode}"
                val existing = dao.getHistoryItem(playbackId)
                dao.upsertHistory(
                    WatchHistoryEntity(
                        id = playbackId,
                        title = episode.title.takeIf { it.isNotBlank() && it != "Episode" }
                            ?: "S${episode.season}:E${episode.episode} - ${meta.name}",
                        poster = meta.poster,
                        position = existing?.position ?: 0L,
                        duration = existing?.duration ?: 0L,
                        lastWatched = System.currentTimeMillis(),
                        type = "series",
                        watched = true,
                        scrobbled = true
                    )
                )
                traktSyncManager.pushEpisodeWatched(streamId, episode.season, episode.episode)
                wutchManager.onMarkedWatched(streamId, episode.season, episode.episode)
            }

            // Refresh the progress map and next-up entry
            val updatedMap = buildEpisodeProgressMap(streamId)
            _state.update { it.copy(episodeProgressMap = updatedMap) }
            computeAndStoreNextUp(streamId, meta.name, meta.poster, meta.videos)
        }
    }

    // 1. Open Episodes (Series)
    /** [focusEpisodeId] (the episode the Resume button names) wins over the last-browsed one. */
    fun openEpisodes(focusEpisodeId: String? = null) {
        val meta = _state.value.meta ?: return
        val videos = meta.videos.orEmpty()
        val initialFocusEpisodeId = focusEpisodeId
            ?: episodeBrowseStore.getRememberedEpisodeSuffix(meta.id)
        val needsRecovery = videos.none { it.season > 0 && it.episode > 0 }
        _state.update { it.copy(
            autoPlayStream = null,
            addonSubtitles = emptyList(),
            availableStreams = emptyList(),
            sidebarState = SidebarState.Episodes(
                videos, initialFocusEpisodeId = initialFocusEpisodeId,
                isLoading = needsRecovery
            )
        ) }
        if (!needsRecovery) return
        episodeMetadataRetryJob?.cancel()
        val contentKey = _state.value.contentKey
        val seriesId = _state.value.resolvedId ?: meta.id
        episodeMetadataRetryJob = viewModelScope.launch {
            val recovered = repository.recoverSeriesEpisodes(
                seriesId, meta.name, meta.releaseInfo
            )
            if (_state.value.contentKey != contentKey || _state.value.meta?.id != meta.id) return@launch
            _state.update { current ->
                val sidebar = current.sidebarState
                current.copy(
                    meta = if (recovered.isNotEmpty()) current.meta?.copy(videos = recovered)
                        else current.meta,
                    resolvedId = recovered.firstOrNull()?.id?.substringBefore(':')
                        ?.takeIf { Regex("^tt[0-9]+$").matches(it) } ?: current.resolvedId,
                    sidebarState = if (sidebar is SidebarState.Episodes) {
                        sidebar.copy(
                            videos = if (recovered.isNotEmpty()) recovered else sidebar.videos,
                            isLoading = false
                        )
                    } else sidebar
                )
            }
        }
    }

    /** Remembers which episode was last browsed for this series, so reopening the
     *  episode list later lands back on the same season/episode. */
    fun rememberEpisodeBrowsePosition(seriesId: String, episode: MetaVideo) {
        episodeBrowseStore.rememberEpisode(seriesId, episode.season, episode.episode)
    }

    private fun streamCacheKey(
        type: String,
        id: String,
        preferredAddonBaseUrl: String?,
        preferredAddonRequestId: String?
    ): String = listOf(type, id, preferredAddonBaseUrl.orEmpty(), preferredAddonRequestId.orEmpty())
        .joinToString("|")

    private fun prefetchStreams(
        type: String,
        id: String,
        preferredAddonBaseUrl: String? = null,
        preferredAddonRequestId: String? = null
    ) {
        prefetchStreamsJob?.cancel()
        val key = streamCacheKey(type, id, preferredAddonBaseUrl, preferredAddonRequestId)
        prefetchedStreamKey = key
        prefetchStreamsJob = viewModelScope.launch {
            try {
                val streamsDeferred = async {
                    repository.getStreams(type, id, preferredAddonBaseUrl, preferredAddonRequestId)
                }
                val subtitlesDeferred = async {
                    subtitleRepository.getSubtitles(
                        type = type,
                        playbackId = id,
                        preferredAddonBaseUrl = preferredAddonBaseUrl,
                        preferredAddonRequestId = preferredAddonRequestId
                    )
                }
                val streams = streamsDeferred.await()
                val subtitles = subtitlesDeferred.await()
                streamsCache[key] = StreamsCacheEntry(streams, subtitles, System.currentTimeMillis())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Prefetch failed silently — loadStreams will fetch fresh
            }
        }
    }

    /** Applies the active profile's sort/filter preferences to a raw stream list. */
    private suspend fun sortStreams(rawStreams: List<Stream>, mediaType: String): List<Stream> {
        val activeProfileId = profileConfigurationManager.getLastActiveProfileId()
        val profile = activeProfileId?.let { dao.getProfileById(it) }
        return if (profile?.sourceSortingEnabled != false) {
            val enabledQualities = StreamSortingService.parseEnabledQualities(profile?.sourceEnabledQualities ?: "4k,1080p,720p,unknown")
            val excludePhrases = StreamSortingService.parseExcludePhrases(profile?.sourceExcludePhrases ?: "")
            val addonSortOrders = dao.getAllAddons().firstOrNull()
                ?.associate { it.transportUrl to it.sortOrder } ?: emptyMap()
            val excludedFormats = StreamSortingService.parseExcludedFormats(profile?.sourceExcludedFormats ?: "")
            val preferredSizeMb = if (mediaType.equals("movie", ignoreCase = true)) {
                profile?.sourceMovieTargetSizeMb ?: 3000
            } else {
                profile?.sourceEpisodeTargetSizeMb ?: 750
            }
            // Parsing and matching every stream is CPU work; off the main thread it can't freeze input.
            withContext(Dispatchers.Default) {
                streamSortingService.sortAndFilter(
                    rawStreams, enabledQualities, excludePhrases, addonSortOrders,
                    profile?.sourceSortPrimary ?: "quality", profile?.sourceMaxSizeGb ?: 0,
                    excludedFormats, preferredSizeMb, profile?.sourceMinimumSeeds ?: 5, profile
                )
            }
        } else rawStreams
    }

    /** Sorts a fresh (or cached) stream result and applies it to state — auto-playing a
     *  preferred/first-playable stream when appropriate, otherwise showing the sources sidebar. */
    private suspend fun applyResolvedStreams(
        mediaType: String,
        streamRequestId: String,
        displayTitle: String,
        sourceSelectionId: String,
        forceSourcePicker: Boolean,
        autoSelectSource: Boolean,
        rememberSourceSelection: Boolean,
        rawStreams: List<Stream>,
        addonSubtitles: List<AddonSubtitle>
    ) {
        val streams = sortStreams(rawStreams, mediaType)
        val sourceListDisabled = sourceSelectionStore.isSourceListDisabled(sourceSelectionId)
        val excludedSourceIds = sourceSelectionStore.getExcludedSources(sourceSelectionId)
        // Auto-selection must never pick a source the user excluded (same key as GlassSidebar).
        val selectableStreams = streams.filterNot {
            (it.sourceSelectionId ?: it.addonTransportUrl ?: it.url) in excludedSourceIds
        }

        val preferredStream = if (forceSourcePicker || !rememberSourceSelection) {
            null
        } else {
            sourceSelectionStore.findPreferredStream(sourceSelectionId, selectableStreams)
        }

        if (preferredStream != null) {
            // Playback starts with the generic subtitles; the player session fetches matches
            // for this exact file in the background, so the stream never waits on them.
            val resolvedSubtitles = addonSubtitles
            _state.update { it.copy(
                isLoadingStreams = false,
                sidebarState = SidebarState.Closed,
                autoPlayStream = preferredStream,
                addonSubtitles = resolvedSubtitles,
                availableStreams = streams,
                activeStreamMediaType = mediaType,
                activeStreamRequestId = streamRequestId,
                activeSourceSelectionId = sourceSelectionId,
                sourceListDisabled = sourceListDisabled,
                excludedSourceIds = excludedSourceIds
            ) }
            return
        }

        // Auto-select first playable source when enabled
        if (autoSelectSource && !forceSourcePicker) {
            val firstPlayable = selectableStreams.firstOrNull {
                !it.url.isNullOrBlank() || !it.infoHash.isNullOrBlank()
            }
            if (firstPlayable != null) {
                // Playback starts with the generic subtitles; the player session fetches matches
                // for this exact file in the background, so the stream never waits on them.
                val resolvedSubtitles = addonSubtitles
                _state.update { it.copy(
                    isLoadingStreams = false,
                    sidebarState = SidebarState.Closed,
                    autoPlayStream = firstPlayable,
                    addonSubtitles = resolvedSubtitles,
                    availableStreams = streams,
                    activeStreamMediaType = mediaType,
                    activeStreamRequestId = streamRequestId,
                    activeSourceSelectionId = sourceSelectionId,
                    sourceListDisabled = sourceListDisabled,
                    excludedSourceIds = excludedSourceIds
                ) }
                return
            }
        }

        // Even when the picker has to be shown (forced, no auto-play match, or nothing
        // playable), highlight and focus whichever source was last picked for this item
        // — so reopening the sources list doesn't lose your place in it.
        val highlightedStreamId = if (rememberSourceSelection) {
            sourceSelectionStore.findPreferredStream(sourceSelectionId, streams)
                ?.let { it.sourceSelectionId ?: it.addonTransportUrl ?: it.url }
        } else null

        // Update sidebar with results
        _state.update { it.copy(
            isLoadingStreams = false,
            autoPlayStream = null,
            addonSubtitles = addonSubtitles,
            availableStreams = streams,
            activeStreamMediaType = mediaType,
            activeStreamRequestId = streamRequestId,
            activeSourceSelectionId = sourceSelectionId,
            sourceListDisabled = sourceListDisabled,
            excludedSourceIds = excludedSourceIds,
            sidebarState = SidebarState.Sources(displayTitle, streams, selectedStreamId = highlightedStreamId)
        ) }
    }

    /** Silently refetches streams and, only if the sources sidebar for this same item is
     *  still open, updates the visible list — never touches autoPlayStream/isLoadingStreams,
     *  since a background refresh must not yank the user away from a list they're browsing. */
    /** Shows the sources answered so far in the open list, keeping the highlighted row. */
    private suspend fun showPartialSources(displayTitle: String, partial: List<Stream>, mediaType: String) {
        // An empty partial would show "no sources" while other addons are still answering.
        if (partial.isEmpty() || _state.value.sidebarState !is SidebarState.Sources) return
        val sorted = sortStreams(partial, mediaType)
        _state.update { current ->
            val open = current.sidebarState as? SidebarState.Sources ?: return@update current
            current.copy(
                availableStreams = sorted,
                sidebarState = SidebarState.Sources(displayTitle, sorted, selectedStreamId = open.selectedStreamId)
            )
        }
    }

    private fun refreshStreamsInBackground(
        type: String,
        id: String,
        key: String,
        displayTitle: String,
        preferredAddonBaseUrl: String?,
        preferredAddonRequestId: String?
    ) {
        backgroundRefreshJob = viewModelScope.launch {
            try {
                val streamsDeferred = async {
                    // No cutoff: the cached list stays on screen, so a slow addon only adds later.
                    repository.getStreams(
                        type, id, preferredAddonBaseUrl, preferredAddonRequestId,
                        timeoutMs = null,
                        onProgress = { partial ->
                            // Only grow the visible list; never shrink it below the cached one mid-refresh.
                            val shown = (_state.value.sidebarState as? SidebarState.Sources)?.streams?.size ?: 0
                            if (partial.size > shown) showPartialSources(displayTitle, partial, type)
                        }
                    )
                }
                val subtitlesDeferred = async {
                    subtitleRepository.getSubtitles(
                        type = type,
                        playbackId = id,
                        preferredAddonBaseUrl = preferredAddonBaseUrl,
                        preferredAddonRequestId = preferredAddonRequestId
                    )
                }
                val rawStreams = streamsDeferred.await()
                val addonSubtitles = subtitlesDeferred.await()
                streamsCache[key] = StreamsCacheEntry(rawStreams, addonSubtitles, System.currentTimeMillis())

                val openSources = _state.value.sidebarState as? SidebarState.Sources
                if (openSources != null) {
                    val streams = sortStreams(rawStreams, type)
                    _state.update { it.copy(
                        addonSubtitles = addonSubtitles,
                        availableStreams = streams,
                        // Preserve whatever was already highlighted/focused rather than
                        // recomputing it, so a silent background refresh never yanks
                        // focus away from where the user currently is in the list.
                        sidebarState = SidebarState.Sources(displayTitle, streams, selectedStreamId = openSources.selectedStreamId)
                    ) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep showing the cached results if a silent refresh fails.
            }
        }
    }

    private data class StreamRequestContext(
        val canonicalId: String,
        val preferredAddonBaseUrl: String?,
        val preferredAddonRequestId: String?
    )

    private fun streamRequestContext(
        type: String,
        requestedId: String,
        sourceSelectionId: String
    ): StreamRequestContext {
        val meta = _state.value.meta
        val canonicalId = if (meta == null) {
            // loadStreams is also unit-tested/usable as a standalone operation. Without
            // loaded metadata there is no trustworthy origin/canonical split to infer.
            requestedId
        } else if (type.equals("series", ignoreCase = true)) {
            // sourceSelectionId is the stable tracking ID. Duplicate cuts can contain a
            // variant= segment; strip only that private segment while keeping S/E.
            val pieces = sourceSelectionId.split(":")
            val season = pieces.getOrNull(pieces.lastIndex - 1)?.toIntOrNull()
            val episode = pieces.lastOrNull()?.toIntOrNull()
            if (season != null && episode != null) {
                val parent = if (":variant=" in sourceSelectionId) {
                    sourceSelectionId.substringBefore(":variant=")
                } else {
                    pieces.dropLast(2).joinToString(":")
                }
                "$parent:$season:$episode"
            } else {
                requestedId
            }
        } else {
            _state.value.resolvedId ?: requestedId
        }

        val preferredBase = meta?.addonBaseUrl
        val preferredId = when {
            preferredBase.isNullOrBlank() -> null
            type.equals("series", ignoreCase = true) -> requestedId
            else -> meta.id
        }
        return StreamRequestContext(canonicalId, preferredBase, preferredId)
    }

    // 2. Open Sources (Movie OR Specific Episode)
    fun loadStreams(
        type: String,
        id: String,
        displayTitle: String,
        sourceSelectionId: String = id,
        forceSourcePicker: Boolean = false,
        autoSelectSource: Boolean = false,
        rememberSourceSelection: Boolean = true
    ) {
        loadStreamsJob?.cancel()
        backgroundRefreshJob?.cancel()

        val request = streamRequestContext(type, id, sourceSelectionId)
        val requestId = request.canonicalId
        val key = streamCacheKey(
            type,
            requestId,
            request.preferredAddonBaseUrl,
            request.preferredAddonRequestId
        )
        val cached = streamsCache[key]

        // Serve a still-fresh cache instantly (no spinner) and refresh it in the background —
        // sources are always updated when the list is opened, but the user only ever waits
        // on a fetch when there's nothing recent enough to show meanwhile.
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt <= STREAMS_CACHE_TTL_MS) {
            loadStreamsJob = viewModelScope.launch {
                applyResolvedStreams(
                    type, requestId, displayTitle, sourceSelectionId, forceSourcePicker, autoSelectSource,
                    rememberSourceSelection, cached.streams, cached.subtitles
                )
            }
            refreshStreamsInBackground(
                type,
                requestId,
                key,
                displayTitle,
                request.preferredAddonBaseUrl,
                request.preferredAddonRequestId
            )
            return
        }

        loadStreamsJob = viewModelScope.launch {
            // Show immediate loading feedback:
            // - Show sources sidebar when user needs to pick manually
            // - Centered spinner when auto-resolve is expected (auto-select or remembered source)
            val hasRemembered = rememberSourceSelection && sourceSelectionStore.hasRememberedSelection(sourceSelectionId)
            val showSidebar = forceSourcePicker || (!autoSelectSource && !hasRemembered)

            _state.update { it.copy(
                autoPlayStream = null,
                addonSubtitles = emptyList(),
                availableStreams = emptyList(),
                isLoadingStreams = true,
                sidebarState = if (showSidebar) SidebarState.Sources(displayTitle, null)
                               else SidebarState.Closed
            ) }

            try {
                val rawStreams: List<Stream>
                val addonSubtitles: List<AddonSubtitle>

                if (prefetchedStreamKey == key) {
                    prefetchStreamsJob?.join()
                }
                val prefetched = streamsCache[key]
                if (prefetched != null) {
                    rawStreams = prefetched.streams
                    addonSubtitles = prefetched.subtitles
                } else {
                    val streamsDeferred = async {
                        if (showSidebar) {
                            // The viewer is looking at the list: no per-addon cutoff, and each
                            // addon's sources appear as it answers (as Stremio does).
                            repository.getStreams(
                                type,
                                requestId,
                                request.preferredAddonBaseUrl,
                                request.preferredAddonRequestId,
                                timeoutMs = null,
                                onProgress = { partial -> showPartialSources(displayTitle, partial, type) }
                            )
                        } else {
                            repository.getStreams(
                                type,
                                requestId,
                                request.preferredAddonBaseUrl,
                                request.preferredAddonRequestId
                            )
                        }
                    }
                    val subtitlesDeferred = async {
                        subtitleRepository.getSubtitles(
                            type = type,
                            playbackId = requestId,
                            preferredAddonBaseUrl = request.preferredAddonBaseUrl,
                            preferredAddonRequestId = request.preferredAddonRequestId
                        )
                    }
                    rawStreams = streamsDeferred.await()
                    addonSubtitles = subtitlesDeferred.await()
                    streamsCache[key] = StreamsCacheEntry(rawStreams, addonSubtitles, System.currentTimeMillis())
                }

                applyResolvedStreams(
                    type, requestId, displayTitle, sourceSelectionId, forceSourcePicker, autoSelectSource,
                    rememberSourceSelection, rawStreams, addonSubtitles
                )
                // A prefetch ran with the per-addon cutoff; let slow addons still fill in the open list.
                if (prefetched != null && _state.value.sidebarState is SidebarState.Sources) {
                    refreshStreamsInBackground(
                        type, requestId, key, displayTitle,
                        request.preferredAddonBaseUrl, request.preferredAddonRequestId
                    )
                }
            } catch (cancelled: CancellationException) {
                // A newer loadStreams() replaced this one; it owns the state now.
                throw cancelled
            } catch (e: Exception) {
                _state.update { it.copy(
                    isLoadingStreams = false,
                    autoPlayStream = null,
                    addonSubtitles = emptyList(),
                    availableStreams = emptyList(),
                    sidebarState = SidebarState.Sources(displayTitle, emptyList())
                ) }
            }
        }
    }

    fun selectStreamForPlayback(stream: Stream) {
        val mediaType = _state.value.activeStreamMediaType ?: return
        val requestId = _state.value.activeStreamRequestId ?: return
        val fallbackSubtitles = _state.value.addonSubtitles

        sourceSelectionJob?.cancel()
        sourceSelectionJob = viewModelScope.launch {
            // File-specific subtitles are fetched by the player session once playback starts.
            val resolvedSubtitles = fallbackSubtitles

            _state.update { it.copy(
                isLoadingStreams = false,
                autoPlayStream = stream,
                addonSubtitles = resolvedSubtitles,
                sidebarState = SidebarState.Closed
            ) }
        }
    }

    fun consumeAutoPlayStream() {
        if (_state.value.autoPlayStream == null) return
        _state.update { it.copy(autoPlayStream = null) }
    }

    // --- Clear Progress (with confirmation dialog) ---

    fun confirmClearProgress() {
        val meta = _state.value.meta ?: return
        // Same canonical id every other history/next-up/Trakt read and write uses.
        val streamId = _state.value.resolvedId ?: meta.id

        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            // Collect items to clear
            val historyItems = if (meta.type == "series") {
                dao.getSeriesEpisodeHistory("${streamId}:%")
            } else {
                listOfNotNull(dao.getHistoryItem(streamId))
            }

            // Delete from local DB
            if (meta.type == "series") {
                dao.deleteSeriesHistory("${streamId}:%")
                dao.deleteSeriesNextUp(profileId, streamId)
                sourceSelectionStore.clearSelectionsForPrefix(streamId)
                playbackTrackSelectionStore.clearSelectionsForPrefix(streamId)
            } else {
                dao.deleteHistoryItem(streamId)
                sourceSelectionStore.clearSelection(streamId)
                playbackTrackSelectionStore.clearSelection(streamId)
            }

            // Remote cleanup is best-effort: a throw must not skip the local state update.
            try {
                // Delete from Trakt (playback progress + watched history)
                for (item in historyItems) {
                    if (item.scrobbled) {
                        traktSyncManager.deletePlaybackFromTrakt(item.id)
                    }
                }
                // Remove watched episodes from Trakt history for series
                if (meta.type == "series") {
                    val watchedEpisodes = historyItems.filter { it.watched }
                    for (ep in watchedEpisodes) {
                        val (season, episode) = parseSeasonEpisode(streamId, ep.id) ?: continue
                        traktSyncManager.pushEpisodeUnwatched(streamId, season, episode)
                    }
                }
            } catch (e: Exception) {
                com.hereliesaz.illumera.crash.AppErrors.w("DetailsViewModel", "Trakt progress cleanup failed", e)
            }

            try {
                profileConfigurationManager.saveActiveRuntimeState()
            } catch (e: Exception) {
                com.hereliesaz.illumera.crash.AppErrors.w("DetailsViewModel", "Saving runtime state failed", e)
            }

            // Refresh episode progress map
            val updatedMap = if (meta.type == "series") buildEpisodeProgressMap(streamId) else emptyMap()

            _state.update { it.copy(
                resumePlaybackId = null,
                isMovieWatched = false,
                episodeProgressMap = updatedMap
            ) }
        }
    }

    // --- Watchlist toggle ---

    fun toggleWatchlist() {
        val meta = _state.value.meta ?: return
        val itemId = _state.value.resolvedId ?: meta.id
        viewModelScope.launch(Dispatchers.IO) {
            if (dao.isInWatchlist(profileId, itemId)) {
                traktSyncManager.removeFromWatchlist(profileId, itemId, meta.type)
                wutchManager.onWatchlistChanged(itemId, added = false)
            } else {
                val entity = WatchlistEntity(
                    profileId = profileId,
                    id = itemId,
                    type = meta.type,
                    title = meta.name,
                    poster = meta.poster,
                    addedAt = System.currentTimeMillis()
                )
                traktSyncManager.addToWatchlist(entity)
                wutchManager.onWatchlistChanged(itemId, added = true)
            }
        }
    }

    // 3. Close Logic
    fun closeSidebar() {
        loadStreamsJob?.cancel()
        loadStreamsJob = null
        sourceSelectionJob?.cancel()
        sourceSelectionJob = null
        _state.update { it.copy(
            isLoadingStreams = false,
            autoPlayStream = null,
            availableStreams = emptyList(),
            sidebarState = SidebarState.Closed
        ) }
    }

    fun toggleSourceListDisabled() {
        val id = _state.value.activeSourceSelectionId ?: return
        val newDisabled = !_state.value.sourceListDisabled
        sourceSelectionStore.rememberSourceListDisabled(id, newDisabled)
        _state.update { it.copy(sourceListDisabled = newDisabled) }
    }

    fun toggleSourceExcluded(stream: Stream) {
        val id = _state.value.activeSourceSelectionId ?: return
        // Per-stream id: the addon URL alone excluded every source that addon returned.
        val streamId = stream.sourceSelectionId ?: stream.addonTransportUrl ?: stream.url ?: return
        val current = _state.value.excludedSourceIds
        val updated = if (streamId in current) current - streamId else current + streamId
        sourceSelectionStore.rememberExcludedSources(id, updated)
        _state.update { it.copy(excludedSourceIds = updated) }
    }

    // 4. Back Button Logic (Drill Up)
    fun goBackInSidebar() {
        val currentState = _state.value.sidebarState

        // If viewing Sources for a Series, go back to Episode List
        if (currentState is SidebarState.Sources && _state.value.meta?.type == "series") {
            openEpisodes()
        } else {
            closeSidebar()
        }
    }
}
