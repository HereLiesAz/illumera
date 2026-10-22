package com.hereliesaz.illumera.data.debrid

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hereliesaz.illumera.data.model.debrid.DebridResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException

/**
 * Thin shared HTTP helper for the debrid provider implementations. Each provider's REST API
 * is small and shaped differently enough (auth scheme, response envelope) that a single
 * Retrofit interface per provider would mostly be boilerplate — plain requests + Gson's
 * dynamic JsonElement parsing keeps each provider file self-contained and short.
 */
@Singleton
class DebridHttp @Inject constructor(private val client: OkHttpClient) {

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap()
    ): DebridResult<JsonElement> = execute {
        val httpUrl = url.toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val requestBuilder = Request.Builder().url(httpUrl).get()
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        requestBuilder.build()
    }

    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        form: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap()
    ): DebridResult<JsonElement> = execute {
        val httpUrl = url.toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val bodyBuilder = FormBody.Builder()
        form.forEach { (k, v) -> bodyBuilder.add(k, v) }
        val requestBuilder = Request.Builder().url(httpUrl).post(bodyBuilder.build())
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        requestBuilder.build()
    }

    suspend fun delete(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): DebridResult<JsonElement> = execute {
        val requestBuilder = Request.Builder().url(url).delete()
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        requestBuilder.build()
    }

    /**
     * Runs the request asynchronously so coroutine cancellation cancels the OkHttp call
     * (a blocking execute() would keep the socket busy until timeout). Cancellation is
     * rethrown, never reported as a network Failure.
     */
    private suspend fun execute(buildRequest: () -> Request): DebridResult<JsonElement> =
        try {
            val call = client.newCall(buildRequest())
            val response = suspendCancellableCoroutine<Response> { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        cont.resume(response) { _, _, _ -> response.close() }
                    }
                })
            }
            withContext<DebridResult<JsonElement>>(Dispatchers.IO) {
                response.use {
                    val bodyString = it.body.string()
                    if (!it.isSuccessful) {
                        DebridResult.Failure(
                            "HTTP ${it.code}${if (bodyString.isNotBlank()) ": ${bodyString.take(200)}" else ""}"
                        )
                    } else if (bodyString.isBlank()) {
                        DebridResult.Success(JsonObject())
                    } else {
                        DebridResult.Success(JsonParser.parseString(bodyString))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebridResult.Failure(e.message ?: "Network error")
        }
}

internal fun JsonElement.asObjectOrNull() = if (isJsonObject) asJsonObject else null
internal fun JsonElement.asArrayOrNull() = if (isJsonArray) asJsonArray else null

// Null-safe field readers: absent keys, JSON null and wrong-typed values all read as null
// instead of throwing (Gson's asString/asLong throw on JsonNull and on objects/arrays).
internal fun JsonElement?.primitiveOrNull() =
    if (this != null && isJsonPrimitive) asJsonPrimitive else null

internal fun JsonElement?.strOrNull(): String? = primitiveOrNull()?.asString

internal fun JsonObject.str(key: String): String? = get(key).strOrNull()

internal fun JsonObject.long(key: String): Long? =
    get(key).primitiveOrNull()?.let { p ->
        if (p.isNumber) p.asNumber.toLong() else p.asString.trim().toDoubleOrNull()?.toLong()
    }

internal fun JsonObject.int(key: String): Int? = long(key)?.toInt()

internal fun JsonObject.double(key: String): Double? =
    get(key).primitiveOrNull()?.let { p ->
        if (p.isNumber) p.asNumber.toDouble() else p.asString.trim().toDoubleOrNull()
    }

internal fun JsonObject.bool(key: String): Boolean? =
    get(key).primitiveOrNull()?.let { p ->
        if (p.isBoolean) p.asBoolean else p.asString.trim().lowercase().toBooleanStrictOrNull()
    }
