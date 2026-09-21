package com.hereliesaz.illumera.data.trailer

import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "YouTubeExtractor"
private const val EXTRACTOR_TIMEOUT_MS = 30_000L
private const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
private const val PREFERRED_SEPARATE_CLIENT = "android_vr"

private val VIDEO_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")
private val API_KEY_REGEX = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"")
private val VISITOR_DATA_REGEX = Regex("\"VISITOR_DATA\":\"([^\"]+)\"")
private val QUALITY_LABEL_REGEX = Regex("(\\d{2,4})p")

private data class YouTubeClient(
    val key: String,
    val id: String,
    val version: String,
    val userAgent: String,
    val context: Map<String, Any>,
    val priority: Int
)

private data class WatchConfig(val apiKey: String?, val visitorData: String?)

private data class StreamCandidate(
    val client: String, val priority: Int, val url: String,
    val score: Double, val hasN: Boolean, val itag: String,
    val height: Int, val fps: Int, val ext: String
)

private data class ManifestBestVariant(
    val url: String, val width: Int, val height: Int, val bandwidth: Long
)

private data class ManifestCandidate(
    val client: String, val priority: Int, val manifestUrl: String,
    val selectedVariantUrl: String, val height: Int, val bandwidth: Long
)

private val DEFAULT_HEADERS = mapOf(
    "accept-language" to "en-US,en;q=0.9",
    "user-agent" to DEFAULT_USER_AGENT
)

private val CLIENTS = listOf(
    YouTubeClient(
        key = "android_vr", id = "28", version = "1.56.21",
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.56.21 " +
            "(Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1) gzip",
        context = mapOf(
            "clientName" to "ANDROID_VR", "clientVersion" to "1.56.21",
            "deviceMake" to "Oculus", "deviceModel" to "Quest 3",
            "osName" to "Android", "osVersion" to "12",
            "platform" to "MOBILE", "androidSdkVersion" to 32,
            "hl" to "en", "gl" to "US"
        ),
        priority = 0
    ),
    YouTubeClient(
        key = "android", id = "3", version = "20.10.35",
        userAgent = "com.google.android.youtube/20.10.35 (Linux; U; Android 14; en_US) gzip",
        context = mapOf(
            "clientName" to "ANDROID", "clientVersion" to "20.10.35",
            "osName" to "Android", "osVersion" to "14",
            "platform" to "MOBILE", "androidSdkVersion" to 34,
            "hl" to "en", "gl" to "US"
        ),
        priority = 1
    ),
    YouTubeClient(
        key = "ios", id = "5", version = "20.10.1",
        userAgent = "com.google.ios.youtube/20.10.1 (iPhone16,2; U; CPU iOS 17_4 like Mac OS X)",
        context = mapOf(
            "clientName" to "IOS", "clientVersion" to "20.10.1",
            "deviceModel" to "iPhone16,2", "osName" to "iPhone",
            "osVersion" to "17.4.0.21E219", "platform" to "MOBILE",
            "hl" to "en", "gl" to "US"
        ),
        priority = 2
    )
)

