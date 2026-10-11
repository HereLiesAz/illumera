package com.hereliesaz.illumera.data.trakt

import com.hereliesaz.illumera.data.model.WatchHistoryEntity
import com.hereliesaz.illumera.data.model.trakt.TraktIds

private val IMDB_ID = Regex("tt\\d+")

/**
 * Maps a local title id to the ids Trakt accepts in a sync body.
 * "tt123" → imdb, "tmdb:456" → tmdb. Episode ids ("tt123:1:2", "tmdb:456:1:2[:idx]")
 * map to their show. Anything else (addon-private ids) has no Trakt identity → null.
 */
fun traktIdsFor(id: String): TraktIds? {
    if (id.startsWith("tmdb:")) {
        val tmdb = id.removePrefix("tmdb:").substringBefore(':').toIntOrNull() ?: return null
        return TraktIds(tmdb = tmdb)
    }
    val base = id.substringBefore(':')
    return if (IMDB_ID.matches(base)) TraktIds(imdb = base) else null
}

/** The local id Lumera stores for a Trakt item: IMDb when known, else "tmdb:N". */
fun localIdFor(ids: TraktIds?): String? =
    ids?.imdb?.takeIf { IMDB_ID.matches(it) } ?: ids?.tmdb?.let { "tmdb:$it" }

/** Every local id a Trakt item could be saved under (IMDb and/or "tmdb:N"). */
fun localIdsFor(ids: TraktIds?): List<String> = listOfNotNull(
    ids?.imdb?.takeIf { IMDB_ID.matches(it) },
    ids?.tmdb?.let { "tmdb:$it" }
)

/**
 * Number of leading ":"-separated parts that form the title id itself:
 * 1 for "tt123", 2 for colon-prefixed ids like "tmdb:456" or "kitsu:789".
 */
internal fun titleIdPartCount(parts: List<String>): Int =
    if (parts.size >= 2 && !IMDB_ID.matches(parts[0]) && parts[0].toIntOrNull() == null) 2 else 1

/**
 * Strip stream index suffix from playback ID for Trakt lookup.
 * "tt123:1:3:0"      → "tt123:1:3"       (has stream index)
 * "tt123:1:3"        → "tt123:1:3"       (already normalized)
 * "tmdb:456:1:3"     → "tmdb:456:1:3"    (prefixed episode, no stream index)
 * "tmdb:456:1:3:0"   → "tmdb:456:1:3"    (prefixed episode with stream index)
 * "tt123"            → "tt123"           (movie, no change)
 */
internal fun normalizePlaybackId(id: String): String {
    val parts = id.split(":")
    // Episode with stream index: title id + season + episode + index, all numeric after the id.
    val streamIndexedSize = titleIdPartCount(parts) + 3
    if (parts.size == streamIndexedSize &&
        parts.takeLast(3).all { it.toIntOrNull() != null }
    ) {
        return parts.dropLast(1).joinToString(":")
    }
    return id
}

/** A watched title/episode pulled from Trakt, with every local id it may already live under. */
internal data class TraktWatchedEntry(
    val localIds: List<String>,
    val entity: WatchHistoryEntity
)

/**
 * Merges Trakt's watched list into local history and returns only the rows to write.
 * - Not present locally (under any of its ids, stream index ignored) → insert as watched.
 * - Present and already watched → untouched.
 * - Present, in progress, and touched locally after Trakt's watch → untouched (newer local state,
 *   e.g. a rewatch in progress).
 * - Present, in progress, older than Trakt's watch → marked watched, keeping its position/duration.
 */
internal fun mergeTraktWatchedHistory(
    local: List<WatchHistoryEntity>,
    incoming: List<TraktWatchedEntry>
): List<WatchHistoryEntity> {
    val byNormalizedId = HashMap<String, WatchHistoryEntity>(local.size)
    for (row in local) {
        val key = normalizePlaybackId(row.id)
        // Prefer the exact (un-suffixed) row when several stream variants exist.
        if (row.id == key || key !in byNormalizedId) byNormalizedId[key] = row
    }
    val writes = LinkedHashMap<String, WatchHistoryEntity>()
    for (entry in incoming) {
        val existing = entry.localIds.firstNotNullOfOrNull { byNormalizedId[it] }
        when {
            existing == null -> writes.putIfAbsent(entry.entity.id, entry.entity)
            existing.watched -> Unit
            existing.lastWatched > entry.entity.lastWatched -> Unit
            else -> writes[existing.id] = existing.copy(watched = true, scrobbled = true)
        }
    }
    return writes.values.toList()
}
