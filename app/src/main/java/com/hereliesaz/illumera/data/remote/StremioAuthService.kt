package com.hereliesaz.illumera.data.remote

import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class StremioLoginRequest(
    val type: String = "Login",
    val email: String,
    val password: String,
    val facebook: Boolean = false
)

/**
 * One entry in a Stremio "libraryItem" datastore collection — a Continue
 * Watching / library entry. For series, `id`/`name`/`poster` describe the
 * show and `state.videoId` names the current episode (matching this app's
 * own "seriesId:season:episode" scheme when the addon follows Cinemeta
 * conventions). Field names/casing match stremio-core's wire format exactly
 * — see StremioLibrarySyncManager for the mapping to/from local history.
 */
data class StremioLibraryItemState(
    val lastWatched: String? = null,
    val timeWatched: Long = 0,
    val timeOffset: Long = 0,
    val overallTimeWatched: Long = 0,
    val timesWatched: Int = 0,
    val flaggedWatched: Int = 0,
    val duration: Long = 0,
    @SerializedName("video_id") val videoId: String? = null,
    val watched: String? = null,
    val noNotif: Boolean = false
)

data class StremioLibraryItem(
    @SerializedName("_id") val id: String,
    val name: String,
    val type: String,
    val poster: String? = null,
    val posterShape: String? = null,
    val removed: Boolean = false,
    val temp: Boolean = false,
    @SerializedName("_ctime") val ctime: String? = null,
    @SerializedName("_mtime") val mtime: String,
    val state: StremioLibraryItemState = StremioLibraryItemState()
)

data class StremioAddonFlags(val official: Boolean = false, val protected: Boolean = false)

data class StremioAddonDescriptor(
    val manifest: JsonObject,
    val transportUrl: String,
    val flags: StremioAddonFlags = StremioAddonFlags()
)

data class StremioLoginResponse(
    val result: StremioAuthResult?
)

data class StremioAuthResult(
    @SerializedName("authKey") val authKey: String,
    val user: StremioUser? = null
)

/**
 * The `user` field on a login response — its `avatar` is the account's
 * profile picture (a Facebook photo URL, for accounts created via Facebook
 * login), mirroring what stremio-web reads as `profile.auth.user.avatar`.
 */
data class StremioUser(
    @SerializedName("_id") val id: String? = null,
    val avatar: String? = null
)

/** Result of a successful [StremioAuthService.login] call. */
data class StremioLoginResult(
    val authKey: String,
    val avatarUrl: String? = null
)

data class StremioAddonCollectionRequest(
    val type: String = "AddonCollectionGet",
    val authKey: String,
    val update: Boolean = true
)

data class StremioAddonCollectionResponse(
    val result: StremioAddonCollectionResult?
)

data class StremioAddonCollectionResult(
    val addons: List<StremioAddonEntry>?
)

data class StremioAddonEntry(
    val transportUrl: String,
    val manifest: StremioAddonManifest?
)

data class StremioAddonManifest(
    val id: String?,
    val name: String?,
    val version: String?,
    val description: String?,
    val logo: String?
)

sealed class StremioAuthError : Exception() {
    data class InvalidCredentials(override val message: String = "Invalid email or password") : StremioAuthError()
    data class NetworkError(override val message: String) : StremioAuthError()
    data class UnknownError(override val message: String) : StremioAuthError()
}

@Singleton
class StremioAuthService @Inject constructor() {

    private val client = OkHttpClient.Builder().build()
    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()
    
    companion object {
        private const val STREMIO_API_BASE = "https://api.strem.io/api"
        private const val LOGIN_ENDPOINT = "$STREMIO_API_BASE/login"
        private const val LOGOUT_ENDPOINT = "$STREMIO_API_BASE/logout"
        private const val ADDON_COLLECTION_ENDPOINT = "$STREMIO_API_BASE/addonCollectionGet"
        private const val ADDON_COLLECTION_SET_ENDPOINT = "$STREMIO_API_BASE/addonCollectionSet"
        private const val DATASTORE_META_ENDPOINT = "$STREMIO_API_BASE/datastoreMeta"
        private const val DATASTORE_GET_ENDPOINT = "$STREMIO_API_BASE/datastoreGet"
        private const val DATASTORE_PUT_ENDPOINT = "$STREMIO_API_BASE/datastorePut"
        private const val LIBRARY_COLLECTION = "libraryItem"

        // Stremio-hosted Facebook OAuth handoff (stremio-web's useFacebookLogin flow) —
        // no Facebook App ID of our own is needed since Stremio's own web property
        // does the OAuth dance and hands back an email + one-time login token.
        private const val FB_LOGIN_BASE = "https://www.strem.io/login-fb"
        private const val FB_LOGIN_POLL_BASE = "https://www.strem.io/login-fb-get-acc"
    }

