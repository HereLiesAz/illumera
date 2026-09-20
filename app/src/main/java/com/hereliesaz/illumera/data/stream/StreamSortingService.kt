package com.hereliesaz.illumera.data.stream

import com.hereliesaz.illumera.data.model.ProfileEntity
import com.hereliesaz.illumera.data.model.StreamQuality
import com.hereliesaz.illumera.data.model.stremio.Stream
import com.hereliesaz.illumera.ui.player.base.normalizeLanguageToIso2
import java.util.Locale
import kotlin.math.abs
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamSortingService @Inject constructor() {

    fun sortAndFilter(
        streams: List<Stream>,
        enabledQualities: Set<StreamQuality>,
        excludePhrases: List<String>,
        addonSortOrders: Map<String, Int>,
        sortBy: String = "quality",
        maxSizeGb: Int = 0,
        excludedFormats: Set<String> = emptySet(),
        preferredSizeMb: Int = 0,
        minimumSeeds: Int = 0,
        profile: ProfileEntity? = null
    ): List<Stream> {
        val lowerPhrases = excludePhrases
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        val maxSizeBytes = if (maxSizeGb > 0) maxSizeGb.toLong() * 1_073_741_824L else Long.MAX_VALUE
        val preferredSizeBytes = if (preferredSizeMb > 0) preferredSizeMb.toLong() * 1_048_576L else 0L
        val skipSeedless = profile?.sourceSkipSeedless ?: false

        return streams
            .map { stream -> stream to StreamParser.parse(stream) }
            .filter { (_, info) -> info.quality in enabledQualities }
            .filter { (stream, _) ->
                if (lowerPhrases.isEmpty()) return@filter true
                val text = StreamParser.combinedText(stream).lowercase()
                lowerPhrases.none { phrase -> text.contains(phrase) }
            }
            .filter { (_, info) -> !skipSeedless || info.seeds != 0 }
            .filter { (stream, _) -> matchesAudioLanguageRequirement(stream, profile) }
            .filter { (stream, _) -> matchesSubtitleLanguageRequirement(stream, profile) }
            .filter { (_, info) ->
                if (maxSizeGb <= 0) return@filter true
                val sizeBytes = info.sizeBytes ?: return@filter true
                sizeBytes <= maxSizeBytes
            }
            .filter { (_, info) ->
                if (excludedFormats.isEmpty()) return@filter true
                excludedFormats.intersect(info.formats).isEmpty()
            }
            .map { (stream, _) -> stream }
            .sortedWith(
                buildComparator(
                    addonSortOrders = addonSortOrders,
                    sortBy = sortBy,
                    secondarySortBy = profile?.sourceSortSecondary,
                    preferredSizeBytes = preferredSizeBytes,
                    minimumSeeds = minimumSeeds
                )
            )
    }

    private fun matchesAudioLanguageRequirement(stream: Stream, profile: ProfileEntity?): Boolean {
        val languages = requiredLanguages(
            mode = profile?.sourceAudioLanguageRequirement.orEmpty(),
            primary = profile?.preferredAudioLanguage.orEmpty(),
            secondary = profile?.preferredAudioLanguageSecondary.orEmpty()
        )
        if (languages.isEmpty()) return true

        val authoritativeFields = listOfNotNull(
            stream.behaviorHints?.filename?.takeIf { it.isNotBlank() },
            stream.name?.takeIf { it.isNotBlank() }
        )
        val contextualText = listOfNotNull(
            stream.title?.takeIf { it.isNotBlank() },
            stream.description?.takeIf { it.isNotBlank() }
        ).joinToString(" ")

        return languages.any { language ->
            authoritativeFields.any { field -> containsAuthoritativeAudioLanguage(field, language) } ||
                containsAudioLanguage(contextualText, language)
        }
    }

    private fun matchesSubtitleLanguageRequirement(stream: Stream, profile: ProfileEntity?): Boolean {
        val languages = requiredLanguages(
            mode = profile?.sourceSubtitleLanguageRequirement.orEmpty(),
            primary = profile?.preferredSubtitleLanguage.orEmpty(),
            secondary = profile?.preferredSubtitleLanguageSecondary.orEmpty()
        )
        if (languages.isEmpty()) return true

        val advertisedSubtitles = stream.subtitles.orEmpty()
        if (advertisedSubtitles.any { subtitle ->
                languages.any { language ->
                    languageCodesMatch(subtitle.lang, language) ||
                        containsLanguageAlias(subtitle.name.orEmpty(), language)
                }
            }) {
            return true
        }

        val text = StreamParser.combinedText(stream)
        return languages.any { language -> containsCaptionLanguage(text, language) }
    }

    private fun requiredLanguages(mode: String, primary: String, secondary: String): List<String> {
        val requested = when (mode) {
            "primary" -> listOf(primary)
            "primary_or_secondary" -> listOf(primary, secondary)
            else -> emptyList()
        }
        return requested
            .map { normalizeLanguageTag(it) }
            .filter { it.isNotEmpty() && it != "#off" }
            .distinct()
    }

    private fun normalizeLanguageTag(raw: String?): String = raw
        ?.trim()
        ?.replace('_', '-')
        ?.lowercase(Locale.ROOT)
        .orEmpty()

    private fun languageCodesMatch(advertised: String?, required: String): Boolean {
        val actual = normalizeLanguageToIso2(advertised)
        val wanted = normalizeLanguageToIso2(required)
        if (actual == "und" || wanted == "und") return false
        return actual.equals(wanted, ignoreCase = true) ||
            actual.substringBefore('-').equals(wanted.substringBefore('-'), ignoreCase = true)
    }

    private fun containsAuthoritativeAudioLanguage(text: String, language: String): Boolean {
        return languageAliases(language)
            .asSequence()
            .filter { alias -> alias.length > 2 }
            .any { alias -> languageMatches(text, alias).any() }
    }

    private fun containsAudioLanguage(text: String, language: String): Boolean {
        val aliases = languageAliases(language)
        if (aliases.isEmpty()) return false

        return aliases.any { alias ->
            languageMatches(text, alias).any { match ->
                val audioDistance = nearestCueDistance(text, match.range, AUDIO_CUE_REGEX)
                val subtitleDistance = nearestCueDistance(text, match.range, SUBTITLE_CUE_REGEX)
                when {
                    audioDistance != null && (subtitleDistance == null || audioDistance <= subtitleDistance) -> true
                    subtitleDistance != null -> false
                    else -> false
                }
            }
        }
    }

    private fun containsCaptionLanguage(text: String, language: String): Boolean {
        return languageAliases(language).any { alias ->
            languageMatches(text, alias).any { match ->
                nearestCueDistance(text, match.range, SUBTITLE_CUE_REGEX) != null
            }
        }
    }

    private fun containsLanguageAlias(text: String, language: String): Boolean {
        return languageAliases(language).any { alias -> languageMatches(text, alias).any() }
    }

    private fun languageAliases(language: String): Set<String> {
        val normalized = normalizeLanguageTag(language)
        if (normalized.isEmpty() || normalized == "#off") return emptySet()
        val base = normalized.substringBefore('-')
        val locale = Locale.forLanguageTag(normalized)

        return buildSet {
            // Two-letter ISO codes such as "no", "it", "he", and "id" are ordinary
            // words in release titles. Keep them for structured lang-code matching, but
            // do not treat them as free-text aliases. Three-letter/region tags remain
            // useful explicit release metadata.
            if (base.length > 2) add(base)
            if (normalized.length > 2) {
                add(normalized)
                add(normalized.replace('-', ' '))
            }
            locale.getDisplayLanguage(Locale.ENGLISH)
                .trim()
                .lowercase(Locale.ROOT)
                .takeIf { it.isNotEmpty() }
                ?.let(::add)
            LANGUAGE_ALIASES[base]?.let { addAll(it) }
            when (normalized) {
                "es-419" -> addAll(setOf("latin american spanish", "latino", "latam"))
                "pt-br" -> addAll(setOf("brazilian portuguese", "brazilian", "pt br"))
            }
        }.filterTo(linkedSetOf()) { it.isNotBlank() }
    }

    private fun languageMatches(text: String, alias: String): Sequence<MatchResult> {
        val escaped = Regex.escape(alias)
        return Regex("(?i)(?<![\\p{L}\\p{N}])$escaped(?![\\p{L}\\p{N}])").findAll(text)
    }

    private fun nearestCueDistance(text: String, languageRange: IntRange, cueRegex: Regex): Int? {
        val radius = 32
        val start = (languageRange.first - radius).coerceAtLeast(0)
        val endExclusive = (languageRange.last + radius + 1).coerceAtMost(text.length)
        if (start >= endExclusive) return null
        val languageCenter = (languageRange.first + languageRange.last) / 2

        return cueRegex.findAll(text.substring(start, endExclusive))
            .map { match ->
                val cueCenter = start + (match.range.first + match.range.last) / 2
                abs(cueCenter - languageCenter)
            }
            .minOrNull()
    }

    private fun buildComparator(
        addonSortOrders: Map<String, Int>,
        sortBy: String,
        secondarySortBy: String?,
        preferredSizeBytes: Long,
        minimumSeeds: Int
    ): Comparator<Stream> {
        var comparator = compareBy<Stream> { stream ->
            if (preferredSizeBytes <= 0L) 0L
            else StreamParser.parse(stream).sizeBytes?.let { abs(it - preferredSizeBytes) } ?: Long.MAX_VALUE / 4
        }

        comparator = comparator.thenBy { stream ->
            if (minimumSeeds <= 0) 0
            else {
                val seeds = StreamParser.parse(stream).seeds
                when {
                    // Unknown is not seedless; absence of a metric must not be treated as failure.
                    seeds == null -> 0
                    seeds >= minimumSeeds -> 0
                    else -> minimumSeeds - seeds
                }
            }
        }

        comparator = comparator.thenBy { addonSortOrders[it.addonTransportUrl] ?: Int.MAX_VALUE }

        val primary = normalizeSortKey(sortBy)
        val secondary = normalizeSortKey(secondarySortBy)
            .takeIf { it != primary }
            ?: legacySecondaryFor(primary)

        val orderedKeys = buildList {
            add(primary)
            if (secondary != primary) add(secondary)
            SORT_KEYS.forEach { key ->
                if (key !in this) add(key)
            }
        }

        orderedKeys.forEach { key ->
            comparator = comparator.then(sortComparatorFor(key))
        }
        return comparator
    }

    private fun normalizeSortKey(sort: String?): String =
        sort?.takeIf { it in SORT_KEYS } ?: "quality"

    private fun legacySecondaryFor(primary: String): String = when (primary) {
        "size" -> "quality"
        "seeds" -> "quality"
        else -> "size"
    }

    private fun sortComparatorFor(sort: String): Comparator<Stream> {
        return when (sort) {
            "quality" -> compareByDescending { StreamParser.parse(it).quality.sortOrder }
            "size" -> compareByDescending { StreamParser.parse(it).sizeBytes ?: 0L }
            "seeds" -> compareByDescending<Stream> { stream ->
                val seeds = StreamParser.parse(stream).seeds
                when {
                    seeds == null -> 1
                    seeds > 0 -> 2
                    else -> 0
                }
            }.thenByDescending { stream ->
                StreamParser.parse(stream).seeds ?: Int.MIN_VALUE
            }
            else -> compareByDescending { StreamParser.parse(it).quality.sortOrder }
        }
    }

    companion object {
        private val SORT_KEYS = listOf("quality", "size", "seeds")
        private val AUDIO_CUE_REGEX = Regex("(?i)\\b(audio|dub(?:bed)?|dual)\\b")
        private val SUBTITLE_CUE_REGEX = Regex("(?i)\\b(sub(?:title)?s?|subbed|cc|captions?)\\b")

        private val LANGUAGE_ALIASES = mapOf(
            "en" to setOf("english", "eng"),
            "es" to setOf("spanish", "espanol", "español", "spa"),
            "fr" to setOf("french", "fra", "fre"),
            "de" to setOf("german", "deu", "ger"),
            "it" to setOf("italian", "ita"),
            "pt" to setOf("portuguese", "por"),
            "ru" to setOf("russian", "rus"),
            "ja" to setOf("japanese", "jpn"),
            "ko" to setOf("korean", "kor"),
            "zh" to setOf("chinese", "mandarin", "zho", "chi"),
            "ar" to setOf("arabic", "ara"),
            "hi" to setOf("hindi", "hin"),
            "tr" to setOf("turkish", "tur"),
            "pl" to setOf("polish", "pol"),
            "nl" to setOf("dutch", "nld", "dut"),
            "sv" to setOf("swedish", "swe"),
            "no" to setOf("norwegian", "nor"),
            "da" to setOf("danish", "dan"),
            "fi" to setOf("finnish", "fin"),
            "cs" to setOf("czech", "ces", "cze"),
            "hu" to setOf("hungarian", "hun"),
            "ro" to setOf("romanian", "ron", "rum"),
            "th" to setOf("thai", "tha"),
            "vi" to setOf("vietnamese", "vie"),
            "id" to setOf("indonesian", "ind"),
            "uk" to setOf("ukrainian", "ukr"),
            "el" to setOf("greek", "ell", "gre"),
            "he" to setOf("hebrew", "heb"),
            "ms" to setOf("malay", "msa", "may"),
            "hr" to setOf("croatian", "hrv"),
            "bg" to setOf("bulgarian", "bul"),
            "sk" to setOf("slovak", "slk", "slo"),
            "sr" to setOf("serbian", "srp"),
            "tl" to setOf("filipino", "tagalog"),
            "fa" to setOf("persian", "farsi", "fas", "per"),
            "bn" to setOf("bengali", "ben"),
            "ta" to setOf("tamil", "tam"),
            "te" to setOf("telugu", "tel")
        )

        fun parseEnabledQualities(qualitiesString: String): Set<StreamQuality> {
            if (qualitiesString.isBlank()) return StreamQuality.entries.toSet()
            return qualitiesString.split(",")
                .mapNotNull { StreamQuality.fromKey(it.trim()) }
                .toSet()
        }

        fun parseExcludePhrases(phrasesString: String): List<String> {
            if (phrasesString.isBlank()) return emptyList()
            return phrasesString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }

        fun parseExcludedFormats(formatsString: String): Set<String> {
            if (formatsString.isBlank()) return emptySet()
            return formatsString.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
    }
}
