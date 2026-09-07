package com.hereliesaz.illumera.data.queue

import android.content.Context
import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.data.remote.TraktApiService
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

    fun toMetaItem(): MetaItem = MetaItem(
        id = seriesId ?: id,
        type = if (type == "episode") "series" else type,
        name = title,
        poster = poster
    )
}

enum class QueueOrigin { MANUAL, SUGGESTED }

enum class QueueSuggestionSource { PLAY_HISTORY, TRAKT }

@Keep
data class QueuePreferences(
    val enabled: Boolean = false,
    val includeMovies: Boolean = true,
    val includeEpisodes: Boolean = true,
    val includeWholeShows: Boolean = false,
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
    private val traktApi: TraktApiService
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
    fun rateSuggestion(key: String, rating: Int) {
        val normalized = rating.coerceIn(-1, 1)
        val suggestions = _state.value.suggestions.map {
            if (it.stableKey == key) it.copy(rating = normalized) else it
        }
        commit(_state.value.copy(suggestions = suggestions))
        prefs.edit().putInt("rating_$key", normalized).apply()
    }

    /** Removes a completed manual item and returns the next queued item, if queue playback is enabled. */
    @Synchronized
    fun advance(completedKey: String?): QueueItem? {
        val current = _state.value
        if (!current.preferences.enabled) return null
        val remaining = if (completedKey == null) current.manualItems else current.manualItems.filterNot { it.stableKey == completedKey }
        if (remaining !== current.manualItems) commit(current.copy(manualItems = remaining))
        return remaining.firstOrNull() ?: current.suggestions.firstOrNull()
    }

    suspend fun refreshSuggestions() {
        val current = _state.value
        if (!current.preferences.enabled) return
        _state.value = current.copy(isRefreshingSuggestions = true)
        try {
            val watched = addonDao.getAllWatchHistoryOnce()
            val excludedIds = buildSet {
                addAll(watched.filter { it.watched }.map { normalizeId(it.id) })
                addAll(current.manualItems.map { normalizeId(it.seriesId ?: it.id) })
            }
            val candidates = mutableListOf<QueueItem>()

            if (QueueSuggestionSource.TRAKT in current.preferences.suggestionSources) {
                if (current.preferences.includeMovies) {
                    runCatching { traktApi.getMovieRecommendations(30) }.getOrNull()
                        ?.takeIf { it.isSuccessful }?.body().orEmpty()
                        .forEach { movie ->
                            val id = movie.ids.imdb ?: movie.ids.tmdb?.let { "tmdb:$it" } ?: return@forEach
                            if (normalizeId(id) !in excludedIds) {
                                candidates += QueueItem(id, "movie", movie.title ?: "Movie", origin = QueueOrigin.SUGGESTED)
                            }
                        }
                }
                if (current.preferences.includeEpisodes || current.preferences.includeWholeShows) {
                    runCatching { traktApi.getShowRecommendations(30) }.getOrNull()
                        ?.takeIf { it.isSuccessful }?.body().orEmpty()
                        .forEach { show ->
                            val id = show.ids.imdb ?: show.ids.tmdb?.let { "tmdb:$it" } ?: return@forEach
                            if (normalizeId(id) !in excludedIds) {
                                candidates += QueueItem(
                                    id = id,
                                    type = "series",
                                    title = show.title ?: "Series",
                                    wholeShow = current.preferences.includeWholeShows,
                                    origin = QueueOrigin.SUGGESTED
                                )
                            }
                        }
                }
            }

            // Local play history participates in ranking even when Trakt is disabled: recent,
            // unfinished items are useful continuation candidates and rated titles are suppressed.
            if (QueueSuggestionSource.PLAY_HISTORY in current.preferences.suggestionSources) {
                watched.asSequence()
                    .filter { !it.watched }
                    .filter {
                        (it.type == "movie" && current.preferences.includeMovies) ||
                            (it.type == "series" && current.preferences.includeEpisodes)
                    }
                    .sortedByDescending { it.lastWatched }
                    .forEach { history ->
                        candidates += QueueItem(
                            id = history.id,
                            type = history.type,
                            title = history.title,
                            poster = history.poster,
                            origin = QueueOrigin.SUGGESTED
                        )
                    }
            }

            val ranked = candidates
                .distinctBy { it.stableKey }
                .filter { prefs.getInt("rating_${it.stableKey}", 0) >= 0 }
                .sortedWith(compareByDescending<QueueItem> { prefs.getInt("rating_${it.stableKey}", 0) }.thenBy { it.title })
                .take(SUGGESTION_COUNT)
                .map { it.copy(rating = prefs.getInt("rating_${it.stableKey}", 0)) }

            commit(_state.value.copy(suggestions = ranked, isRefreshingSuggestions = false))
        } catch (_: Exception) {
            _state.value = _state.value.copy(isRefreshingSuggestions = false)
        }
    }

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
        val manual: List<QueueItem> = runCatching { gson.fromJson<List<QueueItem>>(prefs.getString(KEY_MANUAL, "[]"), listType) }.getOrNull().orEmpty()
        val suggestions: List<QueueItem> = runCatching { gson.fromJson<List<QueueItem>>(prefs.getString(KEY_SUGGESTIONS, "[]"), listType) }.getOrNull().orEmpty()
        return QueueState(preferences, manual, suggestions)
    }

    private fun normalizeId(id: String): String = id.substringBeforeLast(":", id).lowercase()

    companion object {
        private const val PREFS_FILE = "illumera_queue"
        private const val KEY_PREFERENCES = "preferences"
        private const val KEY_MANUAL = "manual"
        private const val KEY_SUGGESTIONS = "suggestions"
        private const val SUGGESTION_COUNT = 10
    }
}
