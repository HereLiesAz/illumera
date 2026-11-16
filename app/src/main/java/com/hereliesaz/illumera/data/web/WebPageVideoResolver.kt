package com.hereliesaz.illumera.data.web

import com.hereliesaz.illumera.data.trailer.YouTubeExtractor
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** A video address found behind a web page, with the request headers it needs to play. */
data class ResolvedWebVideo(val url: String, val headers: Map<String, String> = emptyMap())

/**
 * Turns the address of a web page into the address of the video on it.
 *
 * Streams from video sites and some addons hand over the page (`https://site/watch/123`), not a
 * file, and the player cannot read a page. The resolver takes it from there: a YouTube address
 * goes to the YouTube extractor; any other page is fetched, and the video address it carries is
 * picked out of the page (Open Graph and `<video>` tags, JSON-LD and the player's own settings,
 * HLS and MP4 links), following an embedded player frame one step if the page has none itself.
 * The page address goes along as the `Referer`, since many hosts refuse a video asked for
 * without it. It finds what the page links to; it does not get past a login, a captcha or DRM.
 */
@Singleton
class WebPageVideoResolver @Inject constructor(
    private val client: OkHttpClient,
    private val youTubeExtractor: YouTubeExtractor
) {
    suspend fun resolve(pageUrl: String, requestHeaders: Map<String, String> = emptyMap()): ResolvedWebVideo? =
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            if (isYouTubeAddress(pageUrl)) {
                return@withTimeoutOrNull youTubeExtractor.extractPlaybackSource(pageUrl, preferCompatiblePreview = true)
                    ?.let { ResolvedWebVideo(it.videoUrl) }
            }
            resolveFrom(pageUrl, requestHeaders, FRAME_DEPTH, ::fetchPage)
        }

    /** [fetch] gets a page's text (null on failure); split out so the walk can be tested. */
    internal suspend fun resolveFrom(
        pageUrl: String,
        requestHeaders: Map<String, String>,
        framesLeft: Int,
        fetch: suspend (url: String, headers: Map<String, String>) -> String?
    ): ResolvedWebVideo? {
        val html = fetch(pageUrl, requestHeaders) ?: return null
        findVideoCandidates(html, pageUrl).let(::pickBestVideo)?.let { video ->
            return ResolvedWebVideo(video, playbackHeaders(pageUrl))
        }
        if (framesLeft <= 0) return null
        for (frame in findEmbedFrames(html, pageUrl)) {
            // The frame is shown on the page, so it is fetched as if from it.
            resolveFrom(frame, requestHeaders + ("Referer" to pageUrl), framesLeft - 1, fetch)?.let { return it }
        }
        return null
    }

    private suspend fun fetchPage(url: String, headers: Map<String, String>): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url)
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.source()?.let { source ->
                    // A page that matters is far smaller than this; a huge body is a download.
                    source.request(MAX_PAGE_BYTES)
                    source.buffer.snapshot(minOf(source.buffer.size, MAX_PAGE_BYTES).toInt()).utf8()
                }
            }
        }.getOrNull()
    }

    private companion object {
        const val RESOLVE_TIMEOUT_MS = 20_000L
        const val FRAME_DEPTH = 1
        const val MAX_PAGE_BYTES = 3L * 1024 * 1024
    }
}

internal const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 11; SHIELD Android TV) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36"

internal fun isYouTubeAddress(url: String): Boolean {
    val host = runCatching { URI(url.trim()).host?.lowercase() }.getOrNull() ?: return false
    return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") ||
        host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")
}

/** Headers a host usually wants for a video it served a page for. */
internal fun playbackHeaders(pageUrl: String): Map<String, String> {
    val origin = runCatching {
        val uri = URI(pageUrl)
        "${uri.scheme}://${uri.host}"
    }.getOrNull()
    return buildMap {
        put("Referer", pageUrl)
        origin?.let { put("Origin", it) }
        put("User-Agent", BROWSER_USER_AGENT)
    }
}

