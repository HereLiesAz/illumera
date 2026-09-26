package com.hereliesaz.illumera.ui.player.base

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoplayCountdownTest {

    @get:Rule
    val compose = createComposeRule()

    private class FakeController(initial: PlayerUiState) : PlayerPlaybackController {
        override val backendType = PlayerBackendType.EXOPLAYER
        val state = MutableStateFlow(initial)
        override val uiState: StateFlow<PlayerUiState> = state
        override val sourceOptions = MutableStateFlow<List<PlayerSourceOption>>(emptyList())
        override val excludedSourceIds = MutableStateFlow<Set<String>>(emptySet())
        override val sourceListDisabled = MutableStateFlow(false)
        override val audioTracks = MutableStateFlow<List<PlayerTrackOption>>(emptyList())
        override val subtitleTracks = MutableStateFlow<List<PlayerTrackOption>>(emptyList())
        override fun load(request: PlayerLoadRequest) {}
        override fun retryPlayback() {}
        override fun play() {}
        override fun pause() {}
        override fun seekTo(positionMs: Long) {}
        override fun seekBy(deltaMs: Long) {}
        override fun setPlaybackSpeed(speed: Float) {}
        override fun selectSource(sourceId: String) {}
        override fun setSourceExcluded(sourceId: String, excluded: Boolean) {}
        override fun setSourceListDisabled(disabled: Boolean) {}
        override fun selectAudioTrack(trackId: String?) {}
        override fun selectSubtitleTrack(trackId: String?) {}
        override fun setSubtitleVerticalOffset(percent: Int) {}
        override fun setSubtitleSize(percent: Int) {}
        override fun setSubtitleDelay(delayMs: Long) {}
        override fun setSubtitleTextColor(color: Int) {}
        override fun setSubtitleBackgroundColor(color: Int) {}
        override fun setResizeMode(mode: Int) {}
        override fun release() {}
    }

    private object FakeSurface : PlayerRenderSurface {
        override val backendType = PlayerBackendType.EXOPLAYER
        @Composable override fun Content(modifier: Modifier) {}
    }

    @Test
    fun countdownHandsOffToTheNextEpisode() {
        val controller = FakeController(
            PlayerUiState(isReady = true, isPlaying = true, playWhenReady = true, hasRenderedFirstFrame = true,
                durationMs = 100_000L, positionMs = 96_000L)
        )
        var fired = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            BasePlayerScaffold(
                playbackController = controller,
                renderSurface = FakeSurface,
                title = "S1:E1 - Pilot",
                mediaType = "series",
                onBack = {},
                nextEpisodeInfo = NextEpisodeInfo(title = "S1:E2 - Two", thumbnail = null, seasonNumber = 1, episodeNumber = 2),
                onAutoplayNextEpisode = { fired++ },
                autoplayEnabled = true,
            )
        }
        repeat(15) {
            compose.mainClock.advanceTimeBy(1_000L)
            // Playback keeps moving, as the real player's progress loop does.
            controller.state.value = controller.state.value.copy(positionMs = controller.state.value.positionMs + 250L)
        }
        compose.waitForIdle()
        assertEquals(1, fired)
    }
}
