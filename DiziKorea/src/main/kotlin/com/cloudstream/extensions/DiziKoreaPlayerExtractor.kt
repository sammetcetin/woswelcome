package com.cloudstream.extensions

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

open class DiziKoreaPlayerExtractor : ExtractorApi() {
    override val name = "DiziKoreaPlayer"
    override val mainUrl = "https://playerdkorea.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val videoId = url.substringAfterLast("/video/").substringBefore('?').trimEnd('/')
        if (videoId.isBlank()) throw ErrorLoadingException("Video kimliği bulunamadı")
        val pageReferer = referer.orEmpty()
        val response = app.post(
            "$mainUrl/player/index.php?data=$videoId&do=getVideo",
            data = mapOf("hash" to videoId, "r" to pageReferer),
            referer = pageReferer,
            headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest",
            ),
        ).parsedSafe<PlayerResponse>()
            ?: throw ErrorLoadingException("Oynatıcı yanıtı ayrıştırılamadı")

        val stream = response.securedLink?.takeIf { it.isNotBlank() }
            ?: response.videoSource?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Video bağlantısı bulunamadı")
        callback(
            newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
                this.referer = url
                quality = Qualities.Unknown.value
            },
        )
    }

    private data class PlayerResponse(
        @JsonProperty("videoSource") val videoSource: String? = null,
        @JsonProperty("securedLink") val securedLink: String? = null,
    )
}
