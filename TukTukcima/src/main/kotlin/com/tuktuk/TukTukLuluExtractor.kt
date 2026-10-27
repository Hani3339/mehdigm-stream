package com.tuktuk

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

/**
 * lulustream (lulustream.com) extractor for TukTuk.
 *
 * The embed page `https://lulustream.com/e/<code>` ships a p.a.c.k.e.r-obfuscated
 * jwplayer setup:
 *
 *   setup({sources:[{file:"https://…/hls2/…/master.m3u8?…"}],…});
 *
 * The built-in LuluStream extractor looks for the player script but does not unpack
 * it, so it finds nothing here. We unpack with [JsUnpacker] and emit every master
 * playlist (expanded to all its qualities) for both watching and downloading.
 */
class TukTukLuluExtractor : ExtractorApi() {
    override var name = "lulustream"
    override var mainUrl = "https://lulustream.com"
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
                    "User-Agent" to BROWSER_UA,
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
            val reqHeaders = mapOf(
                "User-Agent" to BROWSER_UA,
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl
            )
            matches.forEach { match ->
                val link = match.value
                Log.d(name, "Found media link: $link")
                val isM3u8 = link.contains(".m3u8") || link.contains(".txt")
                if (isM3u8) {
                    val generated = try {
                        M3u8Helper.generateM3u8(name, link, "$mainUrl/", headers = reqHeaders)
                    } catch (_: Throwable) {
                        emptyList()
                    }
                    if (generated.isNotEmpty()) {
                        generated.forEach(callback)
                        return@forEach
                    }
                }
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = link,
                    ) {
                        this.referer = "$mainUrl/"
                        quality = Qualities.Unknown.value
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        headers = reqHeaders
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(name, "Error in lulustream extractor", e)
        }
    }

    private companion object {
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
