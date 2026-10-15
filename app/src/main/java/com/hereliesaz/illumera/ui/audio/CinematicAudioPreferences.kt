package com.hereliesaz.illumera.ui.audio

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Per-profile audio controls, separate from playback and Room schema migrations. */
data class CinematicAudioPreferences(
    val autoPlayPreviews: Boolean = true,
    val previewSound: Boolean = false,
    val navigationSounds: Boolean = true
)

private const val PREFS_FILE = "illumera_cinematic_audio"
private fun preferenceKey(profileId: Int, name: String) = "$profileId.$name"

internal fun readCinematicAudioPreferences(
    prefs: SharedPreferences, profileId: Int
): CinematicAudioPreferences = CinematicAudioPreferences(
    autoPlayPreviews = prefs.getBoolean(preferenceKey(profileId, "autoplay"), true),
    previewSound = prefs.getBoolean(preferenceKey(profileId, "preview_sound"), false),
    navigationSounds = prefs.getBoolean(preferenceKey(profileId, "navigation_sounds"), true)
)

internal fun writeCinematicAudioPreference(
    prefs: SharedPreferences,
    profileId: Int,
    key: String,
    enabled: Boolean
) {
    require(key in setOf("autoplay", "preview_sound", "navigation_sounds"))
    prefs.edit().putBoolean(preferenceKey(profileId, key), enabled).apply()
}

@Composable
fun rememberCinematicAudioPreferences(profileId: Int?): CinematicAudioPreferences {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }
    var state by remember(prefs, profileId) {
        mutableStateOf(
            if (profileId == null) CinematicAudioPreferences()
            else readCinematicAudioPreferences(prefs, profileId)
        )
    }
    DisposableEffect(prefs, profileId) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (profileId != null && key?.startsWith("$profileId.") == true) {
                state = readCinematicAudioPreferences(prefs, profileId)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

/** One settings write path for the same per-profile preferences all screens observe. */
fun setCinematicAudioPreference(
    context: Context,
    profileId: Int,
    key: String,
    enabled: Boolean
) = writeCinematicAudioPreference(
    context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE),
    profileId,
    key,
    enabled
)
