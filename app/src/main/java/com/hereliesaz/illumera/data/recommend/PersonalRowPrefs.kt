package com.hereliesaz.illumera.data.recommend

import android.content.Context
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-profile settings for Home's personal rows.
 *
 * [moreLikeCount] / [becauseCount]: how many "More like" and "Because you watched" rows.
 * [approved] / [rejected]: seed titles the viewer gave a thumbs up / down on a row header.
 * Rejected seeds never seed a row again; approved seeds are picked first among the random
 * candidates for a row. Ids are root title ids (series id, not episode).
 */
data class PersonalRowSettings(
    val moreLikeCount: Int = 1,
    val becauseCount: Int = 1,
    val approved: Set<String> = emptySet(),
    val rejected: Set<String> = emptySet()
)

@Singleton
class PersonalRowPrefs @Inject constructor(
    @ApplicationContext context: Context,
    private val profileConfigurationManager: ProfileConfigurationManager
) {
    private val prefs = context.getSharedPreferences("illumera_personal_rows", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _settings = MutableStateFlow(PersonalRowSettings())
    /** Active profile's settings; every change is emitted at once so Home rebuilds immediately. */
    val settings: StateFlow<PersonalRowSettings> = _settings.asStateFlow()

    init {
        scope.launch {
            profileConfigurationManager.activeProfileId.collect { id ->
                _settings.value = id?.let(::read) ?: PersonalRowSettings()
            }
        }
    }

    fun setMoreLikeCount(count: Int) = edit { it.copy(moreLikeCount = count.coerceIn(0, MAX_ROWS)) }
    fun setBecauseCount(count: Int) = edit { it.copy(becauseCount = count.coerceIn(0, MAX_ROWS)) }

    /** Thumbs up: [seedId] is favored as a seed. Pressing it again clears the vote. */
    fun approve(seedId: String) = edit { s ->
        val id = root(seedId)
        if (id in s.approved) s.copy(approved = s.approved - id)
        else s.copy(approved = s.approved + id, rejected = s.rejected - id)
    }

    /** Thumbs down: [seedId] never seeds a row again. */
    fun reject(seedId: String) = edit { s ->
        val id = root(seedId)
        s.copy(rejected = s.rejected + id, approved = s.approved - id)
    }

    private fun edit(transform: (PersonalRowSettings) -> PersonalRowSettings) {
        val profileId = profileConfigurationManager.activeProfileId.value ?: return
        val updated = transform(_settings.value)
        if (updated == _settings.value) return
        _settings.value = updated
        prefs.edit()
            .putInt("p${profileId}_more", updated.moreLikeCount)
            .putInt("p${profileId}_because", updated.becauseCount)
            .putStringSet("p${profileId}_up", updated.approved)
            .putStringSet("p${profileId}_down", updated.rejected)
            .apply()
    }

    private fun read(profileId: Int) = PersonalRowSettings(
        moreLikeCount = prefs.getInt("p${profileId}_more", 1),
        becauseCount = prefs.getInt("p${profileId}_because", 1),
        approved = prefs.getStringSet("p${profileId}_up", null)?.toSet().orEmpty(),
        rejected = prefs.getStringSet("p${profileId}_down", null)?.toSet().orEmpty()
    )

    private fun root(id: String) = id.substringBefore(':')

    companion object {
        const val MAX_ROWS = 5
        const val MORE_LIKE_PREFIX = "personal:more_like:"
        const val BECAUSE_PREFIX = "personal:because:"

        /** The seed title id a personal row was built from, or null for any other row. */
        fun seedOf(rowId: String): String? = when {
            rowId.startsWith(MORE_LIKE_PREFIX) -> rowId.removePrefix(MORE_LIKE_PREFIX)
            rowId.startsWith(BECAUSE_PREFIX) -> rowId.removePrefix(BECAUSE_PREFIX)
            else -> null
        }?.takeIf { it.isNotBlank() }
    }
}
