package com.hereliesaz.illumera.data.soundtrack

import com.google.gson.Gson
import com.hereliesaz.illumera.BuildConfig
import com.hereliesaz.illumera.data.cache.boundedCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/** Songs of a title as served by the Soundtrack addon (HereLiesAz/stremio-soundtrack). */
data class Soundtrack(val title: String? = null, val groups: List<SoundtrackGroup> = emptyList())

/** One episode's songs, or the whole movie's when [season] is null; in order of appearance. */
data class SoundtrackGroup(
    val season: Int? = null,
    val episode: Int? = null,
    val title: String? = null,
    val songs: List<SoundtrackSong> = emptyList(),
)

data class SoundtrackSong(val title: String = "", val artist: String? = null)

/**
 * Reads soundtracks from the Soundtrack addon. Only IMDb ids are supported; for an
 * episode pass the series id with season and episode.
 */
@Singleton
class SoundtrackRepository @Inject constructor(private val client: OkHttpClient) {
    private val gson = Gson()
    private val cache = boundedCache<String, Soundtrack>(100)

    /**
     * Null when the title has no IMDb id or the addon is unreachable. Groups without songs
     * are dropped; the title is kept even when none remain, for lookups in other sources.
     */
    suspend fun load(type: String, imdbId: String, season: Int? = null, episode: Int? = null): Soundtrack? {
        if (!imdbId.matches(Regex("tt\\d+"))) return null
        val kind = if (type == "movie") "movie" else "series"
        val id = if (season != null && episode != null) "$imdbId:$season:$episode" else imdbId
        val key = "$kind/$id"
        cache[key]?.let { return it }
        val url = "${BuildConfig.SOUNDTRACK_ADDON_URL.trimEnd('/')}/soundtrack/$kind/$id.json".toHttpUrlOrNull() ?: return null
        return withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val parsed = gson.fromJson(response.body.charStream(), Soundtrack::class.java)
                parsed?.copy(groups = parsed.groups.filter { it.songs.isNotEmpty() })
                    ?.takeIf { it.groups.isNotEmpty() || it.title != null }
                    ?.also { cache[key] = it }
            }
        }
    }
}
