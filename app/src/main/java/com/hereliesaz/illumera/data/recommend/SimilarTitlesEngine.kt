package com.hereliesaz.illumera.data.recommend

import com.hereliesaz.illumera.BuildConfig
import com.hereliesaz.illumera.data.model.WatchHistoryEntity
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.data.model.tmdb.TmdbDetailsResponse
import com.hereliesaz.illumera.data.model.tmdb.TmdbRecommendationResult
import com.hereliesaz.illumera.data.remote.TmdbApiService
import com.hereliesaz.illumera.data.remote.TraktSyncApiService
import com.hereliesaz.illumera.data.taste.Taste
import com.hereliesaz.illumera.data.taste.TasteStore
import com.hereliesaz.illumera.data.tmdb.TmdbService
import com.hereliesaz.illumera.domain.CategoryRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Builds the Home screen's personal rows — "More like <last watched>" and
 * "Because you watched <another title>" — from TMDB recommendations, TMDB similar
 * titles and Trakt related titles.
 *
 * Candidates are ranked by how many sources agree on them, genre overlap with the
 * seed, closeness of rating to the seed, and overlap with the genres of titles the
 * viewer Loved (strong) or Liked (mild). Disliked, watched and seed titles never appear.
 */
@Singleton
class SimilarTitlesEngine @Inject constructor(
    private val tmdbApi: TmdbApiService,
    private val traktApi: TraktSyncApiService,
    private val tmdbService: TmdbService,
    private val tasteStore: TasteStore
) {
    private val apiKey get() = BuildConfig.TMDB_API_KEY
    private val detailsCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, TmdbDetailsResponse>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, TmdbDetailsResponse>) = size > 300
        }
    )

    private data class Seed(val id: String, val type: String)

    private class Candidate(
        val tmdbId: Int,
        val type: String,
        var result: TmdbRecommendationResult?,
        var imdbId: String? = null,
        var votes: Int = 0
    )

    /** Up to two personal rows; a row with too few good titles is left out. */
    suspend fun personalRows(
        history: List<WatchHistoryEntity>,
        hiddenIds: Set<String>,
        language: String
    ): List<CategoryRow> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext emptyList()
        val seeds = history
            .filter(WatchHistoryEntity::hasPlaybackEvidence)
            .sortedByDescending { it.lastWatched }
            .mapNotNull(::seedOf)
            .distinctBy { it.id }
            .filter { tasteStore.tasteOf(it.id) != Taste.DISLIKE }
            .take(12)
        if (seeds.isEmpty()) return@withContext emptyList()
        val tastes = tasteStore.entries.value.values
        val lovedGenres = genreWeights(tastes.filter { it.taste != Taste.DISLIKE }
            .map { Seed(it.id, it.type) to it.taste.weight }, language)
        val disliked = tastes.filter { it.taste == Taste.DISLIKE }.mapTo(mutableSetOf()) { it.id }
        val exclude = hiddenIds + disliked + seeds.map { it.id }

        val rows = mutableListOf<CategoryRow>()
        val used = mutableSetOf<String>()
        val first = seeds.first()
        similarTo(first, language, lovedGenres, exclude)?.let { (name, items) ->
            items.takeIf { it.size >= MIN_ROW }?.let {
                rows += row("personal:more_like", "More like $name", it)
                used += it.map(MetaItem::id)
            }
        }
        // A different title each visit: a Loved one when there is one, else any recent one.
        val others = seeds.drop(1)
        val second = others.firstOrNull { tasteStore.tasteOf(it.id) == Taste.LOVE }
            ?: others.take(8).randomOrNull()
        if (second != null) {
            similarTo(second, language, lovedGenres, exclude + used)?.let { (name, items) ->
                items.takeIf { it.size >= MIN_ROW }?.let {
                    rows += row("personal:because", "Because you watched $name", it)
                }
            }
        }
        rows
    }

    private fun row(id: String, title: String, items: List<MetaItem>) =
        CategoryRow(id = id, title = title, order = -1, items = items)

    private fun seedOf(entry: WatchHistoryEntity): Seed? {
        val isSeries = entry.type.lowercase() in setOf("series", "tv", "episode")
        val id = (if (isSeries) entry.seriesId ?: entry.id else entry.id).substringBefore(':')
        if (!id.startsWith("tt")) return null
        return Seed(id, if (isSeries) "series" else "movie")
    }

    private suspend fun details(tmdbId: Int, mediaType: String, language: String): TmdbDetailsResponse? {
        val key = "$mediaType:$tmdbId:$language"
        detailsCache[key]?.let { return it }
        return safely {
            val response = if (mediaType == "tv") tmdbApi.getTvDetails(tmdbId, apiKey, language)
            else tmdbApi.getMovieDetails(tmdbId, apiKey, language)
            response.body()?.takeIf { response.isSuccessful }
        }?.also { detailsCache[key] = it }
    }

    /** Genre id → summed taste weight over the given titles. */
    private suspend fun genreWeights(titles: List<Pair<Seed, Int>>, language: String): Map<Int, Int> = coroutineScope {
        titles.take(30).map { (seed, weight) ->
            async {
                val mediaType = tmdbService.normalizeMediaType(seed.type)
                val tmdbId = tmdbService.ensureTmdbId(seed.id, mediaType)?.toIntOrNull()
                    ?: return@async emptyList()
                details(tmdbId, mediaType, language)?.genres.orEmpty().map { it.id to weight }
            }
        }.awaitAll().flatten().groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
    }

    private suspend fun similarTo(
        seed: Seed,
        language: String,
        lovedGenres: Map<Int, Int>,
        exclude: Set<String>
    ): Pair<String, List<MetaItem>>? = coroutineScope {
        val mediaType = tmdbService.normalizeMediaType(seed.type)
        val tmdbId = tmdbService.ensureTmdbId(seed.id, mediaType)?.toIntOrNull() ?: return@coroutineScope null
        val source = details(tmdbId, mediaType, language) ?: return@coroutineScope null
        val name = source.title ?: source.name ?: return@coroutineScope null
        val sourceGenres = source.genres.orEmpty().map { it.id }.toSet()
        val sourceRating = source.voteAverage

        val recs = async { safely { page(mediaType, tmdbId, language, similar = false) }.orEmpty() }
        val similar = async { safely { page(mediaType, tmdbId, language, similar = true) }.orEmpty() }
        val related = async {
            safely {
                if (mediaType == "tv") traktApi.getRelatedShows(seed.id).body()?.map { it.ids }
                else traktApi.getRelatedMovies(seed.id).body()?.map { it.ids }
            }.orEmpty()
        }

        val candidates = linkedMapOf<Int, Candidate>()
        (recs.await() + similar.await()).forEach { result ->
            candidates.getOrPut(result.id) { Candidate(result.id, mediaType, result) }.votes++
        }
        related.await().forEach { ids ->
            val id = ids.tmdb ?: return@forEach
            val candidate = candidates.getOrPut(id) { Candidate(id, mediaType, null) }
            candidate.votes++
            candidate.imdbId = candidate.imdbId ?: ids.imdb
        }
        candidates.remove(tmdbId)

        // Trakt-only titles carry no art or genres; fill a handful from TMDB.
        candidates.values.filter { it.result == null }.take(8).map { candidate ->
            async {
                details(candidate.tmdbId, mediaType, language)?.let { d ->
                    candidate.result = TmdbRecommendationResult(
                        id = d.id, title = d.title, name = d.name,
                        posterPath = d.posterPath, backdropPath = d.backdropPath,
                        overview = d.overview, releaseDate = d.releaseDate,
                        firstAirDate = d.firstAirDate, voteAverage = d.voteAverage,
                        voteCount = MIN_VOTES, genreIds = d.genres.orEmpty().map { it.id }
                    )
                }
            }
        }.awaitAll()

        val ranked = candidates.values
            .filter { it.result?.posterPath != null }
            .filter { (it.result?.voteCount ?: 0) >= MIN_VOTES || it.votes > 1 }
            .sortedByDescending { score(it, sourceGenres, sourceRating, lovedGenres) }
            .take(MAX_ROW + 8)

        // Stremio addons and Details resolve IMDb ids best; map before hiding watched titles.
        val gate = Semaphore(6)
        ranked.filter { it.imdbId == null }.map { candidate ->
            async { gate.withPermit { candidate.imdbId = tmdbService.tmdbToImdb(candidate.tmdbId, mediaType) } }
        }.awaitAll()

        val stremioType = if (mediaType == "tv") "series" else "movie"
        val items = ranked.mapNotNull { candidate ->
            val result = candidate.result ?: return@mapNotNull null
            val id = candidate.imdbId ?: "tmdb:${candidate.tmdbId}"
            if (id in exclude || tasteStore.tasteOf(id, "tmdb:${candidate.tmdbId}") == Taste.DISLIKE) {
                return@mapNotNull null
            }
            MetaItem(
                id = id,
                type = stremioType,
                name = result.title ?: result.name ?: return@mapNotNull null,
                poster = image(result.posterPath, "w500"),
                background = image(result.backdropPath, "w1280"),
                description = result.overview,
                releaseInfo = (result.releaseDate ?: result.firstAirDate)?.take(4),
                imdbRating = result.voteAverage?.takeIf { it > 0 }?.let { String.format(java.util.Locale.US, "%.1f", it) }
            )
        }.distinctBy { it.id }.take(MAX_ROW)
        name to items
    }

    private suspend fun page(
        mediaType: String,
        tmdbId: Int,
        language: String,
        similar: Boolean
    ): List<TmdbRecommendationResult>? {
        val response = when {
            mediaType == "tv" && similar -> tmdbApi.getTvSimilar(tmdbId, apiKey, language)
            mediaType == "tv" -> tmdbApi.getTvRecommendations(tmdbId, apiKey, language)
            similar -> tmdbApi.getMovieSimilar(tmdbId, apiKey, language)
            else -> tmdbApi.getMovieRecommendations(tmdbId, apiKey, language)
        }
        return response.body()?.results?.filter { it.id > 0 }?.takeIf { response.isSuccessful }
    }

    private fun score(
        candidate: Candidate,
        sourceGenres: Set<Int>,
        sourceRating: Double?,
        lovedGenres: Map<Int, Int>
    ): Double {
        val result = candidate.result ?: return 0.0
        val genres = result.genreIds.orEmpty().toSet()
        val overlap = if (sourceGenres.isEmpty() || genres.isEmpty()) 0.0
        else genres.intersect(sourceGenres).size.toDouble() / genres.union(sourceGenres).size
        val ratingFit = if (sourceRating == null || result.voteAverage == null) 0.5
        else (1.0 - abs(result.voteAverage - sourceRating) / 4.0).coerceIn(0.0, 1.0)
        val taste = genres.sumOf { lovedGenres[it] ?: 0 }.toDouble().coerceIn(-6.0, 9.0)
        val quality = ((result.voteAverage ?: 5.0) - 5.0) / 5.0
        return candidate.votes * 1.5 + overlap * 3.0 + ratingFit * 2.0 + taste * 0.4 + quality
    }

    private fun image(path: String?, size: String): String? =
        path?.trim()?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }

    private suspend fun <T> safely(block: suspend () -> T?): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val MIN_ROW = 6
        const val MAX_ROW = 20
        const val MIN_VOTES = 25
    }
}
