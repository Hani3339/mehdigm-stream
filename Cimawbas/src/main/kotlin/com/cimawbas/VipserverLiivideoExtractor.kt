package com.cimawbas

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Extractor for vipserver.liiivideo.com (LiiiVideo / Xvideosharing clone).
 *
 * The embed page (`https://vipserver.liiivideo.com/embed-<code>.html`) inlines a
 * P.A.C.K.E.R. obfuscated Playerjs setup that contains the real playlist under the
 * `file` key, e.g.
 *
 *   file:"https://<sub>.visitmycityfor365days.cc/hls2/..,/master.m3u8?t=..&s=.."
 *
 * The playlist is tokenised but requires no headers, so after unpacking we hand the
 * master playlist to [M3u8Helper] which expands the L/N/H variants.
 */
class VipserverLiivideoExtractor : ExtractorApi() {
    override val name = "Vipserver LiiVideo"
    override val mainUrl = "https://vipserver.liiivideo.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        resolve(url, referer, callback)
    }

    /** Fetches the embed page, unpacks the Playerjs setup and emits the playlist. */
    suspend fun resolve(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = embedUrl(url)
        val page = try {
            app.get(
                pageUrl,
                referer = referer ?: "$mainUrl/",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept-Language" to "en-US,en;q=0.9"
                )
            ).text
        } catch (t: Throwable) {
            Log.i(TAG, "fetch failed $pageUrl: $t")
            return false
        }

        val decoded = JsPacker.unpack(page) ?: page
        val file = extractFile(decoded.ifBlank { page })
        if (file.isBlank()) {
            Log.i(TAG, "no playlist found on $pageUrl")
            return false
        }

        val embedOrigin = "https://vipserver.liiivideo.com"
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$embedOrigin/",
            "Origin" to embedOrigin
        )

        if (file.contains(".m3u8")) {
            val generated = runCatching {
                M3u8Helper.generateM3u8(name, file, "$embedOrigin/")
            }.getOrNull()
            if (!generated.isNullOrEmpty()) {
                generated.forEach {
                    it.headers = headers.toMutableMap()
                    callback(it)
                }
                return true
            }
            callback(
                newExtractorLink(name, name, file, ExtractorLinkType.M3U8) {
                    this.referer = "$embedOrigin/"
                    this.headers = headers.toMutableMap()
                }
            )
        } else {
            callback(
                newExtractorLink(name, name, file, ExtractorLinkType.VIDEO) {
                    this.referer = "$embedOrigin/"
                    this.headers = headers.toMutableMap()
                }
            )
        }
        return true
    }

    /** Normalises a watch-page or embed URL into the canonical `embed-<code>.html` URL. */
    private fun embedUrl(url: String): String {
        if (url.contains("/embed-")) return url
        val code = Regex("""([A-Za-z0-9]{6,20})(?:\.html)?(?:[?#].*)?$""")
            .find(url.trimEnd('/'))?.groupValues?.get(1)
        return if (code.isNullOrBlank()) url else "$mainUrl/embed-$code.html"
    }

    /** Pulls the direct media URL out of the decoded Playerjs setup / `sources` array. */
    private fun extractFile(html: String): String {
        val text = html.replace("\\/", "/").replace("\\\"", "\"")
        val patterns = listOf(
            Regex("""sources\s*:\s*\[\s*\{[^}]*?file\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
            Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
            Regex("""["']file["']\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
            Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""")
        )
        for (p in patterns) {
            p.find(text)?.groupValues?.get(1)?.let { raw ->
                val value = raw.trim().trimEnd(',')
                if (value.contains(".m3u8")) return if (value.startsWith("//")) "https:$value" else value
            }
        }
        return ""
    }

    companion object {
        private const val TAG = "CimawbasVipserver"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    }
}