@Singleton
class YouTubeExtractor @Inject constructor() {
    private val gson = Gson()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun extractPlaybackSource(youtubeKey: String): TrailerPlaybackSource? = withContext(Dispatchers.IO) {
        if (youtubeKey.isBlank()) return@withContext null

        Log.d(TAG, "Extracting playback source for key: $youtubeKey")
        try {
            withTimeout(EXTRACTOR_TIMEOUT_MS) {
                extractInternal(youtubeKey)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Extraction failed for $youtubeKey: ${e.message}")
            null
        }
    }

    private suspend fun extractInternal(videoKey: String): TrailerPlaybackSource? {
        val videoId = extractVideoId(videoKey) ?: return null

        val watchUrl = "https://www.youtube.com/watch?v=$videoId&hl=en"
        val watchResponse = performRequest(watchUrl, "GET", DEFAULT_HEADERS)
        if (!watchResponse.ok) throw IllegalStateException("Failed to fetch watch page (${watchResponse.status})")

        val watchConfig = getWatchConfig(watchResponse.body)
        val apiKey = watchConfig.apiKey ?: throw IllegalStateException("Unable to extract INNERTUBE_API_KEY")

        val progressive = mutableListOf<StreamCandidate>()
        val adaptiveVideo = mutableListOf<StreamCandidate>()
        val adaptiveAudio = mutableListOf<StreamCandidate>()
        val manifestUrls = mutableListOf<Triple<String, Int, String>>()

        for (client in CLIENTS) {
            try {
                val playerResponse = fetchPlayerResponse(apiKey, videoId, client, watchConfig.visitorData)
                val streamingData = playerResponse.mapValue("streamingData") ?: continue

                streamingData.stringValue("hlsManifestUrl")?.takeIf { it.isNotBlank() }?.let {
                    manifestUrls += Triple(client.key, client.priority, it)
                }

                for (format in streamingData.listMapValue("formats")) {
                    val url = format.stringValue("url") ?: continue
                    val mimeType = format.stringValue("mimeType").orEmpty()
                    if (!mimeType.contains("video/") && mimeType.isNotBlank()) continue

                    val height = (format.numberValue("height")
                        ?: parseQualityLabel(format.stringValue("qualityLabel"))?.toDouble() ?: 0.0).toInt()
                    val fps = (format.numberValue("fps") ?: 0.0).toInt()
                    val bitrate = format.numberValue("bitrate") ?: format.numberValue("averageBitrate") ?: 0.0

                    progressive += StreamCandidate(
                        client.key, client.priority, url, videoScore(height, fps, bitrate),
                        hasNParam(url), format.stringValue("itag").orEmpty(),
                        height, fps, if (mimeType.contains("webm")) "webm" else "mp4"
                    )
                }

                for (format in streamingData.listMapValue("adaptiveFormats")) {
                    val url = format.stringValue("url") ?: continue
                    val mimeType = format.stringValue("mimeType").orEmpty()

                    if (mimeType.contains("video/")) {
                        val height = (format.numberValue("height")
                            ?: parseQualityLabel(format.stringValue("qualityLabel"))?.toDouble() ?: 0.0).toInt()
                        val fps = (format.numberValue("fps") ?: 0.0).toInt()
                        val bitrate = format.numberValue("bitrate") ?: format.numberValue("averageBitrate") ?: 0.0

                        adaptiveVideo += StreamCandidate(
                            client.key, client.priority, url, videoScore(height, fps, bitrate),
                            hasNParam(url), format.stringValue("itag").orEmpty(),
                            height, fps, if (mimeType.contains("webm")) "webm" else "mp4"
                        )
                    } else if (mimeType.contains("audio/")) {
                        val bitrate = format.numberValue("bitrate") ?: format.numberValue("averageBitrate") ?: 0.0
                        val asr = format.numberValue("audioSampleRate") ?: 0.0

                        adaptiveAudio += StreamCandidate(
                            client.key, client.priority, url, audioScore(bitrate, asr),
                            hasNParam(url), format.stringValue("itag").orEmpty(),
                            0, 0, if (mimeType.contains("webm")) "webm" else "m4a"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Client ${client.key} failed: ${e.message}")
            }
        }

        Log.d(TAG, "Streams found: progressive=${progressive.size} adaptiveVideo=${adaptiveVideo.size} adaptiveAudio=${adaptiveAudio.size} hls=${manifestUrls.size}")

        if (manifestUrls.isEmpty() && progressive.isEmpty() && adaptiveVideo.isEmpty() && adaptiveAudio.isEmpty()) {
            Log.w(TAG, "No playable streams found for $videoId")
            return null
        }

        var bestManifest: ManifestCandidate? = null
        for ((clientKey, priority, manifestUrl) in manifestUrls) {
            try {
                val variant = parseHlsManifest(manifestUrl) ?: continue
                val candidate = ManifestCandidate(clientKey, priority, manifestUrl, variant.url, variant.height, variant.bandwidth)
                if (bestManifest == null || candidate.height > bestManifest.height ||
                    (candidate.height == bestManifest.height && candidate.bandwidth > bestManifest.bandwidth)) {
                    bestManifest = candidate
                }
            } catch (e: Exception) {
                Log.w(TAG, "Manifest parse failed: ${e.message}")
            }
        }

        val bestProgressive = sortCandidates(progressive).firstOrNull()
        val bestVideo = pickBestForClient(adaptiveVideo, PREFERRED_SEPARATE_CLIENT)
        val bestAudio = pickBestForClient(adaptiveAudio, PREFERRED_SEPARATE_CLIENT)

        // Prefer adaptive video+audio (highest quality) with MergingMediaSource.
        // Fall back to progressive, then adaptive video-only, then HLS manifest.
        val videoUrl: String
        val audioUrl: String?
        if (bestVideo != null && bestAudio != null) {
            videoUrl = resolveReachableUrl(bestVideo.url)
            audioUrl = resolveReachableUrl(bestAudio.url)
        } else if (bestProgressive != null) {
            videoUrl = resolveReachableUrl(bestProgressive.url)
            audioUrl = null
        } else if (bestVideo != null) {
            videoUrl = resolveReachableUrl(bestVideo.url)
            audioUrl = null
        } else if (bestManifest != null) {
            videoUrl = bestManifest.manifestUrl
            audioUrl = null
        } else {
            return null
        }

        Log.d(TAG, "Extraction success: video=${videoUrl.take(80)}, audioPresent=${!audioUrl.isNullOrBlank()}")

        return TrailerPlaybackSource(videoUrl = videoUrl, audioUrl = audioUrl)
    }

    private fun extractVideoId(input: String): String? {
        val trimmed = input.trim()
        if (VIDEO_ID_REGEX.matches(trimmed)) return trimmed

        val normalized = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "https://$trimmed"

        return runCatching {
            val uri = Uri.parse(normalized)
            val host = uri.host?.lowercase().orEmpty()
            if (host.endsWith("youtu.be")) {
                uri.pathSegments.firstOrNull()?.takeIf { VIDEO_ID_REGEX.matches(it) }?.let { return it }
            }
            uri.getQueryParameter("v")?.takeIf { VIDEO_ID_REGEX.matches(it) }?.let { return it }
            val segments = uri.pathSegments
            if (segments.size >= 2) {
                val first = segments[0]; val second = segments[1]
                if ((first == "embed" || first == "shorts" || first == "live") && VIDEO_ID_REGEX.matches(second)) return second
            }
            null
        }.getOrNull()
    }

    private fun getWatchConfig(html: String): WatchConfig {
        return WatchConfig(
            apiKey = API_KEY_REGEX.find(html)?.groupValues?.getOrNull(1),
            visitorData = VISITOR_DATA_REGEX.find(html)?.groupValues?.getOrNull(1)
        )
    }

    private fun fetchPlayerResponse(apiKey: String, videoId: String, client: YouTubeClient, visitorData: String?): Map<*, *> {
        val endpoint = "https://www.youtube.com/youtubei/v1/player?key=${Uri.encode(apiKey)}"
        val headers = buildMap {
            putAll(DEFAULT_HEADERS)
            put("content-type", "application/json")
            put("origin", "https://www.youtube.com")
            put("x-youtube-client-name", client.id)
            put("x-youtube-client-version", client.version)
            put("user-agent", client.userAgent)
            if (!visitorData.isNullOrBlank()) put("x-goog-visitor-id", visitorData)
        }
        val payload = mapOf(
            "videoId" to videoId, "contentCheckOk" to true, "racyCheckOk" to true,
            "context" to mapOf("client" to client.context),
            "playbackContext" to mapOf("contentPlaybackContext" to mapOf("html5Preference" to "HTML5_PREF_WANTS"))
        )
        val response = performRequest(endpoint, "POST", headers, gson.toJson(payload))
        if (!response.ok) throw IllegalStateException("player API ${client.key} failed (${response.status})")
        return gson.fromJson(response.body, Map::class.java) ?: emptyMap<String, Any>()
    }

    private fun parseHlsManifest(manifestUrl: String): ManifestBestVariant? {
        val response = performRequest(manifestUrl, "GET", DEFAULT_HEADERS)
        if (!response.ok) throw IllegalStateException("Failed to fetch HLS manifest (${response.status})")

        val lines = response.body.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        var best: ManifestBestVariant? = null

        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val attrs = parseHlsAttributeList(line)
            val nextLine = lines.getOrNull(i + 1) ?: continue
            if (nextLine.startsWith("#")) continue

            val (width, height) = parseResolution(attrs["RESOLUTION"].orEmpty())
            val bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L
            val candidate = ManifestBestVariant(absolutizeUrl(manifestUrl, nextLine), width, height, bandwidth)

            if (best == null || candidate.height > best.height ||
                (candidate.height == best.height && candidate.bandwidth > best.bandwidth)) {
                best = candidate
            }
        }
        return best
    }

    private fun parseHlsAttributeList(line: String): Map<String, String> {
        val index = line.indexOf(':')
        if (index == -1) return emptyMap()
        val raw = line.substring(index + 1)
        val out = LinkedHashMap<String, String>()
        val key = StringBuilder(); val value = StringBuilder()
        var inKey = true; var inQuote = false

        for (ch in raw) {
            if (inKey) { if (ch == '=') inKey = false else key.append(ch); continue }
            if (ch == '"') { inQuote = !inQuote; continue }
            if (ch == ',' && !inQuote) {
                val k = key.toString().trim(); if (k.isNotEmpty()) out[k] = value.toString().trim()
                key.clear(); value.clear(); inKey = true; continue
            }
            value.append(ch)
        }
        val lastKey = key.toString().trim(); if (lastKey.isNotEmpty()) out[lastKey] = value.toString().trim()
        return out
    }

    private fun parseResolution(raw: String): Pair<Int, Int> {
        val parts = raw.split('x')
        return if (parts.size == 2) (parts[0].toIntOrNull() ?: 0) to (parts[1].toIntOrNull() ?: 0) else 0 to 0
    }

    private fun parseQualityLabel(label: String?): Int? =
        label?.let { QUALITY_LABEL_REGEX.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

    private fun hasNParam(url: String) = runCatching { !Uri.parse(url).getQueryParameter("n").isNullOrBlank() }.getOrDefault(false)
    private fun videoScore(height: Int, fps: Int, bitrate: Double) = height * 1_000_000_000.0 + fps * 1_000_000.0 + bitrate
    private fun audioScore(bitrate: Double, asr: Double) = bitrate * 1_000_000.0 + asr

    private fun sortCandidates(items: List<StreamCandidate>) = items.sortedWith(
        compareByDescending<StreamCandidate> { it.score }
            .thenBy { if (it.hasN) 1 else 0 }
            .thenBy { when (it.ext.lowercase()) { "mp4", "m4a" -> 0; "webm" -> 1; else -> 2 } }
            .thenBy { it.priority }
    )

    private fun pickBestForClient(items: List<StreamCandidate>, clientKey: String): StreamCandidate? {
        val sameClient = items.filter { it.client == clientKey }
        return sortCandidates(sameClient.ifEmpty { items }).firstOrNull()
    }

    private suspend fun resolveReachableUrl(url: String): String {
        if (!url.contains("googlevideo.com")) return url
        val uri = Uri.parse(url)
        val servers = uri.getQueryParameter("mn")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: return url
        if (servers.size < 2) return url

        val candidates = mutableListOf(url)
        for ((mviIndex, server) in servers.withIndex()) {
            val altHost = uri.host?.replaceFirst(Regex("^rr\\d+---"), "rr${mviIndex + 1}---")
                ?.replaceFirst(Regex("sn-[a-z0-9]+-[a-z0-9]+"), server) ?: continue
            if (altHost != uri.host) candidates += url.replace(uri.host!!, altHost)
        }
        if (candidates.size == 1) return candidates[0]

        val result = CompletableDeferred<String>()
        val probeScope = CoroutineScope(Dispatchers.IO)
        candidates.forEach { candidate ->
            probeScope.launch {
                if (isUrlReachable(candidate)) result.complete(candidate)
            }
        }
        return try { withTimeoutOrNull(2_000L) { result.await() } ?: url } finally { probeScope.cancel() }
    }

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true)
        .build()

    private fun isUrlReachable(url: String) = runCatching {
        val request = Request.Builder().url(url).get().header("Range", "bytes=0-0").headers(buildHeaders(DEFAULT_HEADERS)).build()
        probeClient.newCall(request).execute().use { it.code == 200 || it.code == 206 }
    }.getOrDefault(false)

    private fun absolutizeUrl(baseUrl: String, maybeRelative: String) =
        runCatching { URL(URL(baseUrl), maybeRelative).toString() }.getOrElse { maybeRelative }

    private fun performRequest(url: String, method: String, headers: Map<String, String>, body: String? = null): RequestResponse {
        val rb = Request.Builder().url(url).headers(buildHeaders(headers))
        when (method.uppercase()) {
            "POST" -> rb.post((body ?: "").toRequestBody())
            else -> rb.get()
        }
        httpClient.newCall(rb.build()).execute().use { r ->
            return RequestResponse(r.isSuccessful, r.code, r.message, r.request.url.toString(), r.body?.string().orEmpty())
        }
    }

    private fun buildHeaders(source: Map<String, String>): Headers {
        val b = Headers.Builder()
        source.forEach { (k, v) -> if (!k.equals("Accept-Encoding", ignoreCase = true)) b.add(k, v) }
        if (source.keys.none { it.equals("User-Agent", ignoreCase = true) }) b.add("User-Agent", DEFAULT_USER_AGENT)
        return b.build()
    }
}

private data class RequestResponse(val ok: Boolean, val status: Int, val statusText: String, val url: String, val body: String)

private fun Map<*, *>.mapValue(key: String) = this[key] as? Map<*, *>
private fun Map<*, *>.listMapValue(key: String) = (this[key] as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: emptyList()
private fun Map<*, *>.stringValue(key: String) = this[key]?.toString()
private fun Map<*, *>.numberValue(key: String): Double? = when (val v = this[key]) {
    is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null
}
