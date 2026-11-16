package com.hereliesaz.illumera.ui.home

import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.data.model.stremio.Stream

/**
 * What a title that is itself a web video (a YouTube video, a video site) previews with: the
 * video, not a trailer. [YouTube] still has to be resolved to a playable address; [Direct] plays
 * as it is.
 */
internal sealed interface WebPreviewSource {
    data class YouTube(val reference: String) : WebPreviewSource
    data class Direct(val url: String) : WebPreviewSource
}

/**
 * A title is looked at as a web video only if an addon's own catalog lists it under an id that
 * is not an IMDb id: Cinemeta-style titles have trailers, these are the video itself.
 */
internal fun isWebVideoCandidate(item: MetaItem): Boolean =
    item.id.isNotBlank() && !item.addonBaseUrl.isNullOrBlank() && !IMDB_ID.matches(item.id)

/**
 * The first stream that is a video on a website: a YouTube video, or a plain http(s) address.
 * Torrents are not (they need debrid or a download), and neither is anything that needs request
 * headers or is marked not web ready, since a bare preview player could not play it.
 */
internal fun webPreviewSource(streams: List<Stream>): WebPreviewSource? {
    for (stream in streams) {
        if (stream.infoHash != null) continue
        if (!stream.behaviorHints?.proxyHeaders?.request.isNullOrEmpty()) continue
        stream.ytId?.takeIf { it.isNotBlank() }?.let { return WebPreviewSource.YouTube(it) }
        val address = stream.url?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?: stream.externalUrl?.trim()?.takeIf { isYouTubeAddress(it) }
            ?: continue
        if (isYouTubeAddress(address)) return WebPreviewSource.YouTube(address)
        if (stream.behaviorHints?.notWebReady == true) continue
        return WebPreviewSource.Direct(address)
    }
    return null
}

private fun isYouTubeAddress(address: String): Boolean =
    address.contains("youtube.com", ignoreCase = true) || address.contains("youtu.be", ignoreCase = true)

/** Where a web video's preview starts: halfway through, or null while the length is unknown. */
internal fun previewMidpointMs(durationMs: Long): Long? =
    durationMs.takeIf { it > 0 }?.div(2)

private val IMDB_ID = Regex("^tt[0-9]+$")
