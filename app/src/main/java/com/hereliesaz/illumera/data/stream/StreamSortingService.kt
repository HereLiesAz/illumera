package com.hereliesaz.illumera.data.stream

import com.hereliesaz.illumera.data.model.ProfileEntity
import com.hereliesaz.illumera.data.model.ParsedStreamInfo
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

        // Pre-compute parsed info once per stream to avoid O(N log N × 3) parse calls.
        val parsed = streams.associateBy({ it }, { StreamParser.parse(it) })

        // Filters rank, never remove: each criterion a source fails pushes it further down,
        // so sources passing every filter are tried first and the rest remain as fallbacks.
        val misses = streams.associateWith { stream ->
            val info = parsed[stream] ?: StreamParser.parse(stream)
            val text by lazy { StreamParser.combinedText(stream).lowercase() }
            listOf(
                info.quality !in enabledQualities,
                lowerPhrases.isNotEmpty() && lowerPhrases.any { text.contains(it) },
                skipSeedless && info.seeds == 0,
                !matchesAudioLanguageRequirement(stream, profile),
                maxSizeGb > 0 && (info.sizeBytes ?: 0L) > maxSizeBytes,
                excludedFormats.isNotEmpty() && excludedFormats.intersect(info.formats).isNotEmpty()
            ).count { it }
        }

        return streams
            .sortedWith(
                compareBy<Stream> { misses[it] ?: 0 }
                    .then(languagePreferenceComparator(profile))
                    .then(buildComparator(
                    addonSortOrders = addonSortOrders,
                    sortBy = sortBy,
                    secondarySortBy = profile?.sourceSortSecondary,
                    preferredSizeBytes = preferredSizeBytes,
                    minimumSeeds = minimumSeeds,
                    parsed = parsed
                ))
            )
    }

    /**
     * Preferred languages rank sources below the filter ranking; they never remove any.
     * Sources declaring the primary audio language come first, then the secondary; within
     * those, the primary then secondary subtitle language. Undeclared sources keep their
     * place after them, ordered by the rest of the ranking.
     */
    private fun languagePreferenceComparator(profile: ProfileEntity?): Comparator<Stream> {
        val audio = preferredLanguages(profile?.preferredAudioLanguage, profile?.preferredAudioLanguageSecondary)
        val subtitles = preferredLanguages(profile?.preferredSubtitleLanguage, profile?.preferredSubtitleLanguageSecondary)
        if (audio.isEmpty() && subtitles.isEmpty()) return Comparator { _, _ -> 0 }
        val ranks = java.util.IdentityHashMap<Stream, Int>()
        return compareBy { stream ->
            ranks.getOrPut(stream) {
                val audioRank = audio.indexOfFirst { declaresAudioLanguage(stream, it) }.let { if (it < 0) audio.size else it }
                val subtitleRank = subtitles.indexOfFirst { declaresSubtitleLanguage(stream, it) }.let { if (it < 0) subtitles.size else it }
                audioRank * (subtitles.size + 1) + subtitleRank
            }
        }
    }

    private fun preferredLanguages(primary: String?, secondary: String?): List<String> =
        listOf(primary, secondary)
            .map { normalizeLanguageTag(it) }
            .filter { it.isNotEmpty() && it != "#off" }
            .distinct()

    /**
     * Looser than the Force filter: a language word anywhere in the source text counts (as
     * "ITALIAN" in a release name), as do flag emoji. A word from the content's own title
     * appears in every source for it, so it can't change their order.
     */
    private fun declaresAudioLanguage(stream: Stream, language: String): Boolean {
        if (matchesAudioLanguage(stream, listOf(language))) return true
        val text = StreamParser.combinedText(stream)
        if (containsLanguageAlias(text, language)) return true
        val flags = LANGUAGE_FLAGS[normalizeLanguageTag(language).substringBefore('-')] ?: return false
        return flags.any { text.contains(it) }
    }

    private fun declaresSubtitleLanguage(stream: Stream, language: String): Boolean =
        matchesSubtitleLanguage(stream, listOf(language))

    private fun matchesAudioLanguageRequirement(stream: Stream, profile: ProfileEntity?): Boolean {
        val languages = requiredLanguages(
            mode = profile?.sourceAudioLanguageRequirement.orEmpty(),
            primary = profile?.preferredAudioLanguage.orEmpty(),
            secondary = profile?.preferredAudioLanguageSecondary.orEmpty()
        )
        return languages.isEmpty() || matchesAudioLanguage(stream, languages)
    }

    private fun matchesAudioLanguage(stream: Stream, languages: List<String>): Boolean {

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

    private fun matchesSubtitleLanguage(stream: Stream, languages: List<String>): Boolean {
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
        // A filename is still free text: movie titles such as "The English Patient"
        // must not satisfy a forced-English rule. Require an audio cue (audio/dub/dual)
        // just like title/description matching; structured language fields are handled
        // separately by languageCodesMatch().
        return containsAudioLanguage(text, language)
    }

    private fun containsAudioLanguage(text: String, language: String): Boolean {
        val aliases = languageAliases(language)
        if (aliases.isEmpty()) return false

        return aliases.any { alias ->
            languageMatches(text, alias).any { match ->
                val audioDistance = nearestCueDistance(text, match.range, AUDIO_CUE_REGEX, requireLinked = true)
                val subtitleDistance = nearestCueDistance(text, match.range, SUBTITLE_CUE_REGEX)
                when {
                    audioDistance != null && (subtitleDistance == null || audioDistance <= subtitleDistance) -> true
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

    private fun languageMatches(text: String, alias: String): Sequence<MatchResult> =
        LANGUAGE_PATTERNS.getOrPut(alias) {
            // Compiling per stream per alias froze the UI on large source lists (ANRs).
            Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(alias)}(?![\\p{L}\\p{N}])")
        }.findAll(text)

    /**
     * Distance from a language word to the nearest cue within [radius]. With [requireLinked],
     * a cue only counts when nothing but numbers, joiners or other language names stands
     * between them: "German Dubbed" and "English, Spanish Audio" link, but the title word in
     * "The English Patient 1996 German Dubbed" does not ("Patient" breaks the link).
     */
    private fun nearestCueDistance(
        text: String,
        languageRange: IntRange,
        cueRegex: Regex,
        requireLinked: Boolean = false
    ): Int? {
        val radius = 32
        val start = (languageRange.first - radius).coerceAtLeast(0)
        val endExclusive = (languageRange.last + radius + 1).coerceAtMost(text.length)
        if (start >= endExclusive) return null
        val languageCenter = (languageRange.first + languageRange.last) / 2

        return cueRegex.findAll(text.substring(start, endExclusive))
            .filter { match ->
                if (!requireLinked) return@filter true
                val cueFirst = start + match.range.first
                val cueLast = start + match.range.last
                val between = if (cueFirst > languageRange.last) {
                    text.substring(languageRange.last + 1, cueFirst)
                } else {
                    text.substring(cueLast + 1, languageRange.first.coerceAtLeast(cueLast + 1))
                }
                isLinkingGap(between)
            }
            .map { match ->
                val cueCenter = start + (match.range.first + match.range.last) / 2
                abs(cueCenter - languageCenter)
            }
            .minOrNull()
    }

    private fun isLinkingGap(between: String): Boolean =
        between.split(GAP_SPLIT_REGEX).filter { it.isNotEmpty() }.all { word ->
            word.any(Char::isDigit) || word.lowercase(Locale.ROOT) in LINKING_WORDS || isLanguageWord(word)
        }

    private fun isLanguageWord(word: String): Boolean = word.lowercase(Locale.ROOT) in KNOWN_LANGUAGE_WORDS

    private fun buildComparator(
        addonSortOrders: Map<String, Int>,
        sortBy: String,
        secondarySortBy: String?,
        preferredSizeBytes: Long,
        minimumSeeds: Int,
        parsed: Map<Stream, ParsedStreamInfo>
    ): Comparator<Stream> {
        // Minimum seeds comes first: a source short of it is less likely to play at all.
        var comparator = compareBy<Stream> { stream ->
            if (minimumSeeds <= 0) 0
            else {
                val seeds = parsed[stream]?.seeds
                when {
                    // Unknown is not seedless; absence of a metric must not be treated as failure.
                    seeds == null -> 0
                    seeds >= minimumSeeds -> 0
                    else -> minimumSeeds - seeds
                }
            }
        }

        // Closeness to the preferred size, in quarter-target bands. Exact byte distance would
        // never tie, so it would decide the whole order and hide seeds, addon order and quality.
        comparator = comparator.thenBy { stream ->
            if (preferredSizeBytes <= 0L) 0L
            else parsed[stream]?.sizeBytes
                ?.let { abs(it - preferredSizeBytes) / (preferredSizeBytes / SIZE_BANDS_PER_TARGET).coerceAtLeast(1L) }
                ?: Long.MAX_VALUE / 4
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
            comparator = comparator.then(sortComparatorFor(key, parsed))
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

    private fun sortComparatorFor(sort: String, parsed: Map<Stream, ParsedStreamInfo>): Comparator<Stream> {
        return when (sort) {
            "quality" -> compareByDescending { parsed[it]?.quality?.sortOrder }
            "size" -> compareByDescending { parsed[it]?.sizeBytes ?: 0L }
            "seeds" -> compareByDescending<Stream> { stream ->
                val seeds = parsed[stream]?.seeds
                when {
                    seeds == null -> 1
                    seeds > 0 -> 2
                    else -> 0
                }
            }.thenByDescending { stream ->
                parsed[stream]?.seeds ?: Int.MIN_VALUE
            }
            else -> compareByDescending { parsed[it]?.quality?.sortOrder }
        }
    }

    companion object {
        private val SORT_KEYS = listOf("quality", "size", "seeds")
        private const val SIZE_BANDS_PER_TARGET = 4L
        private val AUDIO_CUE_REGEX = Regex("(?i)\\b(audio|dub(?:bed)?|dual)\\b")
        private val SUBTITLE_CUE_REGEX = Regex("(?i)\\b(sub(?:title)?s?|subbed|cc|captions?)\\b")
        private val LANGUAGE_PATTERNS = java.util.concurrent.ConcurrentHashMap<String, Regex>()

        /** Flags addons such as Torrentio print to declare a source's audio languages. */
        private val LANGUAGE_FLAGS = mapOf(
            "en" to listOf("🇬🇧", "🇺🇸"),
            "es" to listOf("🇪🇸", "🇲🇽"),
            "fr" to listOf("🇫🇷"),
            "de" to listOf("🇩🇪"),
            "it" to listOf("🇮🇹"),
            "pt" to listOf("🇵🇹", "🇧🇷"),
            "ru" to listOf("🇷🇺"),
            "ja" to listOf("🇯🇵"),
            "ko" to listOf("🇰🇷"),
            "zh" to listOf("🇨🇳", "🇹🇼"),
            "ar" to listOf("🇸🇦"),
            "hi" to listOf("🇮🇳"),
            "tr" to listOf("🇹🇷"),
            "pl" to listOf("🇵🇱"),
            "nl" to listOf("🇳🇱"),
            "sv" to listOf("🇸🇪"),
            "no" to listOf("🇳🇴"),
            "da" to listOf("🇩🇰"),
            "fi" to listOf("🇫🇮"),
            "cs" to listOf("🇨🇿"),
            "hu" to listOf("🇭🇺"),
            "ro" to listOf("🇷🇴"),
            "th" to listOf("🇹🇭"),
            "vi" to listOf("🇻🇳"),
            "id" to listOf("🇮🇩"),
            "uk" to listOf("🇺🇦"),
            "el" to listOf("🇬🇷"),
            "he" to listOf("🇮🇱"),
            "ms" to listOf("🇲🇾"),
            "hr" to listOf("🇭🇷"),
            "bg" to listOf("🇧🇬"),
            "sk" to listOf("🇸🇰"),
            "sr" to listOf("🇷🇸"),
            "tl" to listOf("🇵🇭"),
            "fa" to listOf("🇮🇷"),
        )

        private val GAP_SPLIT_REGEX = Regex("[^\\p{L}\\p{N}]+")
        private val LINKING_WORDS = setOf("and", "or", "&", "+", "track", "tracks", "multi")

        /** Every language name/code the app knows, for telling a list of languages from title words. */
        private val KNOWN_LANGUAGE_WORDS: Set<String> by lazy {
            buildSet {
                LANGUAGE_ALIASES.values.forEach { addAll(it) }
                Locale.getISOLanguages().forEach { code ->
                    Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
                        .lowercase(Locale.ROOT).takeIf { it.isNotBlank() && it != code }?.let(::add)
                }
            }
        }

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
