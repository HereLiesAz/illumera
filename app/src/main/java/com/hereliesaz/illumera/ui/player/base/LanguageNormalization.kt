package com.hereliesaz.illumera.ui.player.base

import java.util.Locale

/**
 * Shared language normalization used by both the subtitle picker UI and
 * the preferred-language auto-selection in ExoPlayerBackend.
 *
 * Converts any addon / ISO-639-2 / ISO-639-3 / alias language tag into a
 * canonical ISO-639-1 two-letter key so that e.g. "eng", "en", "english"
 * all resolve to "en".
 */

/**
 * True when a track's own label names [language] ("English", "eng", "English - 5.1") although its
 * language tag is missing or "und". Whole words only; bare two-letter codes are ordinary words.
 */
internal fun trackLabelMatchesLanguage(label: String?, language: String?): Boolean {
    val key = normalizeLanguageToIso2(language).substringBefore('-')
    if (key == "und" || label.isNullOrBlank()) return false
    val locale = Locale.forLanguageTag(key)
    val names = buildSet {
        locale.getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT).takeIf { it.isNotBlank() }?.let(::add)
        runCatching { locale.isO3Language.lowercase(Locale.ROOT) }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let(::add)
    }
    if (names.isEmpty()) return false
    val words = label.lowercase(Locale.ROOT).split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
    return words.any { it in names }
}

internal val iso3ToIso2LanguageMap: Map<String, String> by lazy {
    buildMap {
        Locale.getISOLanguages().forEach { iso2 ->
            val normalizedIso2 = iso2.lowercase(Locale.ROOT)
            val locale = runCatching {
                Locale.Builder().setLanguage(normalizedIso2).build()
            }.getOrNull() ?: return@forEach
            runCatching { locale.isO3Language.lowercase(Locale.ROOT) }
                .getOrNull()
                ?.let { iso3 -> put(iso3, normalizedIso2) }
        }

        // Common legacy ISO-639-2 bibliographic codes still returned by some subtitle addons.
        put("fre", "fr")
        put("ger", "de")
        put("dut", "nl")
        put("gre", "el")
        put("rum", "ro")
        put("slo", "sk")
        put("alb", "sq")
        put("arm", "hy")
        put("baq", "eu")
        put("cze", "cs")
        put("chi", "zh")
        put("per", "fa")
        put("tib", "bo")
        put("wel", "cy")
        put("mac", "mk")
        put("ice", "is")
    }
}

internal val languageNameToIso2Map: Map<String, String> by lazy {
    buildMap {
        Locale.getISOLanguages().forEach { iso2 ->
            val normalizedIso2 = iso2.lowercase(Locale.ROOT)
            val locale = runCatching {
                Locale.Builder().setLanguage(normalizedIso2).build()
            }.getOrNull() ?: return@forEach
            val names = listOf(
                locale.getDisplayLanguage(Locale.ENGLISH),
                locale.getDisplayLanguage(locale)
            )
            names.forEach { name ->
                normalizedLanguageNameKey(name)?.let { key ->
                    putIfAbsent(key, normalizedIso2)
                }
            }
        }
    }
}

/**
 * Regional language variants to preserve instead of collapsing to the base code.
 * Checked early in normalization to keep e.g. "pt-BR" distinct from "pt".
 */
internal val regionalLanguageVariants = mapOf(
    // Brazilian Portuguese
    "pt-br" to "pt-BR",
    // Latin American Spanish (UN M.49 code 419)
    "es-419" to "es-419",
    "es-mx" to "es-419",
    "es-ar" to "es-419",
    "es-co" to "es-419",
    "es-cl" to "es-419",
    "es-pe" to "es-419",
    "es-ve" to "es-419",
    "es-ec" to "es-419",
    "es-gt" to "es-419",
    "es-cu" to "es-419",
    "es-bo" to "es-419",
    "es-do" to "es-419",
    "es-hn" to "es-419",
    "es-py" to "es-419",
    "es-sv" to "es-419",
    "es-ni" to "es-419",
    "es-cr" to "es-419",
    "es-pa" to "es-419",
    "es-uy" to "es-419",
    "es-pr" to "es-419"
)

