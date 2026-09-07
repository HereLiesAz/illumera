package com.hereliesaz.illumera.data.torrent

import android.app.ActivityManager
import android.content.Context

/**
 * Chooses conservative TorrServer defaults from the device's RAM and CPU count,
 * while allowing an explicit user override from Playback settings.
 */
object TorrentDeviceTuner {
    private const val PREFS_NAME = "illumera_torrent_tuning"
    private const val KEY_INITIALIZED = "auto_tune_initialized"
    private const val KEY_MANUAL_OVERRIDE = "manual_override"
    private const val KEY_CACHE_MB = "cache_mb"
    private const val KEY_CONNECTION_LIMIT = "connection_limit"

    data class Settings(
        val cacheSizeMb: Int,
        val connectionsLimit: Int
    )

    fun getOrCreateSettings(context: Context): Settings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_INITIALIZED, false)) {
            return sanitizeSettings(
                context = context,
                settings = Settings(
                    cacheSizeMb = prefs.getInt(KEY_CACHE_MB, 192),
                    connectionsLimit = prefs.getInt(KEY_CONNECTION_LIMIT, 120)
                )
            )
        }

        val settings = computeAutomaticSettings(context)
        persistSettings(context, settings, manualOverride = false)
        return settings
    }

    fun isManualOverride(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_MANUAL_OVERRIDE, false)
    }

    fun saveManualSettings(context: Context, cacheSizeMb: Int, connectionsLimit: Int): Settings {
        val settings = sanitizeSettings(
            context = context,
            settings = Settings(cacheSizeMb, connectionsLimit)
        )
        persistSettings(context, settings, manualOverride = true)
        return settings
    }

    fun resetToAutomatic(context: Context): Settings {
        val settings = computeAutomaticSettings(context)
        persistSettings(context, settings, manualOverride = false)
        return settings
    }

    fun maxSafeCacheMb(context: Context): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val totalRamMb = (memoryInfo.totalMem / (1024L * 1024L)).coerceAtLeast(512L)
        return (totalRamMb / 8L).toInt().coerceIn(64, 512)
    }

    private fun computeAutomaticSettings(context: Context): Settings {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val totalRamMb = (memoryInfo.totalMem / (1024L * 1024L)).coerceAtLeast(512L)
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

        val base = when {
            totalRamMb <= 1536L || cores <= 2 -> Settings(cacheSizeMb = 96, connectionsLimit = 80)
            totalRamMb <= 3072L || cores <= 4 -> Settings(cacheSizeMb = 192, connectionsLimit = 120)
            else -> Settings(cacheSizeMb = 384, connectionsLimit = 180)
        }

        return sanitizeSettings(context, base)
    }

    private fun sanitizeSettings(context: Context, settings: Settings): Settings {
        return Settings(
            cacheSizeMb = settings.cacheSizeMb.coerceIn(64, maxSafeCacheMb(context)),
            connectionsLimit = settings.connectionsLimit.coerceIn(40, 200)
        )
    }

    private fun persistSettings(context: Context, settings: Settings, manualOverride: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_INITIALIZED, true)
            .putBoolean(KEY_MANUAL_OVERRIDE, manualOverride)
            .putInt(KEY_CACHE_MB, settings.cacheSizeMb)
            .putInt(KEY_CONNECTION_LIMIT, settings.connectionsLimit)
            .apply()
    }
}
