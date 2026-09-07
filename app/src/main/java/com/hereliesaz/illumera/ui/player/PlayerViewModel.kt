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
import kotlin.math.abs
import javax.inject.Inject

private const val DEFAULT_WATCHED_THRESHOLD = 0.85
private const val NEAR_END_MS = 30_000L
private const val DEBRID_DOWNLOADING_MS = 30_000L
private const val DEBRID_REMOVED_MS = 120_000L
private const val PLACEHOLDER_TOLERANCE_MS = 8_000L

enum class PlaybackDurationStatus {
    NORMAL,
    DEBRID_DOWNLOADING,
    DEBRID_REMOVED,
    IMPLAUSIBLY_SHORT
}

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val dao: AddonDao,
    private val traktScrobbleManager: TraktScrobbleManager,
    private val stremioLibrarySyncManager: StremioLibrarySyncManager,
    private val profileConfigurationManager: ProfileConfigurationManager
) : ViewModel() {

    private val _watchedThreshold = MutableStateFlow(DEFAULT_WATCHED_THRESHOLD)
    val watchedThreshold: StateFlow<Double> = _watchedThreshold.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val profileId = profileConfigurationManager.getLastActiveProfileId()
            val threshold = profileId?.let { dao.getProfileById(it)?.watchedThreshold }
            if (threshold != null) _watchedThreshold.value = threshold / 100.0
        }
    }

    fun classifyDuration(mediaType: String, durationMs: Long): PlaybackDurationStatus {
        if (durationMs <= 0L) return PlaybackDurationStatus.NORMAL
        val type = mediaType.lowercase()
        if (type != "movie" && type != "series" && type != "tv" && type != "episode") {
            return PlaybackDurationStatus.NORMAL
        }
        if (abs(durationMs - DEBRID_DOWNLOADING_MS) <= PLACEHOLDER_TOLERANCE_MS) {
            return PlaybackDurationStatus.DEBRID_DOWNLOADING
        }
        if (abs(durationMs - DEBRID_REMOVED_MS) <= PLACEHOLDER_TOLERANCE_MS) {
            return PlaybackDurationStatus.DEBRID_REMOVED
        }

        // Baselines mirror the user's source-size preferences: about 30 minutes for an
        // episode and about 90 minutes for a full-length movie. Only absurdly short media
        // (<5% of that expected runtime) is rejected, so legitimate short-form material
        // is not casually murdered by a heuristic with a clipboard.
        val expectedMs = if (type == "movie") 90L * 60_000L else 30L * 60_000L
        return if (durationMs < expectedMs / 20L) {
            PlaybackDurationStatus.IMPLAUSIBLY_SHORT
        } else {
            PlaybackDurationStatus.NORMAL
        }
    }

    fun isCompleted(positionMs: Long, durationMs: Long, mediaType: String = ""): Boolean {
        if (durationMs <= 0L) return false
        if (mediaType.isNotBlank() && classifyDuration(mediaType, durationMs) != PlaybackDurationStatus.NORMAL) return false
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
            if (id.startsWith("trailer_") || id.startsWith("debrid_")) return@launch
            if (duration != null && classifyDuration(type, duration) != PlaybackDurationStatus.NORMAL) return@launch
            val safePosition = position.coerceAtLeast(0L)
            if (safePosition < 5_000L) return@launch

            val existing = dao.getHistoryItem(id)
            val safeDuration = (duration ?: existing?.duration ?: safePosition).coerceAtLeast(safePosition)
            if (classifyDuration(type, safeDuration) != PlaybackDurationStatus.NORMAL) return@launch

            val completed = isCompleted(safePosition, safeDuration, type)
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
            stremioLibrarySyncManager.syncLibrary()
        }
    }

    fun scrobbleStart(id: String, type: String, positionMs: Long, durationMs: Long) {
        if (classifyDuration(type, durationMs) != PlaybackDurationStatus.NORMAL) return
        viewModelScope.launch(Dispatchers.IO) {
            traktScrobbleManager.scrobbleStart(id, type, positionMs, durationMs)
        }
    }

    fun scrobblePause(id: String, type: String, positionMs: Long, durationMs: Long, force: Boolean = false) {
        if (classifyDuration(type, durationMs) != PlaybackDurationStatus.NORMAL) return
        viewModelScope.launch(Dispatchers.IO) {
            traktScrobbleManager.scrobblePause(id, type, positionMs, durationMs, force = force)
        }
    }

    fun scrobbleStop(id: String, type: String, positionMs: Long, durationMs: Long) {
        if (classifyDuration(type, durationMs) != PlaybackDurationStatus.NORMAL) return
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            traktScrobbleManager.scrobbleStop(id, type, positionMs, durationMs)
        }
    }

    suspend fun getResumePosition(id: String): Long {
        return withContext(Dispatchers.IO) {
            val item = dao.getHistoryItem(id)
            if (item == null || item.watched) 0L else item.position.takeIf { it > 0 } ?: 0L
        }
    }
}
