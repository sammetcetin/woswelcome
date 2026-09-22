package com.cloudstream.extensions

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

open class SetPlay : ExtractorApi() {
    override val name = "SetPlay"
    override val mainUrl = "https://setplay.shop"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val html = app.get(url, referer = referer ?: "$mainUrl/").text
        val match = Regex("""SPG\.cerceve\("b2","([^"]+)","([^"]+)"\)""")
            .find(html) ?: return
        val encrypted = runCatching {
            Base64.decode(match.groupValues[1].replace("\\/", "/"), Base64.DEFAULT)
        }.getOrNull() ?: return
        val key = runCatching {
            Base64.decode(match.groupValues[2], Base64.DEFAULT)
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return
        val decoded = ByteArray(encrypted.size) { index ->
            (encrypted[index].toInt() xor key[index % key.size].toInt()).toByte()
        }.toString(Charsets.UTF_8).substringBefore('|').trim()
        if (!decoded.startsWith("http")) return

        loadExtractor(decoded, url, subtitleCallback, callback)
    }
}
