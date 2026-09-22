@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.cloudstream.extensions

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class SinemaCX : MainAPI() {
    override var mainUrl = "https://sinemacc.com"
    override var name = "SinemaCX"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        mainUrl to "Son Eklenen Filmler",
        "$mainUrl/tur/aile-filmleri/" to "Aile Filmleri",
        "$mainUrl/tur/aksiyon-filmleri/" to "Aksiyon Filmleri",
        "$mainUrl/tur/animasyon-filmleri/" to "Animasyon Filmleri",
        "$mainUrl/tur/belgesel/" to "Belgesel Filmleri",
        "$mainUrl/tur/bilim-kurgu-filmleri/" to "Bilim Kurgu Filmleri",
        "$mainUrl/tur/dram-filmleri/" to "Dram Filmleri",
        "$mainUrl/tur/fantastik-filmler/" to "Fantastik Filmler",
        "$mainUrl/tur/gerilim-filmleri/" to "Gerilim Filmleri",
        "$mainUrl/tur/gizem-filmleri/" to "Gizem Filmleri",
        "$mainUrl/tur/komedi-filmleri/" to "Komedi Filmleri",
        "$mainUrl/tur/korku-filmleri/" to "Korku Filmleri",
        "$mainUrl/tur/macera-filmleri/" to "Macera Filmleri",
        "$mainUrl/tur/romantik-filmler/" to "Romantik Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val items = app.get(url).document
            .select("div.film_kutusu")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("span.title span.text")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val href = fixUrlNull(selectFirst("a[href]")?.attr("href")) ?: return null
        val image = selectFirst("span.image img")
        val poster = fixUrlNull(
            image?.attr("data-src")?.takeIf { it.isNotBlank() } ?: image?.attr("src"),
        )
        val rating = Regex("""\d+(?:[.,]\d+)?""")
            .find(selectFirst("span.imdb")?.text().orEmpty())
            ?.value
            ?.replace(',', '.')
            ?.toDoubleOrNull()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            posterUrl = poster
            if (rating != null) score = Score.from10(rating)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post(
            "$mainUrl/arama/",
            data = mapOf(
                "action" to "ajax_search",
                "arama_kelime" to query,
            ),
            referer = "$mainUrl/",
        ).document

        return document.select("a.arama_s").mapNotNull { item ->
            val title = item.selectFirst("span.detail span.title")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val href = fixUrlNull(item.attr("href")) ?: return@mapNotNull null
            val poster = fixUrlNull(item.selectFirst("span.images img")?.attr("src"))
            val rating = Regex("""\d+(?:[.,]\d+)?""")
                .find(item.selectFirst("span.imdb")?.text().orEmpty())
                ?.value
                ?.replace(',', '.')
                ?.toDoubleOrNull()
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                if (rating != null) score = Score.from10(rating)
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.baslik")?.text()
            ?.substringBeforeLast(" İzle")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
                ?.substringBeforeLast(" İzle")
                ?.trim()
            ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property='og:description']")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val year = Regex("""(?:19|20)\d{2}""").find(url)?.value?.toIntOrNull()
            ?: Regex(""""datePublished"\s*:\s*"((?:19|20)\d{2})""")
                .find(document.html())?.groupValues?.get(1)?.toIntOrNull()
        val duration = Regex(""""duration"\s*:\s*"PT(\d+)M""")
            .find(document.html())?.groupValues?.get(1)?.toIntOrNull()
        val rating = document.selectFirst("a.imdb_link span")?.text()?.toRatingInt()
        val tags = document.select("a[href*='/tur/']")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val actors = document.select("div.oyuncu_kutusu").mapNotNull { actor ->
            val actorName = actor.selectFirst("small")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val actorPoster = fixUrlNull(actor.selectFirst("img")?.attr("src"))
            Actor(actorName, actorPoster)
        }
        val trailer = Regex(""""embedUrl"\s*:\s*"([^"]+)""")
            .find(document.html())?.groupValues?.get(1)
        val recommendations = document.select("div.izle_onerilen div.film_kutusu")
            .mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            this.rating = rating
            this.duration = duration
            this.recommendations = recommendations
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
        val document = app.get(data).document
        val encoded = document.selectFirst("iframe#video_playeriframe[data-vsrc]")
            ?.attr("data-vsrc")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return false
        val iframe = runCatching {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.startsWith("http") } ?: fixUrlNull(encoded) ?: return false

        val iframeSource = app.get(iframe, referer = "$mainUrl/").text
        Regex("""playerjsSubtitle\s*=\s*"(.+?)"""")
            .find(iframeSource)
            ?.groupValues
            ?.get(1)
            ?.let { subtitleSection ->
                Regex("""\[(.*?)](https?://[^\s",]+)""")
                    .findAll(subtitleSection)
                    .forEach { match ->
                        subtitleCallback(
                            SubtitleFile(match.groupValues[1], fixUrl(match.groupValues[2])),
                        )
                    }
            }

        if (!iframe.contains("player.filmizle.in")) {
            loadExtractor(iframe, data, subtitleCallback, callback)
            return true
        }

        val playerBase = Regex("""https?://[^/]+""").find(iframe)?.value ?: return false
        val videoId = iframe.substringBefore('?').trimEnd('/').substringAfterLast('/')
        val securedLink = app.post(
            "$playerBase/player/index.php?data=$videoId&do=getVideo",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            referer = data,
        ).parsedSafe<Panel>()?.securedLink ?: return false

        callback(
            ExtractorLink(
                source = name,
                name = name,
                url = securedLink,
                referer = iframe,
                quality = Qualities.Unknown.value,
                isM3u8 = true,
            ),
        )
        return true
    }

    data class Panel(
        @JsonProperty("securedLink") val securedLink: String? = null,
    )
}
