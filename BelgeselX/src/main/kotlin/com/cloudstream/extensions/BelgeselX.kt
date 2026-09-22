@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.cloudstream.extensions

import java.util.Locale
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class BelgeselX : MainAPI() {
    override var mainUrl        = "https://belgeselx.com"
    override var name           = "BelgeselX"
    override val hasMainPage    = true
    override var lang           = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Documentary)

    override val mainPage = mainPageOf(
        "$mainUrl/konu/turk-tarihi-belgeselleri&page=" to "Türk Tarihi",
        "$mainUrl/konu/tarih-belgeselleri&page=" to "Tarih",
        "$mainUrl/konu/seyehat-belgeselleri&page=" to "Seyahat",
        "$mainUrl/konu/seri-belgeseller&page=" to "Seri",
        "$mainUrl/konu/savas-belgeselleri&page=" to "Savaş",
        "$mainUrl/konu/sanat-belgeselleri&page=" to "Sanat",
        "$mainUrl/konu/psikoloji-belgeselleri&page=" to "Psikoloji",
        "$mainUrl/konu/polisiye-belgeselleri&page=" to "Polisiye",
        "$mainUrl/konu/otomobil-belgeselleri&page=" to "Otomobil",
        "$mainUrl/konu/nazi-belgeselleri&page=" to "Nazi",
        "$mainUrl/konu/muhendislik-belgeselleri&page=" to "Mühendislik",
        "$mainUrl/konu/kultur-din-belgeselleri&page=" to "Kültür Din",
        "$mainUrl/konu/kozmik-belgeseller&page=" to "Kozmik",
        "$mainUrl/konu/hayvan-belgeselleri&page=" to "Hayvan",
        "$mainUrl/konu/eski-tarih-belgeselleri&page=" to "Eski Tarih",
        "$mainUrl/konu/egitim-belgeselleri&page=" to "Eğitim",
        "$mainUrl/konu/dunya-belgeselleri&page=" to "Dünya",
        "$mainUrl/konu/doga-belgeselleri&page=" to "Doğa",
        "$mainUrl/konu/bilim-belgeselleri&page=" to "Bilim",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}$page").document
        val home = document.select("a.px-card").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun String.toTitleCase(): String {
        val locale = Locale("tr", "TR")
        return split(" ").joinToString(" ") { word ->
            word.lowercase(locale).replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(locale) else it.toString()
            }
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("div.px-card-title")?.text()?.trim()?.toTitleCase() ?: return null
        val href = fixUrlNull(attr("href")) ?: return null
        val posterUrl = fixUrlNull(selectFirst("img.px-card-img")?.attr("src"))
        return newTvSeriesSearchResponse(title, href, TvType.Documentary) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cx = "016376594590146270301:iwmy65ijgrm"
        val tokenResponse = app.get("https://cse.google.com/cse.js?cx=$cx")
        val cseLibVersion = Regex("""cselibVersion": "(.*)""").find(tokenResponse.text)?.groupValues?.get(1)
        val cseToken = Regex("""cse_token": "(.*)""").find(tokenResponse.text)?.groupValues?.get(1)
        val response = app.get(
            "https://cse.google.com/cse/element/v1?rsz=filtered_cse&num=100&hl=tr&source=gcsc" +
                "&cselibv=$cseLibVersion&cx=$cx&q=$query&safe=off&cse_tok=$cseToken&oq=$query" +
                "&callback=google.search.cse.api9969&rurl=https%3A%2F%2Fbelgeselx.com%2F"
        )

        val titles = Regex(""""titleNoFormatting": "(.*)""").findAll(response.text).map { it.groupValues[1] }.toList()
        val urls = Regex(""""url": "(.*)""").findAll(response.text).map { it.groupValues[1] }.toList()
        val posterUrls = Regex(""""ogImage": "(.*)""").findAll(response.text).map { it.groupValues[1] }.toList()

        return titles.indices.mapNotNull { index ->
            val url = urls.getOrNull(index)?.takeIf { it.contains("belgeseldizi") } ?: return@mapNotNull null
            val posterUrl = posterUrls.getOrNull(index)
            newTvSeriesSearchResponse(
                titles[index].substringBefore("İzle").trim().toTitleCase(),
                url,
                TvType.Documentary,
            ) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.px-hero-title")?.text()?.trim()?.toTitleCase() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.px-dizi-card-poster img")?.attr("src")) ?: return null
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val tags = document.select("a.px-hero-channel").map { it.text().trim().toTitleCase() }
        val watchPage = fixUrlNull(document.selectFirst("a.px-ep-card")?.attr("href")) ?: return null
        val watchHtml = app.get(watchPage, referer = url).text
        val episodeRegex = Regex(
            """diziGetir\('(\d+)','(\d+)','(\d+)','(\d+)','([^']*)','[^']*','[^']*','(\d+)','(\d+)'[^)]*,'(\d+)'\)"""
        )
        val episodes = episodeRegex.findAll(watchHtml).map { match ->
            val (id, first, second, third, epName, season, episode) = match.destructured
            newEpisode("$watchPage||$id||$first||$second||$third") {
                this.name = epName
                this.season = season.toIntOrNull()
                this.episode = episode.toIntOrNull()
            }
        }.toList()

        return newTvSeriesLoadResponse(title, url, TvType.Documentary, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d("BLX", "data » $data")
        val parts = data.split("||")
        if (parts.size < 5) return false

        val watchPage = parts[0]
        val id = parts[1]
        val sourceMap = mapOf("0" to "new5", "2" to "new1", "5" to "new4", "3" to "new2", "4" to "new3")
        parts.drop(2).take(3).forEachIndexed { index, sourceCode ->
            val sourceName = sourceMap[sourceCode] ?: return@forEachIndexed
            val sourceUrl = "$mainUrl/video/data/$sourceName.php?id=$id&sira=${index + 1}"
            val sourcePage = app.get(sourceUrl, referer = watchPage)

            if (sourceName == "new4") {
                Regex("""file\s*:\s*"([^"]+)"\s*,\s*label\s*:\s*"([^"]+)""").findAll(sourcePage.text).forEach {
                    var sourceLabel = name
                    val videoUrl = it.groupValues[1]
                    var quality = it.groupValues[2]
                    if (quality == "FULL") {
                        quality = "1080p"
                        sourceLabel = "Google"
                    }
                    callback(
                        ExtractorLink(
                            source = sourceLabel,
                            name = sourceLabel,
                            url = videoUrl,
                            referer = sourceUrl,
                            quality = getQualityFromName(quality),
                            type = INFER_TYPE,
                        )
                    )
                }
            } else {
                val iframe = fixUrlNull(sourcePage.document.selectFirst("iframe[src]")?.attr("src"))
                    ?.takeIf { it.isNotBlank() }
                    ?: return@forEachIndexed
                loadExtractor(iframe, sourceUrl, subtitleCallback, callback)
            }
        }
        return true
    }
}
