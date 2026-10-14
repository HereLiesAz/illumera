package com.hereliesaz.illumera.ui.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.illumera.crash.AppErrors
import com.hereliesaz.illumera.data.debrid.DebridManager
import com.hereliesaz.illumera.data.model.ProfileEntity
import com.hereliesaz.illumera.data.model.stremio.MetaVideo
import com.hereliesaz.illumera.data.model.stremio.Stream
import com.hereliesaz.illumera.data.player.PlaybackTrackSelectionStore
import com.hereliesaz.illumera.data.player.SourceSelectionStore
import com.hereliesaz.illumera.data.queue.QueueManager
import com.hereliesaz.illumera.data.repository.AddonRepository
import com.hereliesaz.illumera.data.repository.SubtitleRepository
import com.hereliesaz.illumera.data.stream.StreamSortingService
import com.hereliesaz.illumera.data.torrent.TorrentProgress
import com.hereliesaz.illumera.data.trailer.YouTubeExtractor
import com.hereliesaz.illumera.domain.AddonSubtitle
import com.hereliesaz.illumera.domain.episodeDisplayTitle
import com.hereliesaz.illumera.domain.episodePlaybackId
import com.hereliesaz.illumera.domain.episodeStreamId
import com.hereliesaz.illumera.domain.findNextEpisode
import com.hereliesaz.illumera.ui.player.PlaybackDurationStatus
import com.hereliesaz.illumera.ui.player.PlayerSessionResult
import com.hereliesaz.illumera.ui.player.base.PlayerSourceOption
import com.hereliesaz.illumera.ui.player.base.PlayerSubtitleSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Provider
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

// Addons type shows as "series", "tv" or "anime"; any of them with an episode list has a next episode.
internal val SERIES_PLAYBACK_TYPES = setOf("series", "tv", "anime", "episode")
// A next-episode hand-off that hasn't started playback by then is reported and falls back to the source list.
internal const val AUTOPLAY_STALL_MS = 45_000L
/** Longest a next-episode hand-off waits on any one subtitle lookup. */

// Once the next episode is opened: time allowed for its first frame (torrents start slowly).
internal const val AUTOPLAY_FIRST_FRAME_MS = 60_000L

/**
 * What the session asks the screen host to do. MainActivity collects these and moves the
 * root back stack (see BackStackOps).
 */
sealed interface PlaybackNav {
    /** Show the internal player. */
    data object OpenPlayer : PlaybackNav

    /** Leave the player for the page under it; [trailerEnded] when it was a trailer. */
    data class ReturnFromPlayer(val trailerEnded: Boolean = false) : PlaybackNav

    /** The queue moved on: open the next item's Details, which starts it by [queueAutoPlayId]. */
    data class OpenDetails(
        val movieId: String,
        val movieType: String,
        val title: String,
        val poster: String,
        val queueAutoPlayId: String,
        val wholeShow: Boolean
    ) : PlaybackNav

    /** Hand [url] to an external player app. */
    data class LaunchExternal(val url: String) : PlaybackNav

    /** Ask which player to use for the selected video. */
    data object ShowPlayerChoice : PlaybackNav
}

/**
 * The Details page a playback starts from: the show whose episodes play (next-episode and
 * saved-progress ids are built from [showId]), its title and art for the player, and
 * [ownerTag], the page that gets the resume hint back when the session ends.
 */
data class PlaybackOrigin(
    val ownerTag: String,
    val showId: String,
    val title: String,
    val poster: String,
    val logo: String
)

/**
 * Playback state and actions for the activity: what is playing, its sources and subtitles,
 * the torrent behind it, the autoplay/queue flags, and the jobs that switch episodes and fall
 * back between sources. Scoped to the activity, so it survives configuration changes; the
 * plain ids, titles and flags also survive process death through [SavedStateHandle]. Its jobs
 * run in [viewModelScope]; navigation goes out through [navEvents].
 */
