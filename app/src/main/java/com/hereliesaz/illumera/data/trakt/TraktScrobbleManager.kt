package com.hereliesaz.illumera.data.trakt

import android.util.Log
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.trakt.TraktIds
import com.hereliesaz.illumera.data.model.trakt.TraktScrobbleEpisode
import com.hereliesaz.illumera.data.model.trakt.TraktScrobbleMovie
import com.hereliesaz.illumera.data.model.trakt.TraktScrobbleRequest
import com.hereliesaz.illumera.data.model.trakt.TraktScrobbleShow
import com.hereliesaz.illumera.data.remote.TraktSyncApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles Trakt scrobble events (start/pause/stop) during playback.
 *
 * Scrobble flow:
 * - start: user begins or resumes playback
 * - pause: user pauses
 * - stop:  user exits the player — if progress >= threshold, Trakt auto-marks as watched
 *
 * The playback ID format is:
 * - Movies: "tt1234567"
 * - Episodes: "tt1234567:1:3:0" (imdbId:season:episode:streamIndex)
 */
@Singleton
class TraktScrobbleManager @Inject constructor(
    private val traktSyncApi: TraktSyncApiService,
    private val traktAuthManager: TraktAuthManager,
    private val dao: AddonDao
) {
    companion object {
        private const val TAG = "TraktScrobble"
        private const val MIN_SCROBBLE_INTERVAL_MS = 5_000L
    }

    private var lastScrobbleTimeMs = 0L

    /**
     * Called when playback starts or resumes.
     */
    suspend fun scrobbleStart(playbackId: String, mediaType: String, positionMs: Long, durationMs: Long) {
        Log.d(TAG, "start: id=$playbackId, type=$mediaType")
        if (!shouldScrobble()) return
        val progress = calculateProgress(positionMs, durationMs)
        val request = buildRequest(playbackId, mediaType, progress)
        if (request == null) { Log.w(TAG, "scrobbleStart: buildRequest returned null"); return }

        withContext(Dispatchers.IO) {
            try {
                val response = traktSyncApi.scrobbleStart(request)
                Log.d(TAG, "start: ${response.code()} progress=${"%.1f".format(progress)}%")
                if (response.isSuccessful) {
                    markAsScrobbled(playbackId)
                } else {
                    Log.w(TAG, "start error: ${response.errorBody()?.string()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "start failed for $playbackId", e)
            }
        }
    }

    /**
     * Called when playback is paused.
     */
    suspend fun scrobblePause(playbackId: String, mediaType: String, positionMs: Long, durationMs: Long, force: Boolean = false) {
        Log.d(TAG, "pause: id=$playbackId, force=$force")
        if (!shouldScrobble()) return
        if (!force && !isDebouncedOk()) { Log.d(TAG, "scrobblePause debounced"); return }
        val progress = calculateProgress(positionMs, durationMs)
        val request = buildRequest(playbackId, mediaType, progress)
        if (request == null) { Log.w(TAG, "scrobblePause: buildRequest returned null"); return }

        withContext(Dispatchers.IO) {
            try {
                val response = traktSyncApi.scrobblePause(request)
                Log.d(TAG, "pause: ${response.code()} progress=${"%.1f".format(progress)}%")
                if (!response.isSuccessful) Log.w(TAG, "pause error: ${response.errorBody()?.string()}")
            } catch (e: Exception) {
                Log.w(TAG, "pause failed for $playbackId", e)
            }
        }
    }

    /**
     * Called when playback stops (user exits player).
     * If progress >= threshold, Trakt automatically marks the item as watched.
     */
    suspend fun scrobbleStop(playbackId: String, mediaType: String, positionMs: Long, durationMs: Long) {
        if (!shouldScrobble()) return
        val progress = calculateProgress(positionMs, durationMs)
        val request = buildRequest(playbackId, mediaType, progress) ?: return

        withContext(Dispatchers.IO) {
            try {
                val response = traktSyncApi.scrobbleStop(request)
                Log.d(TAG, "stop: ${response.code()} progress=${"%.1f".format(progress)}%")
                if (!response.isSuccessful) Log.w(TAG, "stop error: ${response.errorBody()?.string()}")
            } catch (e: Exception) {
                Log.w(TAG, "stop failed for $playbackId", e)
            }
        }
    }

    // ── Internal ──

    /**
     * Mark the watch history item as scrobbled so sync knows Trakt is aware of it.
     * Fix #9: The history entry may not exist yet (saveProgress runs on a 10-sec timer),
     * so we track the ID and retry. PlayerViewModel.saveProgress preserves this flag.
     */
    // Only needed transiently until the corresponding watch_history row exists (see
    // markAsScrobbled below), but nothing ever removes an ID from it — bounded with
    // LRU eviction so it can't grow unbounded over a long-running TV session.
    private val scrobbledIdsCap = 500
    private val scrobbledIds = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) =
                size > scrobbledIdsCap
        }
    )

    private suspend fun markAsScrobbled(playbackId: String) {
        scrobbledIds[playbackId] = true
        dao.markHistoryScrobbled(playbackId)
        // If item doesn't exist yet, saveProgress will pick up the flag via isScrobbled()
    }

    /** Check if a playback ID has been scrobbled (for PlayerViewModel to use). */
    fun isScrobbled(playbackId: String): Boolean = scrobbledIds.containsKey(playbackId)

    private fun shouldScrobble(): Boolean = traktAuthManager.getAccessToken() != null

    private fun isDebouncedOk(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastScrobbleTimeMs < MIN_SCROBBLE_INTERVAL_MS) return false
        lastScrobbleTimeMs = now
        return true
    }

    private fun calculateProgress(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return ((positionMs.toFloat() / durationMs.toFloat()) * 100f).coerceIn(0f, 100f)
    }

    /**
     * Build the scrobble request based on playback ID format.
     * Movies: TraktScrobbleMovie with IMDb ID
     * Episodes: TraktScrobbleShow + TraktScrobbleEpisode with season/episode numbers
     */
    private fun buildRequest(playbackId: String, mediaType: String, progress: Float): TraktScrobbleRequest? {
        if (mediaType == "series") {
            // Episode format: "tt1234567:1:3" or "tt1234567:1:3:0" (with optional stream index)
            val parts = playbackId.split(":")
            if (parts.size < 3) {
                Log.w(TAG, "Invalid episode playback ID: $playbackId")
                return null
            }

            // Last part might be stream index (numeric) — detect based on count
            val hasStreamIndex = parts.size >= 4 && parts.last().toIntOrNull() != null
            val dropCount = if (hasStreamIndex) 3 else 2
            val imdbId = parts.dropLast(dropCount).joinToString(":")
            val season = parts[parts.size - if (hasStreamIndex) 3 else 2].toIntOrNull()
            val episode = parts[parts.size - if (hasStreamIndex) 2 else 1].toIntOrNull()
            if (season == null || episode == null) {
                Log.w(TAG, "Failed to parse season/episode from playback ID: $playbackId")
                return null
            }

            return TraktScrobbleRequest(
                show = TraktScrobbleShow(ids = TraktIds(imdb = imdbId)),
                episode = TraktScrobbleEpisode(season = season, number = episode),
                progress = progress
            )
        } else {
            // Movie format: "tt1234567"
            return TraktScrobbleRequest(
                movie = TraktScrobbleMovie(ids = TraktIds(imdb = playbackId)),
                progress = progress
            )
        }
    }
}
