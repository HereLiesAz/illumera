package com.hereliesaz.illumera.data.torrent

import android.content.Context
import android.net.Uri
import android.util.Log
import com.hereliesaz.illumera.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Refreshes a small tracker cache at most once per day. The remote response is bounded
 * and validated before anything is persisted or appended to a magnet link.
 */
object TorrentTrackerCache {
    private const val TAG = "LumeraTorrent"
    private const val TRACKERS_URL =
        "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt"
    private const val PREFS_NAME = "illumera_trackers_cache"
    private const val KEY_TRACKERS = "best_trackers"
    private const val KEY_LAST_UPDATE = "last_update_ms"
    private const val UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1000L
    private const val MAX_TRACKERS = 20
    private const val MAX_RESPONSE_BYTES = 128L * 1024L

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun updateIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0L)
        if (now - lastUpdate < UPDATE_INTERVAL_MS) return@withContext

        val request = Request.Builder().url(TRACKERS_URL).get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Tracker refresh HTTP ${response.code}")
                    return@use
                }

                val body = response.body ?: return@use
                val declaredLength = body.contentLength()
                if (declaredLength > MAX_RESPONSE_BYTES) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Tracker response too large: $declaredLength bytes")
                    return@use
                }

                val source = body.source()
                if (source.request(MAX_RESPONSE_BYTES + 1L)) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Tracker response exceeded size limit")
                    return@use
                }

                val trackers = source.readUtf8()
                    .lineSequence()
                    .map(String::trim)
                    .filter(::isValidTracker)
                    .distinct()
                    .take(MAX_TRACKERS)
                    .toList()

                if (trackers.isNotEmpty()) {
                    prefs.edit()
                        .putString(KEY_TRACKERS, trackers.joinToString("\n"))
                        .putLong(KEY_LAST_UPDATE, now)
                        .apply()
                    if (BuildConfig.DEBUG) Log.d(TAG, "Updated ${trackers.size} cached trackers")
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Tracker refresh failed", e)
        }
    }

    fun getTrackers(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_TRACKERS, null)
            ?.lineSequence()
            ?.map(String::trim)
            ?.filter(::isValidTracker)
            ?.distinct()
            ?.take(MAX_TRACKERS)
            ?.toList()
            .orEmpty()
        return cached.ifEmpty { FALLBACK_TRACKERS }
    }

    internal fun isValidTracker(value: String): Boolean {
        if (value.isBlank() || value.length > 2048) return false
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme !in setOf("udp", "http", "https")) return false
        return !uri.host.isNullOrBlank()
    }

    private val FALLBACK_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://tracker.qu.ax:6969/announce",
        "udp://tracker.theoks.net:6969/announce",
        "udp://tracker.tryhackx.org:6969/announce",
        "udp://tracker.opentorrent.top:6969/announce",
        "https://tracker.gbitt.info/announce"
    )
}

/** Preserves valid addon-provided trackers and appends only missing fallback/cached trackers. */
object TorrentMagnetSanitizer {
    fun prepare(context: Context, magnet: String): String {
        if (!magnet.startsWith("magnet:", ignoreCase = true)) return magnet

        val cleanParts = mutableListOf<String>()
        val existingTrackers = linkedSetOf<String>()

        magnet.split('&').forEach { part ->
            if (!part.startsWith("tr=", ignoreCase = true)) {
                cleanParts += part
                return@forEach
            }

            var encodedValue = part.substringAfter('=', "")
            var decoded = runCatching { URLDecoder.decode(encodedValue, "UTF-8") }
                .getOrDefault(encodedValue)
                .trim()

            if (decoded.startsWith("tracker:", ignoreCase = true)) {
                decoded = decoded.substringAfter(':').trim()
            }

            if (TorrentTrackerCache.isValidTracker(decoded) && existingTrackers.add(decoded)) {
                cleanParts += "tr=${URLEncoder.encode(decoded, "UTF-8")}" 
            }
        }

        TorrentTrackerCache.getTrackers(context).forEach { tracker ->
            if (existingTrackers.add(tracker)) {
                cleanParts += "tr=${URLEncoder.encode(tracker, "UTF-8")}" 
            }
        }

        return cleanParts.joinToString("&")
    }
}
