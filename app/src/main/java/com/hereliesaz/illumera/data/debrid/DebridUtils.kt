package com.hereliesaz.illumera.data.debrid

private val VIDEO_EXTENSIONS = setOf(
    "mkv", "mp4", "avi", "mov", "wmv", "m4v", "mpg", "mpeg",
    "ts", "flv", "webm", "m2ts", "divx", "m2v", "vob", "3gp", "rmvb"
)

fun isVideoFilename(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase().trimEnd()
    return ext in VIDEO_EXTENSIONS
}
