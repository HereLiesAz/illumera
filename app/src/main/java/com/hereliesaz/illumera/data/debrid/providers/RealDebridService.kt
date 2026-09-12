package com.hereliesaz.illumera.data.debrid.providers

import com.hereliesaz.illumera.data.debrid.DebridHttp
import com.hereliesaz.illumera.data.debrid.DebridService
import com.hereliesaz.illumera.data.debrid.asArrayOrNull
import com.hereliesaz.illumera.data.debrid.asObjectOrNull
import com.hereliesaz.illumera.data.debrid.isVideoFilename
import com.hereliesaz.illumera.data.model.debrid.DebridAccountInfo
import com.hereliesaz.illumera.data.model.debrid.DebridItem
import com.hereliesaz.illumera.data.model.debrid.DebridProvider
import com.hereliesaz.illumera.data.model.debrid.DebridResult
import javax.inject.Inject
import javax.inject.Singleton

/** https://api.real-debrid.com/rest/1.0 — API docs at https://api.real-debrid.com */
@Singleton
class RealDebridService @Inject constructor(private val http: DebridHttp) : DebridService {

    private val base = "https://api.real-debrid.com/rest/1.0"
    private fun auth(apiKey: String) = mapOf("Authorization" to "Bearer $apiKey")

    override suspend fun validateApiKey(apiKey: String): DebridResult<DebridAccountInfo> {
        return when (val result = http.get("$base/user", auth(apiKey))) {
            is DebridResult.Success -> {
                val obj = result.value.asObjectOrNull()
                val username = obj?.get("username")?.asString ?: obj?.get("email")?.asString
                if (username == null) {
                    DebridResult.Failure("Unexpected response from Real-Debrid")
                } else {
                    DebridResult.Success(
                        DebridAccountInfo(
                            username = username,
                            premiumUntil = obj?.get("expiration")?.asString
                        )
                    )
                }
            }
            is DebridResult.Failure -> result
        }
    }

    override suspend fun listLibrary(apiKey: String): DebridResult<List<DebridItem>> {
        val torrents = when (val result = http.get("$base/torrents", auth(apiKey))) {
            is DebridResult.Success -> result.value.asArrayOrNull()?.mapNotNull { el ->
                val o = el.asObjectOrNull() ?: return@mapNotNull null
                DebridItem(
                    id = o.get("id")?.asString ?: return@mapNotNull null,
                    name = o.get("filename")?.asString ?: "Unknown",
                    provider = DebridProvider.REAL_DEBRID,
                    sizeBytes = o.get("bytes")?.asLong,
                    status = o.get("status")?.asString,
                    progress = o.get("progress")?.asInt,
                    addedAt = o.get("added")?.asString,
                    infoHash = o.get("hash")?.asString,
                    directLinks = o.get("links")?.asArrayOrNull()?.mapNotNull { it.asString } ?: emptyList()
                )
            } ?: emptyList()
            is DebridResult.Failure -> return result
        }

        val downloads = when (val result = http.get("$base/downloads", auth(apiKey))) {
            is DebridResult.Success -> result.value.asArrayOrNull()?.mapNotNull { el ->
                val o = el.asObjectOrNull() ?: return@mapNotNull null
                val link = o.get("download")?.asString
                DebridItem(
                    id = o.get("id")?.asString ?: return@mapNotNull null,
                    name = o.get("filename")?.asString ?: "Unknown",
                    provider = DebridProvider.REAL_DEBRID,
                    sizeBytes = o.get("filesize")?.asLong,
                    status = "downloaded",
                    addedAt = o.get("generated")?.asString,
                    directLinks = listOfNotNull(link)
                )
            } ?: emptyList()
            is DebridResult.Failure -> emptyList()
        }

        return DebridResult.Success(torrents + downloads)
    }

    override suspend fun getStreamUrl(apiKey: String, item: DebridItem): DebridResult<String> {
        if (item.directLinks.isEmpty()) return DebridResult.Failure("No link available for this item")

        // Pre-unrestricted download links (from the downloads endpoint) need no resolution.
        if (item.status == "downloaded" && item.directLinks.any { it.contains("/d/") }) {
            return DebridResult.Success(item.directLinks.first())
        }

        // Iterate hoster links — prefer the first one whose unrestricted filename is a video.
        var firstUrl: String? = null
        for (link in item.directLinks) {
            val result = http.post("$base/unrestrict/link", auth(apiKey), form = mapOf("link" to link))
            if (result is DebridResult.Success) {
                val obj = result.value.asObjectOrNull() ?: continue
                val url = obj.get("download")?.asString ?: continue
                val filename = obj.get("filename")?.asString ?: ""
                val type = obj.get("type")?.asString ?: ""
                if (firstUrl == null) firstUrl = url
                if (type == "video" || isVideoFilename(filename)) return DebridResult.Success(url)
            }
        }
        return if (firstUrl != null) DebridResult.Success(firstUrl)
        else DebridResult.Failure("No direct link returned")
    }

    override suspend fun deleteItem(apiKey: String, item: DebridItem): DebridResult<Unit> {
        val path = if (item.status == "downloaded" && item.directLinks.any { it.contains("/d/") }) {
            "downloads"
        } else {
            "torrents"
        }
        return when (val result = http.delete("$base/$path/delete/${item.id}", auth(apiKey))) {
            is DebridResult.Success -> DebridResult.Success(Unit)
            is DebridResult.Failure -> result
        }
    }
}
