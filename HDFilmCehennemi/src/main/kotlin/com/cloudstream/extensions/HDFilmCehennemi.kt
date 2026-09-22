@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.cloudstream.extensions

import android.util.Base64
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.Jsoup

private fun caesarShift(input: String, shift: Int): String {
    val out = StringBuilder(input.length)
    for (ch in input) {
        val code = ch.code
        when {
            code in 65..90  -> out.append(((code - 65 + shift) % 26 + 65).toChar())
            code in 97..122 -> out.append(((code - 97 + shift) % 26 + 97).toChar())
            else            -> out.append(ch)
        }
    }
    return out.toString()
}

private fun atobLatin1(data: String): String {
    return String(Base64.decode(data, Base64.DEFAULT), Charsets.ISO_8859_1)
}

/**
 * hdfilmcehennemi.mobi gömü sayfasındaki `var x = fn("...".split("<sep>"))`
 * ifadesini çözer. Fonksiyon/değişken adları ve ayraç istek başına değişir;
 * algoritma (dizi-splice, Caesar, base64, permütasyon, XOR) sabittir.
 */
private fun hdDecode(parts: List<String>): String {
    val list = parts.toMutableList()
    val zLen = list.size - 2
    val rIdx = zLen % 7
    val sIdx = 8 + (zLen % 5)
    val mi = list.removeAt(sIdx)
    val m9 = list.removeAt(rIdx)
    var text = list.joinToString("")
    if (mi.length > 2048) text = text.reversed()

    var o1 = 0
    var n5 = 0
    for ((index, ch) in m9.withIndex()) {
        val code = ch.code
        o1 = (o1 * 37 + code) % 241
        n5 = (n5 + ((code shl 1) xor index)) and 255
    }
    val yv = (o1 * 3 + n5) % 256
    val vf = (n5 % 11) + 5
    var hh = ((n5 * 251 + o1) % 65519) + 1

    for (i in mi.length - 1 downTo 0) {
        when (val marker = mi[i]) {
            '7'  -> text = atobLatin1(text)
            '3'  -> text = text.reversed()
            else -> {
                val shift = (26 - ((marker.code - 96) % 26)) % 26
                text = caesarShift(text, shift)
            }
        }
    }
    if (m9.length > 4096) text = atobLatin1(text)

    val len = text.length
    val perm = IntArray(len)
    for (i in len - 1 downTo 1) {
        hh = (hh * 97 + 41) % 65519
        perm[i] = hh % (i + 1)
    }
    val chars = text.toCharArray()
    for (i in 1 until len) {
        val j = perm[i]
        val tmp = chars[i]
        chars[i] = chars[j]
        chars[j] = tmp
    }
    text = String(chars)

    var key = yv
    val out = StringBuilder(text.length)
    for (ch in text) {
        val code = ch.code
        key = (key * 5 + vf) % 256
        out.append((code xor key).toChar())
        key = (key + code) % 256
    }
    return out.toString()
}

class HDFilmCehennemi : MainAPI() {
    override var mainUrl              = "https://www.hdfilmcehennemi.nl"
    override var name                 = "HDFilmCehennemi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        mainUrl to "Yeni Eklenen Filmler",
        "${mainUrl}/yabancidiziizle-2"                    to "Yeni Eklenen Diziler",
        "${mainUrl}/category/tavsiye-filmler-izle2"       to "Tavsiye Filmler",
        "${mainUrl}/imdb-7-puan-uzeri-filmler"            to "IMDB 7+ Filmler",
        "${mainUrl}/en-cok-yorumlananlar-1"               to "En Çok Yorumlananlar",
        "${mainUrl}/en-cok-begenilen-filmleri-izle"       to "En Çok Beğenilenler",
        "${mainUrl}/tur/aile-filmleri-izleyin-6"          to "Aile Filmleri",
        "${mainUrl}/tur/aksiyon-filmleri-izleyin-3"       to "Aksiyon Filmleri",
        "${mainUrl}/tur/animasyon-filmlerini-izleyin-4"   to "Animasyon Filmleri",
        "${mainUrl}/tur/belgesel-filmlerini-izle-1"       to "Belgesel Filmleri",
        "${mainUrl}/tur/bilim-kurgu-filmlerini-izleyin-2" to "Bilim Kurgu Filmleri",
        "${mainUrl}/tur/komedi-filmlerini-izleyin-1"      to "Komedi Filmleri",
        "${mainUrl}/tur/korku-filmlerini-izle-2/"         to "Korku Filmleri",
        "${mainUrl}/tur/romantik-filmleri-izle-1"         to "Romantik Filmleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document

        val home: List<SearchResponse>?

