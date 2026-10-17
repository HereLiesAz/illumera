package com.hereliesaz.illumera.data.debrid.providers

import com.hereliesaz.illumera.data.debrid.DebridHttp
import com.hereliesaz.illumera.data.debrid.DebridService
import com.hereliesaz.illumera.data.debrid.asObjectOrNull
import com.hereliesaz.illumera.data.debrid.str
import com.hereliesaz.illumera.data.model.debrid.DebridAccountInfo
import com.hereliesaz.illumera.data.model.debrid.DebridItem
import com.hereliesaz.illumera.data.model.debrid.DebridResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * https://easydebrid.com/api/v1 — API docs at https://easydebrid.com/docs.
 *
 * EasyDebrid is a stateless link-unrestricter: it has no persistent cloud storage of its
 * own to browse, so [listLibrary] always returns an empty list rather than failing.
 */
@Singleton
class EasyDebridService @Inject constructor(private val http: DebridHttp) : DebridService {

    private val base = "https://easydebrid.com/api/v1"
    private fun auth(apiKey: String) = mapOf("Authorization" to "Bearer $apiKey")

    override suspend fun validateApiKey(apiKey: String): DebridResult<DebridAccountInfo> {
        return when (val result = http.get("$base/user", auth(apiKey))) {
            is DebridResult.Success -> {
                val obj = result.value.asObjectOrNull()
                val email = obj?.str("email")
                if (email == null) {
                    DebridResult.Failure("Invalid EasyDebrid API key")
                } else {
                    DebridResult.Success(DebridAccountInfo(username = email))
                }
            }
            is DebridResult.Failure -> result
        }
    }

    override suspend fun listLibrary(apiKey: String): DebridResult<List<DebridItem>> =
        DebridResult.Success(emptyList())

    override suspend fun getStreamUrl(apiKey: String, item: DebridItem): DebridResult<String> =
        DebridResult.Failure("EasyDebrid has no stored items to resolve")

    override suspend fun deleteItem(apiKey: String, item: DebridItem): DebridResult<Unit> =
        DebridResult.Failure("EasyDebrid has no stored items to delete")
}
