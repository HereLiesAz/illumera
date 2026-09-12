package com.hereliesaz.illumera.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.SeriesNextUpEntity
import com.hereliesaz.illumera.data.model.WatchHistoryEntity
import com.hereliesaz.illumera.data.model.WatchlistEntity
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.data.model.stremio.MetaVideo
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.repository.AddonRepository
import com.hereliesaz.illumera.data.trakt.TraktSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

/** A small, screen-agnostic target for card context actions. */
data class MediaActionTarget(
    val id: String,
    val type: String,
    val title: String,
    val poster: String? = null,
    val videos: List<MetaVideo> = emptyList(),
    val addonBaseUrl: String? = null,
    val parentSeriesId: String? = null,
    val parentSeriesTitle: String? = null,
    val parentSeriesPoster: String? = null,
    val season: Int? = null,
    val episode: Int? = null
) {
    val key: String
        get() = if (type == "episode") {
            "episode:${parentSeriesId ?: id}:${season ?: 0}:${episode ?: 0}"
        } else {
            "$type:$id"
        }

    fun forEpisode(video: MetaVideo): MediaActionTarget = MediaActionTarget(
        id = video.id,
        type = "episode",
        title = video.title,
        poster = video.thumbnail ?: poster,
        addonBaseUrl = addonBaseUrl,
        parentSeriesId = id,
        parentSeriesTitle = title,
        parentSeriesPoster = poster,
        season = video.season,
        episode = video.episode
    )

    companion object {
        fun fromMeta(item: MetaItem): MediaActionTarget = MediaActionTarget(
            id = item.id,
            type = item.type,
            title = item.name,
            poster = item.poster,
            videos = item.videos.orEmpty(),
            addonBaseUrl = item.addonBaseUrl
        )
    }
}

data class MediaActionState(
    val inWatchlist: Boolean = false,
    val watched: Boolean = false,
    val traktAvailable: Boolean = false
)

private data class ResolvedMediaTarget(
    val canonicalId: String,
    val type: String,
    val title: String,
    val poster: String?,
    val videos: List<MetaVideo>,
    val traktAvailable: Boolean
)

