package com.hereliesaz.illumera.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.illumera.data.auth.StremioLibrarySyncManager
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.WatchHistoryEntity
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.trakt.TraktScrobbleManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val DEFAULT_WATCHED_THRESHOLD = 0.85 // matches ProfileEntity.watchedThreshold's default
private const val NEAR_END_MS = 30_000L

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val dao: AddonDao,
    private val traktScrobbleManager: TraktScrobbleManager,
    private val stremioLibrarySyncManager: StremioLibrarySyncManager,
    private val profileConfigurationManager: ProfileConfigurationManager
) : ViewModel() {

    // Cached once per session so every completion check (periodic saves, session-end
    // save, the Trakt stop/pause decision in PlayerScreen) agrees on the same number
    // instead of each call site re-deriving — or hardcoding — its own threshold.
    private val _watchedThreshold = MutableStateFlow(DEFAULT_WATCHED_THRESHOLD)
    val watchedThreshold: StateFlow<Double> = _watchedThreshold.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val profileId = profileConfigurationManager.getLastActiveProfileId()
            val threshold = profileId?.let { dao.getProfileById(it)?.watchedThreshold }
            if (threshold != null) {
                _watchedThreshold.value = threshold / 100.0
            }
        }
    }

    fun isCompleted(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        val remaining = durationMs - positionMs
        val completionRatio = positionMs.toDouble() / durationMs.toDouble()
        return completionRatio >= _watchedThreshold.value || remaining <= NEAR_END_MS
    }

    fun saveProgress(
        id: String,
        type: String,
        title: String,
        poster: String?,
        position: Long,
        duration: Long?
    ) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            // Trailers and debrid direct-plays are synthetic playback sessions with no
            // catalog identity behind them — saving history/pushing them to a connected
            // Stremio account's Continue Watching would just be noise there.
            if (id.startsWith("trailer_") || id.startsWith("debrid_")) return@launch
            val safePosition = position.coerceAtLeast(0L)
            if (safePosition < 5_000L) return@launch

            val existing = dao.getHistoryItem(id)
            val safeDuration = (duration ?: existing?.duration ?: safePosition)
                .coerceAtLeast(safePosition)

            val completed = isCompleted(safePosition, safeDuration)
            // A session that ends near the end of the video (or past the watched
            // threshold) counts as fully watched, even if it never reached the
            // literal final frame — so it reads as 100% complete everywhere
            // (progress bars, Continue Watching, resume position) instead of
            // leaving a few seconds of "unwatched" tail behind.
            val finalPosition = if (completed) safeDuration else safePosition

            val entry = WatchHistoryEntity(
                id = id,
                title = title,
                poster = poster ?: existing?.poster,
                background = existing?.background,
                logo = existing?.logo,
                position = finalPosition,
                duration = safeDuration,
                lastWatched = System.currentTimeMillis(),
                type = type.ifBlank { "movie" },
                watched = completed,
                scrobbled = existing?.scrobbled ?: traktScrobbleManager.isScrobbled(id)
            )
            dao.upsertHistory(entry)
            // Opportunistic Continue Watching sync. saveProgress is called
            // both periodically during playback and at session end, so this
            // relies on StremioLibrarySyncManager's own internal throttle
            // rather than rate-limiting here.
            stremioLibrarySyncManager.syncLibrary()
        }
    }

    // ── Trakt Scrobbling ──

    fun scrobbleStart(id: String, type: String, positionMs: Long, durationMs: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            traktScrobbleManager.scrobbleStart(id, type, positionMs, durationMs)
        }
    }

    fun scrobblePause(id: String, type: String, positionMs: Long, durationMs: Long, force: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            traktScrobbleManager.scrobblePause(id, type, positionMs, durationMs, force = force)
        }
    }

    fun scrobbleStop(id: String, type: String, positionMs: Long, durationMs: Long) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            traktScrobbleManager.scrobbleStop(id, type, positionMs, durationMs)
        }
    }

    suspend fun getResumePosition(id: String): Long {
        return withContext(Dispatchers.IO) {
            val item = dao.getHistoryItem(id)
            // A completed item is saved with position == duration (see saveProgress) so it
            // reads as 100% everywhere — but that means literally resuming from it would
            // seek straight to end-of-file. Replaying a finished item starts over instead.
            if (item == null || item.watched) 0L else item.position.takeIf { it > 0 } ?: 0L
        }
    }
}
