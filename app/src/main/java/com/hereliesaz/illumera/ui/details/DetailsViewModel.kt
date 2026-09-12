package com.hereliesaz.illumera.ui.details

import android.util.Log
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
import com.hereliesaz.illumera.domain.episodeStreamId
import com.hereliesaz.illumera.domain.hasAired
import com.hereliesaz.illumera.data.trakt.TraktSyncManager
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// How long a fetched stream list is served instantly (with a background refresh kicked off
// alongside it) before a re-open of the sources sidebar has to wait on a fresh fetch again.
private const val STREAMS_CACHE_TTL_MS = 3 * 60_000L

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
    private val traktSyncManager: TraktSyncManager
) : ViewModel() {

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
    )

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

    private var loadDetailsJob: Job? = null
    private var loadStreamsJob: Job? = null
    private var tmdbEnrichmentJob: Job? = null
    private var loadRequestVersion: Long = 0L
    private var loadedContentKey: String? = null

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
        val requestKey = "$type:$id"

        // Keep current details when reopening the same item (e.g., returning from player).
        if (
            loadedContentKey == requestKey &&
            _state.value.meta != null &&
            !_state.value.isLoading
        ) {
            refreshResumeStateIfNeeded(_state.value.meta)
            if (_state.value.sidebarState !is SidebarState.Closed) {
                _state.value = _state.value.copy(sidebarState = SidebarState.Closed)
            }
            return
        }

        loadDetailsJob?.cancel()
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
                // Resolve tmdb: IDs to IMDb IDs via TMDB API so all addons work consistently
                val isTmdbEnabled = profileConfigurationManager.getLastActiveProfileId()
                    ?.let { dao.getProfileById(it) }?.tmdbEnabled == true
                val resolvedId = if (isTmdbEnabled && id.startsWith("tmdb:", ignoreCase = true)) {
                    val tmdbNumericId = id.substringAfter(':').substringBefore(':').toIntOrNull()
                    val mediaType = tmdbService.normalizeMediaType(type)
                    tmdbNumericId?.let { tmdbService.tmdbToImdb(it, mediaType) } ?: id
                } else id

                val details = repository.resolveMetaDetails(type, resolvedId, addonBaseUrl)
                    ?: throw Exception("No meta found")
                if (requestVersion != loadRequestVersion) return@launch
                loadedContentKey = requestKey
                // Use resolved ID for streams — guarantees IMDb format for stream addons
                val streamFetchId = if (details.id.startsWith("tt")) details.id else resolvedId
                // Publish usable details before any watch-history or enrichment work.
                _state.value = _state.value.copy(
                    meta = details,
                    resolvedId = streamFetchId,
                    contentKey = requestKey,
                    isLoading = false,
                    tmdbEnabled = isTmdbEnabled,
                    tmdbLoading = isTmdbEnabled
                )
                loadTmdbEnrichment(details.type, streamFetchId, requestKey)

                val resumePlaybackId = if (details.type == "series") {
                    val latest = dao.getLatestSeriesEpisodeHistory("${streamFetchId}:%")
                    if (latest != null && !latest.watched) {
                        latest.id // In-progress episode — resume it
                    } else {
                        // All episodes watched or no history — use next-up if aired
                        val nextUp = dao.getSeriesNextUp(profileId, streamFetchId)
                        val today = java.time.LocalDate.now().toString()
                        val hasAired = nextUp != null && !nextUp.isComplete &&
                            (nextUp.nextReleased == null || nextUp.nextReleased <= today)
                        if (hasAired) {
                            "${streamFetchId}:${nextUp!!.nextSeason}:${nextUp.nextEpisode}"
                        } else null
                    }
                } else {
                    val movieHistory = dao.getHistoryItem(streamFetchId)
                    if (movieHistory?.watched == true) null else movieHistory?.id
                }
                val isMovieWatched = if (details.type != "series") {
                    dao.getHistoryItem(streamFetchId)?.watched == true
                } else false
                // Build per-episode progress map for the episodes sidebar
                val episodeProgressMap = if (details.type == "series") {
                    buildEpisodeProgressMap(streamFetchId)
                } else emptyMap()

                if (requestVersion != loadRequestVersion) return@launch
                // Preserve interactions and enrichment that arrived during the lookups.
                _state.value = _state.value.copy(
                    resumePlaybackId = resumePlaybackId,
                    isResumeStateReady = true,
                    isMovieWatched = isMovieWatched,
                    episodeProgressMap = episodeProgressMap
                )
                // Update next-up entry when details load
                if (details.type == "series") {
                    computeAndStoreNextUp(streamFetchId, details.name, details.poster, details.videos)
                }

                // Prefetch streams so they're ready when the user hits Play
                val prefetchId = if (resumePlaybackId != null) {
                    resumePlaybackId
                } else if (details.type == "series") {
                    val numbered = details.videos
                        ?.filter { it.season > 0 && it.episode > 0 }
                        .orEmpty()
                    val aired = numbered.filter { it.hasAired() }
                    val pool = if (aired.isNotEmpty()) aired else numbered
                    val firstEpisode = pool
                        .minWithOrNull(compareBy<com.hereliesaz.illumera.data.model.stremio.MetaVideo> { it.season }.thenBy { it.episode })
                    firstEpisode?.let { episodeStreamId(streamFetchId, it) } ?: streamFetchId
                } else {
                    streamFetchId
                }
                prefetchStreams(details.type, prefetchId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (requestVersion != loadRequestVersion) return@launch
                if (_state.value.contentKey == requestKey && _state.value.meta != null) {
                    Log.w("DetailsViewModel", "Optional detail work failed", e)
                    return@launch
                }
                loadedContentKey = null
                _state.value = _state.value.copy(
                    meta = null,
                    isLoading = false,
                    resumePlaybackId = null,
                    autoPlayStream = null,
                    addonSubtitles = emptyList(),
                    availableStreams = emptyList()
                )
            }
        }
    }

    private fun refreshResumeStateIfNeeded(meta: MetaItem?) {
        if (meta == null) {
            if (_state.value.resumePlaybackId != null) {
                _state.value = _state.value.copy(resumePlaybackId = null)
            }
            return
        }

        _state.value = _state.value.copy(isResumeStateReady = false)
        viewModelScope.launch {
            val resumePlaybackId = if (meta.type == "series") {
                resolveSeriesResumePlaybackId(meta.id, meta.videos)
            } else {
                val movieHistory = dao.getHistoryItem(meta.id)
                if (movieHistory?.watched == true) null else movieHistory?.id
            }
            val isMovieWatched = if (meta.type != "series") {
                dao.getHistoryItem(meta.id)?.watched == true
            } else false
            if (_state.value.meta?.id == meta.id && _state.value.meta?.type == meta.type) {
                val episodeProgressMap = if (meta.type == "series") {
                    buildEpisodeProgressMap(meta.id)
                } else emptyMap()
                _state.value = _state.value.copy(
                    resumePlaybackId = resumePlaybackId,
                    isResumeStateReady = true,
                    isMovieWatched = isMovieWatched,
                    autoPlayStream = null,
                    episodeProgressMap = episodeProgressMap
                )
                if (meta.type == "series") {
                    computeAndStoreNextUp(meta.id, meta.name, meta.poster, meta.videos)
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

        val latest = dao.getLatestSeriesEpisodeHistory("$seriesId:%")
        if (latest == null) {
            dao.deleteSeriesNextUp(profileId, seriesId)
            return
        }

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
                unchanged -> existing?.isNewEpisode ?: false
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
                    _state.value = _state.value.copy(tmdbEnabled = false, tmdbLoading = false)
                    return@launch
                }

                _state.value = _state.value.copy(tmdbEnabled = true, tmdbLoading = true)

                val language = profile.tmdbLanguage.ifBlank { null } ?: "en"
                val mediaType = tmdbService.normalizeMediaType(type)

                // Resolve TMDB ID — if unresolvable (e.g. Kitsu IDs), stop loading and show addon data
                val tmdbId = tmdbService.ensureTmdbId(videoId, mediaType)
                if (tmdbId == null) {
                    _state.value = _state.value.copy(tmdbLoading = false)
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
                            String.format("%.1f", it)
                        } ?: currentMeta.imdbRating,
                        // Runtime
                        runtime = enrichment.runtimeMinutes?.let { "${it}m" } ?: currentMeta.runtime
                    )
                } else currentMeta

                // Fetch per-episode enrichment for series (synopsis, runtime, thumbnails)
                val episodeEnrichmentMap = if (mediaType == "tv" && tmdbId != null) {
                    val seasons = enrichedMeta?.videos
                        ?.filter { it.season > 0 }
                        ?.map { it.season }
                        ?.distinct() ?: emptyList()
                    if (seasons.isNotEmpty()) {
                        val raw = tmdbMetadataService.fetchEpisodeEnrichment(tmdbId, seasons, language)
                        raw.mapKeys { (key, _) -> "S${key.first}:E${key.second}" }
                    } else emptyMap()
                } else emptyMap()

                _state.value = _state.value.copy(
                    meta = enrichedMeta,
                    tmdbLoading = false,
                    tmdbEnrichment = enrichment,
                    tmdbRecommendations = recommendations,
                    tmdbTrailer = trailer,
                    tmdbCollection = collection,
                    tmdbCollectionName = enrichment?.collectionName,
                    episodeEnrichmentMap = episodeEnrichmentMap
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("DetailsViewModel", "TMDB enrichment failed: ${e.message}")
                _state.value = _state.value.copy(tmdbLoading = false)
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
            }
            _state.value = _state.value.copy(
                isMovieWatched = !isCurrentlyWatched,
                resumePlaybackId = null
            )
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
            }

            // Refresh the progress map and next-up entry
            val updatedMap = buildEpisodeProgressMap(streamId)
            _state.value = _state.value.copy(episodeProgressMap = updatedMap)
            computeAndStoreNextUp(streamId, meta.name, meta.poster, meta.videos)
        }
    }

    // 1. Open Episodes (Series)
    fun openEpisodes() {
        val meta = _state.value.meta
        val videos = meta?.videos ?: emptyList()
        val initialFocusEpisodeId = meta?.id?.let { episodeBrowseStore.getRememberedEpisodeSuffix(it) }
        _state.value = _state.value.copy(
            autoPlayStream = null,
            addonSubtitles = emptyList(),
            availableStreams = emptyList(),
            sidebarState = SidebarState.Episodes(videos, initialFocusEpisodeId = initialFocusEpisodeId)
        )
    }

    /** Remembers which episode was last browsed for this series, so reopening the
     *  episode list later lands back on the same season/episode. */
    fun rememberEpisodeBrowsePosition(seriesId: String, episode: MetaVideo) {
        episodeBrowseStore.rememberEpisode(seriesId, episode.season, episode.episode)
    }

    private fun prefetchStreams(type: String, id: String) {
        prefetchStreamsJob?.cancel()
        val key = "$type:$id"
        prefetchedStreamKey = key
        prefetchStreamsJob = viewModelScope.launch {
            try {
                val streamsDeferred = async { repository.getStreams(type, id) }
                val subtitlesDeferred = async { subtitleRepository.getSubtitles(type, id) }
                val streams = streamsDeferred.await()
                val subtitles = subtitlesDeferred.await()
                streamsCache[key] = StreamsCacheEntry(streams, subtitles, System.currentTimeMillis())
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
            streamSortingService.sortAndFilter(
                rawStreams, enabledQualities, excludePhrases, addonSortOrders,
                profile?.sourceSortPrimary ?: "quality", profile?.sourceMaxSizeGb ?: 0,
                excludedFormats, preferredSizeMb, profile?.sourceMinimumSeeds ?: 5, profile
            )
        } else rawStreams
    }

    /** Sorts a fresh (or cached) stream result and applies it to state — auto-playing a
     *  preferred/first-playable stream when appropriate, otherwise showing the sources sidebar. */
    private suspend fun applyResolvedStreams(
        mediaType: String,
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

        val preferredStream = if (forceSourcePicker || !rememberSourceSelection) {
            null
        } else {
            sourceSelectionStore.findPreferredStream(sourceSelectionId, streams)
        }

        if (preferredStream != null) {
            _state.value = _state.value.copy(
                isLoadingStreams = false,
                sidebarState = SidebarState.Closed,
                autoPlayStream = preferredStream,
                addonSubtitles = addonSubtitles,
                availableStreams = streams,
                activeSourceSelectionId = sourceSelectionId,
                sourceListDisabled = sourceListDisabled,
                excludedSourceIds = excludedSourceIds
            )
            return
        }

        // Auto-select first playable source when enabled
        if (autoSelectSource && !forceSourcePicker) {
            val firstPlayable = streams.firstOrNull {
                !it.url.isNullOrBlank() || !it.infoHash.isNullOrBlank()
            }
            if (firstPlayable != null) {
                _state.value = _state.value.copy(
                    isLoadingStreams = false,
                    sidebarState = SidebarState.Closed,
                    autoPlayStream = firstPlayable,
                    addonSubtitles = addonSubtitles,
                    availableStreams = streams,
                    activeSourceSelectionId = sourceSelectionId,
                    sourceListDisabled = sourceListDisabled,
                    excludedSourceIds = excludedSourceIds
                )
                return
            }
        }

        // Even when the picker has to be shown (forced, no auto-play match, or nothing
        // playable), highlight and focus whichever source was last picked for this item
        // — so reopening the sources list doesn't lose your place in it.
        val highlightedStreamId = if (rememberSourceSelection) {
            sourceSelectionStore.findPreferredStream(sourceSelectionId, streams)
                ?.let { it.addonTransportUrl ?: it.url }
        } else null

        // Update sidebar with results
        _state.value = _state.value.copy(
            isLoadingStreams = false,
            autoPlayStream = null,
            addonSubtitles = addonSubtitles,
            availableStreams = streams,
            activeSourceSelectionId = sourceSelectionId,
            sourceListDisabled = sourceListDisabled,
            excludedSourceIds = excludedSourceIds,
            sidebarState = SidebarState.Sources(displayTitle, streams, selectedStreamId = highlightedStreamId)
        )
    }

    /** Silently refetches streams and, only if the sources sidebar for this same item is
     *  still open, updates the visible list — never touches autoPlayStream/isLoadingStreams,
     *  since a background refresh must not yank the user away from a list they're browsing. */
    private fun refreshStreamsInBackground(type: String, id: String, key: String, displayTitle: String) {
        backgroundRefreshJob = viewModelScope.launch {
            try {
                val streamsDeferred = async { repository.getStreams(type, id) }
                val subtitlesDeferred = async { subtitleRepository.getSubtitles(type, id) }
                val rawStreams = streamsDeferred.await()
                val addonSubtitles = subtitlesDeferred.await()
                streamsCache[key] = StreamsCacheEntry(rawStreams, addonSubtitles, System.currentTimeMillis())

                val openSources = _state.value.sidebarState as? SidebarState.Sources
                if (openSources != null) {
                    val streams = sortStreams(rawStreams, type)
                    _state.value = _state.value.copy(
                        addonSubtitles = addonSubtitles,
                        availableStreams = streams,
                        // Preserve whatever was already highlighted/focused rather than
                        // recomputing it, so a silent background refresh never yanks
                        // focus away from where the user currently is in the list.
                        sidebarState = SidebarState.Sources(displayTitle, streams, selectedStreamId = openSources.selectedStreamId)
                    )
                }
            } catch (_: Exception) {
                // Keep showing the cached results if a silent refresh fails.
            }
        }
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
        val key = "$type:$id"
        val cached = streamsCache[key]

        // Serve a still-fresh cache instantly (no spinner) and refresh it in the background —
        // sources are always updated when the list is opened, but the user only ever waits
        // on a fetch when there's nothing recent enough to show meanwhile.
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt <= STREAMS_CACHE_TTL_MS) {
            loadStreamsJob = viewModelScope.launch {
                applyResolvedStreams(
                    type, displayTitle, sourceSelectionId, forceSourcePicker, autoSelectSource,
                    rememberSourceSelection, cached.streams, cached.subtitles
                )
            }
            refreshStreamsInBackground(type, id, key, displayTitle)
            return
        }

        loadStreamsJob = viewModelScope.launch {
            // Show immediate loading feedback:
            // - Show sources sidebar when user needs to pick manually
            // - Centered spinner when auto-resolve is expected (auto-select or remembered source)
            val hasRemembered = rememberSourceSelection && sourceSelectionStore.hasRememberedSelection(sourceSelectionId)
            val showSidebar = forceSourcePicker || (!autoSelectSource && !hasRemembered)

            _state.value = _state.value.copy(
                autoPlayStream = null,
                addonSubtitles = emptyList(),
                availableStreams = emptyList(),
                isLoadingStreams = true,
                sidebarState = if (showSidebar) SidebarState.Sources(displayTitle, null)
                               else SidebarState.Closed
            )

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
                    val streamsDeferred = async { repository.getStreams(type, id) }
                    val subtitlesDeferred = async { subtitleRepository.getSubtitles(type, id) }
                    rawStreams = streamsDeferred.await()
                    addonSubtitles = subtitlesDeferred.await()
                    streamsCache[key] = StreamsCacheEntry(rawStreams, addonSubtitles, System.currentTimeMillis())
                }

                applyResolvedStreams(
                    type, displayTitle, sourceSelectionId, forceSourcePicker, autoSelectSource,
                    rememberSourceSelection, rawStreams, addonSubtitles
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoadingStreams = false,
                    autoPlayStream = null,
                    addonSubtitles = emptyList(),
                    availableStreams = emptyList(),
                    sidebarState = SidebarState.Sources(displayTitle, emptyList())
                )
            }
        }
    }

    fun consumeAutoPlayStream() {
        if (_state.value.autoPlayStream == null) return
        _state.value = _state.value.copy(autoPlayStream = null)
    }

    // --- Clear Progress (with confirmation dialog) ---

    fun confirmClearProgress() {
        val meta = _state.value.meta ?: return

        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            // Collect items to clear
            val historyItems = if (meta.type == "series") {
                dao.getSeriesEpisodeHistory("${meta.id}:%")
            } else {
                listOfNotNull(dao.getHistoryItem(meta.id))
            }

            // Delete from local DB
            if (meta.type == "series") {
                dao.deleteSeriesHistory("${meta.id}:%")
                dao.deleteSeriesNextUp(profileId, meta.id)
                sourceSelectionStore.clearSelectionsForPrefix(meta.id)
                playbackTrackSelectionStore.clearSelectionsForPrefix(meta.id)
            } else {
                dao.deleteHistoryItem(meta.id)
                sourceSelectionStore.clearSelection(meta.id)
                playbackTrackSelectionStore.clearSelection(meta.id)
            }

            // Delete from Trakt (playback progress + watched history)
            for (item in historyItems) {
                if (item.scrobbled) {
                    traktSyncManager.deletePlaybackFromTrakt(item.id)
                }
            }
            // Remove watched episodes from Trakt history for series
            if (meta.type == "series") {
                val streamId = _state.value.resolvedId ?: meta.id
                val watchedEpisodes = historyItems.filter { it.watched }
                for (ep in watchedEpisodes) {
                    val (season, episode) = parseSeasonEpisode(meta.id, ep.id) ?: continue
                    traktSyncManager.pushEpisodeUnwatched(streamId, season, episode)
                }
            }

            profileConfigurationManager.saveActiveRuntimeState()

            // Refresh episode progress map
            val streamId = _state.value.resolvedId ?: meta.id
            val updatedMap = if (meta.type == "series") buildEpisodeProgressMap(streamId) else emptyMap()

            _state.value = _state.value.copy(
                resumePlaybackId = null,
                episodeProgressMap = updatedMap
            )
        }
    }

    // --- Watchlist toggle ---

    fun toggleWatchlist() {
        val meta = _state.value.meta ?: return
        val itemId = _state.value.resolvedId ?: meta.id
        viewModelScope.launch(Dispatchers.IO) {
            if (dao.isInWatchlist(profileId, itemId)) {
                dao.removeFromWatchlist(profileId, itemId)
                traktSyncManager.pushRemove(itemId, meta.type)
            } else {
                val entity = WatchlistEntity(
                    profileId = profileId,
                    id = itemId,
                    type = meta.type,
                    title = meta.name,
                    poster = meta.poster,
                    addedAt = System.currentTimeMillis()
                )
                dao.addToWatchlist(entity)
                traktSyncManager.pushAdd(entity)
            }
        }
    }

    // 3. Close Logic
    fun closeSidebar() {
        loadStreamsJob?.cancel()
        loadStreamsJob = null
        _state.value = _state.value.copy(
            isLoadingStreams = false,
            autoPlayStream = null,
            availableStreams = emptyList(),
            sidebarState = SidebarState.Closed
        )
    }

    fun toggleSourceListDisabled() {
        val id = _state.value.activeSourceSelectionId ?: return
        val newDisabled = !_state.value.sourceListDisabled
        sourceSelectionStore.rememberSourceListDisabled(id, newDisabled)
        _state.value = _state.value.copy(sourceListDisabled = newDisabled)
    }

    fun toggleSourceExcluded(stream: Stream) {
        val id = _state.value.activeSourceSelectionId ?: return
        val streamId = stream.addonTransportUrl ?: stream.url ?: return
        val current = _state.value.excludedSourceIds
        val updated = if (streamId in current) current - streamId else current + streamId
        sourceSelectionStore.rememberExcludedSources(id, updated)
        _state.value = _state.value.copy(excludedSourceIds = updated)
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