private val VIDEO_URL = Regex(
    """(?i)(?:https?:)?//[^\s"'<>\\)]+?\.(?:m3u8|mp4|webm|mpd)(?:\?[^\s"'<>\\)]*)?"""
)
private val ATTRIBUTE_VIDEO = Regex(
    """(?i)<(?:video|source)\b[^>]*?\bsrc\s*=\s*["']([^"']+)["']"""
)
private val META_VIDEO = Regex(
    """(?i)<meta\b[^>]*?(?:property|name)\s*=\s*["'](?:og:video(?::url|:secure_url)?|twitter:player:stream)["'][^>]*?\bcontent\s*=\s*["']([^"']+)["']"""
)
private val META_VIDEO_REVERSED = Regex(
    """(?i)<meta\b[^>]*?\bcontent\s*=\s*["']([^"']+)["'][^>]*?(?:property|name)\s*=\s*["'](?:og:video(?::url|:secure_url)?|twitter:player:stream)["']"""
)
private val IFRAME = Regex("""(?i)<iframe\b[^>]*?\bsrc\s*=\s*["']([^"']+)["']""")
private val VIDEO_EXTENSION = Regex("""(?i)\.(?:m3u8|mp4|webm|mpd)(?:$|[?#])""")
private val NOISE = Regex("""(?i)(?:preview|thumb|thumbnail|sprite|trailer|teaser|poster|/ads?/|advert|banner|\.gif|vtt)""")
private val QUALITY = Regex("""(?i)(?<![0-9])(2160|1440|1080|720|576|480|360|240)p?(?![0-9])""")

/** Undoes the escaping a page puts around a URL inside script and JSON text. */
internal fun unescapePageText(text: String): String = text
    .replace("\\/", "/")
    .replace("\\u002F", "/", ignoreCase = true)
    .replace("\\u0026", "&", ignoreCase = true)
    .replace("&amp;", "&")
    .replace("\\x2F", "/", ignoreCase = true)

private fun absolute(base: String, reference: String): String? {
    val ref = reference.trim()
    if (ref.isEmpty() || ref.startsWith("data:", true) || ref.startsWith("blob:", true) ||
        ref.startsWith("javascript:", true)
    ) return null
    return runCatching { URI(base).resolve(ref).toString() }.getOrNull()
        ?.takeIf { it.startsWith("http", ignoreCase = true) }
}

/**
 * Every video address the page text mentions, as absolute URLs, in the order they appear:
 * Open Graph / `<video>` / `<source>` tags first (the page says outright it is the video), then
 * any HLS / MP4 / WebM / DASH address in the page's scripts and JSON.
 */
internal fun findVideoCandidates(html: String, pageUrl: String): List<String> {
    val text = unescapePageText(html)
    val found = LinkedHashSet<String>()
    for (regex in listOf(META_VIDEO, META_VIDEO_REVERSED, ATTRIBUTE_VIDEO)) {
        regex.findAll(text).forEach { match ->
            absolute(pageUrl, match.groupValues[1])?.takeIf { VIDEO_EXTENSION.containsMatchIn(it) }?.let(found::add)
        }
    }
    VIDEO_URL.findAll(text).forEach { match ->
        absolute(pageUrl, match.value)?.let(found::add)
    }
    return found.toList()
}

/**
 * The candidate most likely to be the film rather than an advert, preview or thumbnail clip:
 * HLS above MP4 above the rest, a "master" playlist above a variant, the highest named quality
 * first, noise words last. Null when nothing is left.
 */
internal fun pickBestVideo(candidates: List<String>): String? {
    if (candidates.isEmpty()) return null
    fun score(url: String): Int {
        val path = url.substringBefore('?').lowercase()
        var points = 0
        points += when {
            path.endsWith(".m3u8") -> 300
            path.endsWith(".mpd") -> 250
            path.endsWith(".mp4") -> 200
            else -> 100
        }
        if ("master" in path) points += 40
        QUALITY.find(url)?.groupValues?.get(1)?.toIntOrNull()?.let { points += it / 20 }
        if (NOISE.containsMatchIn(url)) points -= 500
        return points
    }
    return candidates.maxByOrNull(::score)?.takeIf { score(it) > 0 }
}

/** Player frames the page embeds (the video is often one frame in), as absolute addresses. */
internal fun findEmbedFrames(html: String, pageUrl: String): List<String> =
    IFRAME.findAll(unescapePageText(html))
        .mapNotNull { absolute(pageUrl, it.groupValues[1]) }
        .filterNot { NOISE.containsMatchIn(it) || it.contains("google", true) && it.contains("ads", true) }
        .distinct()
        .toList()
