@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.cloudstream.extensions

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import java.net.URLEncoder
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup

class FilmMakinesi : MainAPI() {
    override var mainUrl              = "https://filmmakinesi.to"
    override var name                 = "FilmMakinesi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    // ! CloudFlare bypass
    override var sequentialMainPage            = true // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    override var sequentialMainPageDelay       = 50L  // ? 0.05 saniye
    override var sequentialMainPageScrollDelay = 50L  // ? 0.05 saniye

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.text().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/filmler-1/"              to "Son Filmler",
        "${mainUrl}/tur/aksiyon-fmy54y/film/" to "Aksiyon",
        "${mainUrl}/tur/aile-fm2/film/"       to "Aile",
        "${mainUrl}/tur/animasyon-fm2/film/"  to "Animasyon",
        "${mainUrl}/tur/belgesel/film/"       to "Belgesel",
        "${mainUrl}/tur/bilim-kurgu-fm3/film/" to "Bilim Kurgu",
        "${mainUrl}/tur/biyografi/film/"      to "Biyografi",
        "${mainUrl}/tur/dram-fm1/film/"       to "Dram",
        "${mainUrl}/tur/fantastik-fm1/film/"  to "Fantastik",
        "${mainUrl}/tur/gerilim-fm1/film/"    to "Gerilim",
        "${mainUrl}/tur/gizem/film/"          to "Gizem",
        "${mainUrl}/tur/komedi-fm1/film/"     to "Komedi",
        "${mainUrl}/tur/korku-fm2/film/"      to "Korku",
        "${mainUrl}/tur/macera-fm1/film/"     to "Macera",
        "${mainUrl}/tur/muzik/film/"          to "Müzik",
        "${mainUrl}/tur/polisiye/film/"       to "Polisiye",
        "${mainUrl}/tur/romantik-fm1/film/"   to "Romantik",
        "${mainUrl}/tur/savas-fm1/film/"      to "Savaş",
        "${mainUrl}/tur/spor/film/"           to "Spor",
        "${mainUrl}/tur/tarih-fm1/film/"      to "Tarih",
        "${mainUrl}/tur/western-fm1/film/"    to "Western"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base     = request.data.trimEnd('/')
        val url      = if (page == 1) "$base/" else "$base/sayfa/$page/"
        val document = app.get(url, interceptor = interceptor).document
        val home     = document.select("div.film-list a.item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.attr("data-title").ifBlank { this.selectFirst("div.item-footer div.title")?.text() }?.ifBlank { return null } ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img.thumbnail")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded  = URLEncoder.encode(query, "UTF-8")
        val document = app.get("${mainUrl}/arama/?s=${encoded}", interceptor = interceptor).document

        return document.select("div.film-list a.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document

        val title       = document.selectFirst("h1.title")?.text()?.substringBefore(" izle")?.trim()?.ifBlank { return null } ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.cover img.cover-img")?.attr("src"))
        val year        = document.selectFirst("h1.title span.date a")?.text()?.trim()?.toIntOrNull()
        val description = document.selectFirst("div.info-description p")?.text()?.trim()
        val tags        = document.select("div.type a[href*='/tur/']").map { it.text() }
        val rating      = document.selectFirst("div.stars")?.attr("data-star")?.trim()?.toRatingInt()
        val duration    = Regex("""(\d+)""").find(document.selectFirst("div.time")?.text() ?: "")?.groupValues?.get(1)?.toIntOrNull()
        val actors      = document.select("a.cast").mapNotNull {
            val actorName = it.selectFirst("div.cast-name")?.text()?.trim() ?: return@mapNotNull null
            Actor(actorName, fixUrlNull(it.selectFirst("img.cast-img")?.attr("src")))
        }
        val trailerFrame  = document.selectFirst("iframe[data-src*='youtube.com/embed'], iframe[src*='youtube.com/embed']")
        val trailer       = fixUrlNull(trailerFrame?.attr("data-src")?.ifBlank { trailerFrame.attr("src") })

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.rating    = rating
            this.duration  = duration
            addTrailer(trailer)
            addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("FLMM", "data » $data")
        val document = app.get(data, interceptor = interceptor).document
        var found    = false

        suspend fun invokeLink(videoUrl: String, label: String) {
            loadExtractor(videoUrl, "${mainUrl}/", subtitleCallback) { link ->
                found = true
                callback.invoke(
                    ExtractorLink(
                        source  = "$label - ${link.source}",
                        name    = "$label - ${link.name}",
                        url     = link.url,
                        referer = link.referer,
                        quality = link.quality,
                        headers = link.headers,
                        extractorData = link.extractorData,
                        type    = link.type
                    )
                )
            }
        }

        for (part in document.select("div.video-parts a[data-video_url]")) {
            val videoUrl = fixUrlNull(part.attr("data-video_url")) ?: continue
            if (videoUrl.contains("youtube.com")) continue
            val label = part.text().trim().ifBlank { this.name }
            Log.d("FLMM", "part » $label » $videoUrl")

            invokeLink(videoUrl, label)
        }

        if (!found) {
            for (frame in document.select("div#player-section iframe, div.player-section iframe")) {
                val videoUrl = fixUrlNull(frame.attr("data-src").ifBlank { frame.attr("src") }) ?: continue
                if (videoUrl.contains("youtube.com")) continue
                Log.d("FLMM", "iframe fallback » $videoUrl")

                invokeLink(videoUrl, this.name)
            }
        }

        if (!found) {
            for (videoUrl in Regex("""https?://[^\s"'<>]*closeload\.[^\s"'<>]*""").findAll(document.html()).map { it.value }.toSet()) {
                Log.d("FLMM", "regex fallback » $videoUrl")

                invokeLink(fixUrl(videoUrl), this.name)
            }
        }

        return found
    }
}
