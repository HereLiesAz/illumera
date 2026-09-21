package com.hereliesaz.illumera.data.queue

import android.content.Context
import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.remote.TraktSyncApiService
import com.hereliesaz.illumera.data.repository.AddonRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Keep
data class QueueItem(
    val id: String,
    val type: String,
    val title: String,
    val poster: String? = null,
    val seriesId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val wholeShow: Boolean = false,
    val origin: QueueOrigin = QueueOrigin.MANUAL,
    val rating: Int = 0
) {
    val stableKey: String
        get() = listOf(type, id, season ?: "", episode ?: "", wholeShow).joinToString(":")

    fun toMetaItem(): MetaItem {
        val canonicalId = seriesId ?: if (type == "episode" || type == "series") {
            val parts = id.split(':')
            if (parts.size >= 3 && parts.takeLast(2).all { it.toIntOrNull() != null }) {
                parts.dropLast(2).joinToString(":")
            } else {
                id
            }
        } else {
            id
        }
        return MetaItem(
            id = canonicalId,
            type = if (type == "episode" || type == "series") "series" else type,
            name = title,
            poster = poster
        )
    }
}

enum class QueueOrigin { MANUAL, SUGGESTED }

enum class QueueSuggestionSource { PLAY_HISTORY, TRAKT }

@Keep
data class QueuePreferences(
    val enabled: Boolean = false,
    val includeMovies: Boolean = true,
    val includeEpisodes: Boolean = true,
    val includeWholeShows: Boolean = false,
    val onlyUnseenSuggestions: Boolean = false,
    val suggestionSources: Set<QueueSuggestionSource> = setOf(
        QueueSuggestionSource.PLAY_HISTORY,
        QueueSuggestionSource.TRAKT
    )
)

@Keep
data class QueueState(
    val preferences: QueuePreferences = QueuePreferences(),
    val manualItems: List<QueueItem> = emptyList(),
    val suggestions: List<QueueItem> = emptyList(),
    val isRefreshingSuggestions: Boolean = false
) {
    val playbackLineup: List<QueueItem> get() = manualItems + suggestions
}

