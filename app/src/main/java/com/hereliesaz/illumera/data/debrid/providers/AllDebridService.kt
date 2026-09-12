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

/** https://api.alldebrid.com/v4 — API docs at https://docs.alldebrid.com */
@Singleton
class AllDebridService @Inject constructor(private val http: DebridHttp) : DebridService {

    private val base = "https://api.alldebrid.com/v4"
    private fun key(apiKey: String) = mapOf("apikey" to apiKey, "agent" to "illumera")

    /** AllDebrid wraps every response as {status: "success"|"error", data|error: {...}}. */
    private fun com.google.gson.JsonElement.unwrap(): DebridResult<com.google.gson.JsonObject> {
        val obj = asObjectOrNull() ?: return DebridResult.Failure("Unexpected AllDebrid response")
        val status = obj.get("status")?.asString
        return if (status == "success") {
            DebridResult.Success(obj.get("data")?.asObjectOrNull() ?: com.google.gson.JsonObject())
        } else {
            val message = obj.get("error")?.asObjectOrNull()?.get("message")?.asString ?: "AllDebrid error"
            DebridResult.Failure(message)
        }
    }

    override suspend fun validateApiKey(apiKey: String): DebridResult<DebridAccountInfo> {
        return when (val result = http.get("$base/user", query = key(apiKey))) {
            is DebridResult.Success -> when (val data = result.value.unwrap()) {
                is DebridResult.Success -> {
                    val user = data.value.get("user")?.asObjectOrNull()
                        ?: return DebridResult.Failure("Unexpected AllDebrid response")
                    DebridResult.Success(
                        DebridAccountInfo(
                            username = user.get("username")?.asString ?: "AllDebrid user",
                            premiumUntil = user.get("premiumUntil")?.asString
                        )
                    )
                }
                is DebridResult.Failure -> data
            }
            is DebridResult.Failure -> result
        }
    }

    override suspend fun listLibrary(apiKey: String): DebridResult<List<DebridItem>> {
        return when (val result = http.get("$base/magnet/status", query = key(apiKey))) {
            is DebridResult.Success -> when (val data = result.value.unwrap()) {
                is DebridResult.Success -> {
                    val magnets = data.value.get("magnets")?.asArrayOrNull() ?: return DebridResult.Success(emptyList())
                    DebridResult.Success(
                        magnets.mapNotNull { el ->
                            val o = el.asObjectOrNull() ?: return@mapNotNull null
                            val links = o.get("links")?.asArrayOrNull()?.mapNotNull {
                                it.asObjectOrNull()?.get("link")?.asString
                            } ?: emptyList()
                            DebridItem(
                                id = o.get("id")?.asString ?: return@mapNotNull null,
                                name = o.get("filename")?.asString ?: "Unknown",
                                provider = DebridProvider.ALL_DEBRID,
                                sizeBytes = o.get("size")?.asLong,
                                status = o.get("status")?.asString,
                                addedAt = o.get("uploadDate")?.asString,
                                directLinks = links
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
        if (item.directLinks.isEmpty()) return DebridResult.Failure("No link available for this item")

        // Iterate links — prefer the first whose unlocked filename is a video file.
        var firstUrl: String? = null
        for (link in item.directLinks) {
            val result = http.get("$base/link/unlock", query = key(apiKey) + ("link" to link))
            if (result is DebridResult.Success) {
                when (val data = result.value.unwrap()) {
                    is DebridResult.Success -> {
                        val url = data.value.get("link")?.asString ?: continue
                        val filename = data.value.get("filename")?.asString ?: ""
                        if (firstUrl == null) firstUrl = url
                        if (isVideoFilename(filename)) return DebridResult.Success(url)
                    }
                    is DebridResult.Failure -> continue
                }
            }
        }
        return if (firstUrl != null) DebridResult.Success(firstUrl)
        else DebridResult.Failure("No direct link returned")
    }

    override suspend fun deleteItem(apiKey: String, item: DebridItem): DebridResult<Unit> {
        return when (
            val result = http.get(
                "$base/magnet/delete",
                query = key(apiKey) + ("id" to item.id)
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
