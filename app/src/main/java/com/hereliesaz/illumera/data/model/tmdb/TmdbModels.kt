package com.hereliesaz.illumera.data.model.tmdb

import com.google.gson.annotations.SerializedName

// ── Find / External IDs ──

data class TmdbFindResponse(
    @SerializedName("movie_results") val movieResults: List<TmdbFindResult>? = null,
    @SerializedName("tv_results") val tvResults: List<TmdbFindResult>? = null
)

data class TmdbFindResult(
    @SerializedName("id") val id: Int,
    @SerializedName("title") val title: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("media_type") val mediaType: String? = null,
    @SerializedName("poster_path") val posterPath: String? = null
)

data class TmdbExternalIdsResponse(
    @SerializedName("id") val id: Int,
    @SerializedName("imdb_id") val imdbId: String? = null
)

// ── Details ──

data class TmdbDetailsResponse(
    @SerializedName("id") val id: Int,
    @SerializedName("title") val title: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("overview") val overview: String? = null,
    @SerializedName("genres") val genres: List<TmdbGenre>? = null,
    @SerializedName("created_by") val createdBy: List<TmdbCreatedBy>? = null,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("first_air_date") val firstAirDate: String? = null,
    @SerializedName("runtime") val runtime: Int? = null,
    @SerializedName("episode_run_time") val episodeRunTime: List<Int>? = null,
    @SerializedName("vote_average") val voteAverage: Double? = null,
    @SerializedName("production_companies") val productionCompanies: List<TmdbCompany>? = null,
    @SerializedName("networks") val networks: List<TmdbNetwork>? = null,
    @SerializedName("production_countries") val productionCountries: List<TmdbCountry>? = null,
    @SerializedName("origin_country") val originCountry: List<String>? = null,
    @SerializedName("original_language") val originalLanguage: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("last_air_date") val lastAirDate: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("belongs_to_collection") val belongsToCollection: TmdbCollectionSummary? = null
)

data class TmdbGenre(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String
)

data class TmdbCreatedBy(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("profile_path") val profilePath: String? = null
)

data class TmdbCompany(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("logo_path") val logoPath: String? = null
)

data class TmdbNetwork(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("logo_path") val logoPath: String? = null
)

data class TmdbCountry(
    @SerializedName("iso_3166_1") val iso31661: String? = null,
    @SerializedName("name") val name: String? = null
)

data class TmdbCollectionSummary(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String? = null,
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null
)

// ── Credits ──

data class TmdbCreditsResponse(
    @SerializedName("cast") val cast: List<TmdbCastMember>? = null,
    @SerializedName("crew") val crew: List<TmdbCrewMember>? = null
)

data class TmdbCastMember(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("character") val character: String? = null,
    @SerializedName("profile_path") val profilePath: String? = null
)

data class TmdbCrewMember(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("job") val job: String? = null,
    @SerializedName("department") val department: String? = null,
    @SerializedName("profile_path") val profilePath: String? = null
)

// ── Images ──

data class TmdbImagesResponse(
    @SerializedName("logos") val logos: List<TmdbImage>? = null,
    @SerializedName("backdrops") val backdrops: List<TmdbImage>? = null
)

data class TmdbImage(
    @SerializedName("file_path") val filePath: String? = null,
    @SerializedName("iso_639_1") val iso6391: String? = null,
    @SerializedName("iso_3166_1") val iso31661: String? = null
)

// ── Videos ──

data class TmdbVideosResponse(
    @SerializedName("id") val id: Int,
    @SerializedName("results") val results: List<TmdbVideoResult> = emptyList()
)

data class TmdbVideoResult(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("key") val key: String? = null,
    @SerializedName("site") val site: String? = null,
    @SerializedName("size") val size: Int? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("official") val official: Boolean? = null,
    @SerializedName("iso_639_1") val iso6391: String? = null
)

// ── Age Ratings ──

data class TmdbMovieReleaseDatesResponse(
    @SerializedName("results") val results: List<TmdbMovieReleaseDateCountry>? = null
)

data class TmdbMovieReleaseDateCountry(
    @SerializedName("iso_3166_1") val iso31661: String? = null,
    @SerializedName("release_dates") val releaseDates: List<TmdbMovieReleaseDateItem>? = null
)

data class TmdbMovieReleaseDateItem(
    @SerializedName("certification") val certification: String? = null
)

data class TmdbTvContentRatingsResponse(
    @SerializedName("results") val results: List<TmdbTvContentRatingItem>? = null
)

data class TmdbTvContentRatingItem(
    @SerializedName("iso_3166_1") val iso31661: String? = null,
    @SerializedName("rating") val rating: String? = null
)

// ── Season / Episodes ──