    private fun postJson(url: String, bodyJson: String): JsonObject {
        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()
        if (!response.isSuccessful || responseBody == null) {
            throw StremioAuthError.NetworkError("Server returned ${response.code}")
        }
        return JsonParser.parseString(responseBody).asJsonObject
    }

    /**
     * Authenticates with Stremio and returns the authKey plus the account's avatar
     * URL, when it has one (Facebook-created accounts carry their FB photo here).
     * When [facebook] is true, [password] carries the one-time Facebook login token
     * from [pollFacebookLogin] rather than an actual password — this is how
     * Stremio's own web client does it.
     */
    suspend fun login(email: String, password: String, facebook: Boolean = false): StremioLoginResult = withContext(Dispatchers.IO) {
        val requestBody = gson.toJson(StremioLoginRequest(email = email, password = password, facebook = facebook))

        val request = Request.Builder()
            .url(LOGIN_ENDPOINT)
            .post(requestBody.toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .build()

        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful || responseBody == null) {
                throw StremioAuthError.NetworkError("Server returned ${response.code}")
            }

            val loginResponse = gson.fromJson(responseBody, StremioLoginResponse::class.java)
            val result = loginResponse.result ?: throw StremioAuthError.InvalidCredentials()

            StremioLoginResult(
                authKey = result.authKey,
                avatarUrl = result.user?.avatar?.takeIf { it.isNotBlank() }
            )
        } catch (e: StremioAuthError) {
            throw e
        } catch (e: Exception) {
            throw StremioAuthError.NetworkError(e.message ?: "Network error")
        }
    }
    
    /**
     * Fetches the user's addon collection using their authKey.
     */
    suspend fun getAddonCollection(authKey: String): List<StremioAddonEntry> = withContext(Dispatchers.IO) {
        val requestBody = gson.toJson(StremioAddonCollectionRequest(authKey = authKey))
        
        val request = Request.Builder()
            .url(ADDON_COLLECTION_ENDPOINT)
            .post(requestBody.toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .build()
        
        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (!response.isSuccessful || responseBody == null) {
                throw StremioAuthError.NetworkError("Failed to fetch addons: ${response.code}")
            }
            
            val collectionResponse = gson.fromJson(responseBody, StremioAddonCollectionResponse::class.java)
            
            collectionResponse.result?.addons ?: emptyList()
            
        } catch (e: StremioAuthError) {
            throw e
        } catch (e: Exception) {
            throw StremioAuthError.NetworkError(e.message ?: "Network error")
        }
    }

    /**
     * Starts a Stremio-hosted Facebook login: returns the URL to show as a QR
     * code / link for the user to open (on their phone, since a TV usually has
     * no browser). Call [pollFacebookLogin] with the returned state afterward.
     */
    fun startFacebookLogin(): Pair<String, String> {
        val state = java.util.UUID.randomUUID().toString().replace("-", "") +
            java.util.UUID.randomUUID().toString().replace("-", "")
        return state to "$FB_LOGIN_BASE/$state"
    }

    /**
     * Polls Stremio's Facebook-login handoff endpoint once per second until the
     * user finishes the Facebook OAuth flow in their browser, or [maxAttempts]
     * is reached. Returns the account email + one-time login token to pass to
     * [login] with `facebook = true`, or null if the user never completed it.
     */
    suspend fun pollFacebookLogin(state: String, maxAttempts: Int = 25): Pair<String, String>? = withContext(Dispatchers.IO) {
        repeat(maxAttempts) {
            delay(1000)
            try {
                val request = Request.Builder().url("$FB_LOGIN_POLL_BASE/$state").get().build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    val json = JsonParser.parseString(body).asJsonObject
                    val user = json.getAsJsonObject("user")
                    val email = user?.get("email")?.asString
                    val token = user?.get("fbLoginToken")?.asString
                    if (!email.isNullOrBlank() && !token.isNullOrBlank()) {
                        return@withContext email to token
                    }
                }
            } catch (_: Exception) {
                // Not ready yet / transient — keep polling until maxAttempts.
            }
        }
        null
    }

    /**
     * Invalidates the authKey server-side. Best-effort — local credentials
     * should be cleared regardless of whether this succeeds.
     */
    suspend fun logout(authKey: String) = withContext(Dispatchers.IO) {
        try {
            postJson(LOGOUT_ENDPOINT, gson.toJson(mapOf("type" to "Logout", "authKey" to authKey)))
        } catch (_: Exception) {
            // Best-effort: local state is cleared by the caller either way.
        }
        Unit
    }

    /**
     * Cheap manifest of the account's library — id → last-modified (epoch ms)
     * — used to decide what needs pulling vs. pushing before the heavier
     * [datastoreGet]/[datastorePut] calls.
     */
    suspend fun datastoreMeta(authKey: String): Map<String, Long> = withContext(Dispatchers.IO) {
        try {
            val json = postJson(
                DATASTORE_META_ENDPOINT,
                gson.toJson(mapOf("authKey" to authKey, "collection" to LIBRARY_COLLECTION))
            )
            val result = json.getAsJsonArray("result") ?: return@withContext emptyMap()
            buildMap {
                for (entry in result) {
                    val pair = entry.asJsonArray
                    if (pair.size() >= 2) put(pair[0].asString, pair[1].asLong)
                }
            }
        } catch (e: Exception) {
            throw StremioAuthError.NetworkError(e.message ?: "Network error")
        }
    }

    /** Fetches full library items by id (or all of them when [ids] is empty). */
    suspend fun datastoreGet(authKey: String, ids: List<String>): List<StremioLibraryItem> = withContext(Dispatchers.IO) {
        try {
            val json = postJson(
                DATASTORE_GET_ENDPOINT,
                gson.toJson(
                    mapOf(
                        "authKey" to authKey,
                        "collection" to LIBRARY_COLLECTION,
                        "ids" to ids,
                        "all" to ids.isEmpty()
                    )
                )
            )
            val result = json.getAsJsonArray("result") ?: return@withContext emptyList()
            result.mapNotNull { runCatching { gson.fromJson(it, StremioLibraryItem::class.java) }.getOrNull() }
        } catch (e: Exception) {
            throw StremioAuthError.NetworkError(e.message ?: "Network error")
        }
    }

    /** Pushes local library item changes up to the account. */
    suspend fun datastorePut(authKey: String, changes: List<StremioLibraryItem>) = withContext(Dispatchers.IO) {
        if (changes.isEmpty()) return@withContext
        try {
            postJson(
                DATASTORE_PUT_ENDPOINT,
                gson.toJson(mapOf("authKey" to authKey, "collection" to LIBRARY_COLLECTION, "changes" to changes))
            )
        } catch (e: Exception) {
            throw StremioAuthError.NetworkError(e.message ?: "Network error")
        }
        Unit
    }

    /** Pushes the local addon collection up to the account, replacing it there. */
    suspend fun setAddonCollection(authKey: String, addons: List<StremioAddonDescriptor>) = withContext(Dispatchers.IO) {
        try {
            postJson(
                ADDON_COLLECTION_SET_ENDPOINT,
                gson.toJson(mapOf("type" to "AddonCollectionSet", "authKey" to authKey, "addons" to addons))
            )
        } catch (e: Exception) {
            throw StremioAuthError.NetworkError(e.message ?: "Network error")
        }
        Unit
    }

    /**
     * Fetches an addon's manifest.json as raw JSON (not the app's trimmed-down
     * [StremioAddonManifest]/`Manifest` models) — [setAddonCollection] needs the
     * full, unmodified manifest so pushing doesn't silently drop fields the
     * account (or other Stremio clients) rely on.
     */
    suspend fun fetchRawManifest(transportUrl: String): JsonObject = withContext(Dispatchers.IO) {
        val url = if (transportUrl.endsWith("manifest.json")) transportUrl else "${transportUrl.trimEnd('/')}/manifest.json"
        val request = Request.Builder().url(url).get().build()
        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) {
                throw StremioAuthError.NetworkError("Failed to fetch manifest: ${response.code}")
            }
            JsonParser.parseString(body).asJsonObject
        } catch (e: StremioAuthError) {
            throw e
        } catch (e: Exception) {
            throw StremioAuthError.NetworkError(e.message ?: "Network error")
        }
    }
}