@HiltViewModel
class PlaybackSessionViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val torrent: TorrentStreamController,
    private val addonRepository: AddonRepository,
    private val subtitleRepository: SubtitleRepository,
    private val streamSortingService: StreamSortingService,
    private val sourceSelectionStore: SourceSelectionStore,
    private val playbackTrackSelectionStore: PlaybackTrackSelectionStore,
    private val queueManager: QueueManager,
    private val debridManager: DebridManager,
    private val youTubeExtractor: Provider<YouTubeExtractor>
) : ViewModel() {

    private val navChannel = Channel<PlaybackNav>(Channel.BUFFERED)
    val navEvents: Flow<PlaybackNav> = navChannel.receiveAsFlow()

    // Saved across process death (primitives and strings only).
    var selectedVideoUrl by saved("selectedVideoUrl", "")
    var selectedTrailerAudioUrl by saved("selectedTrailerAudioUrl", "")
    var selectedPlaybackId by saved("selectedPlaybackId", "")
    var selectedPlaybackType by saved("selectedPlaybackType", "movie")
    var selectedPlaybackTitle by saved("selectedPlaybackTitle", "")
    var selectedPlaybackPoster by saved("selectedPlaybackPoster", "")
    // The playback Details should offer to resume, set when a session ends part-way. It
    // belongs to the page that started the playback (resumeHintOwner); see resumeHintFor.
    var detailsResumePlaybackHint by saved<String?>("detailsResumePlaybackHint", null)
    private var resumeHintOwner by saved<String?>("resumeHintOwner", null)

    // The Details page the current playback came from (PlaybackOrigin), not whatever page
    // was opened last: the show's id, title and logo for the player and its episode ids.
    var playbackSeriesId by saved("playbackSeriesId", "")
    var playbackSeriesTitle by saved("playbackSeriesTitle", "")
    var playbackLogo by saved("playbackLogo", "")
    private var playbackOwnerTag by saved<String?>("playbackOwnerTag", null)

    // Autoplay has two owners. Playback the queue started on its own follows the
    // queue's mode: a whole-show item plays straight through, an episode item plays
    // once and hands back to the queue. Anything the viewer starts themselves
    // follows Settings → Autoplay Next Episode, whatever the queue is set to.
    // queueStartPending marks the next play as the queue's; queuePlaybackActive
    // says the current one is.
    var queueWholeShowActive by saved("queueWholeShowActive", false)
    var queueStartPending by saved("queueStartPending", false)
    var queuePlaybackActive by saved("queuePlaybackActive", false)

    // Kept across configuration changes only.
    var torrentProgress: TorrentProgress?
        get() = torrent.progress
        set(value) { torrent.progress = value }
    // What the app is doing around playback (fallback, debrid wait); cleared on the next first frame.
    var playbackStatus by mutableStateOf<String?>(null)
    // The episode an autoplay hand-off opened, until its first frame draws.
    var awaitingFirstFrameId by mutableStateOf<String?>(null)
    var isTrailerLoading by mutableStateOf(false)
    var showTrailerError by mutableStateOf(false)

    internal var selectedPlayerSubtitles by mutableStateOf<List<PlayerSubtitlePayload>>(emptyList())
    var selectedPlayerSources by mutableStateOf<List<PlayerSourceOption>>(emptyList())
    internal var pendingSourceSelection by mutableStateOf<PendingSourceSelection?>(null)
    var showPlayerChoiceDialog by mutableStateOf(false)
    var currentEpisodeList by mutableStateOf<List<MetaVideo>>(emptyList())
    private var episodeListRecoveryJob: Job? = null

    /**
     * Series playback can outlive its Details composable. A missing/partial episode list
     * must not silently remove the Episodes and Next Episode player controls.
     * Recover the full list from installed metadata addons, with Cinemeta fallback,
     * while retaining the exact playback session identity.
     */
    private fun recoverEpisodeListIfMissing() {
        if (!isSeriesPlayback || currentEpisodeList.isNotEmpty()) return
        val seriesId = playbackSeriesId.takeIf { it.isNotBlank() } ?: return
        val playbackId = selectedPlaybackId.takeIf { it.isNotBlank() } ?: return
        if (playbackId.startsWith("trailer_")) return
        episodeListRecoveryJob?.cancel()
        episodeListRecoveryJob = viewModelScope.launch {
            try {
                val meta = addonRepository.resolveMetaDetails("series", seriesId)
                val recovered = meta?.videos.orEmpty()
                    .filter { it.season > 0 && it.episode > 0 }
                    .ifEmpty {
                        addonRepository.recoverSeriesEpisodes(
                            seriesId, meta?.name ?: playbackSeriesTitle, meta?.releaseInfo
                        )
                    }
                if (playbackSeriesId == seriesId && selectedPlaybackId == playbackId &&
                    currentEpisodeList.isEmpty() && recovered.isNotEmpty()
                ) {
                    currentEpisodeList = recovered
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("PlaybackSession", "Episode list recovery unavailable", error)
            }
        }
    }

    var currentStream by mutableStateOf<Stream?>(null)
    internal var pendingEpisodeSwitch by mutableStateOf<PendingEpisodeSwitch?>(null)
    var isEpisodeSwitchLoading by mutableStateOf(false)
    var episodeSwitchJob: Job? = null
    var episodeSwitchGeneration: Long = 0L

    // The autoplay hand-off's watchdogs, and the source-fallback job; all die with end().
    private var stallWatchdog: Job? = null
    private var firstFrameWatchdog: Job? = null
    private var fallbackJob: Job? = null
    // Where the viewer left the playing episode when picking another from the player.
    private var leavingPositionMs = 0L
    private var leavingDurationMs: Long? = null

    // ---- Derived state ----

    val isSeriesPlayback: Boolean
        get() = selectedPlaybackType.lowercase() in SERIES_PLAYBACK_TYPES

    /** Whether the current playback rolls into the next episode on its own. */
    fun autoplayNextEnabled(profileAutoplayNext: Boolean): Boolean =
        if (queuePlaybackActive) queueWholeShowActive else profileAutoplayNext

    /** A queued single episode plays once: no next episode, so its end hands back to the queue. */
    val isQueueSingleEpisode: Boolean
        get() = queuePlaybackActive && !queueWholeShowActive

    /** The episode after the current one of the playing show, when this playback has one. */
    fun nextEpisode(): MetaVideo? =
        if (isSeriesPlayback && !isQueueSingleEpisode && currentEpisodeList.isNotEmpty()) {
            findNextEpisode(playbackSeriesId, selectedPlaybackId, currentEpisodeList)
        } else null

    /**
     * The resume hint for the Details page [ownerTag]: set when a playback that page started
     * ended part-way, null for every other page.
     */
    fun resumeHintFor(ownerTag: String): String? =
        detailsResumePlaybackHint?.takeIf { resumeHintOwner == ownerTag }

    /** A title was opened fresh from a menu screen: no older hint applies to it. */
    fun clearResumeHint() {
        detailsResumePlaybackHint = null
        resumeHintOwner = null
    }

    private fun adoptOrigin(origin: PlaybackOrigin?) {
        playbackOwnerTag = origin?.ownerTag
        playbackSeriesId = origin?.showId.orEmpty()
        playbackSeriesTitle = origin?.title.orEmpty()
        playbackLogo = origin?.logo.orEmpty()
    }

    // ---- Torrent ----

    /**
     * Streams [magnet] through TorrentService. The service's callbacks write into this
     * session; by default a ready stream becomes [selectedVideoUrl].
     */
    fun startTorrent(
        magnet: String,
        fileIdx: Int,
        fileName: String,
        errorContext: String = "Stream error",
        clearProgressOnError: Boolean = true,
        onError: (String) -> Unit = {},
        onReady: (String) -> Unit = { localUrl -> selectedVideoUrl = localUrl }
    ) {
        torrent.start(
            magnet = magnet,
            fileIdx = fileIdx,
            fileName = fileName,
            errorContext = errorContext,
            clearProgressOnError = clearProgressOnError,
            onError = onError,
            onReady = onReady
        )
    }

    fun stopTorrent() {
        torrent.stop()
    }

    // ---- Starting playback ----

    /**
     * Plays [stream] picked on the Details page [origin]. [playbackTitle] is already resolved
     * against the title on screen; [persistProfileState] runs before the player opens.
     */
    fun startFromDetails(
        origin: PlaybackOrigin,
        url: String,
        playbackId: String,
        playbackType: String,
        playbackTitle: String,
        stream: Stream,
        addonSubtitles: List<AddonSubtitle>,
        availableStreams: List<Stream>,
        episodes: List<MetaVideo>,
        playerPreference: String?,
        persistProfileState: suspend () -> Unit
    ) {
        queuePlaybackActive = queueStartPending
        if (!queuePlaybackActive) queueWholeShowActive = false
        queueStartPending = false
        episodeListRecoveryJob?.cancel()
        currentEpisodeList = episodes
        currentStream = stream
        val subtitlePayload = buildSubtitlePayload(stream, addonSubtitles)
        val sourcePayloadInput = if (availableStreams.isNotEmpty()) availableStreams else listOf(stream)
        val sourcePayload = buildSourcePayload(streams = sourcePayloadInput, selectedStream = stream)
        pendingSourceSelection = PendingSourceSelection(
            playbackId = playbackId,
            launchedStream = stream,
            candidateStreams = sourcePayloadInput
        )
        fun select(videoUrl: String) {
            adoptOrigin(origin)
            selectedPlaybackId = playbackId
            selectedPlaybackType = playbackType
            selectedPlaybackTitle = playbackTitle
            selectedPlaybackPoster = origin.poster
            selectedTrailerAudioUrl = ""
            selectedPlayerSubtitles = subtitlePayload
            selectedPlayerSources = sourcePayload
            selectedVideoUrl = videoUrl
            recoverEpisodeListIfMissing()
        }
        if (url.startsWith("magnet:")) {
            viewModelScope.launch {
                persistProfileState()
                select("")
                refineSubtitlesInBackground(playbackType, playbackId, stream, addonSubtitles.ifEmpty { null })
                navChannel.trySend(PlaybackNav.OpenPlayer)
                startTorrent(url, stream.fileIdx ?: -1, stream.behaviorHints?.filename ?: "")
            }
        } else {
            stopTorrent()
            viewModelScope.launch {
                persistProfileState()
                select(url)
                refineSubtitlesInBackground(playbackType, playbackId, stream, addonSubtitles.ifEmpty { null })
                openFor(playerPreference, url)
            }
        }
    }

    /**
     * Plays a debrid library item: a pre-resolved file URL with no addon Stream or catalog
     * metadata behind it, through the same player preference as every other playback.
     */
    fun startResolved(id: String, url: String, title: String, playerPreference: String?) {
        stopTorrent()
        queueStartPending = false
        queuePlaybackActive = false
        queueWholeShowActive = false
        episodeListRecoveryJob?.cancel()
        currentEpisodeList = emptyList()
        currentStream = Stream(url = url, title = title)
        selectedPlayerSubtitles = emptyList()
        selectedPlayerSources = emptyList()
        pendingSourceSelection = null
        adoptOrigin(null)
        selectedPlaybackId = "debrid_$id"
        selectedPlaybackType = "movie"
        selectedPlaybackTitle = title
        selectedPlaybackPoster = ""
        selectedTrailerAudioUrl = ""
        selectedVideoUrl = url
        openFor(playerPreference, url)
    }

    /** Resolves the YouTube trailer [youtubeKey] of the Details page [origin] and plays it. */
    fun startTrailer(origin: PlaybackOrigin, youtubeKey: String, trailerName: String, movieType: String) {
        isTrailerLoading = true
        viewModelScope.launch {
            val source = youTubeExtractor.get().extractPlaybackSource(youtubeKey)
            isTrailerLoading = false
            if (source != null) {
                adoptOrigin(origin)
                // A trailer has no episodes; a list left from the last playback would offer one.
                currentEpisodeList = emptyList()
                selectedVideoUrl = source.videoUrl
                selectedTrailerAudioUrl = source.audioUrl ?: ""
                selectedPlaybackId = "trailer_$youtubeKey"
                selectedPlaybackType = movieType
                selectedPlaybackTitle = trailerName
                selectedPlaybackPoster = origin.poster
                selectedPlayerSubtitles = emptyList()
                selectedPlayerSources = emptyList()
                navChannel.trySend(PlaybackNav.OpenPlayer)
            } else {
                showTrailerError = true
            }
        }
    }

    private fun openFor(playerPreference: String?, url: String) {
        navChannel.trySend(
            when (playerPreference) {
                "external" -> PlaybackNav.LaunchExternal(url)
                "ask" -> PlaybackNav.ShowPlayerChoice
                else -> PlaybackNav.OpenPlayer
            }
        )
    }

    // ---- Episode switching ----

    /**
     * The current episode reached its end and [nextEpisode] of the playing show follows: marks
     * this one completed and opens the next, by bingeGroup, remembered source or first playable
     * source, or shows its source list. Two watchdogs report a hand-off that stalls.
     */
    fun autoplayNextEpisode(
        nextEpisode: MetaVideo,
        playerCurrentSourceUrl: String?,
        profile: ProfileEntity?
    ) {
        // Read before the session end below consumes it.
        val watchedCandidates = pendingSourceSelection?.candidateStreams
        // Mark current episode as completed
        settle(
            PlayerSessionResult(
                positionMs = 0L,
                durationMs = null,
                isCompleted = true,
                selectedSourceUrl = playerCurrentSourceUrl ?: selectedVideoUrl,
                selectedAudioTrackId = null,
                selectedSubtitleTrackId = null
            ),
            profile
        )

        val nextPlaybackId = episodePlaybackId(playbackSeriesId, nextEpisode)
        val nextStreamId = episodeStreamId(playbackSeriesId, nextEpisode)
        val nextPlaybackTitle = episodeDisplayTitle(nextEpisode)

        val autoplay = autoplayNextEnabled(profile?.autoplayNextEpisode == true)
        val autoSelect = profile?.autoSelectSource == true
        val willAutoResolve = autoplay || autoSelect
        beginSwitch()
        isEpisodeSwitchLoading = true
        pendingEpisodeSwitch = if (!willAutoResolve) {
            PendingEpisodeSwitch(
                playbackId = nextPlaybackId,
                playbackTitle = nextPlaybackTitle,
                streamRequestId = nextStreamId,
                streams = null,
                addonSubs = emptyList(),
                playerCurrentSourceUrl = playerCurrentSourceUrl
            )
        } else {
            null
        }

        // Each step names itself on screen (the loading feed) and in the
        // stall report, so a hand-off that stops says where it stopped.
        val switchGeneration = episodeSwitchGeneration
        var switchStep = "finding sources"
        var fetchedStreams: List<Stream>? = null
        if (willAutoResolve) playbackStatus = "Next episode · finding sources for $nextPlaybackTitle"
        fun showSourceList() {
            playbackStatus = null
            isEpisodeSwitchLoading = false
            pendingEpisodeSwitch = PendingEpisodeSwitch(
                playbackId = nextPlaybackId,
                playbackTitle = nextPlaybackTitle,
                streamRequestId = nextStreamId,
                streams = fetchedStreams.orEmpty(),
                addonSubs = emptyList(),
                playerCurrentSourceUrl = playerCurrentSourceUrl
            )
        }
        if (willAutoResolve) stallWatchdog = viewModelScope.launch {
            delay(AUTOPLAY_STALL_MS)
            val stillSwitching = episodeSwitchGeneration == switchGeneration &&
                selectedPlaybackId != nextPlaybackId && pendingEpisodeSwitch == null
            if (stillSwitching) {
                AppErrors.e(
                    "Autoplay",
                    "Next episode didn't start within ${AUTOPLAY_STALL_MS / 1000}s; stopped at: $switchStep"
                )
                episodeSwitchJob?.cancel()
                showSourceList()
            }
        }

        episodeSwitchJob = viewModelScope.launch {
            try {
                val rawStreams = requestOrFallback(emptyList()) { addonRepository.getStreams("series", nextStreamId) }
                // Subtitles are fetched once the episode is playing (refineSubtitlesInBackground).
                val addonSubs = emptyList<AddonSubtitle>()
                switchStep = "ranking ${rawStreams.size} sources"

                // Off the main thread: ranking a long list froze the UI on TV boxes.
                val ranked = rankStreams(rawStreams, profile, offMainThread = true)
                fetchedStreams = ranked
                // The next episode never plays from the file just watched: a stream that
                // resolves to it (same link, or same torrent file) would only replay it.
                val watchedUrl = playerCurrentSourceUrl ?: selectedVideoUrl
                val watched = currentStream
                val streams = ranked.filterNot { isSameFile(it, watched, watchedUrl) }
                if (streams.size < ranked.size) {
                    // Expected behavior, not a fault: logged, never filed.
                    android.util.Log.i("Autoplay", "Skipped ${ranked.size - streams.size} source(s) for $nextPlaybackTitle that point at the episode just watched")
                }
                switchStep = "choosing from ${streams.size} sources"

                if (streams.isEmpty()) {
                    playbackStatus = null
                    isEpisodeSwitchLoading = false
                    pendingEpisodeSwitch = PendingEpisodeSwitch(
                        playbackId = nextPlaybackId,
                        playbackTitle = nextPlaybackTitle,
                        streamRequestId = nextStreamId,
                        streams = emptyList(),
                        addonSubs = emptyList(),
                        playerCurrentSourceUrl = playerCurrentSourceUrl
                    )
                    return@launch
                }

                val bingeMatch = bingeGroupMatch(
                    streams, watchedCandidates, playerCurrentSourceUrl, enabled = autoplay || autoSelect
                )
                // Priority 2: Remembered source
                val rememberSource = profile?.rememberSourceSelection ?: true
                val preferred = if (rememberSource) sourceSelectionStore.findPreferredStream(nextPlaybackId, streams) else null
                // Priority 3: First playable (autoplay or autoSelectSource)
                val streamToPlay = bingeMatch
                    ?: preferred
                    ?: if (autoplay || autoSelect) streams.firstOrNull(::isPlayable) else null
                val nextUrl = streamToPlay?.let(::resolvePlayableSourceUrl)

                if (streamToPlay == null || nextUrl == null) {
                    playbackStatus = null
                    isEpisodeSwitchLoading = false
                    pendingEpisodeSwitch = PendingEpisodeSwitch(
                        playbackId = nextPlaybackId,
                        playbackTitle = nextPlaybackTitle,
                        streamRequestId = nextStreamId,
                        streams = streams,
                        addonSubs = addonSubs,
                        playerCurrentSourceUrl = playerCurrentSourceUrl
                    )
                    return@launch
                }

                // Auto-resolved: keep the guard active through subtitle refinement.
                pendingEpisodeSwitch = null
                switchStep = "opening the source"
                playbackStatus = "Next episode · opening $nextPlaybackTitle"
                // Opening can stall after the switch itself is done: the new
                // episode never draws. Wait for its first frame, not the switch.
                awaitingFirstFrameId = nextPlaybackId
                val openedKind = if (nextUrl.startsWith("magnet:")) "torrent" else "direct link"
                val openedFrom = streamToPlay.addonDisplayName ?: "unknown addon"
                firstFrameWatchdog = viewModelScope.launch {
                    delay(AUTOPLAY_FIRST_FRAME_MS)
                    if (episodeSwitchGeneration == switchGeneration &&
                        awaitingFirstFrameId == nextPlaybackId
                    ) {
                        AppErrors.e(
                            "Autoplay",
                            "Next episode opened ($openedKind from $openedFrom) but drew no frame in " +
                                "${AUTOPLAY_FIRST_FRAME_MS / 1000}s; url set: ${selectedVideoUrl.isNotBlank()}, " +
                                "torrent: ${torrentProgress?.status ?: "none"}"
                        )
                        awaitingFirstFrameId = null
                        showSourceList()
                    }
                }
                openEpisode(
                    playbackId = nextPlaybackId,
                    title = nextPlaybackTitle,
                    url = nextUrl,
                    stream = streamToPlay,
                    candidates = streams,
                    subtitles = addonSubs,
                    subtitleRequestId = nextStreamId
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                AppErrors.e("Autoplay", "Next-episode hand-off failed while $switchStep", e)
                showSourceList()
            }
        }
    }

    /** The viewer picked [episode] of the playing show from the player's episode list. */
    fun selectEpisode(
        episode: MetaVideo,
        playerCurrentSourceUrl: String?,
        profile: ProfileEntity?,
        positionMs: Long = 0L,
        durationMs: Long? = null
    ) {
        // Guard against a double-tap/rapid re-selection firing a second
        // independent switch while one is already resolving — whichever
        // network call happened to finish last would otherwise win,
        // regardless of which episode the user actually intended last.
        if (isEpisodeSwitchLoading) return
        val epPlaybackId = episodePlaybackId(playbackSeriesId, episode)
        val epStreamId = episodeStreamId(playbackSeriesId, episode)
        val epTitle = episodeDisplayTitle(episode)

        // Picking an episode by hand is the viewer's choice, not the queue's.
        queuePlaybackActive = false
        queueWholeShowActive = false
        val autoplay = profile?.autoplayNextEpisode == true
        val autoSelect = profile?.autoSelectSource == true
        val willAutoResolve = autoplay || autoSelect
        beginSwitch()
        leavingPositionMs = positionMs
        leavingDurationMs = durationMs
        isEpisodeSwitchLoading = true
        pendingEpisodeSwitch = if (!willAutoResolve) {
            PendingEpisodeSwitch(
                playbackId = epPlaybackId,
                playbackTitle = epTitle,
                streamRequestId = epStreamId,
                streams = null,
                addonSubs = emptyList(),
                playerCurrentSourceUrl = playerCurrentSourceUrl
            )
        } else {
            null
        }

        episodeSwitchJob = viewModelScope.launch {
            val streamsDeferred = async { requestOrFallback(emptyList()) { addonRepository.getStreams("series", epStreamId) } }
            val subtitlesDeferred = async { requestOrFallback(emptyList()) { subtitleRepository.getSubtitles("series", epStreamId) } }

            val rawStreams = streamsDeferred.await()
            val addonSubs = subtitlesDeferred.await()

            val streams = rankStreams(rawStreams, profile, offMainThread = false)

            if (streams.isEmpty()) {
                isEpisodeSwitchLoading = false
                pendingEpisodeSwitch = PendingEpisodeSwitch(
                    playbackId = epPlaybackId,
                    playbackTitle = epTitle,
                    streamRequestId = epStreamId,
                    streams = emptyList(),
                    addonSubs = emptyList(),
                    playerCurrentSourceUrl = playerCurrentSourceUrl
                )
                return@launch
            }

            val bingeMatch = bingeGroupMatch(
                streams, pendingSourceSelection?.candidateStreams, playerCurrentSourceUrl, enabled = autoplay || autoSelect
            )
            // Priority 2: Auto-select first available (only when autoSelectSource is on)
            val streamToPlay = bingeMatch
                ?: if (autoSelect) streams.firstOrNull(::isPlayable) else null
            val epUrl = streamToPlay?.let(::resolvePlayableSourceUrl)

            if (streamToPlay == null || epUrl == null) {
                isEpisodeSwitchLoading = false
                pendingEpisodeSwitch = PendingEpisodeSwitch(
                    playbackId = epPlaybackId,
                    playbackTitle = epTitle,
                    streamRequestId = epStreamId,
                    streams = streams,
                    addonSubs = addonSubs,
                    playerCurrentSourceUrl = playerCurrentSourceUrl
                )
                return@launch
            }

            // Auto-resolved: keep the guard active through subtitle refinement.
            pendingEpisodeSwitch = null
            settle(
                PlayerSessionResult(
                    positionMs = leavingPositionMs,
                    durationMs = leavingDurationMs,
                    isCompleted = false,
                    selectedSourceUrl = playerCurrentSourceUrl ?: selectedVideoUrl,
                    selectedAudioTrackId = null,
                    selectedSubtitleTrackId = null
                ),
                profile
            )

            openEpisode(
                playbackId = epPlaybackId,
                title = epTitle,
                url = epUrl,
                stream = streamToPlay,
                candidates = streams,
                subtitles = addonSubs,
                subtitleRequestId = epStreamId
            )
        }
    }

    /** The viewer picked [sourceUrl] from the pending episode switch's source list. */
    fun pickEpisodeSwitchSource(sourceUrl: String, profile: ProfileEntity?) {
        val pending = pendingEpisodeSwitch ?: return
        val streamToPlay = pending.streams?.firstOrNull { resolvePlayableSourceUrl(it) == sourceUrl }
        if (streamToPlay == null) {
            pendingEpisodeSwitch = null
            return
        }

        beginSwitch()
        pendingEpisodeSwitch = null
        isEpisodeSwitchLoading = true
        episodeSwitchJob = viewModelScope.launch {
            // Settle the episode being left at the position it was left at.
            settle(
                PlayerSessionResult(
                    positionMs = leavingPositionMs,
                    durationMs = leavingDurationMs,
                    isCompleted = false,
                    selectedSourceUrl = pending.playerCurrentSourceUrl ?: selectedVideoUrl,
                    selectedAudioTrackId = null,
                    selectedSubtitleTrackId = null
                ),
                profile
            )
            pendingEpisodeSwitch = null
            openEpisode(
                playbackId = pending.playbackId,
                title = pending.playbackTitle,
                url = sourceUrl,
                stream = streamToPlay,
                candidates = pending.streams,
                subtitles = pending.addonSubs,
                subtitleRequestId = pending.streamRequestId
            )
        }
    }

    /** The viewer closed the pending episode switch; a result still on its way is dropped. */
    fun dismissEpisodeSwitch() {
        episodeSwitchGeneration += 1L
        episodeSwitchJob?.cancel()
        episodeSwitchJob = null
        cancelWatchdogs()
        pendingEpisodeSwitch = null
        isEpisodeSwitchLoading = false
    }

    // ---- In the player ----

    /** Subtitles for [source] when the viewer switches to it. */
    suspend fun resolveSourceSubtitles(source: PlayerSourceOption): List<PlayerSubtitleSource> {
        val stream = source.addonStream
        val requestType = stream?.addonRequestType
        val requestId = stream?.addonRequestId
        return if (stream == null || requestType.isNullOrBlank() || requestId.isNullOrBlank()) {
            selectedPlayerSubtitles.toPlayerSubtitleSources()
        } else {
            val addonSubs = subtitleRepository.getSubtitlesForStream(
                type = requestType,
                playbackId = requestId,
                stream = stream
            )
            buildSubtitlePayload(stream, addonSubs).toPlayerSubtitleSources()
        }
    }

    /** The viewer switched to a torrent source; [onReady]/[onError] come from the player. */
    fun selectMagnetSource(
        magnetUrl: String,
        fileIdx: Int,
        fileName: String,
        onReady: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        pendingSourceSelection?.candidateStreams
            ?.firstOrNull { resolvePlayableSourceUrl(it) == magnetUrl }
            ?.let { currentStream = it }
        startTorrent(
            magnetUrl, fileIdx, fileName,
            errorContext = "Source switch error",
            onError = onError,
            onReady = onReady
        )
    }

    /**
     * The player switched to [sourceUrl] on its own (its fallback or the viewer's pick).
     * Tracks it as the current stream so the ranked fallback continues from there, and
     * stops a torrent the player has left for a direct source: its later failure would
     * otherwise advance the ranked list away from the source now playing.
     */
    fun onPlayerSourceChanged(sourceUrl: String) {
        val stream = pendingSourceSelection?.candidateStreams
            ?.firstOrNull { resolvePlayableSourceUrl(it) == sourceUrl }
            ?: return
        if (stream === currentStream) return
        currentStream = stream
        // Unconditional: a streaming torrent has already cleared its progress.
        if (!sourceUrl.startsWith("magnet:")) stopTorrent()
    }

    fun onFirstFrame() {
        playbackStatus = null
        awaitingFirstFrameId = null
    }

    /**
     * The player suspects the current source (bogus duration, or a debrid service still
     * downloading it): waits for the debrid download when that is the reason, otherwise
     * falls back to the next ranked source.
     */
    fun onSuspectSource(status: PlaybackDurationStatus, profile: ProfileEntity?) {
        // One fallback at a time; a second signal must not skip a source mid-start.
        fallbackJob?.cancel()
        fallbackJob = viewModelScope.launch {
            val stream = currentStream
            if (status == PlaybackDurationStatus.DEBRID_DOWNLOADING && stream != null) {
                val maxWait = profile?.sourceDebridMaxWaitSeconds ?: 120
                playbackStatus = "Your debrid service is still downloading this · waiting up to ${maxWait}s"
                val readyUrl = debridManager.awaitPlayableSource(
                    infoHash = stream.infoHash,
                    fileName = stream.behaviorHints?.filename,
                    maxWaitSeconds = maxWait
                ) { percent ->
                    playbackStatus = "Your debrid service is downloading this · $percent% · waiting up to ${maxWait}s"
                }
                if (!readyUrl.isNullOrBlank()) {
                    playbackStatus = "Download finished · opening the video"
                    selectedVideoUrl = readyUrl
                    currentStream = stream.copy(url = readyUrl)
                    return@launch
                }
            }
            tryNextRankedSource()
        }
    }

    /** Opens the source ranked after the current one, or leaves the player when none is left. */
    internal suspend fun tryNextRankedSource() {
        val pending = pendingSourceSelection
        val candidates = pending?.candidateStreams.orEmpty()
        val current = currentStream
        val currentIndex = candidates.indexOfFirst { candidate ->
            candidate === current || resolvePlayableSourceUrl(candidate) == selectedVideoUrl ||
                (current != null && candidate.infoHash != null && candidate.infoHash == current.infoHash && candidate.addonTransportUrl == current.addonTransportUrl)
        }
        // If currentIndex is -1 (stream not found), drop(0) would wrap back
        // to the first candidate and loop forever. Guard: no stream found = no fallback.
        val nextStream = if (currentIndex < 0) null
        else candidates.drop(currentIndex + 1).firstOrNull(::isPlayable)
        if (nextStream == null) {
            playbackStatus = null
            pendingSourceSelection = null
            navChannel.trySend(PlaybackNav.ReturnFromPlayer())
            return
        }
        val nextPosition = candidates.indexOf(nextStream) + 1
        playbackStatus = "That source didn't play · trying source $nextPosition of ${candidates.size}"

        val nextUrl = resolvePlayableSourceUrl(nextStream)
        if (nextUrl == null) {
            playbackStatus = null
            navChannel.trySend(PlaybackNav.ReturnFromPlayer())
            return
        }
        currentStream = nextStream
        pendingSourceSelection = PendingSourceSelection(
            playbackId = selectedPlaybackId,
            launchedStream = nextStream,
            candidateStreams = candidates
        )
        selectedPlayerSources = buildSourcePayload(candidates, nextStream)

        val requestType = nextStream.addonRequestType
        val requestId = nextStream.addonRequestId
        if (!requestType.isNullOrBlank() && !requestId.isNullOrBlank()) {
            // Opens with the stream's own subtitles; addon matches follow in the background.
            selectedPlayerSubtitles = buildSubtitlePayload(nextStream, emptyList())
            refineSubtitlesInBackground(requestType, requestId, nextStream, null)
        }
        playbackStatus = "Opening source $nextPosition of ${candidates.size}"

        if (nextUrl.startsWith("magnet:")) {
            selectedVideoUrl = ""
            // TorrentProgress(sourceError=true) is the one signal that advances
            // the ranked list, so an error here only logs (and keeps the progress).
            startTorrent(
                nextUrl, nextStream.fileIdx ?: -1, nextStream.behaviorHints?.filename ?: "",
                errorContext = "Ranked fallback source error",
                clearProgressOnError = false
            )
        } else {
            stopTorrent()
            selectedVideoUrl = nextUrl
        }
    }

    /**
     * The viewer left the player. Settles the session's remembered tracks and source, stops
     * the torrent and the session's jobs, then returns to Details or, after a finished
     * playback with the queue on, opens the queue's next item.
     */
    fun end(sessionResult: PlayerSessionResult, profile: ProfileEntity?) {
        cancelBackgroundWork()
        torrentProgress = null
        playbackStatus = null
        settle(sessionResult, profile)
        stopTorrent()
        if (selectedPlaybackId.startsWith("trailer_")) {
            navChannel.trySend(PlaybackNav.ReturnFromPlayer(trailerEnded = true))
        } else if (sessionResult.isCompleted && queueManager.state.value.preferences.enabled) {
            val next = queueManager.advanceAfterPlayback(selectedPlaybackId)
            if (next != null) {
                val movieType = if (next.type == "movie") "movie" else "series"
                selectedPlaybackId = next.id
                selectedPlaybackType = movieType
                selectedPlaybackTitle = next.title
                selectedPlaybackPoster = next.poster ?: ""
                queueWholeShowActive = next.wholeShow
                queueStartPending = true
                navChannel.trySend(
                    PlaybackNav.OpenDetails(
                        movieId = next.seriesId ?: next.id,
                        movieType = movieType,
                        title = next.title,
                        poster = next.poster ?: "",
                        queueAutoPlayId = next.id,
                        wholeShow = next.wholeShow
                    )
                )
                viewModelScope.launch(Dispatchers.Main) { queueManager.ensureSuggestions() }
            } else {
                queueWholeShowActive = false
                queuePlaybackActive = false
                navChannel.trySend(PlaybackNav.ReturnFromPlayer())
            }
        } else {
            navChannel.trySend(PlaybackNav.ReturnFromPlayer())
        }
    }

    // ---- Helpers ----

    private fun settle(result: PlayerSessionResult, profile: ProfileEntity?) {
        handlePlayerSessionEnd(
            sessionResult = result,
            selectedPlaybackId = selectedPlaybackId,
            playbackTrackSelectionStore = playbackTrackSelectionStore,
            sourceSelectionStore = sourceSelectionStore,
            pendingSourceSelection = pendingSourceSelection,
            onConsumePendingSelection = { pendingSourceSelection = null },
            onResumeHintResolved = {
                detailsResumePlaybackHint = it
                resumeHintOwner = playbackOwnerTag
            },
            rememberSourceSelection = profile?.rememberSourceSelection ?: true
        )
    }

    private suspend fun rankStreams(
        rawStreams: List<Stream>,
        profile: ProfileEntity?,
        offMainThread: Boolean
    ): List<Stream> {
        if (profile?.sourceSortingEnabled != true) return rawStreams
        val rank: suspend () -> List<Stream> = {
            val enabledQ = StreamSortingService.parseEnabledQualities(profile.sourceEnabledQualities)
            val excludeP = StreamSortingService.parseExcludePhrases(profile.sourceExcludePhrases)
            val addonOrders = addonRepository.getAddonSortOrders()
            val excludedF = StreamSortingService.parseExcludedFormats(profile.sourceExcludedFormats)
            streamSortingService.sortAndFilter(
                rawStreams, enabledQ, excludeP, addonOrders, profile.sourceSortPrimary,
                profile.sourceMaxSizeGb, excludedF, profile.sourceEpisodeTargetSizeMb,
                profile.sourceMinimumSeeds, profile
            )
        }
        return if (offMainThread) withContext(Dispatchers.Default) { rank() } else rank()
    }

    /**
     * Priority 1 of an episode switch: the source of [streams] in the same bingeGroup and
     * addon as the one being watched (the player's current source when it switched).
     */
    private fun bingeGroupMatch(
        streams: List<Stream>,
        watchedCandidates: List<Stream>?,
        playerCurrentSourceUrl: String?,
        enabled: Boolean
    ): Stream? {
        // Resolve the actual stream the user was watching (may differ from initial if they switched sources)
        val actualStream = if (playerCurrentSourceUrl != null) {
            watchedCandidates?.firstOrNull { candidate ->
                resolvePlayableSourceUrl(candidate) == playerCurrentSourceUrl
            } ?: currentStream
        } else currentStream
        val currentBingeGroup = actualStream?.behaviorHints?.bingeGroup
        val currentAddonUrl = actualStream?.addonTransportUrl
        return if (enabled && !currentBingeGroup.isNullOrBlank()) {
            streams.firstOrNull {
                it.behaviorHints?.bingeGroup == currentBingeGroup &&
                    it.addonTransportUrl == currentAddonUrl &&
                    isPlayable(it)
            }
        } else null
    }

    private fun isPlayable(stream: Stream): Boolean =
        !stream.url.isNullOrBlank() || !stream.infoHash.isNullOrBlank()

    private var subtitleRefineJob: Job? = null

    /**
     * Playback never waits for subtitles. It opens with the generic results already in hand,
     * and this asks the addons for matches to the exact file (videoHash/size/filename) in the
     * background; the player adds whatever arrives while the stream keeps playing.
     */
    private fun refineSubtitlesInBackground(
        type: String,
        requestId: String,
        stream: Stream,
        generic: List<AddonSubtitle>?
    ) {
        subtitleRefineJob?.cancel()
        val playbackId = selectedPlaybackId
        subtitleRefineJob = viewModelScope.launch {
            val refined = subtitleRepository.getSubtitlesForStream(
                type = type,
                playbackId = requestId,
                stream = stream,
                fallback = generic
            )
            if (selectedPlaybackId != playbackId || currentStream != stream) return@launch
            selectedPlayerSubtitles = buildSubtitlePayload(stream, refined)
        }
    }

    /** Puts a resolved episode on screen; the player recomposes on the new id and url. */
    private fun openEpisode(
        playbackId: String,
        title: String,
        url: String,
        stream: Stream,
        candidates: List<Stream>,
        subtitles: List<AddonSubtitle>,
        subtitleRequestId: String
    ) {
        // A debrid wait or ranked fallback for the episode being left must not land on this one.
        fallbackJob?.cancel()
        fallbackJob = null
        val subtitlePayload = buildSubtitlePayload(stream, subtitles)
        val sourcePayload = buildSourcePayload(candidates, stream)
        pendingSourceSelection = PendingSourceSelection(
            playbackId = playbackId,
            launchedStream = stream,
            candidateStreams = candidates
        )
        currentStream = stream
        isEpisodeSwitchLoading = false

        if (url.startsWith("magnet:")) {
            // Drop the previous episode's URL so it doesn't replay under the new title.
            selectedVideoUrl = ""
            selectedPlaybackId = playbackId
            selectedPlaybackType = "series"
            selectedPlaybackTitle = title
            selectedPlayerSubtitles = subtitlePayload
            selectedPlayerSources = sourcePayload
            startTorrent(url, stream.fileIdx ?: -1, stream.behaviorHints?.filename ?: "")
        } else {
            stopTorrent()
            selectedPlaybackId = playbackId
            selectedPlaybackType = "series"
            selectedPlaybackTitle = title
            selectedPlayerSubtitles = subtitlePayload
            selectedPlayerSources = sourcePayload
            selectedVideoUrl = url
        }
        // Empty means none were fetched yet: let the refinement fetch the generic list too.
        refineSubtitlesInBackground("series", subtitleRequestId, stream, subtitles.ifEmpty { null })
    }

    /** A new hand-off supersedes the running one: its job and watchdogs stop, its late results are dropped. */
    private fun beginSwitch() {
        episodeSwitchJob?.cancel()
        episodeSwitchGeneration += 1L
        cancelWatchdogs()
    }

    private fun cancelWatchdogs() {
        stallWatchdog?.cancel()
        stallWatchdog = null
        firstFrameWatchdog?.cancel()
        firstFrameWatchdog = null
    }

    /**
     * Stops the episode switch, its watchdogs and the source fallback when the player
     * closes, and drops what they would have settled, so the next playback doesn't wait on
     * them or get switched by a result that arrives after the viewer left.
     */
    private fun cancelBackgroundWork() {
        episodeSwitchGeneration += 1L
        episodeSwitchJob?.cancel()
        episodeSwitchJob = null
        cancelWatchdogs()
        fallbackJob?.cancel()
        fallbackJob = null
        if (pendingEpisodeSwitch?.streams == null) pendingEpisodeSwitch = null
        isEpisodeSwitchLoading = false
        awaitingFirstFrameId = null
    }

    override fun onCleared() {
        torrent.release()
    }

    private fun <T> saved(key: String, default: T): ReadWriteProperty<Any?, T> =
        object : ReadWriteProperty<Any?, T> {
            private val state = mutableStateOf(
                if (savedStateHandle.contains(key)) savedStateHandle.get<T>(key) as T else default
            )

            override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
                state.value = value
                savedStateHandle[key] = value
            }
        }
}
