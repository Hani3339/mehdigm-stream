package com.anime4up

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Dedicated Extractor for Moshahda (moshahda.net / moshahda.co),
 * which is Anime4Up's primary internal streaming and direct download server.
 */
open class Anime4UpMoshahdaExtractor : ExtractorApi() {
    override var name = "Moshahda"
    override var mainUrl = "https://moshahda.net"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = "https://" + url.substringAfter("://").substringBefore("/")

        val html = try {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to (referer ?: "$origin/"),
                    "Accept" to "*/*"
                ),
                referer = referer ?: "$origin/"
            ).text
        } catch (_: Exception) {
            return
        }

        if (html.isBlank()) return

        val unpacked = JsUnpacker(html).unpack() ?: html
        val candidates = listOf(html, unpacked)

        val linkHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$origin/",
            "Accept" to "*/*"
        )

        // 1. Check for HLS Master playlist (.m3u8)
        val m3u8Url = candidates.asSequence()
            .flatMap { Regex("""https?://[^"'<>\s]+\.m3u8[^"'<>\s]*""").findAll(it).map { m -> m.value } }
            .firstOrNull { it.isNotBlank() }

        if (m3u8Url != null) {
            val generated = try {
                M3u8Helper.generateM3u8(name, m3u8Url, "$origin/", headers = linkHeaders)
            } catch (_: Throwable) {
                emptyList()
            }
            if (generated.isNotEmpty()) {
                generated.forEach(callback)
                return
            }
        }

        // 2. Check for direct MP4 sources in jwplayer / video setups
        val mp4Matches = candidates.asSequence()
            .flatMap { Regex("""(https?://[^"'<>\s]+\.mp4[^"'<>\s]*)""").findAll(it).map { m -> m.value } }
            .distinct()
            .toList()

        for (mp4 in mp4Matches) {
            val quality = when {
                mp4.contains("1080") || mp4.contains("download_o") -> Qualities.P1080.value
                mp4.contains("720") || mp4.contains("download_x") -> Qualities.P720.value
                mp4.contains("480") || mp4.contains("download_h") -> Qualities.P480.value
                mp4.contains("360") || mp4.contains("download_n") -> Qualities.P360.value
                mp4.contains("240") || mp4.contains("download_l") -> Qualities.P240.value
                else -> Qualities.Unknown.value
            }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name (Direct MP4)",
                    url = mp4,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$origin/"
                    this.quality = quality
                    this.headers = linkHeaders
                }
            )
        }
    }
}