@Singleton
class QueueManager @Inject constructor(
    @ApplicationContext context: Context,
    private val addonDao: AddonDao,
    private val traktApi: TraktSyncApiService,
    private val repository: AddonRepository,
    private val profileConfigurationManager: ProfileConfigurationManager
) {
    private data class QueueScope(
        val profileId: Int,
        val generation: Long
    )

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val gson = Gson()

    private suspend fun <T> requestOrNull(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    @Volatile
    private var loadedProfileId: Int? = activeProfileId()

    @Volatile
    private var scopeGeneration: Long = 0L

    private val _state = MutableStateFlow(
        loadedProfileId?.let(::load) ?: QueueState()
    )
    val state: StateFlow<QueueState> = _state.asStateFlow()

    private fun activeProfileId(): Int? = profileConfigurationManager.getLastActiveProfileId()

    private fun profilePrefix(profileId: Int): String = "profile_${profileId}_"

    private fun profileKey(profileId: Int, key: String): String =
        "${profilePrefix(profileId)}$key"

    private fun ratingKey(profileId: Int, itemKey: String): String =
        profileKey(profileId, "rating_$itemKey")

    private fun ensureScopeLocked(): QueueScope? {
        val active = activeProfileId()
        if (active != loadedProfileId) {
            loadedProfileId = active
            scopeGeneration += 1L
            _state.value = active?.let(::load) ?: QueueState()
        }
        return active?.let { QueueScope(it, scopeGeneration) }
    }

    private fun captureScope(): QueueScope? = synchronized(this) {
        ensureScopeLocked()
    }

    private fun isCurrentScopeLocked(scope: QueueScope): Boolean =
        loadedProfileId == scope.profileId &&
            scopeGeneration == scope.generation &&
            activeProfileId() == scope.profileId

    private fun isCurrentScope(scope: QueueScope): Boolean = synchronized(this) {
        isCurrentScopeLocked(scope)
    }

    private fun stateForScope(scope: QueueScope): QueueState? = synchronized(this) {
        if (isCurrentScopeLocked(scope)) _state.value else null
    }

    private fun setRefreshing(scope: QueueScope, refreshing: Boolean): Boolean = synchronized(this) {
        if (!isCurrentScopeLocked(scope)) return@synchronized false
        _state.value = _state.value.copy(isRefreshingSuggestions = refreshing)
        true
    }

    /**
     * Reload queue state after login, logout, or an explicit profile switch.
     * Incrementing the generation invalidates any async refresh started by the
     * previously active profile, even if the user later switches back to it.
     */
    @Synchronized
    fun reloadForActiveProfile() {
        loadedProfileId = activeProfileId()
        scopeGeneration += 1L
        _state.value = loadedProfileId?.let(::load) ?: QueueState()
    }

    /** Remove every persisted queue key owned by a deleted profile. */
    @Synchronized
    fun clearForProfile(profileId: Int) {
        val prefix = profilePrefix(profileId)
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(prefix) }
            .forEach(editor::remove)
        editor.apply()

        if (loadedProfileId == profileId) {
            loadedProfileId = null
            scopeGeneration += 1L
            _state.value = QueueState()
        }
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        val scope = ensureScopeLocked() ?: return
        updatePreferences(scope) { copy(enabled = enabled) }
    }

    @Synchronized
    fun setIncludeMovies(enabled: Boolean) {
        val scope = ensureScopeLocked() ?: return
        updatePreferences(scope) { copy(includeMovies = enabled) }
    }

    @Synchronized
    fun setIncludeEpisodes(enabled: Boolean) {
        val scope = ensureScopeLocked() ?: return
        updatePreferences(scope) { copy(includeEpisodes = enabled) }
    }

    @Synchronized
    fun setIncludeWholeShows(enabled: Boolean) {
        val scope = ensureScopeLocked() ?: return
        updatePreferences(scope) { copy(includeWholeShows = enabled) }
    }

    @Synchronized
    fun setOnlyUnseenSuggestions(enabled: Boolean) {
        val scope = ensureScopeLocked() ?: return
        updatePreferences(scope) { copy(onlyUnseenSuggestions = enabled) }
    }

    @Synchronized
    fun setSuggestionSource(source: QueueSuggestionSource, enabled: Boolean) {
        val scope = ensureScopeLocked() ?: return
        updatePreferences(scope) {
            val next = suggestionSources.toMutableSet().apply {
                if (enabled) add(source) else remove(source)
            }
            copy(suggestionSources = next)
        }
    }

    @Synchronized
    fun add(item: QueueItem) {
        val scope = ensureScopeLocked() ?: return
        val current = _state.value
        if (current.manualItems.any { it.stableKey == item.stableKey }) return
        commit(scope, current.copy(manualItems = current.manualItems + item.copy(origin = QueueOrigin.MANUAL)))
    }

    @Synchronized
    fun remove(key: String) {
        val scope = ensureScopeLocked() ?: return
        commit(scope, _state.value.copy(manualItems = _state.value.manualItems.filterNot { it.stableKey == key }))
    }

    @Synchronized
    fun move(key: String, delta: Int) {
        val scope = ensureScopeLocked() ?: return
        val items = _state.value.manualItems.toMutableList()
        val from = items.indexOfFirst { it.stableKey == key }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, items.lastIndex)
        if (from == to) return
        val item = items.removeAt(from)
        items.add(to, item)
        commit(scope, _state.value.copy(manualItems = items))
    }

    @Synchronized
    fun moveSuggestion(key: String, targetIndex: Int) {
        val scope = ensureScopeLocked() ?: return
        val suggestions = _state.value.suggestions.toMutableList()
        val from = suggestions.indexOfFirst { it.stableKey == key }
        if (from < 0 || suggestions.isEmpty()) return
        val to = targetIndex.coerceIn(0, suggestions.lastIndex)
        if (from == to) return
        val item = suggestions.removeAt(from)
        suggestions.add(to, item)
        commit(scope, _state.value.copy(suggestions = suggestions))
    }

    @Synchronized
    fun removeSuggestion(key: String) {
        val scope = ensureScopeLocked() ?: return
        val current = _state.value
        if (current.suggestions.none { it.stableKey == key }) return
        val dismissed = dismissedSuggestionKeys(scope.profileId).toMutableSet().apply { add(key) }
        prefs.edit()
            .putStringSet(profileKey(scope.profileId, KEY_DISMISSED_SUGGESTIONS), dismissed)
            .apply()
        commit(scope, current.copy(suggestions = current.suggestions.filterNot { it.stableKey == key }))
    }

    @Synchronized
    fun rateSuggestion(key: String, rating: Int) {
        val scope = ensureScopeLocked() ?: return
        val normalized = rating.coerceIn(-1, 1)
        val suggestions = _state.value.suggestions.map {
            if (it.stableKey == key) it.copy(rating = normalized) else it
        }
        prefs.edit().putInt(ratingKey(scope.profileId, key), normalized).apply()
        commit(scope, _state.value.copy(suggestions = suggestions))
    }

    @Synchronized
    fun advanceAfterPlayback(playbackId: String): QueueItem? {
        val scope = ensureScopeLocked() ?: return null
        val current = _state.value
        if (!current.preferences.enabled) return null

        fun matches(item: QueueItem): Boolean =
            item.id == playbackId ||
                (item.type == "episode" && item.season != null && item.episode != null &&
                    playbackId.endsWith(":${item.season}:${item.episode}"))

        val manual = current.manualItems.toMutableList()
        val manualIndex = manual.indexOfFirst(::matches)
        if (manualIndex >= 0) manual.removeAt(manualIndex)

        val suggestions = current.suggestions.toMutableList()
        val suggestionIndex = suggestions.indexOfFirst(::matches)
        if (suggestionIndex >= 0) suggestions.removeAt(suggestionIndex)

        if (manualIndex >= 0 || suggestionIndex >= 0) {
            commit(scope, current.copy(manualItems = manual, suggestions = suggestions))
        }
        return manual.firstOrNull() ?: suggestions.firstOrNull()
    }

    suspend fun ensureSuggestions() {
        val scope = captureScope() ?: return
        val current = stateForScope(scope) ?: return
        if (current.suggestions.size < SUGGESTION_COUNT) {
            refreshSuggestions(preserveExisting = true)
        }
    }

    /**
     * Queue items can originate from Trakt recommendations, which only provide IDs
     * and titles here. Resolve missing posters through the same addon metadata path
     * used by Watchlist/Home so queue cards visually match the rest of the app.
     */
    suspend fun resolveMissingArtwork() {
        val scope = captureScope() ?: return
        val snapshot = stateForScope(scope) ?: return
        val missing = (snapshot.manualItems + snapshot.suggestions)
            .filter { it.poster.isNullOrBlank() }
            .distinctBy { it.stableKey }
        if (missing.isEmpty()) return

        val resolved = mutableMapOf<String, String>()
        for (item in missing) {
            if (!isCurrentScope(scope)) return
            val meta = requestOrNull {
                val display = item.toMetaItem()
                repository.resolveMetaDetails(display.type, display.id)
            }
            if (!isCurrentScope(scope)) return
            val poster = meta?.poster
            if (!poster.isNullOrBlank()) resolved[item.stableKey] = poster
        }
        if (resolved.isEmpty()) return

        val current = stateForScope(scope) ?: return
        val manual = current.manualItems.map { item ->
            resolved[item.stableKey]?.let { item.copy(poster = it) } ?: item
        }
        val suggestions = current.suggestions.map { item ->
            resolved[item.stableKey]?.let { item.copy(poster = it) } ?: item
        }
        if (manual != current.manualItems || suggestions != current.suggestions) {
            commit(scope, current.copy(manualItems = manual, suggestions = suggestions))
        }
    }

    suspend fun refreshSuggestions(
        resetDismissed: Boolean = false,
        preserveExisting: Boolean = false
    ) {
        val scope = captureScope() ?: return
        val current = stateForScope(scope) ?: return
        if (!current.preferences.enabled) return

        if (resetDismissed) {
            synchronized(this) {
                if (!isCurrentScopeLocked(scope)) return
                prefs.edit()
                    .remove(profileKey(scope.profileId, KEY_DISMISSED_SUGGESTIONS))
                    .apply()
            }
        }
        val dismissedKeys = if (resetDismissed) {
            emptySet()
        } else {
            dismissedSuggestionKeys(scope.profileId)
        }
        if (!setRefreshing(scope, true)) return

        try {
            val history = addonDao.getAllWatchHistoryOnce()
            if (!isCurrentScope(scope)) return

            val excludedIds = mutableSetOf<String>()

            if (current.preferences.onlyUnseenSuggestions) {
                excludedIds += history.map { normalizeId(it.id) }
            } else {
                excludedIds += history.filter { it.watched }.map { normalizeId(it.id) }
            }
            excludedIds += current.manualItems.map { normalizeId(it.seriesId ?: it.id) }

            val useTrakt = QueueSuggestionSource.TRAKT in current.preferences.suggestionSources

            // Keep the unseen filter honest even when the local Trakt sync has not run yet.
            // The authenticated Trakt history is authoritative for items watched elsewhere.
            if (useTrakt && current.preferences.onlyUnseenSuggestions) {
                val watchedMovies = requestOrNull { traktApi.getWatchedMovies() }
                if (!isCurrentScope(scope)) return
                watchedMovies
                    ?.takeIf { it.isSuccessful }
                    ?.body().orEmpty()
                    .forEach { watched ->
                        watched.movie.ids.imdb?.let { excludedIds += normalizeId(it) }
                        watched.movie.ids.tmdb?.let { excludedIds += normalizeId("tmdb:$it") }
                    }

                val watchedShows = requestOrNull { traktApi.getWatchedShows() }
                if (!isCurrentScope(scope)) return
                watchedShows
                    ?.takeIf { it.isSuccessful }
                    ?.body().orEmpty()
                    .forEach { watched ->
                        watched.show.ids.imdb?.let { excludedIds += normalizeId(it) }
                        watched.show.ids.tmdb?.let { excludedIds += normalizeId("tmdb:$it") }
                    }
            }

            fun isEligible(item: QueueItem): Boolean {
                val allowedType = when (item.type) {
                    "movie" -> current.preferences.includeMovies
                    "episode", "series" -> current.preferences.includeEpisodes || current.preferences.includeWholeShows
                    else -> false
                }
                if (!allowedType) return false
                return normalizeId(item.seriesId ?: item.id) !in excludedIds
            }

            val candidates = mutableListOf<QueueItem>()

            // Enabling "only unseen" used to throw away the suggestions already on screen.
            // Re-seed from any still-valid existing suggestions so a refresh cannot collapse
            // to an empty row just because a remote source is temporarily unavailable.
            if (current.preferences.onlyUnseenSuggestions && current.preferences.suggestionSources.isNotEmpty()) {
                current.suggestions.asSequence()
                    .filter { it.stableKey !in dismissedKeys }
                    .filter(::isEligible)
                    .forEach(candidates::add)
            }

            if (useTrakt) {
                val countBeforeRecommendations = candidates.size

                if (current.preferences.includeMovies) {
                    val movieRecommendations = requestOrNull {
                        traktApi.getMovieRecommendations(50)
                    }
                    if (!isCurrentScope(scope)) return
                    movieRecommendations
                        ?.takeIf { it.isSuccessful }
                        ?.body().orEmpty()
                        .forEach { movie ->
                            val id = movie.ids.imdb ?: movie.ids.tmdb?.let { "tmdb:$it" } ?: return@forEach
                            val item = QueueItem(
                                id = id,
                                type = "movie",
                                title = movie.title ?: "Movie",
                                origin = QueueOrigin.SUGGESTED
                            )
                            if (isEligible(item)) candidates += item
                        }
                }

                if (current.preferences.includeEpisodes || current.preferences.includeWholeShows) {
                    val showRecommendations = requestOrNull {
                        traktApi.getShowRecommendations(50)
                    }
                    if (!isCurrentScope(scope)) return
                    showRecommendations
                        ?.takeIf { it.isSuccessful }
                        ?.body().orEmpty()
                        .forEach { show ->
                            val id = show.ids.imdb ?: show.ids.tmdb?.let { "tmdb:$it" } ?: return@forEach
                            val item = QueueItem(
                                id = id,
                                type = "series",
                                title = show.title ?: "Series",
                                wholeShow = current.preferences.includeWholeShows,
                                origin = QueueOrigin.SUGGESTED
                            )
                            if (isEligible(item)) candidates += item
                        }
                }

                // Watchlist is a fallback pool — only used when recommendations alone
                // yield fewer than 10 candidates, to avoid overwhelming personalized results.
                if (candidates.size - countBeforeRecommendations < 10) {
                    val watchlist = requestOrNull { traktApi.getWatchlist(limit = 100) }
                    if (!isCurrentScope(scope)) return
                    watchlist
                        ?.takeIf { it.isSuccessful }
                        ?.body().orEmpty()
                        .forEach { watchlistItem ->
                            when (watchlistItem.type) {
                                "movie" -> {
                                    if (!current.preferences.includeMovies) return@forEach
                                    val movie = watchlistItem.movie ?: return@forEach
                                    val id = movie.ids.imdb ?: movie.ids.tmdb?.let { "tmdb:$it" } ?: return@forEach
                                    val item = QueueItem(
                                        id = id,
                                        type = "movie",
                                        title = movie.title ?: "Movie",
                                        origin = QueueOrigin.SUGGESTED
                                    )
                                    if (isEligible(item)) candidates += item
                                }

                                "show" -> {
                                    if (!current.preferences.includeEpisodes && !current.preferences.includeWholeShows) return@forEach
                                    val show = watchlistItem.show ?: return@forEach
                                    val id = show.ids.imdb ?: show.ids.tmdb?.let { "tmdb:$it" } ?: return@forEach
                                    val item = QueueItem(
                                        id = id,
                                        type = "series",
                                        title = show.title ?: "Series",
                                        wholeShow = current.preferences.includeWholeShows,
                                        origin = QueueOrigin.SUGGESTED
                                    )
                                    if (isEligible(item)) candidates += item
                                }

                                "season", "episode" -> {
                                    if (!current.preferences.includeEpisodes && !current.preferences.includeWholeShows) return@forEach
                                    val show = watchlistItem.show ?: return@forEach
                                    val id = show.ids.imdb ?: show.ids.tmdb?.let { "tmdb:$it" } ?: return@forEach
                                    val item = QueueItem(
                                        id = id,
                                        type = "series",
                                        title = show.title ?: "Series",
                                        wholeShow = current.preferences.includeWholeShows,
                                        origin = QueueOrigin.SUGGESTED
                                    )
                                    if (isEligible(item)) candidates += item
                                }
                            }
                        }
                }
            }

            if (QueueSuggestionSource.PLAY_HISTORY in current.preferences.suggestionSources &&
                !current.preferences.onlyUnseenSuggestions
            ) {
                history.asSequence()
                    .filter { !it.watched }
                    .filter {
                        (it.type == "movie" && current.preferences.includeMovies) ||
                            (it.type == "series" && (current.preferences.includeEpisodes || current.preferences.includeWholeShows))
                    }
                    .sortedByDescending { it.lastWatched }
                    .forEach { historyItem ->
                        candidates += QueueItem(
                            id = historyItem.id,
                            type = historyItem.type,
                            title = historyItem.title,
                            poster = historyItem.poster,
                            origin = QueueOrigin.SUGGESTED
                        )
                    }
            }

            if (!isCurrentScope(scope)) return
            val latestDismissedKeys = dismissedSuggestionKeys(scope.profileId)
            val ranked = candidates
                .asSequence()
                .filter(::isEligible)
                .distinctBy { it.stableKey }
                .filter { it.stableKey !in latestDismissedKeys }
                .filter { prefs.getInt(ratingKey(scope.profileId, it.stableKey), 0) >= 0 }
                .sortedWith(
                    compareByDescending<QueueItem> {
                        prefs.getInt(ratingKey(scope.profileId, it.stableKey), 0)
                    }.thenBy { it.title }
                )
                .take(SUGGESTION_COUNT * 4)
                .map {
                    it.copy(rating = prefs.getInt(ratingKey(scope.profileId, it.stableKey), 0))
                }
                .toList()

            val latestState = stateForScope(scope) ?: return
            val existing = if (preserveExisting) {
                latestState.suggestions
                    .filter(::isEligible)
                    .filter { it.stableKey !in latestDismissedKeys }
                    .filter { prefs.getInt(ratingKey(scope.profileId, it.stableKey), 0) >= 0 }
            } else {
                emptyList()
            }
            val existingKeys = existing.mapTo(mutableSetOf()) { it.stableKey }
            val nextSuggestions = (existing + ranked.filter { existingKeys.add(it.stableKey) })
                .distinctBy { it.stableKey }
                .take(SUGGESTION_COUNT)

            commit(scope, latestState.copy(suggestions = nextSuggestions, isRefreshingSuggestions = false))
        } catch (cancelled: CancellationException) {
            setRefreshing(scope, false)
            throw cancelled
        } catch (_: Exception) {
            setRefreshing(scope, false)
        }
    }

    private fun dismissedSuggestionKeys(profileId: Int): Set<String> =
        prefs.getStringSet(
            profileKey(profileId, KEY_DISMISSED_SUGGESTIONS),
            emptySet()
        )?.toSet().orEmpty()

    private fun updatePreferences(
        scope: QueueScope,
        block: QueuePreferences.() -> QueuePreferences
    ) {
        commit(scope, _state.value.copy(preferences = _state.value.preferences.block()))
    }

    @Synchronized
    private fun commit(scope: QueueScope, next: QueueState) {
        // Scope validation and persistence share this monitor. A profile switch or
        // deletion therefore cannot land between the generation check and the write.
        if (!isCurrentScopeLocked(scope)) return
        prefs.edit()
            .putString(profileKey(scope.profileId, KEY_PREFERENCES), gson.toJson(next.preferences))
            .putString(profileKey(scope.profileId, KEY_MANUAL), gson.toJson(next.manualItems))
            .putString(profileKey(scope.profileId, KEY_SUGGESTIONS), gson.toJson(next.suggestions))
            .apply()
        _state.value = next
    }

    private fun load(profileId: Int): QueueState {
        val preferences = runCatching {
            gson.fromJson(
                prefs.getString(profileKey(profileId, KEY_PREFERENCES), null),
                QueuePreferences::class.java
            )
        }.getOrNull() ?: QueuePreferences()
        val listType = object : TypeToken<List<QueueItem>>() {}.type
        val manual: List<QueueItem> = runCatching {
            gson.fromJson<List<QueueItem>>(
                prefs.getString(profileKey(profileId, KEY_MANUAL), "[]"),
                listType
            )
        }.getOrNull().orEmpty()
        val suggestions: List<QueueItem> = runCatching {
            gson.fromJson<List<QueueItem>>(
                prefs.getString(profileKey(profileId, KEY_SUGGESTIONS), "[]"),
                listType
            )
        }.getOrNull().orEmpty()
        return QueueState(preferences, manual, suggestions)
    }

    private fun normalizeId(id: String): String {
        val value = id.lowercase()
        val parts = value.split(':')
        return if (parts.size >= 3 && parts.takeLast(2).all { it.toIntOrNull() != null }) {
            parts.dropLast(2).joinToString(":")
        } else {
            value
        }
    }

    companion object {
        private const val PREFS_FILE = "illumera_queue"
        private const val KEY_PREFERENCES = "preferences"
        private const val KEY_MANUAL = "manual"
        private const val KEY_SUGGESTIONS = "suggestions"
        private const val KEY_DISMISSED_SUGGESTIONS = "dismissed_suggestions"
        const val SUGGESTION_COUNT = 10
    }
}
