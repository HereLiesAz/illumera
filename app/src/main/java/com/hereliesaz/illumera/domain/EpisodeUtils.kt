package com.hereliesaz.illumera.domain

import com.hereliesaz.illumera.data.model.stremio.MetaVideo
import java.net.URLEncoder

/**
 * Stable tracking ID for watch history.
 *
 * Normal episodes retain the legacy seriesId:season:episode shape. If an addon
 * exposes multiple distinct video variants for the same season/episode, append
 * an encoded variant segment before the numeric suffix so each cut keeps its own
 * progress while prefix queries and season/episode parsers remain compatible.
 */
fun episodePlaybackId(
    seriesId: String,
    episode: MetaVideo,
    siblings: List<MetaVideo>? = null
): String {
    val base = "$seriesId:${episode.season}:${episode.episode}"
    val duplicateCount = siblings.orEmpty().count {
        it.season == episode.season && it.episode == episode.episode
    }
    if (duplicateCount <= 1) return base

    val variantSource = episode.id.trim().takeIf { it.isNotEmpty() }
        ?: episode.title.trim().takeIf { it.isNotEmpty() }
        ?: return base
    val encodedVariant = URLEncoder.encode(variantSource, Charsets.UTF_8.name())
        .replace("+", "%20")
    return "$seriesId:variant=$encodedVariant:${episode.season}:${episode.episode}"
}

/**
 * Canonical cross-addon stream ID. This is deliberately independent of the
 * origin addon's private episode.id.
 */
fun canonicalEpisodeStreamId(seriesId: String, episode: MetaVideo): String {
    return "$seriesId:${episode.season}:${episode.episode}"
}

/**
 * Origin-addon stream fetch ID. Uses the addon's original episode.id for that
 * addon's endpoint, falling back to the canonical constructed format.
 */
fun episodeStreamId(seriesId: String, episode: MetaVideo): String {
    return episode.id.ifBlank { canonicalEpisodeStreamId(seriesId, episode) }
}

fun episodeDisplayTitle(episode: MetaVideo): String {
    val season = episode.season.takeIf { it > 0 } ?: 1
    val number = episode.episode.takeIf { it > 0 } ?: 1
    return "S${season}:E${number} - ${episode.title}"
}

/**
 * Whether an episode's release date has passed (or is unknown, which is
 * treated as aired — matches the existing next-up/continue-watching gates
 * elsewhere in the app). Compares plain YYYY-MM-DD date strings lexically,
 * against the device's local date.
 */
fun MetaVideo.hasAired(): Boolean {
    val releaseDate = released?.take(10) ?: return true
    val today = java.time.LocalDate.now().toString()
    return releaseDate <= today
}

fun findNextEpisode(
    seriesId: String,
    currentPlaybackId: String,
    episodes: List<MetaVideo>
): MetaVideo? {
    if (episodes.isEmpty()) return null
    // Only consider regular episodes (season > 0 and episode > 0)
    val regular = episodes.filter { it.season > 0 && it.episode > 0 }
    if (regular.isEmpty()) return null
    val sorted = regular.sortedWith(
        compareBy<MetaVideo> { it.season }
            .thenBy { it.episode }
    )
    var currentIndex = sorted.indexOfFirst {
        episodePlaybackId(seriesId, it, episodes) == currentPlaybackId
    }
    // Fallback: match by season/episode numbers for old-format playback IDs
    if (currentIndex < 0) {
        val parts = currentPlaybackId.split(":")
        if (parts.size >= 3) {
            val s = parts[parts.lastIndex - 1].toIntOrNull()
            val e = parts.last().toIntOrNull()
            if (s != null && e != null) {
                currentIndex = sorted.indexOfFirst { it.season == s && it.episode == e }
            }
        }
    }
    if (currentIndex < 0 || currentIndex >= sorted.lastIndex) return null
    val next = sorted[currentIndex + 1]
    // Never autoplay into an episode that hasn't been released yet.
    return next.takeIf { it.hasAired() }
}
