package com.cloudstream.extensions

import android.util.Base64
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Dizilla : MainAPI() {
    override var mainUrl = "https://dizilla.now"
    override var name = "Dizilla"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/arsiv" to "Diziler",
    )

    private val mapper = jacksonObjectMapper()
    private val payloadKey = "9bYMCNQiWsXIYFWYAu7EkdsSbmGBTyUI"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val payload = decodePage(app.get(request.data).document)
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val items = payload.path("listItems").mapNotNull { item ->
            item.toSearchResponse(
                slugField = "used_slug",
                titleFields = arrayOf("original_title", "culture_title"),
                posterFields = arrayOf("poster_url", "face_url"),
            )
        }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val envelope = app.post(
            "$mainUrl/api/bg/searchContent?searchterm=$encoded",
            headers = mapOf(
                "Accept" to "application/json",
                "X-Requested-With" to "XMLHttpRequest",
            ),
            referer = "$mainUrl/",
        )
        val encrypted = runCatching { mapper.readTree(envelope.text).path("response").asText() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        val payload = decryptPayload(encrypted) ?: return emptyList()

        return payload.path("result").mapNotNull { item ->
            if (!item.path("used_type").asText().equals("Series", ignoreCase = true)) {
                return@mapNotNull null
            }
            item.toSearchResponse(
                slugField = "used_slug",
                titleFields = arrayOf("object_name", "object_alternative_name"),
                posterFields = arrayOf("object_poster_url", "object_face_url"),
            )
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val payload = decodePage(app.get(url).document) ?: return null
        val item = payload.path("contentItem")
        val title = item.textFrom("original_title", "culture_title") ?: return null
        val poster = item.textFrom("poster_url", "face_url")?.cleanImageUrl()
        val year = item.path("release_year").asInt(0).takeIf { it > 0 }
        val duration = item.path("total_minutes").asInt(0).takeIf { it > 0 }
        val description = item.textFrom("description", "used_short_description")
        val tags = item.path("categories").asText()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val rating = item.path("imdb_point").asDouble(0.0)
        val related = payload.path("RelatedResults")
        val episodes = related.path("getSerieSeasonAndEpisodes").path("result")
            .flatMap { season ->
                val seasonNumber = season.path("season_no").asInt(0).takeIf { it > 0 }
                season.path("episodes").mapNotNull { episode ->
                    val slug = episode.path("used_slug").asText().takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val language = episode.path("episode_language_name").asText()
                        .takeIf { it.isNotBlank() }
                    val episodeName = episode.textFrom("episode_subtitle", "episode_text")
                    newEpisode(fixUrl(slug)) {
                        name = listOfNotNull(episodeName, language).joinToString(" · ")
                        this.season = seasonNumber
                        this.episode = episode.path("episode_no").asInt(0).takeIf { it > 0 }
                        this.description = episode.textFrom(
                            "episode_description",
                            "used_short_description",
                        )
                    }
                }
            }
        val actors = related.path("getSerieCastsById").path("result").mapNotNull { actor ->
            val actorName = actor.path("name").asText().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            Actor(actorName, actor.path("cast_image").asText().takeIf { it.isNotBlank() }?.cleanImageUrl())
        }
        val trailer = related.path("getContentTrailers").path("result")
            .firstOrNull()
            ?.path("raw_url")
            ?.asText()
            ?.takeIf { it.isNotBlank() }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            this.duration = duration
            if (rating > 0) score = Score.from10(rating)
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val payload = decodePage(app.get(data).document) ?: return false
        val links = payload.path("RelatedResults")
            .path("getEpisodeSources")
            .path("result")
            .mapNotNull { source ->
                val raw = Jsoup.parseBodyFragment(source.path("source_content").asText())
                    .selectFirst("iframe[src]")
                    ?.attr("src")
                    ?.trim()
                    .orEmpty()
                when {
                    raw.startsWith("//") -> "https:$raw"
                    raw.startsWith("http://") || raw.startsWith("https://") -> raw
                    raw.isNotEmpty() -> fixUrl(raw)
                    else -> null
                }
            }
            .distinct()

        links.forEach { link -> loadExtractor(link, data, subtitleCallback, callback) }
        return links.isNotEmpty()
    }

    private fun JsonNode.toSearchResponse(
        slugField: String,
        titleFields: Array<String>,
        posterFields: Array<String>,
    ): SearchResponse? {
        val slug = path(slugField).asText().takeIf { it.isNotBlank() } ?: return null
        val title = textFrom(*titleFields) ?: return null
        val poster = textFrom(*posterFields)?.cleanImageUrl()
        val rating = path("imdb_point").asDouble(0.0)

        return newTvSeriesSearchResponse(title, fixUrl(slug), TvType.TvSeries) {
            posterUrl = poster
            if (rating > 0) score = Score.from10(rating)
        }
    }

    private fun decodePage(document: Document): JsonNode? {
        val script = document.selectFirst("script#__NEXT_DATA__") ?: return null
        val rawJson = script.data().ifBlank { script.html() }
        val encrypted = runCatching {
            mapper.readTree(rawJson)
                .path("props")
                .path("pageProps")
                .path("secureData")
                .asText()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return decryptPayload(encrypted)
    }

    private fun decryptPayload(encrypted: String): JsonNode? = runCatching {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(payloadKey.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(ByteArray(16)),
        )
        val clear = cipher.doFinal(Base64.decode(encrypted, Base64.DEFAULT))
        mapper.readTree(String(clear, Charsets.UTF_8))
    }.getOrNull()

    private fun JsonNode.textFrom(vararg fields: String): String? = fields.firstNotNullOfOrNull { field ->
        path(field).asText().trim().takeIf { it.isNotEmpty() }
    }

    private fun String.cleanImageUrl(): String {
        val marker = "cdn.ampproject.org/i/s/"
        return if (contains(marker)) "https://${substringAfter(marker)}" else this
    }
}