@HiltViewModel
class MediaActionsViewModel @Inject constructor(
    private val dao: AddonDao,
    private val repository: AddonRepository,
    private val traktSyncManager: TraktSyncManager,
    private val profileConfigurationManager: ProfileConfigurationManager
) : ViewModel() {

    private val profileId: Int
        get() = profileConfigurationManager.getLastActiveProfileId() ?: 1

    suspend fun loadState(target: MediaActionTarget): MediaActionState = withContext(Dispatchers.IO) {
        val resolved = resolveTarget(target)
        val inWatchlist = dao.isInWatchlist(profileId, resolved.canonicalId)
        val watched = when (target.type) {
            "episode" -> episodeWatched(resolved.canonicalId, target.season, target.episode)
            "series", "tv" -> seriesWatched(resolved.canonicalId, resolved.videos)
            else -> dao.getHistoryItem(resolved.canonicalId)?.watched == true
        }
        MediaActionState(
            inWatchlist = inWatchlist,
            watched = watched,
            traktAvailable = resolved.traktAvailable
        )
    }

    fun toggleWatchlist(target: MediaActionTarget) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolved = resolveTarget(target)
            if (dao.isInWatchlist(profileId, resolved.canonicalId)) {
                dao.removeFromWatchlist(profileId, resolved.canonicalId)
                if (resolved.traktAvailable) {
                    traktSyncManager.pushRemove(resolved.canonicalId, resolved.type)
                }
            } else {
                val entity = WatchlistEntity(
                    profileId = profileId,
                    id = resolved.canonicalId,
                    type = resolved.type,
                    title = resolved.title,
                    poster = resolved.poster,
                    addedAt = System.currentTimeMillis()
                )
                dao.addToWatchlist(entity)
                if (resolved.traktAvailable) {
                    traktSyncManager.pushAdd(entity)
                }
            }
        }
    }

    fun toggleWatched(target: MediaActionTarget, currentlyWatched: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolved = resolveTarget(target)
            when (target.type) {
                "episode" -> toggleEpisodeWatched(target, resolved, currentlyWatched)
                "series", "tv" -> toggleSeriesWatched(target, resolved, currentlyWatched)
                else -> toggleMovieWatched(resolved, currentlyWatched)
            }
        }
    }

    fun addToTraktLibrary(target: MediaActionTarget) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolved = resolveTarget(target)
            if (resolved.traktAvailable) {
                traktSyncManager.pushAddToCollection(resolved.canonicalId, resolved.type)
            }
        }
    }

    private suspend fun resolveTarget(target: MediaActionTarget): ResolvedMediaTarget {
        val baseType = if (target.type == "episode") "series" else target.type
        val baseId = target.parentSeriesId ?: target.id
        val resolvedMeta = runCatching {
            repository.resolveMetaDetails(baseType, baseId, target.addonBaseUrl)
        }.getOrNull()
        val canonicalId = resolvedMeta?.id
            ?.takeIf { it.startsWith("tt") }
            ?: baseId
        val resolvedType = if (baseType == "tv") "series" else baseType
        return ResolvedMediaTarget(
            canonicalId = canonicalId,
            type = resolvedType,
            title = resolvedMeta?.name
                ?: target.parentSeriesTitle
                ?: target.title,
            poster = resolvedMeta?.poster
                ?: target.parentSeriesPoster
                ?: target.poster,
            videos = target.videos.ifEmpty { resolvedMeta?.videos.orEmpty() },
            traktAvailable = canonicalId.startsWith("tt")
        )
    }

    private suspend fun toggleMovieWatched(
        resolved: ResolvedMediaTarget,
        currentlyWatched: Boolean
    ) {
        if (currentlyWatched) {
            dao.deleteHistoryItem(resolved.canonicalId)
            if (resolved.traktAvailable) traktSyncManager.pushMovieUnwatched(resolved.canonicalId)
            return
        }

        val existing = dao.getHistoryItem(resolved.canonicalId)
        dao.upsertHistory(
            WatchHistoryEntity(
                id = resolved.canonicalId,
                title = resolved.title,
                poster = resolved.poster ?: existing?.poster,
                position = existing?.position ?: 0L,
                duration = existing?.duration ?: 0L,
                lastWatched = System.currentTimeMillis(),
                type = "movie",
                watched = true,
                scrobbled = resolved.traktAvailable
            )
        )
        if (resolved.traktAvailable) traktSyncManager.pushMovieWatched(resolved.canonicalId)
    }

    private suspend fun toggleEpisodeWatched(
        target: MediaActionTarget,
        resolved: ResolvedMediaTarget,
        currentlyWatched: Boolean
    ) {
        val season = target.season ?: return
        val episode = target.episode ?: return
        val playbackId = "${resolved.canonicalId}:$season:$episode"
        if (currentlyWatched) {
            dao.deleteHistoryItem(playbackId)
            dao.getSeriesEpisodeHistory("$playbackId:%").forEach { dao.deleteHistoryItem(it.id) }
            if (resolved.traktAvailable) {
                traktSyncManager.pushEpisodeUnwatched(resolved.canonicalId, season, episode)
            }
            return
        }

        val existing = dao.getHistoryItem(playbackId)
        dao.upsertHistory(
            WatchHistoryEntity(
                id = playbackId,
                title = target.title.ifBlank { "S$season:E$episode - ${resolved.title}" },
                poster = resolved.poster ?: existing?.poster,
                position = existing?.position ?: 0L,
                duration = existing?.duration ?: 0L,
                lastWatched = System.currentTimeMillis(),
                type = "series",
                watched = true,
                scrobbled = resolved.traktAvailable
            )
        )
        if (resolved.traktAvailable) {
            traktSyncManager.pushEpisodeWatched(resolved.canonicalId, season, episode)
        }
    }

    private suspend fun toggleSeriesWatched(
        target: MediaActionTarget,
        resolved: ResolvedMediaTarget,
        currentlyWatched: Boolean
    ) {
        val episodes = airedEpisodes(resolved.videos)
        if (currentlyWatched) {
            dao.deleteSeriesHistory("${resolved.canonicalId}:%")
            dao.deleteSeriesNextUp(profileId, resolved.canonicalId)
            if (resolved.traktAvailable && episodes.isNotEmpty()) {
                traktSyncManager.pushSeriesEpisodesWatched(
                    resolved.canonicalId,
                    episodes.map { it.season to it.episode },
                    watched = false
                )
            }
            return
        }
        if (episodes.isEmpty()) return

        val now = System.currentTimeMillis()
        val existing = dao.getSeriesEpisodeHistory("${resolved.canonicalId}:%")
            .mapNotNull { history ->
                parseSeasonEpisode(resolved.canonicalId, history.id)?.let { it to history }
            }
            .toMap()

        dao.upsertHistoryItems(
            episodes.map { video ->
                val prior = existing[video.season to video.episode]
                WatchHistoryEntity(
                    id = "${resolved.canonicalId}:${video.season}:${video.episode}",
                    title = video.title.takeIf { it.isNotBlank() && it != "Episode" }
                        ?: "S${video.season}:E${video.episode} - ${resolved.title}",
                    poster = resolved.poster ?: prior?.poster,
                    position = prior?.position ?: 0L,
                    duration = prior?.duration ?: 0L,
                    lastWatched = now,
                    type = "series",
                    watched = true,
                    scrobbled = resolved.traktAvailable
                )
            }
        )
        dao.upsertSeriesNextUp(
            SeriesNextUpEntity(
                profileId = profileId,
                seriesId = resolved.canonicalId,
                title = resolved.title,
                poster = resolved.poster,
                nextSeason = 0,
                nextEpisode = 0,
                nextEpisodeTitle = null,
                nextReleased = null,
                isComplete = true,
                isNewEpisode = false,
                updatedAt = now
            )
        )
        if (resolved.traktAvailable) {
            traktSyncManager.pushSeriesEpisodesWatched(
                resolved.canonicalId,
                episodes.map { it.season to it.episode },
                watched = true
            )
        }
    }

    private suspend fun episodeWatched(seriesId: String, season: Int?, episode: Int?): Boolean {
        if (season == null || episode == null) return false
        val playbackId = "$seriesId:$season:$episode"
        if (dao.getHistoryItem(playbackId)?.watched == true) return true
        return dao.getSeriesEpisodeHistory("$playbackId:%").any { it.watched }
    }

    private suspend fun seriesWatched(seriesId: String, videos: List<MetaVideo>): Boolean {
        val history = dao.getSeriesEpisodeHistory("$seriesId:%")
        if (history.isEmpty()) return false
        val aired = airedEpisodes(videos)
        if (aired.isEmpty()) return history.all { it.watched }
        val watched = history.asSequence()
            .filter { it.watched }
            .mapNotNull { parseSeasonEpisode(seriesId, it.id) }
            .toSet()
        return aired.all { (it.season to it.episode) in watched }
    }

    private fun airedEpisodes(videos: List<MetaVideo>): List<MetaVideo> {
        val today = LocalDate.now().toString()
        return videos
            .filter { it.season > 0 && it.episode > 0 }
            .filter { video ->
                val date = video.released?.take(10)
                date == null || date <= today
            }
            .sortedWith(compareBy<MetaVideo> { it.season }.thenBy { it.episode })
    }

    private fun parseSeasonEpisode(seriesId: String, playbackId: String): Pair<Int, Int>? {
        val prefix = "$seriesId:"
        if (!playbackId.startsWith(prefix)) return null
        val remainder = playbackId.removePrefix(prefix).split(":")
        if (remainder.size < 2) return null
        val season = remainder[0].toIntOrNull() ?: return null
        val episode = remainder[1].toIntOrNull() ?: return null
        return season to episode
    }
}

