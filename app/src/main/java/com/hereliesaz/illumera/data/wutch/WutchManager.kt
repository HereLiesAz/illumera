package com.hereliesaz.illumera.data.wutch

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.SeriesNextUpEntity
import com.hereliesaz.illumera.data.model.WatchHistoryEntity
import com.hereliesaz.illumera.data.model.WatchlistEntity
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * wutch.tv account, per profile: a free Trakt alternative whose API needs no app key.
 *
 * Signing in with email and password creates a personal API key named "illumera"; only
 * that key is kept (encrypted), never the password. A key made on wutch.tv (Settings →
 * Integrations) can be pasted instead, for accounts that sign in with Google.
 *
 * Import (on sign-in, at launch and on "Sync library") merges the wutch watchlist into the
 * local one, adds titles part-way through to Continue Watching, and each show's next
 * episode to Next Up. Nothing local is deleted. Playback progress, finished movies and
 * episodes, and watchlist adds and removes are sent back as they happen.
 */
@Singleton
class WutchManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: WutchApi,
    private val dao: AddonDao,
    private val profileConfigurationManager: ProfileConfigurationManager,
) {
    private val secure by lazy {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "wutch_auth", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** IMDb id ↔ wutch title lookups; public data, so plain prefs. */
    private val ids by lazy { context.getSharedPreferences("wutch_ids", Context.MODE_PRIVATE) }

    private val _username = MutableStateFlow<String?>(null)
    /** The signed-in wutch username for the active profile, or null. */
    val username: StateFlow<String?> = _username

    private val importMutex = Mutex()

    private val profileId: Int get() = profileConfigurationManager.getLastActiveProfileId() ?: 1

    init {
        refreshConnectionState()
    }

    fun refreshConnectionState() {
        _username.value = runCatching { if (apiKey() != null) secure.getString(key(KEY_USER), null) else null }.getOrNull()
    }

    fun isConnected(): Boolean = apiKey() != null

    private fun key(name: String, profile: Int = profileId) = "${name}_$profile"

    private fun apiKey(): String? = runCatching { secure.getString(key(KEY_API), null) }.getOrNull()

    /** Signs in with email and password; returns the username. Throws with a readable message. */
    suspend fun signIn(email: String, password: String): String = withContext(Dispatchers.IO) {
        val profile = profileId
        val token = try {
            api.login(email.trim(), password)
        } catch (e: WutchApi.WutchException) {
            throw WutchApi.WutchException(e.code, if (e.code == 401) "Wrong email or password." else e.message ?: "Couldn't sign in.")
        }
        val user = api.username(bearer = token)
        val (keyId, apiKey) = api.createApiKey(token, "illumera")
        save(profile, apiKey, user, keyId)
        user
    }

    /** Signs in with an API key made on wutch.tv; returns the username. */
    suspend fun signInWithKey(apiKey: String): String = withContext(Dispatchers.IO) {
        val profile = profileId
        val key = apiKey.trim()
        val user = try {
            api.username(apiKey = key)
        } catch (e: WutchApi.WutchException) {
            throw WutchApi.WutchException(e.code, if (e.code == 401) "wutch.tv didn't accept that API key." else e.message ?: "Couldn't sign in.")
        }
        save(profile, key, user, keyId = null)
        user
    }

    private fun save(profile: Int, apiKey: String, user: String, keyId: Int?) {
        secure.edit {
            putString(key(KEY_API, profile), apiKey)
            putString(key(KEY_USER, profile), user)
            if (keyId != null) putInt(key(KEY_ID, profile), keyId) else remove(key(KEY_ID, profile))
        }
        if (profile == profileId) _username.value = user
    }

    /** Forgets the key; one this app created is also revoked on wutch.tv. */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        val profile = profileId
        val apiKey = apiKey()
        val keyId = runCatching { secure.getInt(key(KEY_ID, profile), -1) }.getOrDefault(-1)
        if (apiKey != null && keyId >= 0) quietly("revoke key") { api.revokeApiKey(apiKey, keyId) }
        secure.edit {
            remove(key(KEY_API, profile))
            remove(key(KEY_USER, profile))
            remove(key(KEY_ID, profile))
        }
        _username.value = null
    }

    // ── Import ──

    /** Brings the wutch watchlist, part-watched titles and next episodes in. Never deletes. */
    suspend fun importAll(): Result<Unit> = importMutex.withLock {
        withContext(Dispatchers.IO) {
            val apiKey = apiKey() ?: return@withContext Result.failure(IllegalStateException("Not signed in to wutch.tv"))
            val profile = profileId
            try {
                importWatchlist(apiKey, profile)
                importUpNext(apiKey, profile)
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                com.hereliesaz.illumera.crash.AppErrors.w(TAG, "wutch.tv import failed", e)
                Result.failure(e)
            }
        }
    }

    private suspend fun importWatchlist(apiKey: String, profile: Int) {
        val now = System.currentTimeMillis()
        var added = 0
        for (item in api.watchlist(apiKey)) {
            if (dao.getWatchlistItem(profile, item.imdbId) != null) continue
            dao.addToWatchlist(
                WatchlistEntity(
                    profileId = profile,
                    id = item.imdbId,
                    type = if (item.isMovie) "movie" else "series",
                    title = item.title,
                    poster = item.poster,
                    addedAt = now,
                )
            )
            added++
        }
        Log.i(TAG, "Watchlist import: added=$added")
    }

    private suspend fun importUpNext(apiKey: String, profile: Int) {
        for (item in api.upNext(apiKey)) {
            val imdb = imdbFor(if (item.isMovie) "movie" else "tv", item.slug, apiKey) ?: continue
            val watchedAt = parseTime(item.lastWatched) ?: System.currentTimeMillis()
            val resumeMs = item.resumeSeconds?.takeIf { it > 0 }?.times(1000)
            if (item.isMovie) {
                if (resumeMs != null) importProgress(imdb, imdb, null, "movie", item.name, item.poster, resumeMs, item.runtimeMinutes, watchedAt)
                continue
            }
            val season = item.season ?: continue
            val episode = item.episode ?: continue
            if (resumeMs != null) {
                importProgress("$imdb:$season:$episode", imdb, item.showName, "series", item.showName ?: item.name, item.poster, resumeMs, item.runtimeMinutes, watchedAt)
            } else if (dao.getSeriesNextUp(profile, imdb) == null) {
                dao.upsertSeriesNextUp(
                    SeriesNextUpEntity(
                        profileId = profile,
                        seriesId = imdb,
                        title = item.showName ?: item.name,
                        poster = item.poster,
                        nextSeason = season,
                        nextEpisode = episode,
                        nextEpisodeTitle = item.name,
                        nextReleased = item.releaseDate,
                        updatedAt = watchedAt,
                    )
                )
            }
        }
    }

    /** Adds a part-watched title unless this device has newer progress for it. */
    private suspend fun importProgress(
        id: String, seriesImdb: String, showTitle: String?, type: String, title: String, poster: String?,
        positionMs: Long, runtimeMinutes: Int?, watchedAt: Long,
    ) {
        val existing = dao.getHistoryItem(id)
        if (existing != null && existing.lastWatched >= watchedAt) return
        val duration = (runtimeMinutes ?: 0) * 60_000L
        dao.upsertHistory(
            WatchHistoryEntity(
                id = id,
                title = existing?.title ?: showTitle ?: title,
                poster = existing?.poster ?: poster,
                background = existing?.background,
                logo = existing?.logo,
                seriesId = if (type == "series") seriesImdb else existing?.seriesId,
                position = positionMs,
                duration = maxOf(duration, positionMs),
                lastWatched = watchedAt,
                type = type,
                watched = false,
                scrobbled = existing?.scrobbled ?: false,
            )
        )
    }

    // ── Write back ──

    /**
     * Playback stopped or paused at [positionMs]. [finished] marks it watched, which also
     * clears the resume position on wutch; otherwise the position is saved.
     */
    suspend fun onPlayback(playbackId: String, positionMs: Long, finished: Boolean) = send("playback") { apiKey ->
        val (imdb, season, episode) = parsePlaybackId(playbackId) ?: return@send
        val ref = resolve(imdb, apiKey) ?: return@send
        val seconds = positionMs / 1000
        when {
            ref.kind == "movie" && finished -> api.markMovieWatched(ref.id, apiKey)
            ref.kind == "movie" -> if (seconds > 0) api.setMovieProgress(ref.id, seconds, apiKey)
            season == null || episode == null -> Unit
            finished -> api.markEpisodeWatched(ref.slug, season, episode, apiKey)
            seconds > 0 -> api.setEpisodeProgress(ref.slug, season, episode, seconds, apiKey)
        }
    }

    /** Marked watched from a menu: a movie, or one episode of a series. */
    suspend fun onMarkedWatched(imdbId: String, season: Int? = null, episode: Int? = null) = send("mark watched") { apiKey ->
        val ref = resolve(imdbId.substringBefore(':'), apiKey) ?: return@send
        if (ref.kind == "movie") api.markMovieWatched(ref.id, apiKey)
        else if (season != null && episode != null) api.markEpisodeWatched(ref.slug, season, episode, apiKey)
    }

    suspend fun onWatchlistChanged(imdbId: String, added: Boolean) = send("watchlist") { apiKey ->
        val ref = resolve(imdbId, apiKey) ?: return@send
        if (added) api.addToWatchlist(ref, apiKey) else api.removeFromWatchlist(ref, apiKey)
    }

    private suspend fun send(what: String, block: suspend (String) -> Unit) {
        val apiKey = apiKey() ?: return
        withContext(Dispatchers.IO) { quietly(what) { block(apiKey) } }
    }

    private inline fun quietly(what: String, block: () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            com.hereliesaz.illumera.crash.AppErrors.w(TAG, "wutch.tv $what failed", e)
        }
    }

    // ── Id mapping ──

    private fun resolve(imdbId: String, apiKey: String): WutchApi.Ref? {
        if (!imdbId.startsWith("tt")) return null
        ids.getString("ref:$imdbId", null)?.split('|')?.takeIf { it.size == 3 }?.let { (kind, id, slug) ->
            id.toIntOrNull()?.let { return WutchApi.Ref(kind, it, slug) }
        }
        val ref = api.resolve(imdbId, apiKey) ?: return null
        ids.edit {
            putString("ref:$imdbId", "${ref.kind}|${ref.id}|${ref.slug}")
            putString("imdb:${ref.kind}:${ref.slug}", imdbId)
        }
        return ref
    }

    private fun imdbFor(kind: String, slug: String, apiKey: String): String? {
        ids.getString("imdb:$kind:$slug", null)?.let { return it }
        val imdb = runCatching { api.imdbId(kind, slug, apiKey) }.getOrNull() ?: return null
        ids.edit { putString("imdb:$kind:$slug", imdb) }
        return imdb
    }

    companion object {
        private const val TAG = "WutchManager"
        private const val KEY_API = "api_key"
        private const val KEY_USER = "username"
        private const val KEY_ID = "api_key_id"

        /** "tt1" → (tt1, null, null); "tt1:2:3" → (tt1, 2, 3); anything else → null. */
        internal fun parsePlaybackId(playbackId: String): Triple<String, Int?, Int?>? {
            val parts = playbackId.split(':')
            if (!parts[0].startsWith("tt")) return null
            return when (parts.size) {
                1 -> Triple(parts[0], null, null)
                3 -> {
                    val season = parts[1].toIntOrNull() ?: return null
                    val episode = parts[2].toIntOrNull() ?: return null
                    Triple(parts[0], season, episode)
                }
                else -> null
            }
        }

        /** wutch sends "2026-08-20 21:04:11" (UTC) or ISO 8601. */
        internal fun parseTime(text: String?): Long? {
            if (text.isNullOrBlank()) return null
            return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching { LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
        }
    }
}
