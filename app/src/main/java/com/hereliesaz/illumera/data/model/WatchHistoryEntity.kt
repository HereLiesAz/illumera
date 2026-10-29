package com.hereliesaz.illumera.data.model

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Minimum actual playback to count as started (milliseconds). */
const val MIN_CONFIRMED_PLAYBACK_MS = 5_000L

@Immutable
@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey val id: String,
    val title: String,
    val poster: String?,
    val background: String? = null,
    val logo: String? = null,
    val seriesId: String? = null,
    val position: Long,
    val duration: Long,
    val lastWatched: Long,
    val type: String,
    val watched: Boolean = false,   // true = fully watched (past threshold), false = in progress
    val scrobbled: Boolean = false  // true = Trakt knows about this item (scrobble was sent successfully)
) {
    /**
     * An entry is watch history only after meaningful playback, or an explicit
     * completed/watched signal from a connected service. Stremio bookmarks and
     * legacy Trakt 0/0 placeholders are NOT evidence that a title was played.
     *
     * Match PlayerViewModel's 5-second persistence floor, so every history
     * consumer agrees on the same definition of an actual play.
     */
    fun hasPlaybackEvidence(): Boolean = watched || position >= MIN_CONFIRMED_PLAYBACK_MS

    fun progress(): Float {
        if (duration == 0L) return 0f
        return position.toFloat() / duration.toFloat()
    }
}