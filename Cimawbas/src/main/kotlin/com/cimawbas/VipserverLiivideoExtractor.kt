package com.cimawbas

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

class VipserverLiivideoExtractor : ExtractorApi() {
    override val name = "Vipserver LiiVideo"
    override val mainUrl = "https://vipserver.liiivideo.com"
    override val requiresReferer = true

    override fun getExtractorUrl(id: String): String {
        return "$mainUrl/$id"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val text = try {
            app.get(url, referer = referer ?: mainUrl).text
        } catch (t: Throwable) {
            Log.i(TAG, "fetch failed $url: $t")
            return
        }
        val m3u8 = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").find(text)?.value
            ?: Regex(""""file"\s*:\s*"([^"]*\.m3u8[^"]*)"""").find(text)?.groupValues?.get(1)
            ?: Regex("""'file'\s*:\s*'([^']*\.m3u8[^']*)""").find(text)?.groupValues?.get(1)
            ?: Regex("""src\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(text)?.groupValues?.get(1)
            ?: ""
        if (m3u8.isNotEmpty()) {
            val finalUrl = if (m3u8.startsWith("//")) "https:$m3u8" else m3u8
            M3u8Helper.generateM3u8(name, finalUrl, url).forEach(callback)
            return
        }
    }

    companion object {
        private const val TAG = "VipserverLiivideo"
    }
}
