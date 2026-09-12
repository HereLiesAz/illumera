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

/** https://www.premiumize.me/api — API docs at https://app.swaggerhub.com/apis/premiumize.me/api */
@Singleton
class PremiumizeService @Inject constructor(private val http: DebridHttp) : DebridService {

    private val base = "https://www.premiumize.me/api"
    private fun key(apiKey: String) = mapOf("apikey" to apiKey)

    override suspend fun validateApiKey(apiKey: String): DebridResult<DebridAccountInfo> {
        return when (val result = http.get("$base/account/info", query = key(apiKey))) {
            is DebridResult.Success -> {
                val obj = result.value.asObjectOrNull()
                val status = obj?.get("status")?.asString
                if (status != "success") {
                    DebridResult.Failure(obj?.get("message")?.asString ?: "Invalid Premiumize API key")
                } else {
                    DebridResult.Success(
                        DebridAccountInfo(
                            username = obj.get("customer_id")?.asString ?: "Premiumize user",
                            premiumUntil = obj.get("premium_until")?.asString
                        )
                    )
                }
            }
            is DebridResult.Failure -> result
        }
    }

    override suspend fun listLibrary(apiKey: String): DebridResult<List<DebridItem>> {
        return when (val result = http.get("$base/transfer/list", query = key(apiKey))) {
            is DebridResult.Success -> {
                val obj = result.value.asObjectOrNull()
                if (obj?.get("status")?.asString != "success") {
                    return DebridResult.Failure(obj?.get("message")?.asString ?: "Failed to list Premiumize transfers")
                }
                val transfers = obj.get("transfers")?.asArrayOrNull() ?: return DebridResult.Success(emptyList())
                DebridResult.Success(
                    transfers.mapNotNull { el ->
                        val o = el.asObjectOrNull() ?: return@mapNotNull null
                        DebridItem(
                            id = o.get("id")?.asString ?: return@mapNotNull null,
                            name = o.get("name")?.asString ?: "Unknown",
                            provider = DebridProvider.PREMIUMIZE,
                            status = o.get("status")?.asString,
                            progress = o.get("progress")?.asDouble?.let { (it * 100).toInt() },
                            directLinks = listOfNotNull(o.get("src")?.asString)
                        )
                    }
                )
            }
            is DebridResult.Failure -> result
        }
    }

    override suspend fun getStreamUrl(apiKey: String, item: DebridItem): DebridResult<String> {
        val src = item.directLinks.firstOrNull() ?: return DebridResult.Failure("No link available for this item")
        return when (
            val result = http.get(
                "$base/transfer/directdl",
                query = key(apiKey) + ("src" to src)
            )
        ) {
            is DebridResult.Success -> {
                val obj = result.value.asObjectOrNull()
                if (obj?.get("status")?.asString != "success") {
                    return DebridResult.Failure(obj?.get("message")?.asString ?: "Failed to resolve link")
                }
                val content = obj.get("content")?.asArrayOrNull()
                    ?.mapNotNull { it.asObjectOrNull() }
                // Prefer the largest video file; fall back to first content item, then location.
                val videoFile = content
                    ?.filter { isVideoFilename(it.get("path")?.asString ?: "") }
                    ?.maxByOrNull { it.get("size")?.asLong ?: 0L }
                val url = videoFile?.get("link")?.asString
                    ?: content?.firstOrNull()?.get("link")?.asString
                    ?: obj.get("location")?.asString
                if (url != null) DebridResult.Success(url) else DebridResult.Failure("No direct link returned")
            }
            is DebridResult.Failure -> result
        }
    }

    override suspend fun deleteItem(apiKey: String, item: DebridItem): DebridResult<Unit> {
        return when (
            val result = http.post(
                "$base/transfer/delete",
                form = key(apiKey) + ("id" to item.id)
            )
        ) {
            is DebridResult.Success -> {
                val obj = result.value.asObjectOrNull()
                if (obj?.get("status")?.asString == "success") {
                    DebridResult.Success(Unit)
                } else {
                    DebridResult.Failure(obj?.get("message")?.asString ?: "Failed to delete transfer")
                }
            }
            is DebridResult.Failure -> result
        }
    }
}
