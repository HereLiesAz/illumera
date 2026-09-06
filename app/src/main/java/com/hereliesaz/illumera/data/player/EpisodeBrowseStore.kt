package com.hereliesaz.illumera.data.player

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which episode a user was last browsing (not necessarily watching) for a
 * series, so reopening the episode list later lands back on the same season/episode
 * instead of always resetting to season 1, episode 1.
 */
@Singleton
class EpisodeBrowseStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun rememberEpisode(seriesId: String, season: Int, episode: Int) {
        if (seriesId.isBlank() || season <= 0) return
        prefs.edit().putString(key(seriesId), "$season:$episode").apply()
    }

    /** Returns a suffix matching `MetaVideo`'s `":$season:$episode"` id convention, or null. */
    fun getRememberedEpisodeSuffix(seriesId: String): String? {
        val raw = prefs.getString(key(seriesId), null) ?: return null
        val parts = raw.split(":")
        if (parts.size != 2) return null
        val season = parts[0].toIntOrNull() ?: return null
        val episode = parts[1].toIntOrNull() ?: return null
        return ":$season:$episode"
    }

    private fun key(seriesId: String): String = "series_${seriesId.trim()}"

    companion object {
        private const val PREFS_FILE = "episode_browse_prefs"
    }
}
