from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one match, found {count}: {old[:80]!r}")
    p.write_text(text.replace(old, new, 1))


def replace_count(path, old, new, expected):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != expected:
        raise RuntimeError(f"{path}: expected {expected} matches, found {count}: {old[:80]!r}")
    p.write_text(text.replace(old, new))


replace_once(
    "app/src/main/java/com/hereliesaz/illumera/data/model/ProfileEntity.kt",
    '''    val sourceExcludedFormats: String = "",       // comma-separated: "dv,hdr,dts,dolby,hevc,av1,3d"
    // Soft auto-selection preferences. These rank sources; they do not hide them.
''',
    '''    val sourceExcludedFormats: String = "",       // comma-separated: "dv,hdr,dts,dolby,hevc,av1,3d"
    val sourceSkipSeedless: Boolean = true,         // hide sources that explicitly report 0 seeds
    val sourceAudioLanguageRequirement: String = "off",    // "off", "primary", "primary_or_secondary"
    val sourceSubtitleLanguageRequirement: String = "off", // "off", "primary", "primary_or_secondary"
    // Soft auto-selection preferences. These rank sources; they do not hide them.
'''
)

replace_once(
    "app/src/main/java/com/hereliesaz/illumera/data/local/LumeraDatabase.kt",
    "    version = 47,",
    "    version = 48,"
)

replace_once(
    "app/src/main/java/com/hereliesaz/illumera/di/DatabaseModule.kt",
    '''private val MIGRATION_46_47 = object : Migration(46, 47) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN menuMoviesEnabled INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE profiles ADD COLUMN menuSeriesEnabled INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE profiles ADD COLUMN menuWatchlistEnabled INTEGER NOT NULL DEFAULT 1")
    }
}
''',
    '''private val MIGRATION_46_47 = object : Migration(46, 47) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN menuMoviesEnabled INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE profiles ADD COLUMN menuSeriesEnabled INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE profiles ADD COLUMN menuWatchlistEnabled INTEGER NOT NULL DEFAULT 1")
    }
}

private val MIGRATION_47_48 = object : Migration(47, 48) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceSkipSeedless INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceAudioLanguageRequirement TEXT NOT NULL DEFAULT 'off'")
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceSubtitleLanguageRequirement TEXT NOT NULL DEFAULT 'off'")
    }
}
'''
)
replace_once(
    "app/src/main/java/com/hereliesaz/illumera/di/DatabaseModule.kt",
    "MIGRATION_44_45, MIGRATION_45_46, MIGRATION_46_47)",
    "MIGRATION_44_45, MIGRATION_45_46, MIGRATION_46_47, MIGRATION_47_48)"
)

replace_once(
    "app/src/main/java/com/hereliesaz/illumera/ui/settings/SettingsViewModel.kt",
    '''    fun updateSourceMinimumSeeds(profileId: Int, seeds: Int) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            dao.getProfileById(profileId)?.let { dao.insertProfile(it.copy(sourceMinimumSeeds = seeds)) }
        }
    }

    fun updateSourceAutoFallback''',
    '''    fun updateSourceMinimumSeeds(profileId: Int, seeds: Int) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            dao.getProfileById(profileId)?.let { dao.insertProfile(it.copy(sourceMinimumSeeds = seeds)) }
        }
    }

    fun updateSourceSkipSeedless(profileId: Int, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            dao.getProfileById(profileId)?.let { dao.insertProfile(it.copy(sourceSkipSeedless = enabled)) }
        }
    }

    fun updateSourceAudioLanguageRequirement(profileId: Int, mode: String) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            dao.getProfileById(profileId)?.let { dao.insertProfile(it.copy(sourceAudioLanguageRequirement = mode)) }
        }
    }

    fun updateSourceSubtitleLanguageRequirement(profileId: Int, mode: String) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            dao.getProfileById(profileId)?.let { dao.insertProfile(it.copy(sourceSubtitleLanguageRequirement = mode)) }
        }
    }

    fun updateSourceAutoFallback'''
)

