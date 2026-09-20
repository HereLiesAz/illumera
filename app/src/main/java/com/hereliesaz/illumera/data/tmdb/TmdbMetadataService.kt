package com.hereliesaz.illumera.data.tmdb

import android.util.Log
import com.hereliesaz.illumera.BuildConfig
import com.hereliesaz.illumera.data.model.tmdb.*
import com.hereliesaz.illumera.data.remote.TmdbApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TmdbMetadataService"

@Singleton
class TmdbMetadataService @Inject constructor(
    private val tmdbApi: TmdbApiService
) {
    private val apiKey get() = BuildConfig.TMDB_API_KEY

    private val enrichmentCache = ConcurrentHashMap<String, TmdbEnrichment>()
    private val episodeCache = ConcurrentHashMap<String, Map<Pair<Int, Int>, TmdbEpisodeEnrichment>>()
    private val personCache = ConcurrentHashMap<String, TmdbPersonDetail>()
    private val recommendationsCache = ConcurrentHashMap<String, List<TmdbMetaPreview>>()
    private val collectionCache = ConcurrentHashMap<String, List<TmdbMetaPreview>>()

    /**
     * Fetch full metadata enrichment for a title: details, credits, images, age ratings — all in parallel.
     */
    suspend fun fetchEnrichment(
        tmdbId: String,
        mediaType: String,
        language: String = "en"
    ): TmdbEnrichment? = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val tmdbType = if (mediaType == "tv" || mediaType == "series") "tv" else "movie"
        val cacheKey = "$tmdbId:$tmdbType:$normalizedLanguage"
        enrichmentCache[cacheKey]?.let { return@withContext it }

        val numericId = tmdbId.toIntOrNull() ?: return@withContext null

        try {
            val includeImageLanguage = buildString {
                append(normalizedLanguage.substringBefore("-"))
                append(",")
                append(normalizedLanguage)
                append(",en,null")
            }

            val (details, credits, images, ageRating) = coroutineScope {
                val detailsDeferred = async {
                    when (tmdbType) {
                        "tv" -> tmdbApi.getTvDetails(numericId, apiKey, normalizedLanguage)
                        else -> tmdbApi.getMovieDetails(numericId, apiKey, normalizedLanguage)
                    }.body()
                }
                val creditsDeferred = async {
                    when (tmdbType) {
                        "tv" -> tmdbApi.getTvCredits(numericId, apiKey, normalizedLanguage)
                        else -> tmdbApi.getMovieCredits(numericId, apiKey, normalizedLanguage)
                    }.body()
                }
                val imagesDeferred = async {
                    when (tmdbType) {
                        "tv" -> tmdbApi.getTvImages(numericId, apiKey, includeImageLanguage)
                        else -> tmdbApi.getMovieImages(numericId, apiKey, includeImageLanguage)
                    }.body()
                }
                val ageRatingDeferred = async {
                    when (tmdbType) {
                        "tv" -> {
                            val ratings = tmdbApi.getTvContentRatings(numericId, apiKey).body()?.results.orEmpty()
                            selectTvAgeRating(ratings, normalizedLanguage)
                        }
                        else -> {
                            val releases = tmdbApi.getMovieReleaseDates(numericId, apiKey).body()?.results.orEmpty()
                            selectMovieAgeRating(releases, normalizedLanguage)
                        }
                    }
                }
                Quadruple(
                    detailsDeferred.await(),
                    creditsDeferred.await(),
                    imagesDeferred.await(),
                    ageRatingDeferred.await()
                )
            }

            val genres = details?.genres?.mapNotNull { it.name.trim().takeIf { n -> n.isNotBlank() } } ?: emptyList()
            val description = details?.overview?.takeIf { it.isNotBlank() }
            val releaseInfo = details?.releaseDate ?: details?.firstAirDate
            val status = details?.status?.trim()?.takeIf { it.isNotBlank() }
            val rating = details?.voteAverage
            val runtime = details?.runtime ?: details?.episodeRunTime?.firstOrNull()
            val localizedTitle = (details?.title ?: details?.name)?.takeIf { it.isNotBlank() }

            val countries = details?.productionCountries
                ?.mapNotNull { it.iso31661?.trim()?.uppercase()?.takeIf { code -> code.isNotBlank() } }
                ?.takeIf { it.isNotEmpty() }
                ?: details?.originCountry?.takeIf { it.isNotEmpty() }
            val originalLanguage = details?.originalLanguage?.takeIf { it.isNotBlank() }

            val productionCompanies = details?.productionCompanies.orEmpty().mapNotNull { company ->
                val name = company.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                TmdbCompanyInfo(name = name, logo = buildImageUrl(company.logoPath, "w300"), tmdbId = company.id)
            }
            val networks = details?.networks.orEmpty().mapNotNull { network ->
                val name = network.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                TmdbCompanyInfo(name = name, logo = buildImageUrl(network.logoPath, "w300"), tmdbId = network.id)
            }

            val poster = buildImageUrl(details?.posterPath, "w500")
            val backdrop = buildImageUrl(details?.backdropPath, "w1280")
            val logoPath = images?.logos?.let { selectBestLocalizedImagePath(it, normalizedLanguage) }
            val logo = buildImageUrl(logoPath, "w500")

            val castMembers = credits?.cast.orEmpty().mapNotNull { member ->
                val name = member.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                TmdbCastInfo(
                    name = name,
                    character = member.character?.takeIf { it.isNotBlank() },
                    photo = buildImageUrl(member.profilePath, "w500"),
                    tmdbId = member.id
                )
            }

            // Crew: creators (TV), directors (movies), writers
            val creatorMembers = if (tmdbType == "tv") {
                details?.createdBy.orEmpty().mapNotNull { creator ->
                    val id = creator.id ?: return@mapNotNull null
                    val name = creator.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    TmdbCastInfo(name = name, character = "Creator", photo = buildImageUrl(creator.profilePath, "w500"), tmdbId = id)
                }.distinctBy { it.tmdbId ?: it.name.lowercase() }
            } else emptyList()

            val directorMembers = credits?.crew.orEmpty()
                .filter { it.job.equals("Director", ignoreCase = true) }
                .mapNotNull { member ->
                    val id = member.id ?: return@mapNotNull null
                    val name = member.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    TmdbCastInfo(name = name, character = "Director", photo = buildImageUrl(member.profilePath, "w500"), tmdbId = id)
                }.distinctBy { it.tmdbId ?: it.name.lowercase() }

            val writerMembers = credits?.crew.orEmpty()
                .filter { crew ->
                    val job = crew.job?.lowercase() ?: ""
                    job.contains("writer") || job.contains("screenplay")
                }
                .mapNotNull { member ->
                    val id = member.id ?: return@mapNotNull null
                    val name = member.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    TmdbCastInfo(name = name, character = "Writer", photo = buildImageUrl(member.profilePath, "w500"), tmdbId = id)
                }.distinctBy { it.tmdbId ?: it.name.lowercase() }

            // Prefer creator (TV) or director (movie); fall back to writer
            val hasCreator = creatorMembers.isNotEmpty()
            val hasDirector = directorMembers.isNotEmpty()
            val exposedDirectorMembers = when {
                tmdbType == "tv" && hasCreator -> creatorMembers
                tmdbType != "tv" && hasDirector -> directorMembers
                else -> emptyList()
            }
            val exposedWriterMembers = when {
                tmdbType == "tv" && hasCreator -> emptyList()
                tmdbType != "tv" && hasDirector -> emptyList()
                else -> writerMembers
            }

            val collectionId = details?.belongsToCollection?.id
            val collectionName = details?.belongsToCollection?.name

            // Don't cache empty results
            if (genres.isEmpty() && description == null && backdrop == null && logo == null &&
                poster == null && castMembers.isEmpty() && releaseInfo == null && rating == null &&
                runtime == null && productionCompanies.isEmpty() && networks.isEmpty()
            ) {
                return@withContext null
            }

            val enrichment = TmdbEnrichment(
                localizedTitle = localizedTitle,
                description = description,
                genres = genres,
                backdrop = backdrop,
                logo = logo,
                poster = poster,
                directorMembers = exposedDirectorMembers,
                writerMembers = exposedWriterMembers,
                castMembers = castMembers,
                releaseInfo = releaseInfo,
                rating = rating,
                runtimeMinutes = runtime,
                productionCompanies = productionCompanies,
                networks = networks,
                ageRating = ageRating,
                status = status,
                countries = countries,
                language = originalLanguage,
                collectionId = collectionId,
                collectionName = collectionName
            )
            enrichmentCache[cacheKey] = enrichment
            enrichment
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch TMDB enrichment: ${e.message}", e)
            null
        }
    }

    /**
     * Fetch localized episode metadata for given seasons.
     */
    suspend fun fetchEpisodeEnrichment(
        tmdbId: String,
        seasonNumbers: List<Int>,
        language: String = "en"
    ): Map<Pair<Int, Int>, TmdbEpisodeEnrichment> = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val cacheKey = "$tmdbId:${seasonNumbers.sorted().joinToString(",")}:$normalizedLanguage"
        episodeCache[cacheKey]?.let { return@withContext it }

        val numericId = tmdbId.toIntOrNull() ?: return@withContext emptyMap()
        val result = mutableMapOf<Pair<Int, Int>, TmdbEpisodeEnrichment>()

        seasonNumbers.distinct().forEach { season ->
            try {
                val response = tmdbApi.getTvSeasonDetails(numericId, season, apiKey, normalizedLanguage)
                response.body()?.episodes.orEmpty().forEach { ep ->
                    val epNum = ep.episodeNumber ?: return@forEach
                    result[season to epNum] = TmdbEpisodeEnrichment(
                        title = ep.name?.takeIf { it.isNotBlank() },
                        overview = ep.overview?.takeIf { it.isNotBlank() },
                        thumbnail = ep.stillPath?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" },
                        airDate = ep.airDate?.takeIf { it.isNotBlank() },
                        runtimeMinutes = ep.runtime
                    )
                }
            } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch TMDB season $season: ${e.message}")
            }
        }

        if (result.isNotEmpty()) episodeCache[cacheKey] = result
        result
    }

    /**
     * Fetch "More Like This" recommendations.
     */
    suspend fun fetchRecommendations(
        tmdbId: String,
        mediaType: String,
        language: String = "en",
        maxItems: Int = 12
    ): List<TmdbMetaPreview> = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val tmdbType = if (mediaType == "tv" || mediaType == "series") "tv" else "movie"
        val cacheKey = "$tmdbId:$tmdbType:$normalizedLanguage:recs"
        recommendationsCache[cacheKey]?.let { return@withContext it }

        val numericId = tmdbId.toIntOrNull() ?: return@withContext emptyList()

        try {
            val response = when (tmdbType) {
                "tv" -> tmdbApi.getTvRecommendations(numericId, apiKey, normalizedLanguage)
                else -> tmdbApi.getMovieRecommendations(numericId, apiKey, normalizedLanguage)
            }
            // Don't cache a failed (e.g. rate-limited) response as if it were a
            // legitimate empty result — that would memoize "no recommendations"
            // forever in this process-lifetime, no-TTL cache.
            if (!response.isSuccessful) return@withContext emptyList()
            val recommendations = response.body()

            val rawResults = recommendations?.results.orEmpty().filter { it.id > 0 }
            val languageCode = normalizedLanguage.substringBefore("-")

            val sorted = rawResults.sortedWith(
                compareByDescending<TmdbRecommendationResult> {
                    it.originalLanguage?.equals(languageCode, ignoreCase = true) == true
                }.thenByDescending { it.voteCount ?: 0 }
                    .thenByDescending { it.voteAverage ?: 0.0 }
            )

            val filtered = sorted.filter { rec ->
                val localized = rec.originalLanguage?.equals(languageCode, ignoreCase = true) == true
                localized || (rec.voteCount ?: 0) >= 20 || (rec.voteAverage ?: 0.0) >= 6.0
            }.ifEmpty { sorted }

            val items = filtered.take(maxItems).mapNotNull { rec ->
                val title = rec.title?.takeIf { it.isNotBlank() }
                    ?: rec.name?.takeIf { it.isNotBlank() }
                    ?: rec.originalTitle?.takeIf { it.isNotBlank() }
                    ?: rec.originalName?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val recType = when (rec.mediaType?.trim()?.lowercase()) {
                    "tv" -> "series"
                    "movie" -> "movie"
                    else -> if (tmdbType == "tv") "series" else "movie"
                }

                TmdbMetaPreview(
                    tmdbId = rec.id,
                    type = recType,
                    name = title,
                    poster = buildImageUrl(rec.posterPath, "w500"),
                    backdrop = buildImageUrl(rec.backdropPath, "w1280"),
                    description = rec.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = rec.releaseDate?.take(4) ?: rec.firstAirDate?.take(4),
                    rating = rec.voteAverage
                )
            }

            recommendationsCache[cacheKey] = items
            items
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch recommendations for $tmdbId: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetch all movies in a collection, ordered by release date.
     */
    suspend fun fetchCollection(
        collectionId: Int,
        language: String = "en"
    ): List<TmdbMetaPreview> = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val cacheKey = "$collectionId:$normalizedLanguage:collection"
        collectionCache[cacheKey]?.let { return@withContext it }

        try {
            val response = tmdbApi.getCollectionDetails(collectionId, apiKey, normalizedLanguage)
            // Don't cache a failed (e.g. rate-limited) response as "no collection" forever.
            if (!response.isSuccessful) return@withContext emptyList()
            val collection = response.body()
            val parts = collection?.parts.orEmpty().sortedBy { it.releaseDate ?: "9999" }

            val items = parts.mapNotNull { part ->
                val title = part.title ?: return@mapNotNull null
                TmdbMetaPreview(
                    tmdbId = part.id,
                    type = "movie",
                    name = title,
                    poster = buildImageUrl(part.posterPath, "w500"),
                    backdrop = buildImageUrl(part.backdropPath, "w1280"),
                    description = part.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = part.releaseDate?.take(4),
                    rating = part.voteAverage
                )
            }

            collectionCache[cacheKey] = items
            items
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch collection $collectionId: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetch person detail with filmography.
     */
    suspend fun fetchPersonDetail(
        personId: Int,
        language: String = "en"
    ): TmdbPersonDetail? = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val cacheKey = "$personId:$normalizedLanguage"
        personCache[cacheKey]?.let { return@withContext it }

        try {
            val (person, credits) = coroutineScope {
                val personDeferred = async {
                    tmdbApi.getPersonDetails(personId, apiKey, normalizedLanguage).body()
                }
                val creditsDeferred = async {
                    tmdbApi.getPersonCombinedCredits(personId, apiKey, normalizedLanguage).body()
                }
                Pair(personDeferred.await(), creditsDeferred.await())
            }

            if (person == null) return@withContext null

            // English fallback for empty biography
            val biography = if (person.biography.isNullOrBlank() && normalizedLanguage != "en") {
                try {
                    tmdbApi.getPersonDetails(personId, apiKey, "en").body()?.biography
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            } else {
                person.biography
            }?.takeIf { it.isNotBlank() }

            val preferCrew = person.knownForDepartment?.trim()?.lowercase()?.let {
                it.isNotBlank() && it != "acting" && it != "actors"
            } ?: false

            val movieCredits = mapCredits(credits?.cast.orEmpty(), credits?.crew.orEmpty(), "movie", preferCrew)
            val tvCredits = mapCredits(credits?.cast.orEmpty(), credits?.crew.orEmpty(), "tv", preferCrew)

            val detail = TmdbPersonDetail(
                tmdbId = person.id,
                name = person.name ?: "Unknown",
                biography = biography,
                birthday = person.birthday?.takeIf { it.isNotBlank() },
                deathday = person.deathday?.takeIf { it.isNotBlank() },
                placeOfBirth = person.placeOfBirth?.takeIf { it.isNotBlank() },
                profilePhoto = buildImageUrl(person.profilePath, "w500"),
                knownFor = person.knownForDepartment?.takeIf { it.isNotBlank() },
                movieCredits = movieCredits,
                tvCredits = tvCredits
            )
            personCache[cacheKey] = detail
            detail
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch person detail: ${e.message}", e)
            null
        }
    }

    /**
     * Fetch trailers/videos for a title.
     */
    suspend fun fetchVideos(
        tmdbId: String,
        mediaType: String,
        language: String = "en"
    ): List<TmdbVideoInfo> = withContext(Dispatchers.IO) {
        val numericId = tmdbId.toIntOrNull() ?: return@withContext emptyList()
        val tmdbType = if (mediaType == "tv" || mediaType == "series") "tv" else "movie"
        val normalizedLanguage = normalizeTmdbLanguage(language)

        try {
            val response = when (tmdbType) {
                "tv" -> tmdbApi.getTvVideos(numericId, apiKey, normalizedLanguage)
                else -> tmdbApi.getMovieVideos(numericId, apiKey, normalizedLanguage)
            }
            val results = response.body()?.results.orEmpty()

            // Prioritize: official trailers > trailers > teasers > others
            results
                .filter { it.site.equals("YouTube", ignoreCase = true) && !it.key.isNullOrBlank() }
                .sortedWith(
                    compareByDescending<TmdbVideoResult> { it.official == true }
                        .thenByDescending { it.type.equals("Trailer", ignoreCase = true) }
                        .thenByDescending { it.type.equals("Teaser", ignoreCase = true) }
                        .thenByDescending { it.size ?: 0 }
                )
                .map { video ->
                    TmdbVideoInfo(
                        name = video.name ?: "Trailer",
                        key = video.key!!,
                        type = video.type ?: "Video",
                        thumbnail = "https://img.youtube.com/vi/${video.key}/hqdefault.jpg"
                    )
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch videos for $tmdbId: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetch the best single trailer key (YouTube video ID) for a title.
     * Tries the preferred language first, falls back to English.
     * Only considers trailers and teasers, ranked by: trailer > teaser, official first, larger size, newest.
     */
    suspend fun fetchBestTrailerKey(
        tmdbId: String,
        mediaType: String,
        language: String = "en"
    ): TmdbVideoInfo? = withContext(Dispatchers.IO) {
        val numericId = tmdbId.toIntOrNull() ?: return@withContext null
        val tmdbType = if (mediaType == "tv" || mediaType == "series") "tv" else "movie"
        val normalizedLanguage = normalizeTmdbLanguage(language)

        try {
            // Try preferred language first
            val localizedResult = fetchAndRankTrailers(numericId, tmdbType, normalizedLanguage)
            if (localizedResult != null) return@withContext localizedResult

            // Fallback to English if different
            if (!normalizedLanguage.equals("en", ignoreCase = true) && !normalizedLanguage.startsWith("en-")) {
                val englishResult = fetchAndRankTrailers(numericId, tmdbType, "en-US")
                if (englishResult != null) return@withContext englishResult
            }

            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch best trailer for $tmdbId: ${e.message}")
            null
        }
    }

    private suspend fun fetchAndRankTrailers(tmdbId: Int, tmdbType: String, language: String): TmdbVideoInfo? {
        val response = when (tmdbType) {
            "tv" -> tmdbApi.getTvVideos(tmdbId, apiKey, language)
            else -> tmdbApi.getMovieVideos(tmdbId, apiKey, language)
        }
        val results = response.body()?.results.orEmpty()

        return results
            .filter { it.site.equals("YouTube", ignoreCase = true) && !it.key.isNullOrBlank() }
            .filter {
                val t = it.type?.trim()?.lowercase()
                t == "trailer" || t == "teaser"
            }
            .sortedWith(
                compareBy<TmdbVideoResult> { if (it.type.equals("Trailer", ignoreCase = true)) 0 else 1 }
                    .thenBy { if (it.official == true) 0 else 1 }
                    .thenByDescending { it.size ?: 0 }
            )
            .firstOrNull()
            ?.let { video ->
                TmdbVideoInfo(
                    name = video.name ?: "Trailer",
                    key = video.key!!,
                    type = video.type ?: "Trailer",
                    thumbnail = "https://img.youtube.com/vi/${video.key}/hqdefault.jpg"
                )
            }
    }

    /**
     * Fetch company or network detail.
     */
    suspend fun fetchEntityDetail(
        entityId: Int,
        kind: String // "company" or "network"
    ): TmdbEntityDetail? = withContext(Dispatchers.IO) {
        try {
            if (kind == "network") {
                val resp = tmdbApi.getNetworkDetails(entityId, apiKey).body() ?: return@withContext null
                TmdbEntityDetail(
                    tmdbId = resp.id, kind = kind, name = resp.name ?: "Unknown",
                    logo = buildImageUrl(resp.logoPath, "w500"),
                    originCountry = resp.originCountry?.takeIf { it.isNotBlank() },
                    headquarters = resp.headquarters?.takeIf { it.isNotBlank() },
                    description = null
                )
            } else {
                val resp = tmdbApi.getCompanyDetails(entityId, apiKey).body() ?: return@withContext null
                TmdbEntityDetail(
                    tmdbId = resp.id, kind = kind, name = resp.name ?: "Unknown",
                    logo = buildImageUrl(resp.logoPath, "w500"),
                    originCountry = resp.originCountry?.takeIf { it.isNotBlank() },
                    headquarters = resp.headquarters?.takeIf { it.isNotBlank() },
                    description = resp.description?.takeIf { it.isNotBlank() }
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch $kind detail $entityId: ${e.message}", e)
            null
        }
    }

    /**
     * Fetch discover results for a company or network.
     */
    suspend fun fetchDiscover(
        entityId: Int,
        kind: String,
        mediaType: String,
        sortBy: String,
        page: Int = 1,
        voteCountGte: Int? = null,
        dateLte: String? = null,
        language: String = "en"
    ): Pair<List<TmdbMetaPreview>, Int> = withContext(Dispatchers.IO) {
        try {
            val normalizedLanguage = normalizeTmdbLanguage(language)
            val resp = if (mediaType == "movie") {
                tmdbApi.discoverMovies(
                    apiKey = apiKey, language = normalizedLanguage, page = page,
                    sortBy = sortBy, withCompanies = entityId.toString(),
                    releaseDateLte = dateLte, voteCountGte = voteCountGte
                ).body()
            } else {
                tmdbApi.discoverTv(
                    apiKey = apiKey, language = normalizedLanguage, page = page,
                    sortBy = sortBy,
                    withCompanies = if (kind == "company") entityId.toString() else null,
                    withNetworks = if (kind == "network") entityId.toString() else null,
                    firstAirDateLte = dateLte, voteCountGte = voteCountGte
                ).body()
            }
            val items = resp?.results?.mapNotNull { r ->
                val title = (if (mediaType == "tv") r.name ?: r.title else r.title ?: r.name) ?: return@mapNotNull null
                val poster = buildImageUrl(r.posterPath, "w500") ?: return@mapNotNull null
                val year = if (mediaType == "tv") r.firstAirDate?.take(4) else r.releaseDate?.take(4)
                TmdbMetaPreview(
                    tmdbId = r.id, type = if (mediaType == "tv") "series" else "movie",
                    name = title, poster = poster,
                    backdrop = buildImageUrl(r.backdropPath, "w1280"),
                    description = r.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = year, rating = r.voteAverage
                )
            } ?: emptyList()
            Pair(items, resp?.totalPages ?: 1)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "Failed to discover $mediaType for $kind $entityId: ${e.message}")
            Pair(emptyList(), 1)
        }
    }

    // ── Private helpers ──

    private fun mapCredits(
        cast: List<TmdbPersonCreditCast>,
        crew: List<TmdbPersonCreditCrew>,
        targetType: String,
        preferCrew: Boolean
    ): List<TmdbMetaPreview> {
        val seenIds = mutableSetOf<Int>()

        val castCredits = cast
            .filter { it.mediaType == targetType && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seenIds.add(credit.id)) return@mapNotNull null
                val title = (if (targetType == "tv") credit.name ?: credit.title else credit.title ?: credit.name)
                    ?: return@mapNotNull null
                val year = if (targetType == "tv") credit.firstAirDate?.take(4) else credit.releaseDate?.take(4)
                TmdbMetaPreview(
                    tmdbId = credit.id,
                    type = if (targetType == "tv") "series" else "movie",
                    name = title,
                    poster = buildImageUrl(credit.posterPath, "w500"),
                    backdrop = buildImageUrl(credit.backdropPath, "w1280"),
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = year,
                    rating = credit.voteAverage
                )
            }

        val crewSeenIds = mutableSetOf<Int>()
        val crewCredits = crew
            .filter { it.mediaType == targetType && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!crewSeenIds.add(credit.id)) return@mapNotNull null
                val title = (if (targetType == "tv") credit.name ?: credit.title else credit.title ?: credit.name)
                    ?: return@mapNotNull null
                val year = if (targetType == "tv") credit.firstAirDate?.take(4) else credit.releaseDate?.take(4)
                TmdbMetaPreview(
                    tmdbId = credit.id,
                    type = if (targetType == "tv") "series" else "movie",
                    name = title,
                    poster = buildImageUrl(credit.posterPath, "w500"),
                    backdrop = buildImageUrl(credit.backdropPath, "w1280"),
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = year,
                    rating = credit.voteAverage
                )
            }

        return when {
            preferCrew && crewCredits.isNotEmpty() -> crewCredits
            castCredits.isNotEmpty() -> castCredits
            else -> crewCredits
        }
    }

    private fun buildImageUrl(path: String?, size: String): String? {
        val clean = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return "https://image.tmdb.org/t/p/$size$clean"
    }

    private fun normalizeTmdbLanguage(language: String?): String {
        val raw = language?.trim()?.takeIf { it.isNotBlank() }?.replace('_', '-') ?: return "en"
        val normalized = raw.split("-").let { parts ->
            if (parts.size == 2) "${parts[0].lowercase(Locale.US)}-${parts[1].uppercase(Locale.US)}"
            else raw.lowercase(Locale.US)
        }
        return when (normalized) {
            "es-419" -> "es-MX"
            else -> normalized
        }
    }

    private fun selectBestLocalizedImagePath(images: List<TmdbImage>, normalizedLanguage: String): String? {
        if (images.isEmpty()) return null
        val languageCode = normalizedLanguage.substringBefore("-")
        val regionCode = normalizedLanguage.substringAfter("-", "").uppercase(Locale.US).takeIf { it.length == 2 }
            ?: DEFAULT_LANGUAGE_REGIONS[languageCode]
        return images.sortedWith(
            compareByDescending<TmdbImage> { it.iso6391 == languageCode && it.iso31661 == regionCode }
                .thenByDescending { it.iso6391 == languageCode && it.iso31661 == null }
                .thenByDescending { it.iso6391 == languageCode }
                .thenByDescending { it.iso6391 == "en" }
                .thenByDescending { it.iso6391 == null }
        ).firstOrNull()?.filePath
    }

    private fun preferredRegions(normalizedLanguage: String): List<String> {
        val fromLanguage = normalizedLanguage.substringAfter("-", "").uppercase(Locale.US).takeIf { it.length == 2 }
        return buildList {
            if (!fromLanguage.isNullOrBlank()) add(fromLanguage)
            add("US")
            add("GB")
        }.distinct()
    }

    private fun selectMovieAgeRating(countries: List<TmdbMovieReleaseDateCountry>, normalizedLanguage: String): String? {
        val preferred = preferredRegions(normalizedLanguage)
        val byRegion = countries.associateBy { it.iso31661?.uppercase(Locale.US) }
        preferred.forEach { region ->
            val rating = byRegion[region]?.releaseDates.orEmpty()
                .mapNotNull { it.certification?.trim() }
                .firstOrNull { it.isNotBlank() }
            if (!rating.isNullOrBlank()) return rating
        }
        return countries.asSequence()
            .flatMap { it.releaseDates.orEmpty().asSequence() }
            .mapNotNull { it.certification?.trim() }
            .firstOrNull { it.isNotBlank() }
    }

    private fun selectTvAgeRating(ratings: List<TmdbTvContentRatingItem>, normalizedLanguage: String): String? {
        val preferred = preferredRegions(normalizedLanguage)
        val byRegion = ratings.associateBy { it.iso31661?.uppercase(Locale.US) }
        preferred.forEach { region ->
            val rating = byRegion[region]?.rating?.trim()
            if (!rating.isNullOrBlank()) return rating
        }
        return ratings.mapNotNull { it.rating?.trim() }.firstOrNull { it.isNotBlank() }
    }

    companion object {
        private val DEFAULT_LANGUAGE_REGIONS = mapOf("pt" to "PT", "es" to "ES")
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
