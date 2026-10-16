package com.cimawbas

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

class VidmolyCustom : ExtractorApi() {
    override val name = "Vidmoly"
    override val mainUrl = "https://vidmoly.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val text = try {
            app.get(url, referer = referer).text
        } catch (t: Throwable) {
            Log.i(TAG, "fetch failed $url: $t")
            return
        }
        val m3u8 = extractM3u8(text)
        if (m3u8.isNotEmpty()) {
            M3u8Helper.generateM3u8(name, m3u8, url).forEach(callback)
        }
    }

    private fun extractM3u8(html: String): String {
        val fixed = html.replace("'", "\"")
        Regex(""""file"\s*:\s*"([^"]*master\.m3u8[^"]*)"""").find(fixed)?.let {
            return it.groupValues[1]
        }
        Regex(""""file"\s*:\s*"([^"]*\.m3u8[^"]*)"""").find(fixed)?.let {
            return it.groupValues[1]
        }
        Regex("""file['"]\s*:\s*['"]([^'"]*master\.m3u8[^'"]*)""").find(html)?.let {
            return it.groupValues[1]
        }
        Regex("""file['"]\s*:\s*['"]([^'"]*\.m3u8[^'"]*)""").find(html)?.let {
            return it.groupValues[1]
        }
        Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").find(html)?.let {
            return it.value
        }
        return ""
    }

    companion object {
        private const val TAG = "CimawbasVidmoly"
    }
}
