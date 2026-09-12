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

/** https://debrid-link.com/api/v2 — API docs at https://debrid-link.com/api_doc */
@Singleton
class DebridLinkService @Inject constructor(private val http: DebridHttp) : DebridService {

    private val base = "https://debrid-link.com/api/v2"
    private fun auth(apiKey: String) = mapOf("Authorization" to "Bearer $apiKey")

    private fun com.google.gson.JsonElement.unwrap(): DebridResult<com.google.gson.JsonElement> {
        val obj = asObjectOrNull() ?: return DebridResult.Failure("Unexpected Debrid-Link response")
        return if (obj.get("success")?.asBoolean == true) {
            DebridResult.Success(obj.get("value") ?: com.google.gson.JsonObject())
        } else {
            DebridResult.Failure(obj.get("error")?.asString ?: "Debrid-Link error")
        }
    }

    override suspend fun validateApiKey(apiKey: String): DebridResult<DebridAccountInfo> {
        return when (val result = http.get("$base/account/infos", auth(apiKey))) {
            is DebridResult.Success -> when (val data = result.value.unwrap()) {
                is DebridResult.Success -> {
                    val obj = data.value.asObjectOrNull() ?: return DebridResult.Failure("Unexpected Debrid-Link response")
                    DebridResult.Success(
                        DebridAccountInfo(
                            username = obj.get("pseudo")?.asString
                                ?: obj.get("email")?.asString
                                ?: "Debrid-Link user",
                            premiumUntil = obj.get("accountType")?.asString
                        )
                    )
                }
                is DebridResult.Failure -> data
            }
            is DebridResult.Failure -> result
        }
    }

    override suspend fun listLibrary(apiKey: String): DebridResult<List<DebridItem>> {
        return when (val result = http.get("$base/seedbox/list", auth(apiKey))) {
            is DebridResult.Success -> when (val data = result.value.unwrap()) {
                is DebridResult.Success -> {
                    val list = data.value.asArrayOrNull() ?: return DebridResult.Success(emptyList())
                    DebridResult.Success(
                        list.mapNotNull { el ->
                            val o = el.asObjectOrNull() ?: return@mapNotNull null
                            val files = o.get("files")?.asArrayOrNull()
                                ?.mapNotNull { it.asObjectOrNull()?.get("downloadUrl")?.asString }
                                ?: emptyList()
                            DebridItem(
                                id = o.get("id")?.asString ?: return@mapNotNull null,
                                name = o.get("name")?.asString ?: "Unknown",
                                provider = DebridProvider.DEBRID_LINK,
                                sizeBytes = o.get("size")?.asLong,
                                status = if (o.get("downloadPercent")?.asInt == 100) "downloaded" else "downloading",
                                progress = o.get("downloadPercent")?.asInt,
                                directLinks = files
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
        // URLs include the filename; prefer the first video file by extension.
        val url = item.directLinks.firstOrNull { link ->
            val filename = link.substringAfterLast('/').substringBefore('?')
            isVideoFilename(filename)
        } ?: item.directLinks.firstOrNull()
        return if (url != null) DebridResult.Success(url) else DebridResult.Failure("No link available for this item")
    }

    override suspend fun deleteItem(apiKey: String, item: DebridItem): DebridResult<Unit> {
        return when (
            val result = http.delete("$base/seedbox/${item.id}/remove", auth(apiKey))
        ) {
            is DebridResult.Success -> when (val data = result.value.unwrap()) {
                is DebridResult.Success -> DebridResult.Success(Unit)
                is DebridResult.Failure -> data
            }
            is DebridResult.Failure -> result
        }
    }
}
