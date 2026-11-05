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

    override suspend fun addMagnet(apiKey: String, magnet: String): DebridResult<Unit> {
        val added = when (val result = http.post("$base/torrents/addMagnet", auth(apiKey), form = mapOf("magnet" to magnet))) {
            is DebridResult.Success -> result.value.asObjectOrNull()?.str("id")
                ?: return DebridResult.Failure("Unexpected response from ${provider.displayName}")
            is DebridResult.Failure -> return result
        }
        // A new magnet converts first; the file choice opens once it is "waiting_files_selection".
        repeat(10) {
            val info = (http.get("$base/torrents/info/$added", auth(apiKey)) as? DebridResult.Success)
                ?.value?.asObjectOrNull()
            when (info?.str("status")) {
                "waiting_files_selection" -> {
                    return when (val r = http.post(
                        "$base/torrents/selectFiles/$added", auth(apiKey), form = mapOf("files" to "all")
                    )) {
                        is DebridResult.Success -> DebridResult.Success(Unit)
                        is DebridResult.Failure -> r
                    }
                }
                // Already past file selection (cached, or the account had it): nothing to do.
                "downloading", "downloaded", "queued", "uploading", "compressing" -> return DebridResult.Success(Unit)
                "magnet_error", "error", "virus", "dead" -> return DebridResult.Failure("${provider.displayName} could not fetch this torrent")
            }
            kotlinx.coroutines.delay(1_000)
        }
        return DebridResult.Failure("${provider.displayName} took too long to read this torrent")
    }

    override suspend fun getStreamUrl(
        apiKey: String,
        item: DebridItem,
        preferredFileName: String?
    ): DebridResult<String> {
        if (preferredFileName.isNullOrBlank() || item.origin == DebridItemOrigin.DOWNLOAD || item.directLinks.size < 2) {
            return getStreamUrl(apiKey, item)
        }
        // A pack: unrestrict links one by one and stop at the file that is the wanted release.
        for (link in item.directLinks) {
            val obj = (http.post("$base/unrestrict/link", auth(apiKey), form = mapOf("link" to link))
                as? DebridResult.Success)?.value?.asObjectOrNull() ?: continue
            val url = obj.str("download") ?: continue
            if (com.hereliesaz.illumera.data.debrid.fileNamesMatch(preferredFileName, obj.str("filename"))) {
                return DebridResult.Success(url)
            }
        }
        return getStreamUrl(apiKey, item)
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
