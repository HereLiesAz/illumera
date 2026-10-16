package com.hereliesaz.illumera.data.model

import java.time.LocalDate

/**
 * The calendar date of a release string. Sources disagree on format ("2026-04-06",
 * "2026-04-06T00:00:00.000Z"), so only the leading yyyy-MM-dd is parsed; null when absent
 * or unparsable.
 */
fun parseReleaseDate(released: String?): LocalDate? {
    val head = released?.trim()?.take(10)?.takeIf { it.length == 10 } ?: return null
    return runCatching { LocalDate.parse(head) }.getOrNull()
}

/**
 * Whether a release has happened by [today]. A missing or unparsable date counts as
 * released (the caller's "null = assume aired" rule), never as a future date.
 */
fun isReleasedBy(released: String?, today: LocalDate = LocalDate.now()): Boolean {
    val date = parseReleaseDate(released) ?: return true
    return !date.isAfter(today)
}

/** Normalizes a release string to yyyy-MM-dd for storage, or null when unparsable. */
fun normalizeReleaseDate(released: String?): String? = parseReleaseDate(released)?.toString()
