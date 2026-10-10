package com.animeslayer

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * MediaFire extractor (port of the `Mediafire()` routine in AnimeSlayer's own
 * `ol6.js`). Resolves the real `download####.mediafire.com/...mp4` file from the
 * `data-scrambled-url` base64 payload, falling back to the plain `download`
 * anchor used by older layouts.
 */
class AnimeSlayerMediafire : ExtractorApi() {
    override var name = "MediaFire"
    override var mainUrl = "https://www.mediafire.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val page = runCatching {
            app.get(url, referer = referer, headers = mapOf("User-Agent" to UA)).text
        }.getOrNull() ?: return

        val direct = Regex("""data-scrambled-url="([^"]+)"""").find(page)?.groupValues?.get(1)
            ?.let { runCatching { String(Base64.decode(it, Base64.DEFAULT)) }.getOrNull() }
            ?.takeIf { it.startsWith("http") }
            ?: Regex("""https://download\d+\.mediafire\.com/[^"'\s]+?\.(?:mp4|mkv|webm)[^"'\s]*""")
                .find(page)?.value

        if (direct.isNullOrBlank()) return

        callback(
            newExtractorLink(name, name, direct, ExtractorLinkType.VIDEO) {
                this.referer = referer ?: mainUrl
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to UA)
            }
        )
    }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    }
}
