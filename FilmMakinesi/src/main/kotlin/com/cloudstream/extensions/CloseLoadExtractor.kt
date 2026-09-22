@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.cloudstream.extensions

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

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
 * closeload.filmmakinesi.to gömü sayfasındaki `var m2j = uc90("...".split("#"))`
 * ifadesini çözer. JavaScript `atob` ikili dize döndürdüğü için base64
 * adımları Latin-1 üzerinden taşınır; geri kalanı birebir porttur.
 */
private fun uc90Decode(parts: List<String>): String {
    val list = parts.toMutableList()
    val zLen = list.size - 2
    val r19g = zLen % 7
    val s14k = 8 + (zLen % 5)
    val mi7 = list.removeAt(s14k)
    val m91 = list.removeAt(r19g)
    var text = list.joinToString("")
    if (mi7.length > 2048) text = text.reversed()

    var o1i7g = 0
    var n5v4 = 0
    for ((index, ch) in m91.withIndex()) {
        val code = ch.code
        o1i7g = (o1i7g * 37 + code) % 241
        n5v4 = (n5v4 + ((code shl 1) xor index)) and 255
    }
    val yv279 = (o1i7g * 3 + n5v4) % 256
    val vf1x6 = (n5v4 % 11) + 5
    var h7k5o = ((n5v4 * 251 + o1i7g) % 65519) + 1

    for (i in mi7.length - 1 downTo 0) {
        when (val marker = mi7[i]) {
            '7'  -> text = atobLatin1(text)
            '3'  -> text = text.reversed()
            else -> {
                val shift = (26 - ((marker.code - 96) % 26)) % 26
                text = caesarShift(text, shift)
            }
        }
    }
    if (m91.length > 4096) text = atobLatin1(text)

    val len = text.length
    val perm = IntArray(len)
    for (i in len - 1 downTo 1) {
        h7k5o = (h7k5o * 97 + 41) % 65519
        perm[i] = h7k5o % (i + 1)
    }
    val chars = text.toCharArray()
    for (i in 1 until len) {
        val j = perm[i]
        val tmp = chars[i]
        chars[i] = chars[j]
        chars[j] = tmp
    }
    text = String(chars)

    var key = yv279
    val out = StringBuilder(text.length)
    for (ch in text) {
        val code = ch.code
        key = (key * 5 + vf1x6) % 256
        out.append((code xor key).toChar())
        key = (key + code) % 256
    }
    return out.toString()
}

open class CloseLoad : ExtractorApi() {
    override val name            = "CloseLoad"
    override val mainUrl         = "https://closeload.filmmakinesi.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef = referer ?: ""
        Log.d("Provider_${this.name}", "url » $url")

        val page = app.get(url, referer = extRef).text

        Regex(""""file":"([^"]+)","kind":"captions","label":"([^"]+)"""").findAll(page).forEach { match ->
            val subUrl = fixUrl(match.groupValues[1].replace("\\/", "/"))
            subtitleCallback.invoke(SubtitleFile(lang = match.groupValues[2], url = subUrl))
        }

        val encoded = Regex("""var m2j = uc90\("([^"]+)"\.split\("#"\)\)""").find(page)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("CloseLoad verisi bulunamadi")
        val m3uLink = uc90Decode(encoded.split("#"))
        Log.d("Provider_${this.name}", "m3uLink » $m3uLink")

        callback.invoke(
            ExtractorLink(
                source  = this.name,
                name    = this.name,
                url     = m3uLink,
                referer = "${mainUrl}/",
                quality = Qualities.Unknown.value,
                isM3u8  = true
            )
        )
    }
}
