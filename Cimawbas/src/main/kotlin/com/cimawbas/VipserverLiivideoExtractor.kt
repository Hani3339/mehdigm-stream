package com.cimawbas

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.json.JSONObject

class VipserverLiivideoExtractor : ExtractorApi() {
    override val name = "Vipserver LiiVideo"
    override val mainUrl = "https://vipserver.liiivideo.com"
    override val requiresReferer = true



    override fun getExtractorUrl(id: String): String {
        return if (id.startsWith("http")) id else "$mainUrl/$id"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val ref = referer ?: url
        if (url.contains(".m3u8")) {
            val headers = mapOf(
                "Referer" to ref,
                "Origin" to "https://vipserver.liiivideo.com"
            )
            M3u8Helper.generateM3u8(name, url, ref).forEach { link ->
                link.headers = headers
                callback(link)
            }
            return
        }

        val page = try {
            app.get(url, referer = ref).text
        } catch (e: Exception) {
            return
        }

        val m3u8 = extractM3u8(page)
        if (m3u8.isNotEmpty()) {
            val headers = mapOf(
                "Referer" to ref,
                "Origin" to "https://vipserver.liiivideo.com"
            )
            M3u8Helper.generateM3u8(name, m3u8, ref).forEach { link ->
                link.headers = headers
                callback(link)
            }
        }
    }

    private fun extractM3u8(html: String): String {
        val cleaned = html.replace("\\/", "/").replace("\\\"", "\"")
        // try common patterns
        val patterns = listOf(
            Regex("""file\s*["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
            Regex("""hls\s*["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
            Regex("""src\s*["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""")
        )
        for (p in patterns) {
            p.find(cleaned)?.groupValues?.get(1)?.let {
                return if (it.startsWith("//")) "https:$it" else it
            }
        }
        return ""
    }

    companion object {
        private const val TAG = "VipserverLiiVideo"
    }
}
