package com.hereliesaz.illumera.data.wutch

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * wutch.tv's API (https://docs.wutch.tv/api, spec at https://wutch.tv/be/api/doc.json).
 *
 * Unlike Trakt it needs no app key: a user signs in with their own email and password,
 * and that session creates a personal API key, which authenticates every later call as
 * `?api-key=`. Titles are wutch's own numeric ids and slugs; IMDb ids come from
 * `external_links` on the detail endpoints and from list exports.
 *
 * Every call blocks; run on an IO dispatcher.
 */
@Singleton
class WutchApi @Inject constructor(private val http: OkHttpClient) {

    class WutchException(val code: Int, message: String) : Exception(message)

    /** A title on wutch: [kind] is "movie" or "tv". */
    data class Ref(val kind: String, val id: Int, val slug: String)

    data class WatchlistItem(val isMovie: Boolean, val title: String, val poster: String?, val imdbId: String)

    /** One Up Next card: the next unwatched episode of a show, or something part-way through. */
    data class UpNextItem(
        val isMovie: Boolean,
        val slug: String,
        val name: String,
        val showName: String?,
        val poster: String?,
        val season: Int?,
        val episode: Int?,
        val releaseDate: String?,
        val lastWatched: String?,
        val resumeSeconds: Long?,
        val runtimeMinutes: Int?,
    )

    /** Email and password sign-in; returns a short-lived bearer token. */
    fun login(email: String, password: String): String {
        val body = JsonObject().apply { addProperty("email", email); addProperty("password", password) }
        val json = call("POST", "/api/login", body = body)
        return json.obj()?.str("token") ?: throw WutchException(0, "wutch.tv didn't return a session.")
    }

    fun username(bearer: String? = null, apiKey: String? = null): String =
        call("GET", "/api/v1/user/me", bearer = bearer, apiKey = apiKey).obj()?.str("username")
            ?: throw WutchException(0, "wutch.tv didn't return the account.")

    /** Creates a personal API key; returns (key id, key). The key is shown only this once. */
    fun createApiKey(bearer: String, name: String): Pair<Int?, String> {
        val body = JsonObject().apply { addProperty("name", name) }
        val json = call("POST", "/api/v1/user/me/api-keys", body = body, bearer = bearer).obj()
        val key = json?.str("key") ?: throw WutchException(0, "wutch.tv didn't return an API key.")
        return json.int("id") to key
    }

    fun revokeApiKey(apiKey: String, keyId: Int) {
        call("DELETE", "/api/v1/user/me/api-keys/$keyId", apiKey = apiKey)
    }

    /** The whole watchlist, with IMDb ids (the list export carries them). */
    fun watchlist(apiKey: String): List<WatchlistItem> =
        call("GET", "/api/v1/user/me/list/watchlist/export", query = mapOf("type" to "json"), apiKey = apiKey)
            .arr().mapNotNull { row ->
                val o = row.obj() ?: return@mapNotNull null
                val imdb = o.str("imdb_id")?.takeIf { it.startsWith("tt") } ?: return@mapNotNull null
                WatchlistItem(
                    isMovie = o.str("type") == "movie",
                    title = o.str("title").orEmpty(),
                    poster = o.str("poster_url"),
                    imdbId = imdb,
                )
            }

    fun upNext(apiKey: String): List<UpNextItem> =
        call("GET", "/api/v1/dashboard/up-next", apiKey = apiKey).obj()?.get("up_next").arr().mapNotNull { row ->
            val o = row.obj() ?: return@mapNotNull null
            val isMovie = o.str("type") == "movie"
            UpNextItem(
                isMovie = isMovie,
                slug = (if (isMovie) o.str("slug") else o.str("tv_show_slug") ?: o.str("slug")) ?: return@mapNotNull null,
                name = o.str("name").orEmpty(),
                showName = o.str("tv_show_name"),
                // On episodes "poster" is the season backdrop and "season_poster" the season's poster.
                poster = if (isMovie) o.str("poster") else o.str("season_poster") ?: o.str("poster"),
                season = o.int("season_number"),
                episode = o.int("episode_number"),
                releaseDate = o.str("release_date"),
                lastWatched = o.str("last_watched"),
                resumeSeconds = o.long("resume_position_seconds"),
                runtimeMinutes = o.int("resume_runtime_minutes") ?: o.int("runtime"),
            )
        }