@Composable
fun MediaCardActionMenu(
    target: MediaActionTarget,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    viewModel: MediaActionsViewModel = hiltViewModel()
) {
    var state by remember(target.key) { mutableStateOf<MediaActionState?>(null) }

    LaunchedEffect(expanded, target.key) {
        if (expanded) state = viewModel.loadState(target)
    }

    val watchlistLabel = when {
        state?.inWatchlist == true -> "Remove from watchlist"
        target.type == "episode" -> "Add show to watchlist"
        else -> "Add to watchlist"
    }
    val watchedLabel = if (state?.watched == true) "Mark unwatched" else "Mark watched"

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest
    ) {
        DropdownMenuItem(
            text = { Text(watchlistLabel) },
            leadingIcon = {
                Icon(
                    imageVector = if (state?.inWatchlist == true) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = null
                )
            },
            onClick = {
                viewModel.toggleWatchlist(target)
                state = state?.copy(inWatchlist = state?.inWatchlist != true)
                onDismissRequest()
            }
        )
        DropdownMenuItem(
            text = { Text(watchedLabel) },
            leadingIcon = {
                Icon(
                    imageVector = if (state?.watched == true) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null
                )
            },
            onClick = {
                val wasWatched = state?.watched == true
                viewModel.toggleWatched(target, wasWatched)
                state = state?.copy(watched = !wasWatched)
                onDismissRequest()
            }
        )
        DropdownMenuItem(
            text = { Text("Add to Trakt library") },
            leadingIcon = { Icon(Icons.Default.LibraryAdd, contentDescription = null) },
            enabled = state?.traktAvailable != false,
            onClick = {
                viewModel.addToTraktLibrary(target)
                onDismissRequest()
            }
        )
    }
}
