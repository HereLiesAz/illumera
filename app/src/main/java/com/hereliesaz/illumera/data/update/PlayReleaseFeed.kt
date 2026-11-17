package com.hereliesaz.illumera.data.update

import com.google.gson.JsonParser

/** A build the central Play release workflow published to Google Play. */
data class PlayRelease(val versionName: String, val versionCode: Int)

/**
 * The central Play release workflow (HereLiesAz/workflows, `android-play-release.yml`) files one
 * closed issue in this repository for every build it publishes to Google Play, labelled
 * [PLAY_RELEASE_LABEL] and titled `Play release <versionName> (<versionCode>)`. Those issues are
 * how the Play build learns that Play has something newer: the public store page only shows the
 * production track, and Play Core does not run on Android TV.
 */
internal const val PLAY_RELEASE_LABEL = "play-release"

private val PLAY_RELEASE_TITLE = Regex("""^\s*Play release\s+(\d+(?:\.\d+){1,3})\s+\((\d+)\)\s*$""")

/** The release a `Play release 0.18.2.1 (110080066)` title names, or null for any other title. */
internal fun parsePlayReleaseTitle(title: String): PlayRelease? {
    val match = PLAY_RELEASE_TITLE.matchEntire(title) ?: return null
    val code = match.groupValues[2].toIntOrNull() ?: return null
    return PlayRelease(match.groupValues[1], code)
}

/**
 * The newest release among a GitHub issues-API response ([json]): the highest versionCode (Play
 * rejects reuse and lowering, so it only goes up). Pull requests (the API lists them as issues)
 * and issues with another title are ignored. Null when there is none or the text is not a list.
 */
internal fun latestPlayRelease(json: String): PlayRelease? = runCatching {
    JsonParser.parseString(json).asJsonArray
        .mapNotNull { element ->
            val issue = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            if (issue.has("pull_request")) return@mapNotNull null
            val title = issue.get("title")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
            parsePlayReleaseTitle(title)
        }
        .maxByOrNull { it.versionCode }
}.getOrNull()

/** Whether [release] is newer than the installed build (by versionCode, which only goes up). */
internal fun isNewerThanInstalled(release: PlayRelease, installedVersionCode: Int): Boolean =
    release.versionCode > installedVersionCode
