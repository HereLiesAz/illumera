package com.hereliesaz.illumera.ui.player

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import com.hereliesaz.illumera.ui.player.base.BasePlayerScaffold
import com.hereliesaz.illumera.ui.player.base.NextEpisodeInfo
import com.hereliesaz.illumera.ui.player.base.PlaybackSettings
import com.hereliesaz.illumera.ui.player.base.ExoPlayerBackend
import com.hereliesaz.illumera.ui.player.base.PlayerBackendFactory
import com.hereliesaz.illumera.ui.player.base.PlayerBackendType
import com.hereliesaz.illumera.ui.player.base.PlayerLoadRequest
import com.hereliesaz.illumera.ui.player.base.PlayerSourceOption
import com.hereliesaz.illumera.ui.player.base.PlayerSubtitleSource
import com.hereliesaz.illumera.ui.player.base.SkipSegmentInfo
import com.hereliesaz.illumera.ui.player.base.isWithinEndWindow
import com.hereliesaz.illumera.data.model.stremio.MetaVideo
import com.hereliesaz.illumera.data.torrent.TorrentProgress

data class PlayerSessionResult(
    val positionMs: Long,
    val durationMs: Long?,
    val isCompleted: Boolean,
    val selectedSourceUrl: String?,
    val selectedAudioTrackId: String?,
    val selectedSubtitleTrackId: String?,
    val subtitleDelayMs: Long = 0L
)

