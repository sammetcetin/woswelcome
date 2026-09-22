@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.cloudstream.extensions

import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class KultFilmler : MainAPI() {
    override var mainUrl = "https://kultfilmler.net"
    override var name = "KultFilmler"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val mapper = jacksonObjectMapper()
    private val healthProbeUrl = "$mainUrl/"

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Son Filmler",
        "$mainUrl/dizi-kategori/mini-dizi-izle/page/" to "Diziler",
        "$mainUrl/category/aile-filmleri-izle/page/" to "Aile",
        "$mainUrl/category/aksiyon-filmleri-izle/page/" to "Aksiyon",
        "$mainUrl/category/animasyon-filmleri-izle/page/" to "Animasyon",
        "$mainUrl/category/belgesel-izle/page/" to "Belgesel",
        "$mainUrl/category/bilim-kurgu-filmleri-izle/page/" to "Bilim Kurgu",
        "$mainUrl/category/biyografi-filmleri-izle/page/" to "Biyografi",
        "$mainUrl/category/dram-filmleri-izle/page/" to "Dram",
        "$mainUrl/category/fantastik-filmleri-izle/page/" to "Fantastik",
        "$mainUrl/category/gerilim-filmleri-izle/page/" to "Gerilim",
        "$mainUrl/category/gizem-filmleri-izle/page/" to "Gizem",
        "$mainUrl/category/kara-filmleri-izle/page/" to "Kara",
        "$mainUrl/category/kisa-film-izle/page/" to "Kısa Metrajlı",
        "$mainUrl/category/komedi-filmleri-izle/page/" to "Komedi",
        "$mainUrl/category/korku-filmleri-izle/page/" to "Korku",
        "$mainUrl/category/macera-filmleri-izle/page/" to "Macera",
        "$mainUrl/category/muzik-filmleri-izle/page/" to "Müzik",
        "$mainUrl/category/polisiye-filmleri-izle/page/" to "Polisiye",
        "$mainUrl/category/politik-filmleri-izle/page/" to "Politik",
        "$mainUrl/category/romantik-filmleri-izle/page/" to "Romantik",
        "$mainUrl/category/savas-filmleri-izle/page/" to "Savaş",
        "$mainUrl/category/spor-filmleri-izle/page/" to "Spor",
        "$mainUrl/category/suc-filmleri-izle/page/" to "Suç",
        "$mainUrl/category/tarih-filmleri-izle/page/" to "Tarih",
        "$mainUrl/category/yerli-filmleri-izle/page/" to "Yerli",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}$page").document
        val home = document.select("a.mcard, a.dcard").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h3")?.text()?.trim() ?: return null
        val href = fixUrlNull(attr("href")) ?: return null
        val image: Element? = selectFirst("img")
        val poster = fixUrlNull(
            image?.attr("src")?.ifBlank { image.attr("data-src") }
        )
        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val document = app.get("$mainUrl/?s=$encoded").document
        return document.select("a.mcard, a.dcard").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val isSeries = url.contains("/dizi/")
        val title = document.selectFirst("h1.vtitle, .hero2 h1, h1")?.text()?.trim()
            ?: return null
        val poster = fixUrlNull(
            document.selectFirst(".top .poster img, .hero2 .backdrop img")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )
        val description = document.selectFirst(".desc, .dsyn")?.text()?.trim()
        val tags = document.select(".info .genres a, .side .gn a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
        val rating = document.selectFirst(".imdb .score")?.text()?.trim()?.toRatingInt()
        val detailRows = document.select(".info .irow, .side .irow")
        val year = detailRows.firstOrNull {
            it.selectFirst(".ilabel, .il")?.text()?.contains("Yıl", ignoreCase = true) == true
        }?.text()?.let { Regex("""(?:19|20)\d{2}""").find(it)?.value?.toIntOrNull() }
        val duration = detailRows.firstOrNull {
            it.selectFirst(".ilabel, .il")?.text()?.contains("Süre", ignoreCase = true) == true
        }?.text()?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
        val actors = document.select(".cast a.cmember, .midcast a.cm").mapNotNull {
            val actorName = it.selectFirst("h5")?.text()?.trim() ?: return@mapNotNull null
            Actor(actorName, fixUrlNull(it.selectFirst("img")?.attr("src")))
        }
        val recommendations = document.select("a.mcard, a.dcard")
            .mapNotNull { it.toSearchResult() }

        if (isSeries) {
            val episodes = document.select("a.ep").mapNotNull {
                val epHref = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val epName = it.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
                val match = Regex("""(\d+)\.\s*Sezon\s+(\d+)\.\s*Bölüm""")
                    .find(epName)
                val hrefMatch = Regex("""(\d+)-sezon-(\d+)-bolum""").find(epHref)
                newEpisode(epHref) {
                    name = epName
                    season = match?.groupValues?.get(1)?.toIntOrNull()
                        ?: hrefMatch?.groupValues?.get(1)?.toIntOrNull()
                    episode = match?.groupValues?.get(2)?.toIntOrNull()
                        ?: hrefMatch?.groupValues?.get(2)?.toIntOrNull()
                        ?: Regex("""^[Bb](\d+)""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.rating = rating
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            this.rating = rating
            this.duration = duration
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
        Log.d("KLT", "data = $data")
        val document = app.get(data).document
        val iframes = linkedSetOf<String>()

        document.select("iframe[src]").forEach {
            fixUrlNull(it.attr("src"))?.let(iframes::add)
        }
        document.selectFirst("script#kf-srcdata")?.data()?.takeIf { it.isNotBlank() }?.let { json ->
            runCatching { mapper.readTree(json) }.getOrNull()?.forEach { source ->
                val embed = source.path("html").asText()
                Jsoup.parseBodyFragment(embed).select("iframe[src]").forEach {
                    fixUrlNull(it.attr("src"))?.let(iframes::add)
                }
            }
        }

        iframes.forEach { iframe ->
            Log.d("KLT", "iframe = $iframe")
            loadExtractor(iframe, data, subtitleCallback, callback)
        }
        return iframes.isNotEmpty()
    }
}
