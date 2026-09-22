package com.cloudstream.extensions

import com.fasterxml.jackson.annotation.JsonProperty

data class SineResult(
    @JsonProperty("data") val data: List<SineData> = emptyList(),
)

data class SineData(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("vote_average") val vote: Double? = null,
)

data class SineMovie(
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("vote_average") val vote: Double? = null,
    @JsonProperty("runtime") val runtime: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("preview_path") val trailer: String? = null,
    @JsonProperty("casterslist") val cast: List<SineCast>? = null,
    @JsonProperty("genres") val genres: List<SineGenre>? = null,
    @JsonProperty("videos") val videos: List<SineVideo>? = null,
)

data class SineSeries(
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("vote_average") val vote: Double? = null,
    @JsonProperty("first_air_date") val releaseDate: String? = null,
    @JsonProperty("preview_path") val trailer: String? = null,
    @JsonProperty("casterslist") val cast: List<SineCast>? = null,
    @JsonProperty("genreslist") val genres: List<String>? = null,
    @JsonProperty("seasons") val seasons: List<SineSeason>? = null,
)

data class SineCast(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("profile_path") val profilePath: String? = null,
)

data class SineGenre(
    @JsonProperty("name") val name: String? = null,
)

data class SineVideo(
    @JsonProperty("link") val link: String? = null,
)

data class SineSeason(
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("episodes") val episodes: List<SineEpisode>? = null,
)

data class SineEpisode(
    @JsonProperty("episode_number") val episodeNumber: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("still_path") val stillPath: String? = null,
    @JsonProperty("videos") val videos: List<SineVideo>? = null,
)

data class SineSearch(
    @JsonProperty("search") val search: List<SineData>? = null,
)
