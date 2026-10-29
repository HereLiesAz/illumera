package com.hereliesaz.illumera.data.debrid.providers

import com.hereliesaz.illumera.data.debrid.DebridHttp
import com.hereliesaz.illumera.data.debrid.DebridService
import com.hereliesaz.illumera.data.debrid.asArrayOrNull
import com.hereliesaz.illumera.data.debrid.asObjectOrNull
import com.hereliesaz.illumera.data.debrid.int
import com.hereliesaz.illumera.data.debrid.isVideoFilename
import com.hereliesaz.illumera.data.debrid.long
import com.hereliesaz.illumera.data.debrid.str
import com.hereliesaz.illumera.data.debrid.strOrNull
import com.hereliesaz.illumera.data.model.debrid.DebridAccountInfo
import com.hereliesaz.illumera.data.model.debrid.DebridItem
import com.hereliesaz.illumera.data.model.debrid.DebridItemOrigin
import com.hereliesaz.illumera.data.model.debrid.DebridProvider
import com.hereliesaz.illumera.data.model.debrid.DebridResult
import javax.inject.Inject
import javax.inject.Singleton

/** https://api.real-debrid.com/rest/1.0 — API docs at https://api.real-debrid.com */
@Singleton
open class RealDebridService @Inject constructor(private val http: DebridHttp) : DebridService {

    protected open val base = "https://api.real-debrid.com/rest/1.0"
    protected open val provider = DebridProvider.REAL_DEBRID
    // Torrin implements RD torrent endpoints but not RD's optional downloads list.
    protected open val hasDownloadsEndpoint = true
    private fun auth(apiKey: String) = mapOf("Authorization" to "Bearer $apiKey")

    override suspend fun validateApiKey(apiKey: String): DebridResult<DebridAccountInfo> {
        return when (val result = http.get("$base/user", auth(apiKey))) {
            is DebridResult.Success -> {
                val obj = result.value.asObjectOrNull()
                val username = obj?.str("username") ?: obj?.str("email")
                if (username == null) {
                    DebridResult.Failure("Unexpected response from ${provider.displayName}")
                } else {
                    DebridResult.Success(
                        DebridAccountInfo(
                            username = username,
                            premiumUntil = obj?.str("expiration")
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
                    id = o.str("id") ?: return@mapNotNull null,
                    name = o.str("filename") ?: "Unknown",
                    provider = provider,
                    sizeBytes = o.long("bytes"),
                    status = o.str("status"),
                    progress = o.int("progress"),
                    addedAt = o.str("added"),
                    infoHash = o.str("hash"),
                    directLinks = o.get("links")?.asArrayOrNull()?.mapNotNull { it.strOrNull() } ?: emptyList()
                )
            } ?: emptyList()
            is DebridResult.Failure -> return result
        }

        val downloads = if (hasDownloadsEndpoint) when (val result = http.get("$base/downloads", auth(apiKey))) {
            is DebridResult.Success -> result.value.asArrayOrNull()?.mapNotNull { el ->
                val o = el.asObjectOrNull() ?: return@mapNotNull null
                val link = o.str("download")
                DebridItem(
                    id = o.str("id") ?: return@mapNotNull null,
                    name = o.str("filename") ?: "Unknown",
                    provider = provider,
                    sizeBytes = o.long("filesize"),
                    status = "downloaded",
                    addedAt = o.str("generated"),
                    directLinks = listOfNotNull(link),
                    origin = DebridItemOrigin.DOWNLOAD
                )
            } ?: emptyList()
            is DebridResult.Failure -> emptyList()
        } else emptyList()

        return DebridResult.Success(torrents + downloads)
    }

    override suspend fun getStreamUrl(apiKey: String, item: DebridItem): DebridResult<String> {
        if (item.directLinks.isEmpty()) return DebridResult.Failure("No link available for this item")

        // Pre-unrestricted download links (from the downloads endpoint) need no resolution.
        // Torrent links are hoster links (also real-debrid.com/d/...) and must be unrestricted.
        if (item.origin == DebridItemOrigin.DOWNLOAD) {
            return DebridResult.Success(item.directLinks.first())
        }

        // Iterate hoster links — prefer the first one whose unrestricted filename is a video.
        var firstUrl: String? = null
        for (link in item.directLinks) {
            val result = http.post("$base/unrestrict/link", auth(apiKey), form = mapOf("link" to link))
            if (result is DebridResult.Success) {
                val obj = result.value.asObjectOrNull() ?: continue
                val url = obj.str("download") ?: continue
                val filename = obj.str("filename") ?: ""
                val type = obj.str("type") ?: ""
                if (firstUrl == null) firstUrl = url
                if (type == "video" || isVideoFilename(filename)) return DebridResult.Success(url)
            }
        }
        return if (firstUrl != null) DebridResult.Success(firstUrl)
        else DebridResult.Failure("No direct link returned")
    }

    override suspend fun deleteItem(apiKey: String, item: DebridItem): DebridResult<Unit> {
        val path = when (item.origin) {
            DebridItemOrigin.DOWNLOAD -> "downloads"
            DebridItemOrigin.TORRENT -> "torrents"
        }
        return when (val result = http.delete("$base/$path/delete/${item.id}", auth(apiKey))) {
            is DebridResult.Success -> DebridResult.Success(Unit)
            is DebridResult.Failure -> result
        }
    }
}
