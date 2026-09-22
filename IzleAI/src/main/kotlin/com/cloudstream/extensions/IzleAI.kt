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
        val document = try {
            app.get(data).document
        } catch (_: Exception) {
            return false
        }
        val payload = decodePage(document) ?: return false
        val related = payload.path("RelatedResults")
        val blobs = linkedSetOf<String>()

        val fields = related.fields()
        while (fields.hasNext()) {
            val entry = fields.next()
            if (!entry.key.startsWith("getMoviePartSourcesById_") && entry.key != "getMovieSourcesById") continue
            entry.value.path("result").forEach { source ->
                source.path("source_content").asText()
                    .takeIf { it.isNotBlank() }
                    ?.let { blobs += it }
            }
        }

        // Yedek secici: sifresi cozulmus yukun tamaminda iframe / dogrudan medya tara.
        if (blobs.isEmpty()) {
            val raw = payload.toString()
            Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(raw).forEach { blobs += it.groupValues[1] }
            Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""")
                .findAll(raw).forEach { blobs += it.value }
            Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*""")
                .findAll(raw).forEach { blobs += it.value }
        }

        var emitted = false
        val countingCallback: (ExtractorLink) -> Unit = {
            emitted = true
            callback(it)
        }

        blobs.forEach { blob ->
            try {
                val trimmed = blob.trim()
                val iframe = when {
                    trimmed.startsWith("http", ignoreCase = true) -> trimmed
                    else -> Jsoup.parseBodyFragment(blob)
                        .selectFirst("iframe[src], source[src], video[src]")
                        ?.attr("src")
                        ?.trim()
                        .orEmpty()
                        .let { rawUrl ->
                            when {
                                rawUrl.startsWith("//") -> "https:$rawUrl"
                                rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
                                rawUrl.isNotEmpty() -> fixUrl(rawUrl)
                                else -> null
                            }
                        }
                } ?: return@forEach

                if (iframe.contains("iframe.php", ignoreCase = true)) {
                    if (resolvePichiveIframe(iframe, data, subtitleCallback, countingCallback)) return@forEach
                }
                if (iframe.contains(".m3u8")) {
                    countingCallback(
                        newExtractorLink(name, name, iframe, ExtractorLinkType.M3U8) {
                            this.referer = data
                            this.quality = Qualities.Unknown.value
                        },
                    )
                    return@forEach
                }
                if (iframe.contains(".mp4")) {
                    countingCallback(
                        newExtractorLink(name, name, iframe, ExtractorLinkType.VIDEO) {
                            this.referer = data
                            this.quality = Qualities.Unknown.value
                        },
                    )
                    return@forEach
                }
                loadExtractor(iframe, data, subtitleCallback, countingCallback)
            } catch (_: Exception) {
            }
        }
        return emitted
    }

    /**
     * Pichive/DPlayer ailesi iframe'leri (sn.dplayer*.site, *.pichive.online):
     * iframe HTML'indeki window.openPlayer('<token>', ...) jetonuyla
     * source2.php'den gercek master.m3u8 adresi alinir. Jenerik
     * loadExtractor bu hostlari tanimadigi icin burada elle cozuluyor.
     */
    private suspend fun resolvePichiveIframe(
        iframeUrl: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        try {
            val html = app.get(iframeUrl, referer = pageUrl).text
            val token = Regex("""openPlayer\('([^']{64,})'""")
                .find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                ?: return false

            Regex("""\{[^{}]*"file"\s*:\s*"(https:[^"]+?\.vtt[^"]*)"[^{}]*\}""").findAll(html).forEach { match ->
                try {
                    val obj = match.value
                    val file = Regex(""""file"\s*:\s*"(https:[^"]+?\.vtt[^"]*)"""")
                        .find(obj)?.groupValues?.get(1)?.replace("\\/", "/")
                        ?: return@forEach
                    val lang = Regex(""""lang"\s*:\s*"([^"]*)"""")
                        .find(obj)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                        ?: Regex(""""label"\s*:\s*"((?:\\u[0-9a-fA-F]{4}|[^"\\])*)"""")
                            .find(obj)?.groupValues?.get(1)
                            ?.let { unescapeJson(it) }
                            ?.takeIf { it.isNotBlank() }
                        ?: "Türkçe"
                    subtitleCallback(SubtitleFile(lang, fixUrl(file)))
                } catch (_: Exception) {
                }
            }

            val origin = Regex("""^(https?://[^/]+)""").find(iframeUrl)?.groupValues?.get(1)
                ?: return false
            val apiUrl = "$origin/source2.php?v=" + URLEncoder.encode(token, Charsets.UTF_8.name())
            val root = try {
                mapper.readTree(app.get(apiUrl, referer = iframeUrl).text)
            } catch (_: Exception) {
                return false
            }
            if (!root.path("state").asBoolean(false)) return false

            var found = false
            root.path("playlist").forEach { item ->
                item.path("sources").forEach { source ->
                    val file = source.path("file").asText()
                        .takeIf { it.isNotBlank() } ?: return@forEach
                    val master = fixUrl(file.replace("\\/", "/").replace("m.php", "master.m3u8"))
                    callback(
                        newExtractorLink(name, name, master, ExtractorLinkType.M3U8) {
                            this.referer = iframeUrl
                            this.quality = Qualities.Unknown.value
                        },
                    )
                    found = true
                }
            }
            // Yedek: akan yukte cıplak master/m3u8 adresi varsa onu da dene.
            if (!found) {
                Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").findAll(root.toString()).forEach {
                    callback(
                        newExtractorLink(name, name, fixUrl(it.value), ExtractorLinkType.M3U8) {
                            this.referer = iframeUrl
                            this.quality = Qualities.Unknown.value
                        },
                    )
                    found = true
                }
            }
            return found
        } catch (_: Exception) {
            return false
        }
    }

    private fun unescapeJson(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'u' -> {
                        if (i + 5 < s.length) {
                            val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) out.append(code.toChar()) else out.append(s.substring(i, i + 6))
                            i += 6
                        } else {
                            out.append('u'); i += 2
                        }
                    }
                    '/' -> { out.append('/'); i += 2 }
                    '"' -> { out.append('"'); i += 2 }
                    '\\' -> { out.append('\\'); i += 2 }
                    'n' -> { out.append('\n'); i += 2 }
                    else -> { out.append(s[i + 1]); i += 2 }
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
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
