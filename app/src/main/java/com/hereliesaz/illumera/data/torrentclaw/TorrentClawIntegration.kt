@file:Suppress("DEPRECATION")

package com.hereliesaz.illumera.data.torrentclaw

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hereliesaz.illumera.data.debrid.DebridHttp
import com.hereliesaz.illumera.data.debrid.asArrayOrNull
import com.hereliesaz.illumera.data.debrid.asObjectOrNull
import com.hereliesaz.illumera.data.debrid.int
import com.hereliesaz.illumera.data.debrid.str
import com.hereliesaz.illumera.data.model.debrid.DebridResult
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opt-in TorrentClaw title discovery. Uses GET /api/v1/search (not torrent download,
 * debrid, or the separate Stremio stream protocol). Results without canonical IMDb IDs
 * cannot be routed reliably into Illumera's Details screen and are skipped.
 *
 * Key is optional (anonymous search is documented). Keys stay in Android's encrypted
 * preference store and are sent only as Authorization headers, never query strings.
 * Settings are scoped to the active Illumera profile; disabling changes no stored key.
 * https://torrentclaw.com/api/docs#/Discovery/searchContent
 */
@Singleton
class TorrentClawIntegration @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profiles: ProfileConfigurationManager,
    private val http: DebridHttp
) {
    private val preferences by lazy {
        context.getSharedPreferences("illumera_torrentclaw", Context.MODE_PRIVATE)
    }
    private val secrets by lazy {
        val master = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "illumera_torrentclaw_keys", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun profileId() = profiles.activeProfileId.value
    private fun enabledKey(id: Int) = "enabled_$id"
    private fun apiKeyName(id: Int) = "key_$id"

    fun isEnabled(): Boolean = profileId()?.let { preferences.getBoolean(enabledKey(it), false) } ?: false
    fun hasKey(): Boolean = profileId()?.let { id ->
        runCatching { !secrets.getString(apiKeyName(id), null).isNullOrBlank() }.getOrDefault(false)
    } ?: false

    fun setEnabled(enabled: Boolean) {
        profileId()?.let { preferences.edit().putBoolean(enabledKey(it), enabled).apply() }
    }

    /** False means encryption is unavailable; the key must not be persisted unencrypted. */
    fun saveKey(value: String): Boolean {
        val id = profileId() ?: return false
        val key = value.trim()
        if (key.isBlank()) return false
        return runCatching { secrets.edit().putString(apiKeyName(id), key).commit() }.getOrDefault(false)
    }

    fun clearKey() {
        profileId()?.let { id -> runCatching { secrets.edit().remove(apiKeyName(id)).apply() } }
    }

    suspend fun search(query: String): DebridResult<List<MetaItem>> {
        val id = profileId() ?: return DebridResult.Success(emptyList())
        if (!preferences.getBoolean(enabledKey(id), false) || query.isBlank()) {
            return DebridResult.Success(emptyList())
        }
        val key = runCatching { secrets.getString(apiKeyName(id), null) }.getOrNull()
        val headers = if (key.isNullOrBlank()) emptyMap()
            else mapOf("Authorization" to "Bearer $key")
        return when (val result = http.get(
            "https://torrentclaw.com/api/v1/search",
            headers = headers,
            query = mapOf("q" to query, "limit" to "20")
        )) {
            is DebridResult.Failure -> result
            is DebridResult.Success -> {
                val entries = result.value.asObjectOrNull()?.get("results")?.asArrayOrNull()
                    ?: return DebridResult.Failure("Unexpected TorrentClaw search response")
                DebridResult.Success(entries.mapNotNull { element ->
                    val obj = element.asObjectOrNull() ?: return@mapNotNull null
                    val imdbId = obj.str("imdbId")?.takeIf { it.matches(Regex("tt[0-9]{7,10}")) }
                        ?: return@mapNotNull null
                    val type = when (obj.str("contentType")?.lowercase()) {
                        "movie" -> "movie"
                        "series", "show", "tv" -> "series"
                        else -> return@mapNotNull null
                    }
                    val title = obj.str("title")?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    MetaItem(
                        id = imdbId,
                        type = type,
                        name = title,
                        poster = obj.str("posterUrl")?.takeIf { it.startsWith("https://") },
                        background = obj.str("backdropUrl")?.takeIf { it.startsWith("https://") },
                        description = obj.str("overview"),
                        releaseInfo = obj.int("year")?.toString(),
                        imdbRating = obj.str("ratingImdb"),
                        genres = obj.get("genres")?.asArrayOrNull()
                            ?.mapNotNull { it.takeIf { v -> v.isJsonPrimitive }?.asString }
                    )
                }.distinctBy { it.type to it.id })
            }
        }
    }
}