replace_once(
    "app/src/main/java/com/hereliesaz/illumera/ui/settings/SettingsSubScreens.kt",
    '''            Spacer(Modifier.height(8.dp))

            SettingToggleRow(
                label = "Try Sources Automatically",''',
    '''            Spacer(Modifier.height(8.dp))

            SettingToggleRow(
                label = "Skip seedless sources",
                subtitle = "Hide sources that explicitly report 0 seeds",
                isChecked = currentProfile.sourceSkipSeedless,
                onCheckedChange = { viewModel.updateSourceSkipSeedless(currentProfile.id, it) },
                onBack = onGoBack
            )

            Spacer(Modifier.height(10.dp))
            Text(
                "Language requirements",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
                color = Color.White
            )
            Text(
                "Uses the primary and secondary languages selected under Playback. Forced sources must advertise the required language.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = Color.White.copy(0.6f),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )

            val languageForceOptions = listOf("Don't force", "Force primary", "Force primary or secondary")
            fun forceModeLabel(mode: String): String = when (mode) {
                "primary" -> "Force primary"
                "primary_or_secondary" -> "Force primary or secondary"
                else -> "Don't force"
            }
            fun forceModeValue(label: String): String = when (label) {
                "Force primary" -> "primary"
                "Force primary or secondary" -> "primary_or_secondary"
                else -> "off"
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Audio language", color = Color.White.copy(0.8f), modifier = Modifier.weight(1f))
                FilterDropdown(
                    currentValue = forceModeLabel(currentProfile.sourceAudioLanguageRequirement),
                    options = languageForceOptions,
                    modifier = Modifier.width(220.dp),
                    onSelect = { label ->
                        viewModel.updateSourceAudioLanguageRequirement(currentProfile.id, forceModeValue(label))
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("CC language", color = Color.White.copy(0.8f), modifier = Modifier.weight(1f))
                FilterDropdown(
                    currentValue = forceModeLabel(currentProfile.sourceSubtitleLanguageRequirement),
                    options = languageForceOptions,
                    modifier = Modifier.width(220.dp),
                    onSelect = { label ->
                        viewModel.updateSourceSubtitleLanguageRequirement(currentProfile.id, forceModeValue(label))
                    }
                )
            }
            Spacer(Modifier.height(8.dp))

            SettingToggleRow(
                label = "Try Sources Automatically",'''
)

Path("app/src/main/java/com/hereliesaz/illumera/data/stream/StreamSortingService.kt").write_text(r'''package com.hereliesaz.illumera.data.stream

import com.hereliesaz.illumera.data.model.ProfileEntity
import com.hereliesaz.illumera.data.model.StreamQuality
import com.hereliesaz.illumera.data.model.stremio.Stream
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
        val skipSeedless = profile?.sourceSkipSeedless ?: true

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
        return languages.any { language -> containsAudioLanguage(StreamParser.combinedText(stream), language) }
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
        val actual = normalizeLanguageTag(advertised)
        if (actual.isEmpty()) return false
        val wanted = normalizeLanguageTag(required)
        if (wanted.isEmpty()) return false
        return actual == wanted || actual.substringBefore('-') == wanted.substringBefore('-')
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
                    else -> true
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
            add(base)
            add(normalized)
            add(normalized.replace('-', ' '))
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
                    seeds == null -> minimumSeeds + 1
                    seeds >= minimumSeeds -> 0
                    else -> minimumSeeds - seeds
                }
            }
        }

        comparator = comparator.thenBy { addonSortOrders[it.addonTransportUrl] ?: Int.MAX_VALUE }

        comparator = when (sortBy) {
            "size" -> comparator.then(sortComparatorFor("size")).then(sortComparatorFor("quality"))
            "seeds" -> comparator.then(sortComparatorFor("seeds")).then(sortComparatorFor("quality"))
            else -> comparator.then(sortComparatorFor("quality")).then(sortComparatorFor("size"))
        }
        comparator = comparator.then(sortComparatorFor("seeds"))
        return comparator
    }

    private fun sortComparatorFor(sort: String): Comparator<Stream> {
        return when (sort) {
            "quality" -> compareByDescending { StreamParser.parse(it).quality.sortOrder }
            "size" -> compareByDescending { StreamParser.parse(it).sizeBytes ?: 0L }
            "seeds" -> compareByDescending { StreamParser.parse(it).seeds ?: 0 }
            else -> compareByDescending { StreamParser.parse(it).quality.sortOrder }
        }
    }

    companion object {
        private val AUDIO_CUE_REGEX = Regex("(?i)\\b(audio|dub(?:bed)?|dual)\\b")
        private val SUBTITLE_CUE_REGEX = Regex("(?i)\\b(sub(?:title)?s?|cc|captions?)\\b")

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
''')

