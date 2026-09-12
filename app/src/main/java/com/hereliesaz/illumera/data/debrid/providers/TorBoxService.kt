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

/** https://api.torbox.app/v1/api — API docs at https://api-docs.torbox.app */
@Singleton
class TorBoxService @Inject constructor(private val http: DebridHttp) : DebridService {

    private val base = "https://api.torbox.app/v1/api"
    private fun auth(apiKey: String) = mapOf("Authorization" to "Bearer $apiKey")

    private fun com.google.gson.JsonElement.unwrap(): DebridResult<com.google.gson.JsonElement> {
        val obj = asObjectOrNull() ?: return DebridResult.Failure("Unexpected TorBox response")
        return if (obj.get("success")?.asBoolean == true) {
            DebridResult.Success(obj.get("data") ?: com.google.gson.JsonObject())
        } else {
            DebridResult.Failure(obj.get("detail")?.asString ?: "TorBox error")
        }
    }

    override suspend fun validateApiKey(apiKey: String): DebridResult<DebridAccountInfo> {
        return when (val result = http.get("$base/user/me", auth(apiKey))) {
            is DebridResult.Success -> when (val data = result.value.unwrap()) {
                is DebridResult.Success -> {
                    val obj = data.value.asObjectOrNull() ?: return DebridResult.Failure("Unexpected TorBox response")
                    DebridResult.Success(
                        DebridAccountInfo(
                            username = obj.get("email")?.asString ?: "TorBox user",
                            premiumUntil = obj.get("plan")?.asString
                        )
                    )
                }
                is DebridResult.Failure -> data
            }
            is DebridResult.Failure -> result
        }
    }

    override suspend fun listLibrary(apiKey: String): DebridResult<List<DebridItem>> {
        return when (val result = http.get("$base/torrents/mylist", auth(apiKey))) {
            is DebridResult.Success -> when (val data = result.value.unwrap()) {
                is DebridResult.Success -> {
                    val list = data.value.asArrayOrNull() ?: return DebridResult.Success(emptyList())
                    DebridResult.Success(
                        list.mapNotNull { el ->
                            val o = el.asObjectOrNull() ?: return@mapNotNull null
                            val files = o.get("files")?.asArrayOrNull()
                                ?.mapNotNull { it.asObjectOrNull() }
                                ?: emptyList()
                            // Prefer video file IDs; fall back to all IDs so nothing disappears from the library UI.
                            val videoFileIds = files.filter {
                                isVideoFilename(it.get("name")?.asString ?: "")
                            }.mapNotNull { it.get("id")?.asString }
                            val allFileIds = files.mapNotNull { it.get("id")?.asString }
                            val fileIds = videoFileIds.ifEmpty { allFileIds }
                            DebridItem(
                                id = o.get("id")?.asString ?: return@mapNotNull null,
                                name = o.get("name")?.asString ?: "Unknown",
                                provider = DebridProvider.TORBOX,
                                sizeBytes = o.get("size")?.asLong,
                                status = o.get("download_state")?.asString,
                                progress = o.get("progress")?.asDouble?.let { (it * 100).toInt() },
                                addedAt = o.get("created_at")?.asString,
                                directLinks = fileIds
                            )
                        }
                    )
                }
                is DebridResult.Failure -> data
            }
            is DebridResult.Failure -> result
        }
    }

    override suspend fun getStreamUrl(apiKey: String, item: DebridItem): DebridResult<String> {
        if (item.directLinks.isEmpty()) return DebridResult.Failure("No file available for this item")
        // directLinks contains video-preferred file IDs; return the first that resolves successfully.
        var lastFailure: DebridResult<String> = DebridResult.Failure("No direct link returned")
        for (fileId in item.directLinks) {
            val result = http.get(
                "$base/torrents/requestdl",
                query = mapOf("token" to apiKey, "torrent_id" to item.id, "file_id" to fileId)
            )
            when (result) {
                is DebridResult.Success -> when (val data = result.value.unwrap()) {
                    is DebridResult.Success -> {
                        val url = data.value.asString
                        if (url != null) return DebridResult.Success(url)
                    }
                    is DebridResult.Failure -> lastFailure = data
                }
                is DebridResult.Failure -> lastFailure = result
            }
        }
        return lastFailure
    }

    override suspend fun deleteItem(apiKey: String, item: DebridItem): DebridResult<Unit> {
        return when (
            val result = http.post(
                "$base/torrents/controltorrent",
                auth(apiKey),
                form = mapOf("torrent_id" to item.id, "operation" to "delete")
            )
        ) {
            is DebridResult.Success -> when (val data = result.value.unwrap()) {
                is DebridResult.Success -> DebridResult.Success(Unit)
                is DebridResult.Failure -> data
            }
            is DebridResult.Failure -> result
        }
    }
}