        home = document.select("div.section-content a.poster").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("strong.poster-title")?.text() ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response      = app.get(
            "${mainUrl}/search?q=${query}",
            headers = mapOf("X-Requested-With" to "fetch")
        ).parsedSafe<Results>() ?: return emptyList()
        val searchResults = mutableListOf<SearchResponse>()

        response.results.forEach { resultHtml ->
            val document = Jsoup.parse(resultHtml)

            val title     = document.selectFirst("h4.title")?.text() ?: return@forEach
            val href      = fixUrlNull(document.selectFirst("a")?.attr("href")) ?: return@forEach
            val posterUrl = fixUrlNull(document.selectFirst("img")?.attr("src")) ?: fixUrlNull(document.selectFirst("img")?.attr("data-src"))

            searchResults.add(
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl?.replace("/thumb/", "/list/") }
            )
        }

        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("h1.section-title")?.text()?.substringBefore(" izle") ?: return null
        val poster      = fixUrlNull(document.select("aside.post-info-poster img.lazyload").lastOrNull()?.attr("data-src"))
        val tags        = document.select("div.post-info-genres a").map { it.text() }
        val year        = document.selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
        val tvType      = if (document.select("div.seasons").isEmpty()) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst("article.post-info-content > p")?.text()?.trim()
        val rating      = document.selectFirst("div.post-info-imdb-rating span")?.text()?.substringBefore("(")?.trim()?.toRatingInt()
        val actors      = document.select("div.post-info-cast a").map {
            Actor(it.selectFirst("strong")!!.text(), it.select("img").attr("data-src"))
        }

        val recommendations = document.select("div.section-slider-container div.slider-slide").mapNotNull {
                val recName      = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
                val recHref      = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src")) ?: fixUrlNull(it.selectFirst("img")?.attr("src"))

                newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                    this.posterUrl = recPosterUrl
                }
            }

        return if (tvType == TvType.TvSeries) {
            val trailer  = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")?.substringAfter("trailer/")?.let { "https://www.youtube.com/embed/$it" }
            val episodes = document.select("div.seasons-tab-content a").mapNotNull {
                val epName    = it.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
                val epHref    = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                val epSeason  = Regex("""(\d+)\. ?Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                newEpisode(epHref) {
                    this.name = epName
                    this.season = epSeason
                    this.episode = epEpisode
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.rating          = rating
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val trailer = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")?.substringAfter("trailer/")?.let { "https://www.youtube.com/embed/$it" }

            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.rating          = rating
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private suspend fun invokeLocalSource(source: String, url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit ) {
        val page = app.get(url, referer = "${mainUrl}/").text

        Regex(""""file":"([^"]+)","kind":"captions","label":"([^"]+)"""").findAll(page).forEach { match ->
            subtitleCallback.invoke(
                SubtitleFile(
                    lang = match.groupValues[2],
                    url  = fixUrl(match.groupValues[1].replace("\\/", "/"))
                )
            )
        }

        val encoded = Regex("""var \w+ = \w+\("([^"]+)"\.split\("([^"]+)"\)\)""").find(page)
            ?: throw ErrorLoadingException("HDCH verisi bulunamadi")
        val m3uLink = hdDecode(encoded.groupValues[1].split(encoded.groupValues[2]))
        Log.d("HDCH", "m3uLink » $m3uLink")

        callback.invoke(
            ExtractorLink(
                source  = source,
                name    = source,
                url     = m3uLink,
                referer = "${mainUrl}/",
                quality = Qualities.Unknown.value,
                isM3u8  = true
            )
        )
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit ): Boolean {
        Log.d("HDCH", "data » $data")
        val document = app.get(data).document

        document.select("div.alternative-links").map { element ->
            element to element.attr("data-lang").uppercase()
        }.forEach { (element, langCode) ->
            element.select("button.alternative-link").map { button ->
                button.text().replace("(HDrip Xbet)", "").trim() + " $langCode" to button.attr("data-video")
            }.forEach { (source, videoID) ->
                val apiGet = app.get(
                    "${mainUrl}/video/$videoID/",
                    headers = mapOf(
                        "Content-Type"     to "application/json",
                        "X-Requested-With" to "fetch"
                    ),
                    referer = data
                ).text

                var iframe = Regex("""data-src=\\"([^"]+)""").find(apiGet)?.groupValues?.get(1)?.replace("\\", "")
                    ?: return@forEach
                if (!iframe.contains("hdfilmcehennemi.mobi") && iframe.contains("?rapidrame_id=")) {
                    iframe = "${mainUrl}/playerr/" + iframe.substringAfter("?rapidrame_id=")
                }

                Log.d("HDCH", "$source » $videoID » $iframe")
                invokeLocalSource(source, iframe, subtitleCallback, callback)
            }
        }

        return true
    }

    data class Results(
        @JsonProperty("results") val results: List<String> = arrayListOf()
    )
}
