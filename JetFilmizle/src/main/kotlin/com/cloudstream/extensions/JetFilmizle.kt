package com.cloudstream.extensions

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class JetFilmizle : MainAPI() {
    override var mainUrl = "https://jetfilmizle.now"
    override var name = "JetFilmizle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        mainUrl to "Son Filmler",
        "$mainUrl/saglayici/netflix" to "Netflix",
        "$mainUrl/gunun-kesleri" to "Editörün Seçimi",
        "$mainUrl/yerli-filmler" to "Türk Filmleri",
        "$mainUrl/nette-ilkler" to "Nette İlk Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/page/$page"
        val items = app.get(url).document
            .select("div.film-card")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(selectFirst("a[href]")?.attr("href")) ?: return null
        val title = selectFirst(".card-title a")
            ?.text()
            ?.substringBeforeLast(" izle")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val image = selectFirst(".film-poster img")
        val poster = fixUrlNull(
            image?.attr("data-src")?.takeIf { it.isNotBlank() } ?: image?.attr("src"),
        )

        return newMovieSearchResponse(title, href, TvType.Movie) {
            posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Site aramayı GET /arama?q=<sorgu> ile yapar; eski POST uç noktası 404 veriyor.
        val document = app.get(
            "$mainUrl/arama?q=${URLEncoder.encode(query, "UTF-8")}",
            referer = "$mainUrl/",
        ).document
        return document.select("div.film-card").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.film-title")?.ownText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("h1.film-title")?.text()?.substringBefore("(")?.trim()
            ?: return null
        val image = document.selectFirst("img.film-poster")
        val poster = fixUrlNull(
            image?.attr("data-src")?.takeIf { it.isNotBlank() } ?: image?.attr("src"),
        )
        val traktUrl = document.selectFirst("a.trakt")?.attr("href")
        val year = traktUrl?.substringAfterLast("-")?.toIntOrNull()
            ?: Regex("""\b(?:19|20)\d{2}\b""").find(document.text())?.value?.toIntOrNull()
        val description = document.selectFirst("div.description-text")?.text()?.trim()
        val tags = document.select("div.catss a, div.film-categories a")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
        val actors = document.select("div.oyuncu, div.cast-item").mapNotNull { actor ->
            val actorName = actor.selectFirst("div.name, span.actor-name")?.text()?.trim()
                ?: return@mapNotNull null
            val actorImage = actor.selectFirst("img")
            val actorPoster = fixUrlNull(
                actorImage?.attr("data-src")?.takeIf { it.isNotBlank() } ?: actorImage?.attr("src"),
            )
            Actor(actorName, actorPoster)
        }
        val recommendations = document.select("div#benzers article, div#benzers div.film-card")
            .mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/",
        )
        val document = app.get(data, headers = headers).document
        val filmId = document.selectFirst("input[name=film_id]")?.attr("value")
            ?.takeIf { it.isNotBlank() }
            ?: return false
        val maxIndex = document.select(".player-source-btn")
            .mapNotNull { it.attr("data-source-index").toIntOrNull() }
            .maxOrNull()
            ?: 4
        var found = false

        for (playerType in listOf("dublaj", "altyazili")) {
            for (sourceIndex in 0..maxIndex) {
                val response = runCatching {
                    app.post(
                        "$mainUrl/jetplayer",
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to data,
                            "X-Requested-With" to "XMLHttpRequest",
                            "Content-Type" to "application/x-www-form-urlencoded",
                        ),
                        data = mapOf(
                            "film_id" to filmId,
                            "source_index" to sourceIndex.toString(),
                            "player_type" to playerType,
                        ),
                    ).text
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
                val raw = Jsoup.parse(response).selectFirst("iframe[src]")?.attr("src")?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: continue
                val iframe = when {
                    raw.startsWith("//") -> "https:$raw"
                    raw.startsWith("http://") || raw.startsWith("https://") -> raw
                    else -> fixUrl(raw)
                }
                loadExtractor(iframe, data, subtitleCallback, callback)
                found = true
            }
        }
        return found
    }
}
