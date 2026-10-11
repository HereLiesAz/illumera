@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.hereliesaz.illumera.ui.player.base

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/**
 * A subtitle file parsed outside the player, drawn over the video by position.
 * Addon subtitles that arrive after a source is built are shown this way, so switching to one
 * never rebuilds (and rebuffers) the video.
 */
internal class OutOfBandSubtitle private constructor(private val cues: List<CuesWithTiming>) {

    /** Cues visible at [positionUs], in file order. */
    fun cuesAt(positionUs: Long): List<Cue> =
        cues.filter { positionUs >= it.startTimeUs && positionUs < it.endTimeUs }.flatMap { it.cues }

    companion object {
        /** Parses [bytes]; [mimeHint] comes from the URL and the content is sniffed when it is null. */
        fun parse(bytes: ByteArray, mimeHint: String?): OutOfBandSubtitle? {
            val mime = mimeHint ?: sniffMime(bytes)
            val format = Format.Builder().setSampleMimeType(mime).build()
            val factory = DefaultSubtitleParserFactory()
            if (!factory.supportsFormat(format)) return null
            val parsed = mutableListOf<CuesWithTiming>()
            factory.create(format).parse(bytes, SubtitleParser.OutputOptions.allCues()) { parsed += it }
            val timed = parsed.filter { it.startTimeUs != C.TIME_UNSET && it.durationUs != C.TIME_UNSET }
            return if (timed.isEmpty()) null else OutOfBandSubtitle(timed.sortedBy { it.startTimeUs })
        }

        private fun sniffMime(bytes: ByteArray): String {
            val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.UTF_8).trimStart('﻿', ' ', '\r', '\n', '\t')
            return when {
                head.startsWith("WEBVTT") -> MimeTypes.TEXT_VTT
                head.contains("[Script Info]", ignoreCase = true) -> MimeTypes.TEXT_SSA
                head.startsWith("<?xml") || head.startsWith("<tt") -> MimeTypes.APPLICATION_TTML
                else -> MimeTypes.APPLICATION_SUBRIP
            }
        }
    }
}
