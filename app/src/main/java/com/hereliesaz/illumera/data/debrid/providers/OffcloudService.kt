package com.hereliesaz.illumera.data.debrid.providers

import com.hereliesaz.illumera.data.debrid.DebridHttp
import com.hereliesaz.illumera.data.debrid.DebridService
import com.hereliesaz.illumera.data.debrid.asArrayOrNull
import com.hereliesaz.illumera.data.debrid.asObjectOrNull
import com.hereliesaz.illumera.data.model.debrid.DebridAccountInfo
import com.hereliesaz.illumera.data.model.debrid.DebridItem
import com.hereliesaz.illumera.data.model.debrid.DebridProvider
import com.hereliesaz.illumera.data.model.debrid.DebridResult
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/** https://offcloud.com/api — API docs at https://offcloud.com/#/api */
@Singleton
class OffcloudService @Inject constructor(private val http: DebridHttp) : DebridService {

    private val base = "https://offcloud.com/api"
    private fun key(apiKey: String) = mapOf("key" to apiKey)

    // Offcloud has no dedicated "who am I" endpoint; history doubling as a key check.
    override suspend fun validateApiKey(apiKey: String): DebridResult<DebridAccountInfo> {
        return when (http.get("$base/cloud/history", query = key(apiKey))) {
            is DebridResult.Success -> DebridResult.Success(DebridAccountInfo(username = "Offcloud"))
            is DebridResult.Failure -> DebridResult.Failure("Invalid Offcloud API key")
        }
    }

    override suspend fun listLibrary(apiKey: String): DebridResult<List<DebridItem>> {
        return when (val result = http.get("$base/cloud/history", query = key(apiKey))) {
            is DebridResult.Success -> {
                val list = result.value.asArrayOrNull() ?: return DebridResult.Success(emptyList())
                DebridResult.Success(
                    list.mapNotNull { el ->
                        val o = el.asObjectOrNull() ?: return@mapNotNull null
                        val requestId = o.get("requestId")?.asString ?: return@mapNotNull null
                        val fileName = o.get("fileName")?.asString ?: "Unknown"
                        val server = o.get("server")?.asString
                        val directLink = if (server != null) {
                            val encodedFileName = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
                            "https://$server.offcloud.com/cloud/download/$requestId/$encodedFileName"
                        } else null
                        DebridItem(
                            id = requestId,
                            name = fileName,
                            provider = DebridProvider.OFFCLOUD,
                            sizeBytes = o.get("fileSize")?.asLong,
                            status = o.get("status")?.asString,
                            directLinks = listOfNotNull(directLink)
                        )
                    }
                )
            }
            is DebridResult.Failure -> result
        }
    }

    override suspend fun getStreamUrl(apiKey: String, item: DebridItem): DebridResult<String> {
        val url = item.directLinks.firstOrNull()
        return if (url != null) DebridResult.Success(url) else DebridResult.Failure("No link available for this item")
    }

    override suspend fun deleteItem(apiKey: String, item: DebridItem): DebridResult<Unit> {
        return when (
            val result = http.get("$base/cloud/remove", query = key(apiKey) + ("requestId" to item.id))
        ) {
            is DebridResult.Success -> DebridResult.Success(Unit)
            is DebridResult.Failure -> result
        }
    }
}
