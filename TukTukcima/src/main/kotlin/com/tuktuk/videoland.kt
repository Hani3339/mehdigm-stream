package com.tuktuk

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class VideolandExtractor : ExtractorApi() {
    override var name = "Videoland"
    override var mainUrl = "https://videoland.sbs"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = TukTukMediaUtils.originOf(url, mainUrl)
        val pageReferer = referer?.takeIf { it.isNotBlank() } ?: "$origin/"
        try {
            val response = app.get(
                url,
                referer = pageReferer,
                headers = mapOf(
                    "User-Agent" to TukTukMediaUtils.BROWSER_USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            ).text
            val playerHtml = JsUnpacker(response).unpack() ?: response
            val links = TukTukMediaUtils.mediaUrls(url, playerHtml)
            if (links.isEmpty()) {
                Log.w(name, "No media URLs found in unpacked player page: $url")
                return
            }

            val mediaHeaders = mapOf(
                "User-Agent" to TukTukMediaUtils.BROWSER_USER_AGENT,
                "Referer" to pageReferer,
                "Origin" to origin,
                "Accept" to "*/*",
                "Sec-Fetch-Site" to "cross-site",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Dest" to "empty"
            )
            for (link in links) {
                val isHls = TukTukMediaUtils.isHls(link)
                val quality = TukTukMediaUtils.qualityHint(link)
                if (isHls) {
                    val generated = try {
                        M3u8Helper.generateM3u8(
                            name,
                            link,
                            pageReferer,
                            quality = quality.takeIf { it > 0 },
                            headers = mediaHeaders
                        )
                    } catch (e: Exception) {
                        Log.w(name, "Could not expand HLS playlist: $link", e)
                        emptyList()
                    }
                    if (generated.isNotEmpty()) {
                        generated.forEach(callback)
                        continue
                    }
                }

                val type = when {
                    isHls -> ExtractorLinkType.M3U8
                    link.substringBefore('?').endsWith(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                    else -> ExtractorLinkType.VIDEO
                }
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = link
                    ) {
                        this.referer = pageReferer
                        this.quality = quality.takeIf { it > 0 } ?: Qualities.Unknown.value
                        this.type = type
                        this.headers = mediaHeaders
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(name, "Videoland extraction failed for $url", e)
        }
    }
}
