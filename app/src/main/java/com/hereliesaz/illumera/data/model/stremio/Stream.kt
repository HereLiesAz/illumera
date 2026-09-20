package com.hereliesaz.illumera.data.model.stremio

import kotlin.jvm.Transient

data class StreamResponse(
    val streams: List<Stream>? = null
)

data class SubtitleResponse(
    val subtitles: List<StreamSubtitle> = emptyList()
)

data class Stream(
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val ytId: String? = null,
    val externalUrl: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val sources: List<String>? = null,
    val subtitles: List<StreamSubtitle>? = null,
    val behaviorHints: StreamBehaviorHints? = null,

    // Illumera-only provenance/UI metadata. Never serialize these as addon protocol fields.
    @Transient val addonTransportUrl: String? = null,
    @Transient val addonDisplayName: String? = null,
    @Transient val addonRequestType: String? = null,
    @Transient val addonRequestId: String? = null,
    @Transient val sourceSelectionId: String? = null
)

data class StreamSubtitle(
    val id: String? = null,
    val lang: String? = null,
    val url: String? = null,
    val name: String? = null,
    val transportUrl: String? = null
)

data class StreamBehaviorHints(
    val countryWhitelist: List<String>? = null,
    val notWebReady: Boolean? = null,
    val bingeGroup: String? = null,
    val proxyHeaders: StreamProxyHeaders? = null,
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val filename: String? = null
)

data class StreamProxyHeaders(
    val request: Map<String, String>? = null,
    val response: Map<String, String>? = null
)
