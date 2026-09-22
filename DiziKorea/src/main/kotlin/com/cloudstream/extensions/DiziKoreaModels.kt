package com.cloudstream.extensions

import com.fasterxml.jackson.annotation.JsonProperty

data class KoreaSearch(
    @JsonProperty("success") val success: Boolean = false,
    @JsonProperty("items") val items: List<KoreaSearchItem> = emptyList(),
)

data class KoreaSearchItem(
    @JsonProperty("title") val title: String,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("url") val url: String,
)