    /** The IMDb id of a wutch title, from its detail page's external links. */
    fun imdbId(kind: String, slug: String, apiKey: String): String? {
        val path = if (kind == "movie") "/api/v1/movie/$slug" else "/api/v1/tv-show/$slug"
        return call("GET", path, apiKey = apiKey).obj()?.get("external_links").obj()?.str("imdb_id")
            ?.takeIf { it.startsWith("tt") }
    }

    /**
     * The wutch title for an IMDb id. wutch adds titles it doesn't have yet in the
     * background (202); those resolve on a later call, so this returns null meanwhile.
     */
    fun resolve(imdbId: String, apiKey: String): Ref? {
        val body = JsonObject().apply { addProperty("imdb_id", imdbId) }
        val o = call("POST", "/api/v1/import-content", body = body, apiKey = apiKey, accept202 = true).obj() ?: return null
        val id = o.int("id") ?: return null
        val slug = o.str("slug") ?: return null
        return Ref(if (o.str("type") == "movie") "movie" else "tv", id, slug)
    }

    fun setMovieProgress(movieId: Int, seconds: Long, apiKey: String) {
        call("PUT", "/api/v1/movie/$movieId/progress", body = JsonObject().apply { addProperty("position_seconds", seconds) }, apiKey = apiKey)
    }

    fun markMovieWatched(movieId: Int, apiKey: String) {
        call("POST", "/api/v1/movie/$movieId/watched", body = JsonObject(), apiKey = apiKey)
    }

    fun setEpisodeProgress(showSlug: String, season: Int, episode: Int, seconds: Long, apiKey: String) {
        call(
            "PUT", "/api/v1/tv-show/$showSlug/season/$season/episode/$episode/progress",
            body = JsonObject().apply { addProperty("position_seconds", seconds) }, apiKey = apiKey,
        )
    }

    fun markEpisodeWatched(showSlug: String, season: Int, episode: Int, apiKey: String) {
        call("POST", "/api/v1/tv-show/$showSlug/season/$season/episode/$episode/watched", body = JsonObject(), apiKey = apiKey)
    }

    fun addToWatchlist(ref: Ref, apiKey: String) {
        val body = JsonObject().apply {
            addProperty("type", if (ref.kind == "movie") "movie" else "tv_show")
            addProperty("id", ref.id)
        }
        call("POST", "/api/v1/user/me/list/watchlist/item", body = body, apiKey = apiKey)
    }

    fun removeFromWatchlist(ref: Ref, apiKey: String) {
        val type = if (ref.kind == "movie") "movie" else "tv_show"
        call("DELETE", "/api/v1/user/me/list/watchlist/item/$type/${ref.id}", apiKey = apiKey)
    }

    private fun call(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JsonObject? = null,
        bearer: String? = null,
        apiKey: String? = null,
        accept202: Boolean = false,
    ): JsonElement {
        val url = (BASE + path).toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
            if (apiKey != null) addQueryParameter("api-key", apiKey)
        }.build()
        val requestBody = body?.toString()?.toRequestBody(JSON)
        val request = Request.Builder()
            .url(url)
            .method(method, requestBody ?: if (method == "POST" || method == "PUT") "{}".toRequestBody(JSON) else null)
            .header("Accept", "application/json")
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 202 && !accept202) return JsonNull.INSTANCE
            if (!response.isSuccessful) {
                val message = runCatching { JsonParser.parseString(text).obj()?.let { it.str("message") ?: it.str("error") } }.getOrNull()
                throw WutchException(response.code, message ?: "wutch.tv returned ${response.code}.")
            }
            if (response.code == 202) return JsonNull.INSTANCE
            return if (text.isBlank()) JsonNull.INSTANCE else JsonParser.parseString(text)
        }
    }

    companion object {
        const val BASE = "https://wutch.tv/be"
        private val JSON = "application/json".toMediaType()

        private fun JsonElement?.obj(): JsonObject? = if (this != null && isJsonObject) asJsonObject else null
        private fun JsonElement?.arr(): JsonArray = if (this != null && isJsonArray) asJsonArray else JsonArray()
        private fun JsonObject.prim(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        private fun JsonObject.str(key: String): String? = prim(key)?.asString?.takeIf { it.isNotBlank() }
        private fun JsonObject.int(key: String): Int? = prim(key)?.takeIf { it.isNumber }?.asInt
        private fun JsonObject.long(key: String): Long? = prim(key)?.takeIf { it.isNumber }?.asLong
    }
}
