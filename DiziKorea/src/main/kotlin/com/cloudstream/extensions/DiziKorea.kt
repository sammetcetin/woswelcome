@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.cloudstream.extensions

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DiziKorea : MainAPI() {
    override var mainUrl = "https://dizikorea3.com"
    override var name = "DiziKorea"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie)

    private val healthProbeUrl = "$mainUrl/kore-dizileri-izle-dq1"

    override val mainPage = mainPageOf(
        healthProbeUrl to "Kore Dizileri",
        "$mainUrl/cin-dizileri" to "Çin Dizileri",
        "$mainUrl/japon-dizileri" to "Japon Dizileri",
        "$mainUrl/tayland-dizileri" to "Tayland Dizileri",
        "$mainUrl/tayvan-dizileri" to "Tayvan Dizileri",
        "$mainUrl/filipin-dizileri" to "Filipin Dizileri",
        "$mainUrl/filmler" to "Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/sayfa/$page"
        val items = app.get(url).document
            .select("a.poster-card")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".poster-card-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val href = fixUrlNull(attr("href")) ?: return null
        val image = selectFirst(".poster-card-image img")
        val poster = fixUrlNull(
            image?.attr("data-src")?.takeIf { it.isNotBlank() } ?: image?.attr("src"),
        )

        return if (href.contains("/film/")) {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        val response = app.get(
            "$mainUrl/ara?q=$encodedQuery",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            referer = "$mainUrl/",
        ).parsedSafe<KoreaSearch>() ?: return emptyList()

        if (!response.success) return emptyList()
        return response.items.mapNotNull { item ->
            val title = item.title.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val href = fixUrlNull(item.url) ?: return@mapNotNull null
            val poster = fixUrlNull(item.poster)
            if (item.type.equals("movie", ignoreCase = true) || href.contains("/film/")) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    posterUrl = poster
                    year = item.year
                }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                    posterUrl = poster
                    year = item.year
                }
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val isSeries = url.contains("/dizi/")
        val titleSelector = if (isSeries) ".series-title" else ".watch-title"
        val title = document.selectFirst(titleSelector)?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val poster = fixUrlNull(
            document.selectFirst(".series-hero-poster img, img.sidebar-poster")?.attr("src"),
        )
        val metaText = document.select(
            if (isSeries) ".series-meta" else ".watch-ep-date, .watch-meta-row",
        ).text()
        val year = Regex("""(?:19|20)\d{2}""").find(metaText)?.value?.toIntOrNull()
        val description = document.select(".series-about-preview .series-about-text")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n\n")
            .takeIf { it.isNotEmpty() }
        val genreSelector = if (isSeries) {
            ".series-meta a[href*='/tur/']"
        } else {
            ".watch-meta-row a[href*='/tur/']"
        }
        val tags = document.select(genreSelector)
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val rating = document.selectFirst(".meta-rating")?.text()?.toRatingInt()
        val duration = Regex("""(\d+)\s*dk""", RegexOption.IGNORE_CASE)
            .find(metaText)?.groupValues?.get(1)?.toIntOrNull()
        val trailer = document.selectFirst(".btn-trailer[data-trailer]")
            ?.attr("data-trailer")?.takeIf { it.isNotBlank() }
        val actors = document.select(".series-cast-grid a.cast-card").mapNotNull { actor ->
            val actorName = actor.selectFirst(".cast-name")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val actorPoster = fixUrlNull(actor.selectFirst(".cast-photo img")?.attr("src"))
            Actor(actorName, actorPoster)
        }

        if (isSeries) {
            val episodes = document.select(".episode-list").flatMap { episodeList ->
                val season = episodeList.attr("data-season").toIntOrNull()
                episodeList.select("a.episode-item[href]").mapNotNull { episodeElement ->
                    val episodeUrl = fixUrlNull(episodeElement.attr("href")) ?: return@mapNotNull null
                    val episode = episodeElement.selectFirst(".ep-number")?.text()?.trim()?.toIntOrNull()
                        ?: Regex("""/bolum-(\d+)""").find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull()
                    val episodeName = episodeElement.selectFirst(".ep-title")?.text()?.trim()
                        ?.takeIf { it.isNotEmpty() }
                    newEpisode(episodeUrl) {
                        name = episodeName
                        this.season = season
                        this.episode = episode
                    }
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.rating = rating
                this.duration = duration
                addActors(actors)
                addTrailer(trailer)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            this.rating = rating
            this.duration = duration
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
        val iframes = app.get(data).document
            .select(".player-source iframe[data-src], .player-source iframe[src]")
            .mapNotNull { iframe ->
                val source = iframe.attr("data-src").takeIf { it.isNotBlank() }
                    ?: iframe.attr("src").takeIf { it.isNotBlank() }
                fixUrlNull(source)
            }
            .distinct()

        iframes.forEach { iframe ->
            loadExtractor(iframe, data, subtitleCallback, callback)
        }
        return iframes.isNotEmpty()
    }
}
