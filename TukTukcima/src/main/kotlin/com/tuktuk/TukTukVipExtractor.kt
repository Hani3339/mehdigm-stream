package com.tuktuk

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class TukTukVipExtractor : ExtractorApi() {
    override var name = "TukTukVIP"
    override var mainUrl = "https://lolololu.website"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d(name, "Step 1: Fetching page -> $url")
            val response = app.get(
                url,
                referer = referer ?: mainUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            ).text
            val unpackedHtml = JsUnpacker(response).unpack() ?: response
            val regex = Regex("""https://[^"']+?/hls\d+/[^"']+?/master\.(?:txt|m3u8)(?:\?[^"']*)?""")

            val matches = regex.findAll(unpackedHtml).toList()

            if (matches.isEmpty()) {
                Log.w(name, "No media links found in unpacked HTML")
                return
            }
            matches.forEach { match ->
                val link = match.value
                Log.d(name, "Found media link: $link")
                val isM3u8 = link.contains(".m3u8") || link.contains(".txt")

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = link,
                    ) {
                        this.referer = "https://lolololu.website/"
                        quality = Qualities.Unknown.value
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        headers = mapOf(
                            "accept" to "*/*",
                            "origin" to "https://lolololu.website",
                            "sec-fetch-site" to "cross-site",
                            "sec-fetch-mode" to "cors",
                            "sec-fetch-dest" to "empty"
                        )
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(name, "Error in TukTukVIP extractor", e)
        }
    }
}
