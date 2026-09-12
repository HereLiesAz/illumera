package com.hereliesaz.illumera.data.queue

import android.content.Context
import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.data.remote.TraktSyncApiService
import com.hereliesaz.illumera.data.repository.AddonRepository
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val repository: AddonRepository
) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<QueueState> = _state.asStateFlow()

    @Synchronized
    fun setEnabled(enabled: Boolean) = updatePreferences { copy(enabled = enabled) }

    @Synchronized
    fun setIncludeMovies(enabled: Boolean) = updatePreferences { copy(includeMovies = enabled) }

    @Synchronized
    fun setIncludeEpisodes(enabled: Boolean) = updatePreferences { copy(includeEpisodes = enabled) }

    @Synchronized
    fun setIncludeWholeShows(enabled: Boolean) = updatePreferences { copy(includeWholeShows = enabled) }

    @Synchronized
    fun setOnlyUnseenSuggestions(enabled: Boolean) = updatePreferences { copy(onlyUnseenSuggestions = enabled) }

    @Synchronized
    fun setSuggestionSource(source: QueueSuggestionSource, enabled: Boolean) {
        updatePreferences {
            val next = suggestionSources.toMutableSet().apply {
                if (enabled) add(source) else remove(source)
            }
            copy(suggestionSources = next)
        }
    }

    @Synchronized
    fun add(item: QueueItem) {
        if (_state.value.manualItems.any { it.stableKey == item.stableKey }) return
        commit(_state.value.copy(manualItems = _state.value.manualItems + item.copy(origin = QueueOrigin.MANUAL)))
    }

    @Synchronized
    fun remove(key: String) {
        commit(_state.value.copy(manualItems = _state.value.manualItems.filterNot { it.stableKey == key }))
    }

    @Synchronized
    fun move(key: String, delta: Int) {
        val items = _state.value.manualItems.toMutableList()
        val from = items.indexOfFirst { it.stableKey == key }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, items.lastIndex)
        if (from == to) return
        val item = items.removeAt(from)
        items.add(to, item)
        commit(_state.value.copy(manualItems = items))
    }

    @Synchronized
    fun moveSuggestion(key: String, targetIndex: Int) {
        val suggestions = _state.value.suggestions.toMutableList()
        val from = suggestions.indexOfFirst { it.stableKey == key }
        if (from < 0 || suggestions.isEmpty()) return
        val to = targetIndex.coerceIn(0, suggestions.lastIndex)
        if (from == to) return
        val item = suggestions.removeAt(from)
        suggestions.add(to, item)
        commit(_state.value.copy(suggestions = suggestions))
    }

    @Synchronized
    fun removeSuggestion(key: String) {
        val current = _state.value
        if (current.suggestions.none { it.stableKey == key }) return
        val dismissed = dismissedSuggestionKeys().toMutableSet().apply { add(key) }
        prefs.edit().putStringSet(KEY_DISMISSED_SUGGESTIONS, dismissed).apply()
        commit(current.copy(suggestions = current.suggestions.filterNot { it.stableKey == key }))
    }

    @Synchronized
    fun rateSuggestion(key: String, rating: Int) {
        val normalized = rating.coerceIn(-1, 1)
        val suggestions = _state.value.suggestions.map {
            if (it.stableKey == key) it.copy(rating = normalized) else it
        }
        commit(_state.value.copy(suggestions = suggestions))
        prefs.edit().putInt("rating_$key", normalized).apply()
    }

    @Synchronized
    fun advanceAfterPlayback(playbackId: String): QueueItem? {
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
            commit(current.copy(manualItems = manual, suggestions = suggestions))
        }
        return manual.firstOrNull() ?: suggestions.firstOrNull()
    }

    suspend fun ensureSuggestions() {
        if (_state.value.suggestions.size < SUGGESTION_COUNT) {
            refreshSuggestions(preserveExisting = true)
        }
    }

    /**
     * Queue items can originate from Trakt recommendations, which only provide IDs
     * and titles here. Resolve missing posters through the same addon metadata path
     * used by Watchlist/Home so queue cards visually match the rest of the app.
     */
    suspend fun resolveMissingArtwork() {
        val snapshot = _state.value
        val missing = (snapshot.manualItems + snapshot.suggestions)
            .filter { it.poster.isNullOrBlank() }
            .distinctBy { it.stableKey }
        if (missing.isEmpty()) return

        val resolved = mutableMapOf<String, String>()
        for (item in missing) {
            val meta = runCatching {
                val display = item.toMetaItem()
                repository.resolveMetaDetails(display.type, display.id)
            }.getOrNull()
            val poster = meta?.poster
            if (!poster.isNullOrBlank()) resolved[item.stableKey] = poster
        }
        if (resolved.isEmpty()) return

        synchronized(this) {
            val current = _state.value
            val manual = current.manualItems.map { item ->
                resolved[item.stableKey]?.let { item.copy(poster = it) } ?: item
            }
            val suggestions = current.suggestions.map { item ->
                resolved[item.stableKey]?.let { item.copy(poster = it) } ?: item
            }
            if (manual != current.manualItems || suggestions != current.suggestions) {
                commit(current.copy(manualItems = manual, suggestions = suggestions))
            }
        }
    }

    suspend fun refreshSuggestions(
        resetDismissed: Boolean = false,
        preserveExisting: Boolean = false
    ) {
        val current = _state.value
        if (!current.preferences.enabled) return
        if (resetDismissed) {
            prefs.edit().remove(KEY_DISMISSED_SUGGESTIONS).apply()
        }
        val dismissedKeys = if (resetDismissed) emptySet() else dismissedSuggestionKeys()
        _state.value = current.copy(isRefreshingSuggestions = true)

        try {
            val history = addonDao.getAllWatchHistoryOnce()
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
                runCatching { traktApi.getWatchedMovies() }
                    .getOrNull()
                    ?.takeIf { it.isSuccessful }
                    ?.body().orEmpty()
                    .forEach { watched ->
                        watched.movie.ids.imdb?.let { excludedIds += normalizeId(it) }
                        watched.movie.ids.tmdb?.let { excludedIds += normalizeId("tmdb:$it") }
                    }

                runCatching { traktApi.getWatchedShows() }
                    .getOrNull()
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
                    runCatching { traktApi.getMovieRecommendations(50) }
                        .getOrNull()
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
                    runCatching { traktApi.getShowRecommendations(50) }
                        .getOrNull()
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
                    runCatching { traktApi.getWatchlist(limit = 100) }
                        .getOrNull()
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
                            (it.type == "series" && current.preferences.includeEpisodes)
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

            val latestDismissedKeys = dismissedSuggestionKeys()
            val ranked = candidates
                .asSequence()
                .filter(::isEligible)
                .distinctBy { it.stableKey }
                .filter { it.stableKey !in latestDismissedKeys }
                .filter { prefs.getInt("rating_${it.stableKey}", 0) >= 0 }
                .sortedWith(
                    compareByDescending<QueueItem> { prefs.getInt("rating_${it.stableKey}", 0) }
                        .thenBy { it.title }
                )
                .take(SUGGESTION_COUNT * 4)
                .map { it.copy(rating = prefs.getInt("rating_${it.stableKey}", 0)) }
                .toList()

            val latestState = _state.value
            val existing = if (preserveExisting) {
                latestState.suggestions
                    .filter(::isEligible)
                    .filter { it.stableKey !in latestDismissedKeys }
                    .filter { prefs.getInt("rating_${it.stableKey}", 0) >= 0 }
            } else {
                emptyList()
            }
            val existingKeys = existing.mapTo(mutableSetOf()) { it.stableKey }
            val nextSuggestions = (existing + ranked.filter { existingKeys.add(it.stableKey) })
                .distinctBy { it.stableKey }
                .take(SUGGESTION_COUNT)

            commit(latestState.copy(suggestions = nextSuggestions, isRefreshingSuggestions = false))
        } catch (_: Exception) {
            _state.value = _state.value.copy(isRefreshingSuggestions = false)
        }
    }

    private fun dismissedSuggestionKeys(): Set<String> =
        prefs.getStringSet(KEY_DISMISSED_SUGGESTIONS, emptySet())?.toSet().orEmpty()

    private fun updatePreferences(block: QueuePreferences.() -> QueuePreferences) {
        commit(_state.value.copy(preferences = _state.value.preferences.block()))
    }

    private fun commit(next: QueueState) {
        _state.value = next
        prefs.edit()
            .putString(KEY_PREFERENCES, gson.toJson(next.preferences))
            .putString(KEY_MANUAL, gson.toJson(next.manualItems))
            .putString(KEY_SUGGESTIONS, gson.toJson(next.suggestions))
            .apply()
    }

    private fun load(): QueueState {
        val preferences = runCatching {
            gson.fromJson(prefs.getString(KEY_PREFERENCES, null), QueuePreferences::class.java)
        }.getOrNull() ?: QueuePreferences()
        val listType = object : TypeToken<List<QueueItem>>() {}.type
        val manual: List<QueueItem> = runCatching {
            gson.fromJson<List<QueueItem>>(prefs.getString(KEY_MANUAL, "[]"), listType)
        }.getOrNull().orEmpty()
        val suggestions: List<QueueItem> = runCatching {
            gson.fromJson<List<QueueItem>>(prefs.getString(KEY_SUGGESTIONS, "[]"), listType)
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
