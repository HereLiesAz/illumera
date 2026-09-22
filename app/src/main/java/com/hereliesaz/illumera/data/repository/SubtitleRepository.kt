package com.hereliesaz.illumera.data.repository

import com.google.gson.JsonElement
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.AddonEntity
import com.hereliesaz.illumera.data.model.stremio.Manifest
import com.hereliesaz.illumera.data.model.stremio.Stream
import com.hereliesaz.illumera.data.model.stremio.StreamSubtitle
import com.hereliesaz.illumera.data.remote.StremioApiService
import com.hereliesaz.illumera.domain.AddonSubtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubtitleRepository @Inject constructor(
    private val api: StremioApiService,
    private val dao: AddonDao
) {

    companion object {
        private const val MANIFEST_TIMEOUT_MS = 6_000L
        private const val PER_ADDON_TIMEOUT_MS = 8_000L
    }

    private data class SubtitleResourceRule(
        val types: Set<String> = emptySet(),
        val idPrefixes: Set<String> = emptySet()
    )

    private data class SubtitleRequest(
        val contentType: String,
        val baseId: String,
        val requestId: String
    )

    private sealed interface SubtitleCapability {
        data class Known(val rules: List<SubtitleResourceRule>) : SubtitleCapability
        data object Unknown : SubtitleCapability
    }

    private val subtitleCapabilityCache = ConcurrentHashMap<String, SubtitleCapability>()

    suspend fun getSubtitles(
        type: String,
        playbackId: String,
        videoHash: String? = null,
        videoSize: Long? = null,
        filename: String? = null,
        preferredAddonBaseUrl: String? = null,
        preferredAddonRequestId: String? = null
    ): List<AddonSubtitle> = withContext(Dispatchers.IO) {
        val canonicalRequest = buildSubtitleRequest(type, playbackId)
        val preferredBase = preferredAddonBaseUrl?.trimEnd('/')
        val addons = dao.getAllAddons().firstOrNull()?.filter { it.isEnabled } ?: emptyList()
        if (addons.isEmpty()) return@withContext emptyList()

        val jobs = addons.map { addon ->
            async {
                val isPreferred = preferredBase != null &&
                    addon.transportUrl.trimEnd('/') == preferredBase
                val request = if (isPreferred && !preferredAddonRequestId.isNullOrBlank()) {
                    buildSubtitleRequest(type, preferredAddonRequestId)
                } else {
                    canonicalRequest
                }

                if (!shouldQueryAddonForSubtitles(addon, request.contentType, request.baseId)) {
                    return@async emptyList()
                }
                fetchSubtitlesFromAddon(
                    addon = addon,
                    request = request,
                    videoHash = videoHash,
                    videoSize = videoSize,
                    filename = filename,
                    preferredAddonBaseUrl = preferredAddonBaseUrl,
                    preferredAddonRequestId = preferredAddonRequestId
                )
            }
        }

        distinctSubtitles(jobs.awaitAll().flatten())
    }

    /**
     * Refines a generic subtitle result once the actual stream is known. Stremio subtitle
     * addons may key results by behaviorHints.videoHash/videoSize/filename; those values do
     * not exist until after source selection, so callers should use this at that boundary.
     *
     * When a stream exposes no such hints the already-fetched generic results are returned
     * without another network round-trip.
     */
    suspend fun getSubtitlesForStream(
        type: String,
        playbackId: String,
        stream: Stream,
        fallback: List<AddonSubtitle>? = null
    ): List<AddonSubtitle> {
        val hints = stream.behaviorHints
        val videoHash = hints?.videoHash?.trim()?.takeIf { it.isNotEmpty() }
        val videoSize = hints?.videoSize?.takeIf { it > 0L }
        val filename = hints?.filename?.trim()?.takeIf { it.isNotEmpty() }

        if (videoHash == null && videoSize == null && filename == null) {
            return fallback?.let(::distinctSubtitles)
                ?: requestOrNull {
                    getSubtitles(
                        type = type,
                        playbackId = playbackId,
                        preferredAddonBaseUrl = stream.addonTransportUrl,
                        preferredAddonRequestId = stream.addonRequestId
                    )
                }.orEmpty()
        }

        if (fallback != null) {
            val sourceAware = requestOrNull {
                getSubtitles(
                    type = type,
                    playbackId = playbackId,
                    videoHash = videoHash,
                    videoSize = videoSize,
                    filename = filename,
                    preferredAddonBaseUrl = stream.addonTransportUrl,
                    preferredAddonRequestId = stream.addonRequestId
                )
            }.orEmpty()

            // Source-aware rows take precedence when the generic request returned the same track.
            return distinctSubtitles(sourceAware + fallback)
        }

        // Live source switches do not have a trustworthy generic fallback: the current
        // player's subtitle set may already be source-specific. Fetch the generic and
        // source-aware variants concurrently so correctness does not double the latency.
        return coroutineScope {
            val genericDeferred = async {
                requestOrNull {
                    getSubtitles(
                        type = type,
                        playbackId = playbackId,
                        preferredAddonBaseUrl = stream.addonTransportUrl,
                        preferredAddonRequestId = stream.addonRequestId
                    )
                }.orEmpty()
            }
            val sourceAwareDeferred = async {
                requestOrNull {
                    getSubtitles(
                        type = type,
                        playbackId = playbackId,
                        videoHash = videoHash,
                        videoSize = videoSize,
                        filename = filename,
                        preferredAddonBaseUrl = stream.addonTransportUrl,
                        preferredAddonRequestId = stream.addonRequestId
                    )
                }.orEmpty()
            }

            distinctSubtitles(sourceAwareDeferred.await() + genericDeferred.await())
        }
    }

    private suspend fun <T> requestOrNull(block: suspend () -> T): T? {
        return try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun distinctSubtitles(subtitles: List<AddonSubtitle>): List<AddonSubtitle> =
        subtitles.distinctBy { subtitle ->
            val url = subtitle.url.lowercase(Locale.ROOT)
            val lang = subtitle.lang.orEmpty().lowercase(Locale.ROOT)
            val addon = subtitle.addonName.lowercase(Locale.ROOT)
            "$url|$lang|$addon"
        }

    private fun buildSubtitleRequest(type: String, playbackId: String): SubtitleRequest {
        val normalizedType = type.trim().lowercase(Locale.ROOT)
        val normalizedId = playbackId.trim()

        if (normalizedType == "series") {
            val parts = normalizedId.split(":")
            if (
                parts.size >= 3 &&
                parts[parts.lastIndex - 1].toIntOrNull() != null &&
                parts.last().toIntOrNull() != null
            ) {
                val baseId = parts.dropLast(2).joinToString(":")
                if (baseId.isNotBlank()) {
                    return SubtitleRequest(
                        contentType = normalizedType,
                        baseId = baseId,
                        requestId = normalizedId
                    )
                }
            }
        }

        return SubtitleRequest(
            contentType = normalizedType,
            baseId = normalizedId,
            requestId = normalizedId
        )
    }

    private suspend fun shouldQueryAddonForSubtitles(
        addon: AddonEntity,
        contentType: String,
        baseId: String
    ): Boolean {
        val capability = getSubtitleCapability(addon.transportUrl)
        return when (capability) {
            SubtitleCapability.Unknown -> true
            is SubtitleCapability.Known -> {
                if (capability.rules.isEmpty()) {
                    false
                } else {
                    capability.rules.any { rule ->
                        val typeSupported = rule.types.isEmpty() || contentType in rule.types
                        val idSupported = rule.idPrefixes.isEmpty() || rule.idPrefixes.any { prefix ->
                            baseId.startsWith(prefix)
                        }
                        typeSupported && idSupported
                    }
                }
            }
        }
    }

    private suspend fun getSubtitleCapability(transportUrl: String): SubtitleCapability {
        subtitleCapabilityCache[transportUrl]?.let { return it }

        val manifestUrl = "${transportUrl.trimEnd('/')}/manifest.json"
        val capability = requestOrNull {
            val manifest = withTimeout(MANIFEST_TIMEOUT_MS) { api.getManifest(manifestUrl) }
            SubtitleCapability.Known(parseSubtitleResourceRules(manifest))
        } ?: SubtitleCapability.Unknown

        subtitleCapabilityCache[transportUrl] = capability
        return capability
    }

    private fun parseSubtitleResourceRules(manifest: Manifest): List<SubtitleResourceRule> {
        return manifest.resources.orEmpty().mapNotNull { resource ->
            parseSubtitleResourceRule(resource)
        }
    }

    private fun parseSubtitleResourceRule(resource: JsonElement): SubtitleResourceRule? {
        if (resource.isJsonPrimitive && resource.asJsonPrimitive.isString) {
            val resourceName = resource.asString.trim().lowercase(Locale.ROOT)
            if (resourceName == "subtitle" || resourceName == "subtitles") {
                return SubtitleResourceRule()
            }
            return null
        }

        if (!resource.isJsonObject) {
            return null
        }

        val obj = resource.asJsonObject
        val name = obj.get("name")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?: return null

        if (name != "subtitle" && name != "subtitles") {
            return null
        }

        val types = parseStringSet(obj.get("types"))
        val idPrefixes = parseStringSet(obj.get("idPrefixes"))
        return SubtitleResourceRule(types = types, idPrefixes = idPrefixes)
    }

    private fun parseStringSet(value: JsonElement?): Set<String> {
        if (value == null || !value.isJsonArray) return emptySet()
        return value.asJsonArray.mapNotNull { element ->
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
                null
            } else {
                element.asString.trim().takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)
            }
        }.toSet()
    }

    private suspend fun fetchSubtitlesFromAddon(
        addon: AddonEntity,
        request: SubtitleRequest,
        videoHash: String?,
        videoSize: Long?,
        filename: String?,
        preferredAddonBaseUrl: String? = null,
        preferredAddonRequestId: String? = null
    ): List<AddonSubtitle> {
        val baseUrl = addon.transportUrl.trimEnd('/')
        val pathType = request.contentType
        val isPreferred = !preferredAddonBaseUrl.isNullOrBlank() &&
            baseUrl == preferredAddonBaseUrl.trimEnd('/')
        val pathId = if (isPreferred && !preferredAddonRequestId.isNullOrBlank()) {
            preferredAddonRequestId
        } else {
            request.requestId
        }
        val extraParams = buildExtraParams(videoHash, videoSize, filename)
        val subtitleUrl = if (extraParams.isNotEmpty()) {
            "$baseUrl/subtitles/$pathType/$pathId/$extraParams.json"
        } else {
            "$baseUrl/subtitles/$pathType/$pathId.json"
        }

        val response = requestOrNull {
            withTimeout(PER_ADDON_TIMEOUT_MS) { api.getSubtitles(subtitleUrl) }
        } ?: return emptyList()

        val addonName = addon.nickname?.takeIf { it.isNotBlank() } ?: addon.name
        return response.subtitles.mapNotNull { subtitle ->
            subtitle.toAddonSubtitle(addonName = addonName, addonBaseUrl = addon.transportUrl)
        }
    }

    private fun StreamSubtitle.toAddonSubtitle(addonName: String, addonBaseUrl: String): AddonSubtitle? {
        val resolvedUrl = resolveSubtitleUrl(url, addonBaseUrl) ?: return null
        val subtitleId = id?.trim()?.takeIf { it.isNotEmpty() } ?: "$addonName:${resolvedUrl.hashCode()}"
        return AddonSubtitle(
            id = subtitleId,
            url = resolvedUrl,
            lang = normalizeLanguageCode(lang),
            addonName = addonName
        )
    }

    private fun resolveSubtitleUrl(rawUrl: String?, addonBaseUrl: String): String? {
        val urlValue = rawUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = runCatching { URI(urlValue) }.getOrNull() ?: return null
        if (uri.isAbsolute) {
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if (scheme != "http" && scheme != "https") return null
            return urlValue
        }

        val base = addonBaseUrl.trimEnd('/')
        val path = urlValue.trimStart('/')
        return "$base/$path"
    }

    private fun normalizeLanguageCode(rawLanguage: String?): String? {
        val normalized = rawLanguage?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return normalized.replace('_', '-').lowercase(Locale.ROOT)
    }

    private fun buildExtraParams(
        videoHash: String?,
        videoSize: Long?,
        filename: String?
    ): String {
        val params = mutableListOf<String>()
        videoHash?.trim()?.takeIf { it.isNotEmpty() }?.let {
            params.add("videoHash=${encodeQueryValue(it)}")
        }
        videoSize?.takeIf { it > 0L }?.let {
            params.add("videoSize=$it")
        }
        filename?.trim()?.takeIf { it.isNotEmpty() }?.let {
            params.add("filename=${encodeQueryValue(it)}")
        }
        return params.joinToString("&")
    }

    private fun encodeQueryValue(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    }
}
