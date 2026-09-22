@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.cloudstream.extensions

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder

class SetFilmIzle : MainAPI() {
    override var mainUrl = "https://www.setfilmizle.ltd"
    override var name = "SetFilmIzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        mainUrl to "Yeni Eklenenler",
        "$mainUrl/film/" to "Filmler",
        "$mainUrl/dizi/" to "Diziler",
        "$mainUrl/tur/aile/" to "Aile",
        "$mainUrl/tur/aksiyon/" to "Aksiyon",
        "$mainUrl/tur/animasyon/" to "Animasyon",
        "$mainUrl/tur/belgesel/" to "Belgesel",
        "$mainUrl/tur/bilim-kurgu/" to "Bilim-Kurgu",
        "$mainUrl/tur/dram/" to "Dram",
        "$mainUrl/tur/fantastik/" to "Fantastik",
        "$mainUrl/tur/gerilim/" to "Gerilim",
        "$mainUrl/tur/gizem/" to "Gizem",
        "$mainUrl/tur/komedi/" to "Komedi",
        "$mainUrl/tur/korku/" to "Korku",
        "$mainUrl/tur/macera/" to "Macera",
        "$mainUrl/tur/romantik/" to "Romantik",
    )

    private val mapper = jacksonObjectMapper()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val items = app.get(url).document
            .select("a.card-link")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = selectFirst("h2.card-ad, .hcard-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val image = selectFirst("div.poster-art img, div.poster img")
        val poster = fixUrlNull(
            image?.attr("data-src")?.takeIf { it.isNotBlank() } ?: image?.attr("src"),
        )
        val rating = selectFirst("span.badge-imdb")?.text()?.toRatingInt()

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                this.score = rating?.let { Score.from10(it / 10.0) }
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                this.score = rating?.let { Score.from10(it / 10.0) }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val response = app.get(
            "$mainUrl/wp-admin/admin-ajax.php?action=stf_live_search&keyword=$encoded",
            referer = "$mainUrl/",
        )
        val root = runCatching { mapper.readTree(response.text) }.getOrNull() ?: return emptyList()
        val results = mutableListOf<SearchResponse>()
        val fields = root.fields()
        while (fields.hasNext()) {
            val item = fields.next().value
            val title = item.path("title").asText().takeIf { it.isNotBlank() } ?: continue
            val url = item.path("url").asText().takeIf { it.isNotBlank() } ?: continue
            val poster = item.path("img").asText().takeIf { it.isNotBlank() }
            val type = if (url.contains("/dizi/")) TvType.TvSeries else TvType.Movie
            val result = if (type == TvType.TvSeries) {
                newTvSeriesSearchResponse(title, url, type) { posterUrl = poster }
            } else {
                newMovieSearchResponse(title, url, type) { posterUrl = poster }
            }
            results += result
        }
        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.fbox-title span.fbox-title-tx")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property='og:description']")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val year = document.selectFirst("span.fbox-date")?.text()
            ?.let { Regex("""(?:19|20)\d{2}""").find(it)?.value?.toIntOrNull() }
        val duration = Regex(""""duration"\s*:\s*"PT(\d+)M""")
            .find(document.html())?.groupValues?.get(1)?.toIntOrNull()
        val tags = document.select("a[href*='/tur/']")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val rating = document.selectFirst("a[href*='imdb.com']")?.text()?.toRatingInt()
        val actors = document.select("div.oyuncu_kutusu").mapNotNull { actor ->
            val actorName = actor.selectFirst("small, .detail")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            Actor(actorName, fixUrlNull(actor.selectFirst("img")?.attr("src")))
        }
        val trailer = document.selectFirst("button[data-trailer]")?.attr("data-trailer")
            ?.takeIf { it.isNotBlank() }
            ?.let { "https://www.youtube.com/embed/$it" }

        if (url.contains("/dizi/")) {
            val episodePattern = Regex("""-(\d+)-sezon-(\d+)-bolum/?$""")
            val episodes = document.select("a[href*='/bolum/']")
                .mapNotNull { link ->
                    val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                    val match = episodePattern.find(href) ?: return@mapNotNull null
                    val seasonNumber = match.groupValues[1].toIntOrNull()
                    val episodeNumber = match.groupValues[2].toIntOrNull()
                    newEpisode(href) {
                        name = link.selectFirst(".episode-title, .ep-title")?.text()?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?: "${episodeNumber ?: 0}. Bölüm"
                        season = seasonNumber
                        episode = episodeNumber
                    }
                }
                .distinctBy { it.data }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                plot = description
                this.year = year
                this.tags = tags
                this.rating = rating
                this.duration = duration
                addActors(actors)
                addTrailer(trailer)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            plot = description
            this.year = year
            this.tags = tags
            this.rating = rating
            this.duration = duration
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private fun sendMultipartRequest(
        nonce: String,
        postId: String,
        playerName: String,
        partKey: String,
        referer: String,
    ): Response {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("action", "get_video_url")
            .addFormDataPart("nonce", nonce)
            .addFormDataPart("post_id", postId)
            .addFormDataPart("player_name", playerName)
            .addFormDataPart("part_key", partKey)
            .build()
        val request = Request.Builder()
            .url("$mainUrl/wp-admin/admin-ajax.php")
            .header("Referer", referer)
            .header("X-Requested-With", "XMLHttpRequest")
            .post(requestBody)
            .build()
        return OkHttpClient().newCall(request).execute()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data).document
        val player = document.selectFirst("div.fplayer[data-post-id]") ?: return false
        val postId = player.attr("data-post-id").takeIf { it.isNotBlank() } ?: return false
        val nonce = Regex("""nonces:\s*\{video:\s*"([^"]+)"""")
            .find(document.html())?.groupValues?.get(1) ?: return false
        var found = false

        player.select("button.fsrc[data-player-name]").forEach { element ->
            val playerName = element.attr("data-player-name").takeIf { it.isNotBlank() }
                ?: return@forEach
            val partKey = element.attr("data-part-key")
            val response = runCatching {
                sendMultipartRequest(nonce, postId, playerName, partKey, data)
            }.getOrNull() ?: return@forEach
            response.use {
                val payload = runCatching { JSONObject(it.body.string()) }.getOrNull()
                    ?: return@forEach
                val responseData = payload.optJSONObject("data") ?: return@forEach
                val sourceUrl = responseData.optJSONObject("stream")?.optString("url")
                    ?.takeIf { url -> url.isNotBlank() }
                    ?: responseData.optString("url").takeIf { url -> url.isNotBlank() }
                    ?: return@forEach
                loadExtractor(sourceUrl, data, subtitleCallback, callback)
                found = true
            }
        }
        return found
    }
}
