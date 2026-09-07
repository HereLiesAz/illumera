package com.hereliesaz.illumera.data.stream

import com.hereliesaz.illumera.data.model.StreamQuality
import com.hereliesaz.illumera.data.model.stremio.Stream
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
        minimumSeeds: Int = 0
    ): List<Stream> {
        val lowerPhrases = excludePhrases
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        val maxSizeBytes = if (maxSizeGb > 0) maxSizeGb.toLong() * 1_073_741_824L else Long.MAX_VALUE
        val preferredSizeBytes = if (preferredSizeMb > 0) preferredSizeMb.toLong() * 1_048_576L else 0L

        return streams
            .map { stream -> stream to StreamParser.parse(stream) }
            .filter { (_, info) -> info.quality in enabledQualities }
            .filter { (stream, _) ->
                if (lowerPhrases.isEmpty()) return@filter true
                val text = StreamParser.combinedText(stream).lowercase()
                lowerPhrases.none { phrase -> text.contains(phrase) }
            }
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

    private fun buildComparator(
        addonSortOrders: Map<String, Int>,
        sortBy: String,
        preferredSizeBytes: Long,
        minimumSeeds: Int
    ): Comparator<Stream> {
        // Preferred size and minimum seeds are deliberately soft. A source never disappears
        // merely because it misses either preference; it just gets tried later.
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

        // The Addons screen already owns a stable user-defined sortOrder. Put that ahead
        // of generic tie-breakers so addon priority actually affects auto-selection.
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