data class TmdbSeasonResponse(
    @SerializedName("season_number") val seasonNumber: Int? = null,
    @SerializedName("episodes") val episodes: List<TmdbEpisode>? = null
)

data class TmdbEpisode(
    @SerializedName("episode_number") val episodeNumber: Int? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("overview") val overview: String? = null,
    @SerializedName("still_path") val stillPath: String? = null,
    @SerializedName("air_date") val airDate: String? = null,
    @SerializedName("runtime") val runtime: Int? = null
)

// ── Recommendations ──

data class TmdbRecommendationsResponse(
    @SerializedName("results") val results: List<TmdbRecommendationResult>? = null
)

data class TmdbRecommendationResult(
    @SerializedName("id") val id: Int,
    @SerializedName("title") val title: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("original_title") val originalTitle: String? = null,
    @SerializedName("original_name") val originalName: String? = null,
    @SerializedName("media_type") val mediaType: String? = null,
    @SerializedName("original_language") val originalLanguage: String? = null,
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    @SerializedName("overview") val overview: String? = null,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("first_air_date") val firstAirDate: String? = null,
    @SerializedName("vote_average") val voteAverage: Double? = null,
    @SerializedName("vote_count") val voteCount: Int? = null,
    @SerializedName("genre_ids") val genreIds: List<Int>? = null
)

// ── Collections ──

data class TmdbCollectionResponse(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String? = null,
    @SerializedName("overview") val overview: String? = null,
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    @SerializedName("parts") val parts: List<TmdbCollectionPart>? = null
)

data class TmdbCollectionPart(
    @SerializedName("id") val id: Int,
    @SerializedName("title") val title: String? = null,
    @SerializedName("overview") val overview: String? = null,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    @SerializedName("vote_average") val voteAverage: Double? = null
)

// ── Person ──

data class TmdbPersonResponse(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String? = null,
    @SerializedName("biography") val biography: String? = null,
    @SerializedName("birthday") val birthday: String? = null,
    @SerializedName("deathday") val deathday: String? = null,
    @SerializedName("place_of_birth") val placeOfBirth: String? = null,
    @SerializedName("profile_path") val profilePath: String? = null,
    @SerializedName("known_for_department") val knownForDepartment: String? = null,
    @SerializedName("also_known_as") val alsoKnownAs: List<String>? = null,
    @SerializedName("imdb_id") val imdbId: String? = null
)

data class TmdbPersonCreditsResponse(
    @SerializedName("cast") val cast: List<TmdbPersonCreditCast>? = null,
    @SerializedName("crew") val crew: List<TmdbPersonCreditCrew>? = null
)

data class TmdbPersonCreditCast(
    @SerializedName("id") val id: Int,
    @SerializedName("title") val title: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("media_type") val mediaType: String? = null,
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("first_air_date") val firstAirDate: String? = null,
    @SerializedName("character") val character: String? = null,
    @SerializedName("vote_average") val voteAverage: Double? = null,
    @SerializedName("overview") val overview: String? = null
)

data class TmdbPersonCreditCrew(
    @SerializedName("id") val id: Int,
    @SerializedName("title") val title: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("media_type") val mediaType: String? = null,
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("first_air_date") val firstAirDate: String? = null,
    @SerializedName("job") val job: String? = null,
    @SerializedName("vote_average") val voteAverage: Double? = null,
    @SerializedName("overview") val overview: String? = null
)

// ── Company / Network Details ──

data class TmdbCompanyDetailsResponse(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("headquarters") val headquarters: String? = null,
    @SerializedName("homepage") val homepage: String? = null,
    @SerializedName("logo_path") val logoPath: String? = null,
    @SerializedName("origin_country") val originCountry: String? = null
)

data class TmdbNetworkDetailsResponse(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String? = null,
    @SerializedName("headquarters") val headquarters: String? = null,
    @SerializedName("homepage") val homepage: String? = null,
    @SerializedName("logo_path") val logoPath: String? = null,
    @SerializedName("origin_country") val originCountry: String? = null
)

// ── Discover ──

data class TmdbDiscoverResponse(
    @SerializedName("page") val page: Int? = null,
    @SerializedName("results") val results: List<TmdbDiscoverResult>? = null,
    @SerializedName("total_pages") val totalPages: Int? = null,
    @SerializedName("total_results") val totalResults: Int? = null
)

data class TmdbDiscoverResult(
    @SerializedName("id") val id: Int,
    @SerializedName("title") val title: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("original_title") val originalTitle: String? = null,
    @SerializedName("original_name") val originalName: String? = null,
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    @SerializedName("overview") val overview: String? = null,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("first_air_date") val firstAirDate: String? = null,
    @SerializedName("vote_average") val voteAverage: Double? = null,
    @SerializedName("vote_count") val voteCount: Int? = null,
    @SerializedName("popularity") val popularity: Double? = null
)
