package com.cloudstream.extensions

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class SineWix : MainAPI() {
    override var mainUrl = "https://ydfvfdizipanel.ru/public/api"
    override var name = "SineWix"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/genres/topteen/all" to "Top 10 Listesi",
        "$mainUrl/genres/latestmovies/all" to "Son Eklenen Filmler",
        "$mainUrl/genres/latestseries/all" to "Son Eklenen Diziler",
        "$mainUrl/genres/latestanimes/all" to "Son Eklenen Animeler",
    )

    private val apiKey = "9iQNC5HQwPlaFuJDkhncJ5XTJ8feGXOJatAA"
    private val healthProbeUrl = "$mainUrl/genres/latestmovies/all/$apiKey?page=1"
    private val apiHeaders = mapOf(
        "signature" to (
            "308202c3308201aba0030201020204075cec01300d06092a864886f70d01010b050030123110300e0603550403130753696e65776978301e1" +
                "70d3231303932313233333334395a170d3436303931353233333334395a30123110300e0603550403130753696e6577697830820122300d06092a864" +
                "886f70d01010105000382010f003082010a0282010100b0a2a1bc5c3f16f19c3b2456cfd0a6128ced9f5e2e2c4cca1a100e17b07b86256258f372e76" +
                "a95a17e9e4a1c048e364835723a95e8ef6d5bdfb5694b50277c65a64f7b012fdf164e5dc93629561f6ca29b7dc82ebb3d6f3c8e8fc6795847fe331ad" +
                "4a13ed6c059a83804c43d3747526d769580f3a4153752eb22dac66dd15f1582caa43305dc49f55ac7b1b89013e654d2ca8c94c30956659674cc67325" +
                "6c04208f09118bae14cdd72d78f9ee2aece958084a8c2e315deff45726d4fc1f18ec39569ff1abe4f36a8d01090e5f68c07c28763513b88208bcac1a" +
                "6e1941f6fd8bfdd52f832098ddb2154c8f565bc5d58c7106a19e03787e75c7f34997000e3bcf30203010001a321301f301d0603551d0e04160414b54" +
                "5fc18e74a791d9402b53940ae38b96e9e209c300d06092a864886f70d01010b05000382010100a8a64d9e7c8b5db102af15d3caf94ff8d3e9be9008b" +
                "b0021117ca2f0762e68583354b126a041bb1fb6e6308e421e4b5a71f779cde63e5d2fc5976bff966c3c4034e852c077d8e74458fbae2ec1db74b1f40" +
                "82e188bf8ef7c42a44e3fbfb693bb00ee2a727096b42360ddce1bdcd3536f50c8693bcc62a7b7204bcefe2ecf1f7c820bcd63e1d7a6acc8bf6163086" +
                "915fc5f607cf51bc7a8635f98bb4c65a8f24b7b5a82c7b06868f565cb0d6ac4775c4aac777536ddd1a565f990fd8cbe539185fa7aab610b7855a687a" +
                "00f4e55536d72873444552c50fd10727dbf298a9be6ed6ae62148dd1de365f3729915dd31975e28a472d752ac14db3db548405cc31e1e"
            ),
        "hash256" to "f4d4bc98a3fc4600e7f2c2bab7533f1f03d8a70ff03c256bb11dc57050536bd0",
        "user-agent" to "EasyPlex (Android 13; SM-A546E; samsung; tr)",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val result = app.get("${request.data}/$apiKey?page=$page", headers = apiHeaders)
            .parsedSafe<SineResult>()
        val home = result?.data.orEmpty().mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun SineData.toSearchResponse(): SearchResponse? {
        val itemTitle = title ?: name ?: return null
        val itemType = when (type) {
            "movie" -> TvType.Movie
            "serie" -> TvType.TvSeries
            else -> TvType.Anime
        }
        val href = when (itemType) {
            TvType.Movie -> "$mainUrl/media/detail/$id/$apiKey"
            TvType.TvSeries -> "$mainUrl/series/show/$id/$apiKey"
            else -> "$mainUrl/animes/show/$id/$apiKey"
        }

        return when (itemType) {
            TvType.Movie -> newMovieSearchResponse(itemTitle, href, itemType) {
                posterUrl = posterPath
                vote?.let { score = Score.from10(it) }
            }
            TvType.TvSeries -> newTvSeriesSearchResponse(itemTitle, href, itemType) {
                posterUrl = posterPath
                vote?.let { score = Score.from10(it) }
            }
            else -> newAnimeSearchResponse(itemTitle, href, itemType) {
                posterUrl = posterPath
                vote?.let { score = Score.from10(it) }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        return app.get("$mainUrl/search/$encoded/$apiKey", headers = apiHeaders)
            .parsedSafe<SineSearch>()
            ?.search.orEmpty()
            .mapNotNull { it.toSearchResponse() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? =
        if (url.contains("/media/detail/")) loadMovie(url) else loadSeries(url)

    private suspend fun loadMovie(url: String): LoadResponse? {
        val media = app.get(url, headers = apiHeaders).parsedSafe<SineMovie>() ?: return null
        val stream = media.videos.orEmpty().firstNotNullOfOrNull { it.link } ?: return null
        val title = listOfNotNull(media.originalName, media.title)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" - ")
            .ifBlank { return null }

        return newMovieLoadResponse(title, url, TvType.Movie, stream) {
            posterUrl = media.backdropPath ?: media.posterPath
            plot = media.overview
            year = media.releaseDate?.substringBefore("-")?.toIntOrNull()
            tags = media.genres.orEmpty().mapNotNull { it.name }
            media.vote?.let { score = Score.from10(it) }
            duration = media.runtime?.toIntOrNull()
            addActors(media.cast.orEmpty().mapNotNull { cast ->
                cast.name?.let { Actor(it, cast.profilePath) }
            })
            media.trailer?.takeIf { it.isNotBlank() }?.let {
                addTrailer(if (it.startsWith("http")) it else "https://www.youtube.com/embed/$it")
            }
        }
    }

    private suspend fun loadSeries(url: String): LoadResponse? {
        val media = app.get(url, headers = apiHeaders).parsedSafe<SineSeries>() ?: return null
        val title = listOfNotNull(media.originalName, media.name)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" - ")
            .ifBlank { return null }
        val episodes = media.seasons.orEmpty().flatMap { season ->
            season.episodes.orEmpty().mapNotNull { episode ->
                val stream = episode.videos.orEmpty().firstNotNullOfOrNull { it.link }
                    ?: return@mapNotNull null
                newEpisode(stream) {
                    name = episode.name
                    this.season = season.seasonNumber
                    this.episode = episode.episodeNumber
                    posterUrl = episode.stillPath
                    description = episode.overview
                }
            }
        }
        val type = if (url.contains("/series/show/")) TvType.TvSeries else TvType.Anime

        return newTvSeriesLoadResponse(title, url, type, episodes) {
            posterUrl = media.backdropPath ?: media.posterPath
            plot = media.overview
            year = media.releaseDate?.substringBefore("-")?.toIntOrNull()
            tags = media.genres
            media.vote?.let { score = Score.from10(it) }
            addActors(media.cast.orEmpty().mapNotNull { cast ->
                cast.name?.let { Actor(it, cast.profilePath) }
            })
            media.trailer?.takeIf { it.isNotBlank() }?.let {
                addTrailer(if (it.startsWith("http")) it else "https://www.youtube.com/embed/$it")
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d("SWX", "data = $data")
        if (data.contains("snwaxdop")) {
            callback(
                newExtractorLink(name, name, data, ExtractorLinkType.VIDEO) {
                    quality = Qualities.Unknown.value
                    headers = apiHeaders
                }
            )
        } else {
            loadExtractor(data, "$mainUrl/", subtitleCallback, callback)
        }
        return true
    }
}
