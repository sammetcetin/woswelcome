package com.cloudstream.extensions

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.utils.*

/**
 * fastplay.mom oynaticisi.
 *
 * Zincir: setfilmizle film sayfasi -> admin-ajax get_video_url
 * -> setplay.shop bridge (SPG.cerceve sifreli iframe)
 * -> fastplay.mom/stfplay.php -> window.STF_KOPRU.src icindeki
 * /manifests/.../master.txt HLS manifesti.
 *
 * Manifest istegi X-Sp basligini zorunlu kiliyor (sayfadaki
 * window.SPG_A {sp, spT} degerlerinden uretiliyor). Dogrudan
 * manifest URL'sini dondurmek yeterli degil; ExoPlayer X-Sp
 * gondermedigi icin 404 yerdi. Bu extractor X-Sp'yi hesaplayip
 * linke header olarak ekliyor.
 */
open class FastPlay : ExtractorApi() {
    override val name = "FastPlay"
    override val mainUrl = "https://fastplay.mom"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val pageReferer = referer?.takeIf { it.isNotBlank() } ?: "$mainUrl/"
        val html = app.get(url, referer = pageReferer).text
        val origin = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1)
            ?: mainUrl

        Regex("""\{[^{}]*"file"\s*:\s*"(https:[^"]+?\.vtt[^"]*)"[^{}]*\}""").findAll(html).forEach { match ->
            runCatching {
                val obj = match.value
                val file = Regex(""""file"\s*:\s*"(https:[^"]+?\.vtt[^"]*)"""")
                    .find(obj)?.groupValues?.get(1)?.replace("\\/", "/")
                    ?: return@runCatching
                val label = Regex(""""label"\s*:\s*"((?:\\u[0-9a-fA-F]{4}|[^"\\])*)"""")
                    .find(obj)?.groupValues?.get(1)?.let { unescapeJson(it) }
                    ?.takeIf { it.isNotBlank() } ?: "Türkçe"
                val lang = Regex(""""lang"\s*:\s*"([^"]*)"""")
                    .find(obj)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                    ?: label
                subtitleCallback(SubtitleFile(lang, fixUrl(file)))
            }
        }

        val src = Regex("""src:\s*"([^"]*master[^"]*)"""")
            .find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("FastPlay manifest yolu bulunamadi")
        val manifest = if (src.startsWith("http")) src else origin + src

        val sp = Regex(""""sp"\s*:\s*"([0-9a-fA-F]+)"""").find(html)?.groupValues?.get(1)
        val spT = Regex(""""spT"\s*:\s*(\d+)"""").find(html)?.groupValues?.get(1)
        val headers = mutableMapOf(
            "Referer" to url,
            "Origin" to origin,
        )
        if (!sp.isNullOrBlank() && !spT.isNullOrBlank()) {
            headers["X-Sp"] = buildXSp(sp)
        }

        val manifestBody = try {
            app.get(manifest, referer = url, headers = headers).text
        } catch (_: Exception) {
            throw ErrorLoadingException("FastPlay manifestine erisilemedi")
        }
        if (!manifestBody.contains("#EXTM3U")) {
            throw ErrorLoadingException("FastPlay manifesti gecersiz")
        }

        callback(
            newExtractorLink(name, name, manifest, ExtractorLinkType.M3U8) {
                this.referer = url
                this.headers = headers
                this.quality = Qualities.Unknown.value
            },
        )
    }

    /**
     * Sayfadaki SPG anti-bot scriptiyle ayni X-Sp degerini uretir:
     * n = su anki unix saniye, r = rastgele base36, h = FNV-1a(sp|n|r).
     */
    private fun buildXSp(sp: String): String {
        val now = System.currentTimeMillis() / 1000L
        val rand = Math.floor(Math.random() * 2176782336).toLong().toString(36)
        var hash = 2166136261.toInt()
        for (ch in "$sp|$now|$rand") {
            hash = hash xor ch.code
            hash *= 16777619
        }
        val hex = (hash.toLong() and 0xFFFFFFFFL).toString(16)
        return "$now.$rand.$hex"
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
}
