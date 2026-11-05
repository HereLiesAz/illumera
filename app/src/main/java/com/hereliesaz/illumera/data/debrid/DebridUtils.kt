package com.hereliesaz.illumera.data.debrid

import com.hereliesaz.illumera.data.model.debrid.DebridItem
import com.hereliesaz.illumera.data.model.debrid.DebridResult

private val VIDEO_EXTENSIONS = setOf(
    "mkv", "mp4", "avi", "mov", "wmv", "m4v", "mpg", "mpeg",
    "ts", "flv", "webm", "m2ts", "divx", "m2v", "vob", "3gp", "rmvb"
)

fun isVideoFilename(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase().trimEnd()
    return ext in VIDEO_EXTENSIONS
}

/**
 * The file a playback wants out of a torrent: its file name when the addon gave one, and the
 * season/episode when it is a series episode. A pack holds many episodes, so the first or the
 * largest video is the wrong file for all but one of them.
 */
data class WantedFile(
    val fileName: String? = null,
    val season: Int? = null,
    val episode: Int? = null
) {
    val isEpisode: Boolean get() = season != null && episode != null
    val hasPreference: Boolean get() = !fileName.isNullOrBlank() || isEpisode
}

/** What [chooseWantedFile] decided. */
sealed interface FilePick {
    /** Nothing to go by (a movie, or only one video): the caller keeps its usual choice. */
    data object NoPreference : FilePick
    data class Index(val index: Int) : FilePick
    /** Several videos and none is the wanted one: playing any would be the wrong file. */
    data object NoMatch : FilePick
}

/** The failure message for [FilePick.NoMatch]; callers stop waiting when they see it. */
internal const val NO_WANTED_FILE = "This torrent has no file for the wanted episode"

internal fun nameMatchesWanted(name: String?, wanted: WantedFile): Boolean {
    if (!wanted.fileName.isNullOrBlank() && fileNamesMatch(wanted.fileName, name)) return true
    val season = wanted.season
    val episode = wanted.episode
    return season != null && episode != null && episodeToken(name) == (season to episode)
}

/** Picks the wanted video among [names] (any file names, in the caller's order). */
internal fun chooseWantedFile(names: List<String?>, wanted: WantedFile?): FilePick {
    if (wanted == null || !wanted.hasPreference) return FilePick.NoPreference
    val videos = names.indices.filter { isVideoFilename(names[it].orEmpty()) }
    videos.firstOrNull { nameMatchesWanted(names[it], wanted) }?.let { return FilePick.Index(it) }
    return if (videos.size <= 1) FilePick.NoPreference else FilePick.NoMatch
}

/**
 * [item] narrowed to the wanted file when the provider lists file names, or a failure when it is
 * a pack without that file. Left as is when the names aren't known (the provider picks then).
 */
internal fun DebridItem.withWantedFile(wanted: WantedFile?): DebridResult<DebridItem> {
    if (directLinks.size < 2 || linkNames.size != directLinks.size) return DebridResult.Success(this)
    return when (val pick = chooseWantedFile(linkNames, wanted)) {
        is FilePick.Index -> DebridResult.Success(
            copy(directLinks = listOf(directLinks[pick.index]), linkNames = listOf(linkNames[pick.index]))
        )
        FilePick.NoMatch -> DebridResult.Failure(NO_WANTED_FILE)
        FilePick.NoPreference -> DebridResult.Success(this)
    }
}
