@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.cloudstream.extensions

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class DiziMom : MainAPI() {
    override var mainUrl              = "https://www.dizimom.beer"
    override var name                 = "DiziMom"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/tum-bolumler/page/"        to "Son Bölümler",
        "${mainUrl}/yerli-dizi-izle/page/"     to "Yerli Diziler",
        "${mainUrl}/yabanci-dizi-izle/page/"   to "Yabancı Diziler",
        "${mainUrl}/tv-programlari-izle/page/" to "TV Programları",
        // "${mainUrl}/turkce-dublaj-diziler/page/"      to "Dublajlı Diziler",   // ! "Son Bölümler" Ana sayfa yüklenmesini yavaşlattığı için bunlar devre dışı bırakılmıştır..
        // "${mainUrl}/netflix-dizileri-izle/page/"      to "Netflix Dizileri",
        // "${mainUrl}/kore-dizileri-izle/page/"         to "Kore Dizileri",
        // "${mainUrl}/full-hd-hint-dizileri-izle/page/" to "Hint Dizileri",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}/").document
        val home     = if (request.data.contains("/tum-bolumler/")) {
            document.select("div.episode-box").mapNotNull { it.sonBolumler() } 
        } else {
            document.select("div.single-item").mapNotNull { it.diziler() }
        }

        return newHomePageResponse(request.name, home)
    }

    private suspend fun Element.sonBolumler(): SearchResponse? {
        val name      = this.selectFirst("div.episode-name a")?.text()?.substringBefore(" izle") ?: return null
        val title     = name.replace(".Sezon ", "x").replace(".Bölüm", "")

        val epHref   = fixUrlNull(this.selectFirst("div.episode-name a")?.attr("href")) ?: return null
        val epDoc    = app.get(epHref).document
        val href     = epDoc.selectFirst("div#benzerli a")?.attr("href") ?: return null

        val posterUrl = fixUrlNull(this.selectFirst("a img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    private fun Element.diziler(): SearchResponse? {
        val title     = this.selectFirst("div.categorytitle a")?.text()?.substringBefore(" izle") ?: return null
        val href      = fixUrlNull(this.selectFirst("div.categorytitle a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.cat-img img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document

        return document.select("div.single-item").mapNotNull { it.diziler() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("div.title h1")?.text()?.substringBefore(" izle") ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.category_image img")?.attr("src")) ?: return null
        val year        = document.selectXpath("//div[span[contains(text(), 'Yapım Yılı')]]").text().substringAfter("Yapım Yılı : ").trim().toIntOrNull()
        val description = document.selectFirst("div.category_desc")?.text()?.trim()
        val tags        = document.select("div.genres a").mapNotNull { it.text().trim() }
        val rating      = document.selectXpath("//div[span[contains(text(), 'IMDB')]]").text().substringAfter("IMDB : ").trim().toRatingInt()
        val actors      = document.selectXpath("//div[span[contains(text(), 'Oyuncular')]]").text().substringAfter("Oyuncular : ").split(", ").map {
            Actor(it.trim())
        }

        val episodes    = document.select("div.bolumust").mapNotNull {
            val epName    = it.selectFirst("div.baslik")?.text()?.trim() ?: return@mapNotNull null
            val epHref    = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val epEpisode = Regex("""(\d+)\.Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
            val epSeason  = Regex("""(\d+)\.Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            newEpisode(epHref) {
                this.name    = epName.substringBefore(" izle").replace(title, "").trim()
                this.season  = epSeason
                this.episode = epEpisode
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.rating    = rating
            addActors(actors)
        }
    }

    private fun extractMomIframeSrc(doc: org.jsoup.nodes.Document): String? {
        val selectors = listOf(
            "div.video p iframe",
            "div.video iframe",
            "div.video-container iframe",
            "div.dizialani iframe",
            "iframe[data-src]"
        )
        for (sel in selectors) {
            val el = doc.selectFirst(sel) ?: continue
            var src = el.attr("src").takeIf { it.isNotBlank() && !it.contains("about:blank") }
                ?: el.attr("data-src").takeIf { it.isNotBlank() && !it.contains("about:blank") }
                ?: el.attr("data-lazy-src").takeIf { it.isNotBlank() && !it.contains("about:blank") }
                ?: continue
            if (src.startsWith("//")) src = "https:$src"
            if (src.isNotBlank()) return src
        }
        // JSON-LD gömülü oynatıcı (lazy-load sonrası yedek)
        Regex(""""embedUrl"\s*:\s*"([^"]+)"""").find(doc.html())?.groupValues?.get(1)?.let {
            val url = it.replace("\\/", "/").trim()
            if (url.isNotBlank() && !url.contains("about:blank")) return url
        }
        return null
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZM", "data » $data")

        val ua = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36")

        val document = try { app.get(data, headers=ua, referer="${mainUrl}/").document } catch (_: Exception) { return false }

        val iframes = mutableListOf<String>()
        val mainIframe = extractMomIframeSrc(document) ?: return false
        iframes.add(mainIframe)

        // Site artık "div.sources" dönmüyor (boş diziplus_sources); birden çok seçici dene.
        val altLinks = mutableSetOf<String>()
        document.select("div.sources a, div.diziplus_sources a, div.source-list a, div.video-sources a, div.sources option[value]").forEach {
            val href = it.attr("href").takeIf { h -> h.isNotBlank() }
                ?: it.attr("value").takeIf { h -> h.isNotBlank() }
                ?: return@forEach
            fixUrlNull(href)?.let { altLinks.add(it) }
        }

        for (link in altLinks) {
            try {
                val subDocument = app.get(link, headers=ua, referer=data).document
                extractMomIframeSrc(subDocument)?.let { iframes.add(it) }
            } catch (_: Exception) { }
        }

        var anyOk = false
        for (iframe in iframes.distinct()) {
            Log.d("DZM", "iframe » $iframe")
            try {
                if (loadExtractor(fixUrl(iframe), "${mainUrl}/", subtitleCallback, callback)) anyOk = true
            } catch (_: Exception) { }
        }

        return anyOk || iframes.isNotEmpty()
    }
}
