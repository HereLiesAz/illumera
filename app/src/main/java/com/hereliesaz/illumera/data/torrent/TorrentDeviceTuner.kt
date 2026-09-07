package com.hereliesaz.illumera.data.torrent

import android.app.ActivityManager
import android.content.Context

/**
 * Chooses conservative TorrServer defaults from the device's RAM and CPU count.
 * The selected defaults are persisted once so an OS update cannot silently change
 * playback behavior by reporting slightly different hardware capabilities.
 */
object TorrentDeviceTuner {
    private const val PREFS_NAME = "illumera_torrent_tuning"
    private const val KEY_INITIALIZED = "auto_tune_initialized"
    private const val KEY_CACHE_MB = "cache_mb"
    private const val KEY_CONNECTION_LIMIT = "connection_limit"

    data class Settings(
        val cacheSizeMb: Int,
        val connectionsLimit: Int
    )

    fun getOrCreateSettings(context: Context): Settings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_INITIALIZED, false)) {
            return Settings(
                cacheSizeMb = prefs.getInt(KEY_CACHE_MB, 192).coerceIn(64, 512),
                connectionsLimit = prefs.getInt(KEY_CONNECTION_LIMIT, 120).coerceIn(40, 200)
            )
        }

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

        // Never dedicate more than one eighth of reported RAM to TorrServer's cache.
        val ramBoundCacheMb = (totalRamMb / 8L).toInt().coerceIn(64, 512)
        val settings = base.copy(cacheSizeMb = minOf(base.cacheSizeMb, ramBoundCacheMb))

        prefs.edit()
            .putBoolean(KEY_INITIALIZED, true)
            .putInt(KEY_CACHE_MB, settings.cacheSizeMb)
            .putInt(KEY_CONNECTION_LIMIT, settings.connectionsLimit)
            .apply()

        return settings
    }
}
