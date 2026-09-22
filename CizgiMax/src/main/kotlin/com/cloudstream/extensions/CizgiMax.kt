@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.cloudstream.extensions

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class CizgiMax : MainAPI() {
    override var mainUrl        = "https://cizgimax.online"
    override var name           = "CizgiMax"
    override val hasMainPage    = true
    override var lang           = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Cartoon, TvType.Anime, TvType.TvSeries)

    private val mapper = jacksonObjectMapper()

    override val mainPage = mainPageOf(
        "$mainUrl/diziler/" to "Tüm Diziler",
        "$mainUrl/diziler/cizgi-film/" to "Çizgi Filmler",
        "$mainUrl/diziler/anime/" to "Animeler",
        "$mainUrl/diziler/dizi/" to "Diziler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val document = app.get("${request.data}${separator}page=$page").document
        val home = document.select("div.film-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("a.film-name")?.text()?.trim() ?: return null
        val href = fixUrlNull(selectFirst("a.film-name")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(selectFirst("a.poster img")?.attr("src"))
        val category = selectFirst("span.corner-tag")?.text()?.lowercase().orEmpty()
        val type = when {
            "anime" in category -> TvType.Anime
            "dizi" in category -> TvType.TvSeries
            else -> TvType.Cartoon
        }
        return newTvSeriesSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/ara/?q=$query").document
        return document.select("div.film-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("a.anime-title-link")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.anime-poster img")?.attr("src")) ?: return null
        val description = document.selectFirst("p.anime-desc")?.text()?.trim()
        val tags = document.select("li.meta-genres a").map { it.text().trim() }
        val rating = document.selectFirst("span.meta-score-val")?.text()?.trim()?.toRatingInt()
        val year = Regex(""""datePublished"\s*:\s*"(\d{4})"""")
            .find(document.html())?.groupValues?.get(1)?.toIntOrNull()
        val episodes = document.select("a.ep-num-btn").mapNotNull {
            val epHref = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
            val match = Regex("""-(\d+)-sezon-(\d+)-bolum""").find(epHref)
            val epSeason = match?.groupValues?.get(1)?.toIntOrNull()
            val epEpisode = match?.groupValues?.get(2)?.toIntOrNull()
            newEpisode(epHref) {
                this.name = it.attr("title").ifBlank { "${epEpisode ?: "?"}. Bölüm" }
                this.season = epSeason
                this.episode = epEpisode
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.rating = rating
            this.year = year
        }
    }

    private suspend fun loadTauVideo(
        server: JsonNode,
        episodeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val resolvePath = server.path("resolveUrl").asText()
        if (resolvePath.isBlank()) return
        val resolveUrl = fixUrl(resolvePath)
        val resolved = runCatching {
            mapper.readTree(app.get(resolveUrl, referer = episodeUrl).text)
        }.getOrNull() ?: return
        val videoId = resolved.path("id").asText()
        if (videoId.isBlank()) return

        val videoData = runCatching {
            mapper.readTree(app.get("https://tau-video.xyz/api/video/$videoId").text)
        }.getOrNull() ?: return
        videoData.path("urls").forEach { source ->
            val videoUrl = source.path("url").asText()
            if (videoUrl.isBlank()) return@forEach
            callback(
                ExtractorLink(
                    source = name,
                    name = server.path("label").asText(name),
                    url = videoUrl,
                    referer = "https://tau-video.xyz/",
                    quality = getQualityFromName(source.path("label").asText()),
                    type = ExtractorLinkType.VIDEO,
                )
            )
        }

        val titleId = videoData.path("title_id").asInt(0)
        val season = videoData.path("season_number").asInt(0)
        val episode = videoData.path("episode_number").asInt(0)
        if (titleId == 0 || season == 0 || episode == 0) return
        val captionData = runCatching {
            mapper.readTree(
                app.get(
                    "https://animecix.tv/secure/episode-videos?titleId=$titleId&season=$season&episode=$episode"
                ).text
            )
        }.getOrNull() ?: return
        captionData.firstOrNull {
            it.path("url").asText().contains("/embed/$videoId")
        }?.path("captions")?.forEach { caption ->
            val captionUrl = caption.path("url").asText()
            if (captionUrl.isNotBlank()) {
                subtitleCallback(
                    SubtitleFile(
                        lang = caption.path("name").asText(caption.path("language").asText("Altyazı")),
                        url = captionUrl,
                    )
                )
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d("CZGM", "data » $data")
        val page = app.get(data)
        val encoded = Regex("""var\s+servers\s*=\s*JSON\.parse\(atob\("([^"]+)"\)\)""")
            .find(page.text)?.groupValues?.get(1)
            ?: return false
        val servers = runCatching {
            mapper.readTree(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8))
        }.getOrNull() ?: return false

        servers.forEach { server ->
            when (server.path("type").asText()) {
                "dzen" -> loadTauVideo(server, data, subtitleCallback, callback)
                else -> {
                    val streamPath = server.path("streamUrl").asText()
                    if (streamPath.isNotBlank()) {
                        callback(
                            ExtractorLink(
                                source = name,
                                name = server.path("label").asText(name),
                                url = fixUrl(streamPath),
                                referer = data,
                                quality = Qualities.Unknown.value,
                                type = INFER_TYPE,
                            )
                        )
                    } else {
                        val iframe = listOf("iframeUrl", "embedUrl", "url")
                            .firstNotNullOfOrNull { key -> server.path(key).asText().takeIf(String::isNotBlank) }
                        if (iframe != null) loadExtractor(fixUrl(iframe), data, subtitleCallback, callback)
                    }
                }
            }
        }
        return true
    }
}