internal val addonLanguageAliasToIso2 = mapOf(
    // Common addon/non-ISO language tags.
    "pob" to "pt-BR", // Portuguese (Brazil)
    "ptbr" to "pt-BR",
    "pb" to "pt-BR",
    "ptpt" to "pt",   // Portuguese (Portugal)
    "spn" to "es",     // Spanish (generic)
    "esp" to "es",
    "spl" to "es-419", // Spanish (Latin America)
    "esl" to "es-419",
    "zht" to "zh", // Chinese (traditional variants)
    "zhs" to "zh", // Chinese (simplified variants)
    "zhe" to "zh",
    "cht" to "zh",
    "chs" to "zh",
    "zho" to "zh",
    "zhcn" to "zh",
    "zhtw" to "zh",
    "zhhk" to "zh",
    "zhhans" to "zh",
    "zhhant" to "zh",
    "scc" to "sr", // Legacy Serbian code
    "scr" to "hr", // Legacy Croatian code
    "may" to "ms", // Legacy Malay code
    "bur" to "my", // Legacy Burmese code
    "geo" to "ka", // Legacy Georgian code
    "mao" to "mi", // Legacy Maori code
    "mol" to "ro"  // Legacy Moldavian code
)

/**
 * Normalizes any language string (ISO-639-1, ISO-639-2, ISO-639-3, addon
 * alias, full language name) into a canonical ISO-639-1 two-letter key.
 * Returns "und" for null, blank, or unrecognizable input.
 */
internal fun normalizeLanguageToIso2(language: String?): String {
    val normalized = language
        ?.trim()
        ?.replace('_', '-')
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.isNotEmpty() }
        ?: return "und"

    // Check regional variants before stripping region (e.g. pt-br → pt-BR, es-419 → es-419)
    regionalLanguageVariants[normalized]?.let { return it }

    val primarySubtag = normalized.substringBefore('-').takeIf { it.isNotBlank() } ?: return "und"
    val regionSubtag = normalized.substringAfter('-', "").takeIf { it.isNotBlank() }
    val compactPrimarySubtag = primarySubtag.filter { it in 'a'..'z' }
    if (compactPrimarySubtag == "und") return "und"

    addonLanguageAliasToIso2[normalized]?.let { return it }
    addonLanguageAliasToIso2[primarySubtag]?.let { return it }
    addonLanguageAliasToIso2[compactPrimarySubtag]?.let { return it }

    // Group all Chinese addon variants into one bucket (zht, zhs, zh-cn, zh.tw, etc.).
    if (compactPrimarySubtag.startsWith("zh")) return "zh"

    // Resolve to ISO-639-1 then check if the resolved code + region is a known variant
    val iso2 = when {
        compactPrimarySubtag.length == 2 -> compactPrimarySubtag
        else -> iso3ToIso2LanguageMap[compactPrimarySubtag]
    }

    if (iso2 != null) {
        if (regionSubtag != null) {
            regionalLanguageVariants["$iso2-$regionSubtag"]?.let { return it }
        }
        return iso2
    }

    normalizedLanguageNameKey(primarySubtag)
        ?.let { key ->
            languageNameToIso2Map[key]
                ?: key.substringBefore(' ').takeIf { it != key }?.let { firstWord ->
                    languageNameToIso2Map[firstWord]
                }
        }
        ?.let { return it }

    return when {
        compactPrimarySubtag.length >= 2 -> compactPrimarySubtag
        else -> "und"
    }
}

internal fun normalizedLanguageNameKey(rawValue: String?): String? {
    val value = rawValue
        ?.lowercase(Locale.ROOT)
        ?.replace(Regex("[^\\p{L}]"), " ")
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
    return value?.takeIf { it.isNotEmpty() }
}