@Composable
fun PlayerScreen(
    videoUrl: String,
    trailerAudioUrl: String? = null,
    title: String,
    seriesTitle: String? = null,
    logoUrl: String? = null,
    poster: String,
    movieId: String,
    mediaType: String,
    seriesId: String? = null,
    onBack: (PlayerSessionResult) -> Unit,
    backendType: PlayerBackendType = PlayerBackendType.EXOPLAYER,
    sources: List<PlayerSourceOption> = emptyList(),
    subtitles: List<PlayerSubtitleSource> = emptyList(),
    preferredAudioTrackId: String? = null,
    preferredSubtitleTrackId: String? = null,
    initialSubtitleDelayMs: Long = 0L,
    playbackSettings: PlaybackSettings = PlaybackSettings(),
    skipSegmentInfo: SkipSegmentInfo? = null,
    nextEpisodeInfo: NextEpisodeInfo? = null,
    onAutoplayNextEpisode: ((currentSourceUrl: String?) -> Unit)? = null,
    episodes: List<MetaVideo> = emptyList(),
    currentPlaybackId: String? = null,
    onEpisodeSelected: ((episode: MetaVideo, currentSourceUrl: String?, positionMs: Long, durationMs: Long?) -> Unit)? = null,
    episodeSwitchSources: List<PlayerSourceOption>? = null,
    isEpisodeSwitchLoading: Boolean = false,
    episodeSwitchTitle: String? = null,
    onEpisodeSwitchSourceSelected: ((sourceUrl: String) -> Unit)? = null,
    onEpisodeSwitchDismissed: (() -> Unit)? = null,
    onResolveSourceSubtitles: (suspend (PlayerSourceOption) -> List<PlayerSubtitleSource>)? = null,
    onMagnetSourceSelected: ((magnetUrl: String, fileIdx: Int, fileName: String, onReady: (localUrl: String) -> Unit, onError: (message: String) -> Unit) -> Unit)? = null,
    torrentProgress: TorrentProgress? = null,
    playbackStatus: String? = null,
    /** Called when a stream draws its first frame, so a [playbackStatus] can be cleared. */
    onFirstFrameRendered: () -> Unit = {},
    autoFallbackEnabled: Boolean = false,
    onSuspectSource: ((PlaybackDurationStatus) -> Unit)? = null,
    onSourceChanged: ((String) -> Unit)? = null,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val runtime = remember(movieId, backendType, playbackSettings) {
        PlayerBackendFactory.create(context, backendType, playbackSettings)
    }
    val playbackController = runtime.playbackController
    val renderSurface = runtime.renderSurface

    LaunchedEffect(playbackController, onMagnetSourceSelected, onResolveSourceSubtitles) {
        (playbackController as? ExoPlayerBackend)?.apply {
            this.onMagnetSourceSelected = onMagnetSourceSelected
            this.onResolveSourceSubtitles = onResolveSourceSubtitles
        }
    }

    // Subtitles found after playback started (stream-specific matches fetched in the
    // background) join the running player instead of holding the stream back.
    LaunchedEffect(playbackController, videoUrl, subtitles) {
        if (videoUrl.isNotBlank()) {
            (playbackController as? ExoPlayerBackend)?.addExternalSubtitles(subtitles, forMediaUrl = videoUrl)
        }
    }

    // Pre-create ExoPlayer + OkHttpClient while torrent pieces are still downloading.
    // By the time the URL arrives, the player is ready — prepareSource() just calls prepare().
    LaunchedEffect(playbackController, videoUrl) {
        if (videoUrl.isBlank()) {
            (playbackController as? ExoPlayerBackend)?.warmup()
        }
    }
    val uiState by playbackController.uiState.collectAsStateWithLifecycle()
    var suspectHandledForUrl by remember(videoUrl) { mutableStateOf(false) }
    var handledTorrentFailureAttemptId by remember(movieId) { mutableStateOf<Long?>(null) }

    // The URL the player was last told to load. Until it matches videoUrl the player still
    // holds the previous source's state (a torrent resolving, or a load not yet issued).
    var loadedUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.isReady, uiState.durationMs, videoUrl, loadedUrl, autoFallbackEnabled) {
        if (!autoFallbackEnabled || suspectHandledForUrl || videoUrl.isBlank() || loadedUrl != videoUrl) return@LaunchedEffect
        // Live state, not the composed snapshot: that can lag the load's isReady reset by a frame.
        val state = playbackController.uiState.value
        if (!state.isReady || state.durationMs <= 0L) return@LaunchedEffect
        if (movieId.startsWith("trailer_") || movieId.startsWith("debrid_")) return@LaunchedEffect
        val status = viewModel.classifyDuration(mediaType, state.durationMs)
        if (status != PlaybackDurationStatus.NORMAL) {
            suspectHandledForUrl = true
            playbackController.pause()
            onSuspectSource?.invoke(status)
        }
    }

    // The player's own fallback switches sources without the session; report each switch
    // so the session's ranked fallback continues from the source actually playing.
    // Keyed on the id alone: a new source list arrives before the player opens its source.
    LaunchedEffect(uiState.currentSourceId) {
        if (videoUrl.isBlank()) return@LaunchedEffect
        val url = sources.firstOrNull { it.id == uiState.currentSourceId }?.url ?: return@LaunchedEffect
        onSourceChanged?.invoke(url)
    }

    LaunchedEffect(uiState.hasRenderedFirstFrame) {
        if (uiState.hasRenderedFirstFrame) onFirstFrameRendered()
    }

    val shouldKeepScreenOn = uiState.playWhenReady || uiState.isPlaying || uiState.isBuffering

    DisposableEffect(hostView, shouldKeepScreenOn) {
        hostView.keepScreenOn = shouldKeepScreenOn
        onDispose {
            hostView.keepScreenOn = false
        }
    }

    // Trakt scrobble: track last known state for episode switch detection
    var lastScrobbleId by remember { mutableStateOf(movieId) }
    var lastScrobblePositionMs by remember { mutableStateOf(0L) }
    var lastScrobbleDurationMs by remember { mutableStateOf(0L) }

    // Update last known state while playing
    LaunchedEffect(uiState.positionMs, uiState.durationMs) {
        if (uiState.durationMs > 0L) {
            lastScrobblePositionMs = uiState.positionMs
            lastScrobbleDurationMs = uiState.durationMs
        }
    }

    // Stop previous episode when switching to a new one
    LaunchedEffect(movieId) {
        if (lastScrobbleId != movieId && lastScrobbleDurationMs > 0L) {
            viewModel.scrobbleStop(lastScrobbleId, mediaType, lastScrobblePositionMs, lastScrobbleDurationMs)
        }
        lastScrobbleId = movieId
    }

    // Trakt scrobble: start when playing, pause when paused
    LaunchedEffect(uiState.isPlaying) {
        if (uiState.durationMs <= 0L) return@LaunchedEffect
        if (uiState.isPlaying) {
            viewModel.scrobbleStart(movieId, mediaType, uiState.positionMs, uiState.durationMs)
        } else if (uiState.isReady) {
            viewModel.scrobblePause(movieId, mediaType, uiState.positionMs, uiState.durationMs)
        }
    }

    DisposableEffect(playbackController) {
        onDispose {
            // Safety net: persist whatever progress was reached even if this
            // composable leaves composition without going through persistAndBack
            // (e.g. a parent popping the destination directly) — saveProgress is
            // idempotent, so this is a no-op when persistAndBack already saved.
            val state = playbackController.uiState.value
            viewModel.saveProgress(
                id = movieId,
                type = mediaType,
                title = title,
                poster = poster,
                position = state.positionMs.coerceAtLeast(0L),
                duration = state.durationMs.takeIf { it > 0L },
                seriesId = seriesId
            )
            playbackController.release()
        }
    }

    val videoSize by remember(playbackController) {
        (playbackController as? ExoPlayerBackend)?.videoSize ?: MutableStateFlow(0 to 0)
    }.collectAsStateWithLifecycle()
    val isInPip by rememberPictureInPictureState(
        isPlaying = uiState.playWhenReady && !uiState.isEnded && uiState.errorMessage.isNullOrBlank(),
        videoWidth = videoSize.first,
        videoHeight = videoSize.second,
        sourceRectHint = remember(hostView.width, hostView.height) {
            Rect(0, 0, hostView.width, hostView.height)
        },
        onTogglePlayPause = { playbackController.togglePlayPause() },
        onSeekBy = { playbackController.seekBy(it) },
        onPipDismissed = { playbackController.pause() }
    )

    DisposableEffect(lifecycleOwner, playbackController) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                // Keep playing in the PiP window; dismissing it pauses via onPipDismissed.
                val inPip = context.findActivity()?.isInPictureInPictureMode == true
                if (!inPip) playbackController.pause()
                val state = playbackController.uiState.value
                val pos = state.positionMs.coerceAtLeast(0L)
                val dur = state.durationMs.takeIf { it > 0L }
                viewModel.saveProgress(
                    id = movieId,
                    type = mediaType,
                    title = title,
                    poster = poster,
                    position = pos,
                    duration = dur,
                    seriesId = seriesId
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(playbackController) {
        while (true) {
            delay(10_000L)
            val state = playbackController.uiState.value
            if (state.isPlaying && state.positionMs > 0L) {
                viewModel.saveProgress(
                    id = movieId,
                    type = mediaType,
                    title = title,
                    poster = poster,
                    position = state.positionMs.coerceAtLeast(0L),
                    duration = state.durationMs.takeIf { it > 0L },
                seriesId = seriesId
                )
            }
        }
    }

    LaunchedEffect(movieId, videoUrl, backendType, autoFallbackEnabled) {
        if (videoUrl.isBlank()) return@LaunchedEffect // Wait for torrent stream URL
        val resumePosition = viewModel.getResumePosition(movieId)
        (playbackController as? ExoPlayerBackend)?.setVolume(
            if (movieId.startsWith("trailer_")) 0.6f else 1f
        )
        playbackController.load(
            PlayerLoadRequest(
                mediaUrl = videoUrl,
                title = title,
                startPositionMs = resumePosition,
                autoPlay = true,
                sources = sources,
                subtitles = subtitles,
                preferredAudioTrackId = preferredAudioTrackId,
                preferredSubtitleTrackId = preferredSubtitleTrackId,
                separateAudioUrl = trailerAudioUrl,
                sourceAutoFallbackEnabled = autoFallbackEnabled
            )
        )
        loadedUrl = videoUrl
        playbackController.setSubtitleVerticalOffset(playbackSettings.subtitleOffset)
        playbackController.setSubtitleSize(playbackSettings.subtitleSize)
        playbackController.setSubtitleTextColor(playbackSettings.subtitleTextColor)
        playbackController.setSubtitleBackgroundColor(playbackSettings.subtitleBackgroundColor)
        if (initialSubtitleDelayMs != 0L) {
            playbackController.setSubtitleDelay(initialSubtitleDelayMs)
        }
    }

    val persistAndBack = {
        val hasError = !uiState.errorMessage.isNullOrBlank()
        val position = uiState.positionMs.coerceAtLeast(0L)
        val duration = uiState.durationMs.takeIf { it > 0L }
        // Single source of truth for "close enough to done to count as watched" —
        // shared with the periodic/session-end saves in PlayerViewModel.saveProgress
        // so a session that ends near the end of playback is never left showing as
        // partially watched by one path while another already treats it as done.
        val completed = duration != null && viewModel.isCompleted(position, duration, mediaType)

        // Trakt: pause keeps item in continue watching, stop marks as watched
        if (!hasError && duration != null) {
            if (completed) {
                viewModel.scrobbleStop(movieId, mediaType, position, duration)
            } else {
                viewModel.scrobblePause(movieId, mediaType, position, duration, force = true)
            }
        }

        // Save whatever progress was made even if the session is ending because the
        // source errored out — the position reached before the error is still real
        // watch progress and shouldn't be discarded (only the Trakt stop/pause call
        // above is skipped on error, since scrobbling a dead-source exit as a normal
        // stop would be misleading).
        viewModel.saveProgress(
            id = movieId,
            type = mediaType,
            title = title,
            poster = poster,
            position = position,
            duration = duration,
            seriesId = seriesId
        )
        val selectedSourceUrl = sources.firstOrNull { it.id == uiState.currentSourceId }?.url
            ?: videoUrl
        onBack(
            PlayerSessionResult(
                positionMs = if (hasError) 0L else position,
                durationMs = duration,
                isCompleted = !hasError && completed,
                selectedSourceUrl = selectedSourceUrl,
                selectedAudioTrackId = uiState.selectedAudioTrackId,
                selectedSubtitleTrackId = uiState.subtitleChoiceToRemember ?: uiState.selectedSubtitleTrackId,
                subtitleDelayMs = uiState.subtitleDelayMs
            )
        )
    }

    LaunchedEffect(
        torrentProgress?.sourceError,
        torrentProgress?.attemptId,
        autoFallbackEnabled,
        videoUrl,
        onSuspectSource
    ) {
        val progress = torrentProgress ?: return@LaunchedEffect
        if (!progress.sourceError || handledTorrentFailureAttemptId == progress.attemptId) {
            return@LaunchedEffect
        }
        handledTorrentFailureAttemptId = progress.attemptId

        // A torrent that fails once the episode has played (or loaded) to its end has been
        // watched: that is the end of the episode, never a reason to try another source.
        val state = playbackController.uiState.value
        if (state.isEnded ||
            isWithinEndWindow(maxOf(state.positionMs, state.bufferedPositionMs), state.durationMs)
        ) {
            return@LaunchedEffect
        }

        if (autoFallbackEnabled && onSuspectSource != null) {
            playbackController.pause()
            onSuspectSource(PlaybackDurationStatus.SOURCE_ERROR)
        } else if (videoUrl.isBlank()) {
            // Preserve the old no-fallback behavior for an initial torrent failure:
            // leave the player and return to Details instead of hanging on an error overlay.
            persistAndBack()
        }
    }

    if (isInPip) {
        // PiP window: video only, no controls, overlays or panels.
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            renderSurface.Content(Modifier.fillMaxSize())
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        BasePlayerScaffold(
            playbackController = playbackController,
            renderSurface = renderSurface,
            title = title,
            seriesTitle = seriesTitle,
            logoUrl = logoUrl,
            mediaType = mediaType,
            onBack = persistAndBack,
            skipSegmentInfo = skipSegmentInfo,
            nextEpisodeInfo = nextEpisodeInfo,
            onAutoplayNextEpisode = onAutoplayNextEpisode,
            autoplayEnabled = playbackSettings.autoplayNextEpisode,
            autoplayThresholdMode = playbackSettings.autoplayThresholdMode,
            autoplayThresholdPercent = playbackSettings.autoplayThresholdPercent,
            autoplayThresholdSeconds = playbackSettings.autoplayThresholdSeconds,
            episodes = episodes,
            currentPlaybackId = currentPlaybackId,
            // The leaving episode's real position goes along, so the switch saves it
            // instead of settling the episode as abandoned at 0.
            onEpisodeSelected = onEpisodeSelected?.let { select ->
                { episode: MetaVideo, currentSourceUrl: String? ->
                    val state = playbackController.uiState.value
                    select(episode, currentSourceUrl, state.positionMs.coerceAtLeast(0L), state.durationMs.takeIf { it > 0L })
                }
            },
            episodeSwitchSources = episodeSwitchSources,
            isEpisodeSwitchLoading = isEpisodeSwitchLoading,
            episodeSwitchTitle = episodeSwitchTitle,
            onEpisodeSwitchSourceSelected = onEpisodeSwitchSourceSelected,
            onEpisodeSwitchDismissed = onEpisodeSwitchDismissed,
            torrentProgress = torrentProgress,
            playbackStatus = playbackStatus,
            isTrailer = movieId.startsWith("trailer_")
        )
    }
}
