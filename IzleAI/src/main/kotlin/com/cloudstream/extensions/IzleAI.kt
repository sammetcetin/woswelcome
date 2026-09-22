package com.cloudstream.extensions

import android.util.Base64
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class IzleAI : MainAPI() {
    override var mainUrl = "https://selcukflix.com"
    override var name = "SelcukFlix"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/film-izle" to "Filmler",
    )

    private val mapper = jacksonObjectMapper()
    private val payloadSecret = "!!22xx!!90!!"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val items = app.get(request.data).document
            .select("a[href^='/film/'][href$='/izle']")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val href = fixUrlNull(attr("href")) ?: return null
        val poster = fixUrlNull(selectFirst("img[src]")?.attr("src"))
        return newMovieSearchResponse(title, href, TvType.Movie) {
            posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val envelope = app.post(
            "$mainUrl/api/bg/searchContent?searchterm=$encoded",
            headers = mapOf(
                "Accept" to "application/json",
                "X-Requested-With" to "XMLHttpRequest",
            ),
        )
        val encrypted = runCatching { mapper.readTree(envelope.text).path("response").asText() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        val result = decryptPayload(encrypted)?.path("result") ?: return emptyList()

        return result.mapNotNull { item ->
            if (!item.path("used_type").asText().equals("Movies", ignoreCase = true)) {
                return@mapNotNull null
            }
            val slug = item.path("used_slug").asText().takeIf { it.endsWith("/izle") }
                ?: return@mapNotNull null
            val title = item.path("object_name").asText().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val poster = item.path("object_poster_url").asText().takeIf { it.isNotBlank() }
            val rating = item.path("object_related_imdb_point").asDouble(0.0)

            newMovieSearchResponse(title, fixUrl(slug), TvType.Movie) {
                posterUrl = poster
                if (rating > 0) score = Score.from10(rating)
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val payload = decodePage(app.get(url).document) ?: return null
        val item = payload.path("contentItem")
        val title = item.path("original_title").asText().takeIf { it.isNotBlank() }
            ?: item.path("culture_title").asText().takeIf { it.isNotBlank() }
            ?: return null
        val poster = item.path("poster_url").asText().takeIf { it.isNotBlank() }
        val year = item.path("release_year").asInt(0).takeIf { it > 0 }
        val duration = item.path("total_minutes").asInt(0).takeIf { it > 0 }
        val description = item.path("description").asText().takeIf { it.isNotBlank() }
        val tags = item.path("categories").asText()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val rating = item.path("imdb_point").asDouble(0.0)
        val trailer = payload.path("RelatedResults")
            .path("getContentTrailers")
            .path("result")
            .firstOrNull()
            ?.path("raw_url")
            ?.asText()
            ?.takeIf { it.isNotBlank() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            this.duration = duration
            if (rating > 0) score = Score.from10(rating)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val payload = decodePage(app.get(data).document) ?: return false
        val related = payload.path("RelatedResults")
        val fields = related.fields()
        val links = linkedSetOf<String>()

        while (fields.hasNext()) {
            val entry = fields.next()
            if (!entry.key.startsWith("getMoviePartSourcesById_")) continue
            entry.value.path("result").forEach { source ->
                val sourceHtml = source.path("source_content").asText()
                val rawUrl = Jsoup.parseBodyFragment(sourceHtml)
                    .selectFirst("iframe[src]")
                    ?.attr("src")
                    ?.trim()
                    .orEmpty()
                val iframe = when {
                    rawUrl.startsWith("//") -> "https:$rawUrl"
                    rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
                    rawUrl.isNotEmpty() -> fixUrl(rawUrl)
                    else -> null
                }
                if (iframe != null) links += iframe
            }
        }

        links.forEach { iframe ->
            loadExtractor(iframe, data, subtitleCallback, callback)
        }
        return links.isNotEmpty()
    }

    private fun decodePage(document: Document): JsonNode? {
        val script = document.selectFirst("script#__NEXT_DATA__") ?: return null
        val rawJson = script.data().ifBlank { script.html() }
        val encrypted = runCatching {
            mapper.readTree(rawJson)
                .path("props")
                .path("pageProps")
                .path("secureData")
                .asText()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return decryptPayload(encrypted)
    }

    private fun decryptPayload(encrypted: String): JsonNode? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payloadSecret.toByteArray(Charsets.UTF_8))
        val key = Base64.encodeToString(digest, Base64.NO_WRAP)
            .take(32)
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(ByteArray(16)),
        )
        val clear = cipher.doFinal(Base64.decode(encrypted, Base64.DEFAULT))
        mapper.readTree(String(clear, Charsets.UTF_8))
    }.getOrNull()
}