replace_once(
    "app/src/main/java/com/hereliesaz/illumera/ui/details/DetailsViewModel.kt",
    '''                profile?.sourceSortPrimary ?: "quality", profile?.sourceMaxSizeGb ?: 0,
                excludedFormats, preferredSizeMb, profile?.sourceMinimumSeeds ?: 5
            )''',
    '''                profile?.sourceSortPrimary ?: "quality", profile?.sourceMaxSizeGb ?: 0,
                excludedFormats, preferredSizeMb, profile?.sourceMinimumSeeds ?: 5, profile
            )'''
)
replace_count(
    "app/src/main/java/com/hereliesaz/illumera/MainActivity.kt",
    'currentProfile?.sourceMinimumSeeds ?: 5)',
    'currentProfile?.sourceMinimumSeeds ?: 5, currentProfile)',
    2
)

replace_once(
    "app/src/main/java/com/hereliesaz/illumera/ui/navigation/NavDrawer.kt",
    'import androidx.compose.foundation.focusGroup\n',
    'import androidx.compose.foundation.focusGroup\nimport androidx.compose.foundation.gestures.detectHorizontalDragGestures\n'
)
replace_once(
    "app/src/main/java/com/hereliesaz/illumera/ui/navigation/NavDrawer.kt",
    'import androidx.compose.ui.input.key.*\n',
    'import androidx.compose.ui.input.key.*\nimport androidx.compose.ui.input.pointer.pointerInput\n'
)
replace_once(
    "app/src/main/java/com/hereliesaz/illumera/ui/navigation/NavDrawer.kt",
    '''    // Standard BackHandler for when the drawer container is focused
    BackHandler(enabled = isMenuFocused) {
        onClose()
    }

''',
    ''
)
replace_once(
    "app/src/main/java/com/hereliesaz/illumera/ui/navigation/NavDrawer.kt",
    '''        Box(modifier = Modifier.fillMaxSize()) {
            content()
        }

        // LAYER 2: Static Hero Mask''',
    '''        Box(modifier = Modifier.fillMaxSize()) {
            content()
        }

        // BackHandlers are dispatched last-composed first. Register this after screen content
        // so an open drawer wins over Watchlist (and any other screen-level BackHandler).
        BackHandler(enabled = isMenuFocused) {
            onClose()
        }

        // LAYER 2: Static Hero Mask'''
)
replace_once(
    "app/src/main/java/com/hereliesaz/illumera/ui/navigation/NavDrawer.kt",
    '''        // LAYER 4: Interactive Drawer
        Box(
            modifier = Modifier
                .width(width)
                .fillMaxHeight()
                .zIndex(2f)
                .onFocusChanged { isMenuFocused = it.hasFocus }
                .padding(top = 30.dp, bottom = 30.dp)
        ) {''',
    '''        // LAYER 4: Interactive Drawer
        val closeSwipeThresholdPx = with(density) { 48.dp.toPx() }
        Box(
            modifier = Modifier
                .width(width)
                .fillMaxHeight()
                .zIndex(2f)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.DirectionRight || event.key == Key.Back)
                    ) {
                        onClose()
                        true
                    } else {
                        false
                    }
                }
                .pointerInput(onClose, closeSwipeThresholdPx) {
                    var horizontalDrag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { horizontalDrag = 0f },
                        onDragCancel = { horizontalDrag = 0f },
                        onDragEnd = {
                            if (horizontalDrag >= closeSwipeThresholdPx) onClose()
                            horizontalDrag = 0f
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            horizontalDrag += dragAmount
                        }
                    )
                }
                .onFocusChanged { isMenuFocused = it.hasFocus }
                .padding(top = 30.dp, bottom = 30.dp)
        ) {'''
)
