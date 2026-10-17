package com.hereliesaz.illumera.data.debrid.providers

import com.hereliesaz.illumera.data.debrid.DebridHttp
import com.hereliesaz.illumera.data.debrid.DebridService
import com.hereliesaz.illumera.data.debrid.asArrayOrNull
import com.hereliesaz.illumera.data.debrid.asObjectOrNull
import com.hereliesaz.illumera.data.debrid.double
import com.hereliesaz.illumera.data.debrid.isVideoFilename
import com.hereliesaz.illumera.data.debrid.long
import com.hereliesaz.illumera.data.debrid.str
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
                val status = obj?.str("status")
                if (status != "success") {
                    DebridResult.Failure(obj?.str("message") ?: "Invalid Premiumize API key")
                } else {
                    DebridResult.Success(
                        DebridAccountInfo(
                            username = obj.str("customer_id") ?: "Premiumize user",
                            premiumUntil = obj.str("premium_until")
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
                if (obj?.str("status") != "success") {
                    return DebridResult.Failure(obj?.str("message") ?: "Failed to list Premiumize transfers")
                }
                val transfers = obj.get("transfers")?.asArrayOrNull() ?: return DebridResult.Success(emptyList())
                DebridResult.Success(
                    transfers.mapNotNull { el ->
                        val o = el.asObjectOrNull() ?: return@mapNotNull null
                        DebridItem(
                            id = o.str("id") ?: return@mapNotNull null,
                            name = o.str("name") ?: "Unknown",
                            provider = DebridProvider.PREMIUMIZE,
                            // Transfers expose the source magnet, not a bare hash.
                            infoHash = o.str("src")
                                ?.let { Regex("btih:([a-fA-F0-9]{40})").find(it)?.groupValues?.get(1) },
                            status = o.str("status"),
                            progress = o.double("progress")?.let { (it * 100).toInt() },
                            directLinks = listOfNotNull(o.str("src"))
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
                if (obj?.str("status") != "success") {
                    return DebridResult.Failure(obj?.str("message") ?: "Failed to resolve link")
                }
                val content = obj.get("content")?.asArrayOrNull()
                    ?.mapNotNull { it.asObjectOrNull() }
                // Prefer the largest video file; fall back to first content item, then location.
                val videoFile = content
                    ?.filter { isVideoFilename(it.str("path") ?: "") }
                    ?.maxByOrNull { it.long("size") ?: 0L }
                val url = videoFile?.str("link")
                    ?: content?.firstOrNull()?.str("link")
                    ?: obj.str("location")
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
                if (obj?.str("status") == "success") {
                    DebridResult.Success(Unit)
                } else {
                    DebridResult.Failure(obj?.str("message") ?: "Failed to delete transfer")
                }
            }
            is DebridResult.Failure -> result
        }
    }
}
