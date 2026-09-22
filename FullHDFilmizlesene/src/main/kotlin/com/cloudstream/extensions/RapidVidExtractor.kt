@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.cloudstream.extensions

import android.util.Base64
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class RapidVid : ExtractorApi() {
    override val name            = "RapidVid"
    override val mainUrl         = "https://rapidvid.org"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef    = referer?.takeIf { it.isNotBlank() } ?: "$mainUrl/"
        val headers   = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            "Referer" to extRef,
        )
        val videoPage = app.get(url, referer = extRef, headers = headers).text
        val payload   = Regex("""window\._p8\s*=\s*['\"]([^'\"]+)""")
            .find(videoPage)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("RapidVid payload not found")

        val firstStage = String(Base64.decode(payload.reversed(), Base64.DEFAULT), Charsets.ISO_8859_1)
        val key        = "K9L"
        val secondStage = buildString(firstStage.length) {
            firstStage.forEachIndexed { index, char ->
                val shift = key[index % key.length].code % 5 + 1
                append((char.code - shift).toChar())
            }
        }
        val playerData = jacksonObjectMapper().readTree(
            String(Base64.decode(secondStage, Base64.DEFAULT), Charsets.UTF_8)
        )

        playerData.path("ct").forEach { track ->
            val subtitleUrl = track.path("file").asText()
            if (subtitleUrl.isNotBlank()) {
                subtitleCallback(
                    SubtitleFile(
                        lang = track.path("label").asText("Altyazı").trim(),
                        url = subtitleUrl,
                    )
                )
            }
        }

        val streamUrl = playerData.path("cm").asText()
            .ifBlank { playerData.path("tm").asText() }
        if (streamUrl.isBlank()) throw ErrorLoadingException("RapidVid stream not found")

        callback.invoke(
            ExtractorLink(
                source  = this.name,
                name    = this.name,
                url     = streamUrl,
                referer = "$mainUrl/",
                quality = Qualities.Unknown.value,
                isM3u8  = true
            )
        )
    }
}
