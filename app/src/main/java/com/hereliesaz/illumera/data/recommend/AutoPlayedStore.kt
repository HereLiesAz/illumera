package com.hereliesaz.illumera.data.recommend

import android.content.Context
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-profile set of titles last started by the queue or its suggested list rather than
 * by the viewer. SimilarTitlesEngine skips them as seeds for "More like" / "Because you
 * watched". A later manual play clears the mark. Ids are root ids (series id, not episode).
 */
@Singleton
class AutoPlayedStore @Inject constructor(
    @ApplicationContext context: Context,
    private val profileConfigurationManager: ProfileConfigurationManager
) {
    private val prefs = context.getSharedPreferences("illumera_auto_played", Context.MODE_PRIVATE)

    fun contains(id: String): Boolean = read().contains(rootId(id))

    /** Records how [playbackId]'s title was started: by the queue ([automatic]) or by hand. */
    fun record(playbackId: String, automatic: Boolean) {
        val profileId = profileConfigurationManager.activeProfileId.value ?: return
        val id = rootId(playbackId).takeIf { it.isNotBlank() } ?: return
        val current = read()
        val updated = if (automatic) current + id else current - id
        if (updated != current) prefs.edit().putStringSet("p$profileId", updated).apply()
    }

    private fun read(): Set<String> {
        val profileId = profileConfigurationManager.activeProfileId.value ?: return emptySet()
        return prefs.getStringSet("p$profileId", null)?.toSet().orEmpty()
    }

    private fun rootId(id: String) = id.substringBefore(':')
}
