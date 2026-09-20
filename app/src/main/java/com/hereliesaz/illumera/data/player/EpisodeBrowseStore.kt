package com.hereliesaz.illumera.data.player

import android.content.Context
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
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
    @ApplicationContext context: Context,
    private val profileConfigurationManager: ProfileConfigurationManager
) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun rememberEpisode(seriesId: String, season: Int, episode: Int) {
        if (season <= 0) return
        val scopedKey = key(seriesId) ?: return
        prefs.edit()
            .putString(scopedKey, "$season:$episode")
            .remove(legacyKey(seriesId))
            .apply()
    }

    /** Returns a suffix matching `MetaVideo`'s `":$season:$episode"` id convention, or null. */
    fun getRememberedEpisodeSuffix(seriesId: String): String? {
        val scopedKey = key(seriesId) ?: return null
        val scopedRaw = prefs.getString(scopedKey, null)
        val legacyKey = legacyKey(seriesId)
        val raw = scopedRaw ?: prefs.getString(legacyKey, null) ?: return null
        val parts = raw.split(":")
        if (parts.size != 2) return null
        val season = parts[0].toIntOrNull() ?: return null
        val episode = parts[1].toIntOrNull() ?: return null

        if (scopedRaw == null) {
            // The old store was global, so ownership cannot be reconstructed perfectly.
            // Assign its last visible value once to the profile active at upgrade, then
            // delete the global key so another profile cannot inherit it later.
            prefs.edit()
                .putString(scopedKey, raw)
                .remove(legacyKey)
                .apply()
        }
        return ":$season:$episode"
    }

    private fun key(seriesId: String): String? {
        val normalizedSeriesId = seriesId.trim().takeIf { it.isNotEmpty() } ?: return null
        val profileId = profileConfigurationManager.getLastActiveProfileId() ?: return null
        return "p$profileId:series_$normalizedSeriesId"
    }

    private fun legacyKey(seriesId: String): String =
        "series_${seriesId.trim()}"

    companion object {
        private const val PREFS_FILE = "episode_browse_prefs"
    }
}
