@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.cloudstream.extensions

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

open class HDPlayerSystem : ExtractorApi() {
    override val name            = "HDPlayerSystem"
    override val mainUrl         = "https://hdplayersystem.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef  = referer ?: ""
        // Site artık /embed/<id> kullanıyor (eski: video/<id> ve ?data=<id>)
        val vidId   = when {
            url.contains("/embed/") -> url.substringAfter("/embed/").substringBefore("?").substringBefore("/").substringBefore("#")
            url.contains("video/")  -> url.substringAfter("video/").substringBefore("?").substringBefore("/").substringBefore("#")
            url.contains("?data=")  -> url.substringAfter("?data=").substringBefore("&")
            url.contains("data=")   -> url.substringAfter("data=").substringBefore("&")
            else                    -> url.substringAfterLast("/").substringBefore("?").substringBefore("#")
        }.trim()
        if (vidId.isBlank()) throw ErrorLoadingException("hdplayersystem: empty video id ($url)")
        // Gömülü host ne ise POST oraya (com/live uyumu)
        val host = when {
            url.contains("hdplayersystem.live") -> "https://hdplayersystem.live"
            url.contains("hdplayersystem.com")  -> "https://hdplayersystem.com"
            else                                -> mainUrl
        }
        val postUrl = "${host}/player/index.php?data=${vidId}&do=getVideo"
        Log.d("Provider_${this.name}", "postUrl » $postUrl")

        val response = app.post(
            postUrl,
            data = mapOf(
                "hash" to vidId,
                "r"    to extRef
            ),
            referer = extRef,
            headers = mapOf(
                "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest"
            )
        )

        val videoResponse = response.parsedSafe<SystemResponse>() ?: throw ErrorLoadingException("failed to parse response")
        val m3uLink       = videoResponse.securedLink

        callback.invoke(
            ExtractorLink(
                source  = this.name,
                name    = this.name,
                url     = m3uLink,
                referer = extRef,
                quality = Qualities.Unknown.value,
                type    = INFER_TYPE
            )
        )
    }

    data class SystemResponse(
        @JsonProperty("hls")         val hls: String,
        @JsonProperty("videoImage")  val videoImage: String? = null,
        @JsonProperty("videoSource") val videoSource: String,
        @JsonProperty("securedLink") val securedLink: String
    )
}
