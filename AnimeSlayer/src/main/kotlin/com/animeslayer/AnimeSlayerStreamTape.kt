package com.animeslayer

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * StreamTape extractor (port of the `streamtape()` routine in AnimeSlayer's
 * own `ol6.js`). The direct `get_video` URL is split across a hidden `#ideoooolink`
 * div and a `norobotlink` script that carries the `token`.
 */
class AnimeSlayerStreamTape : ExtractorApi() {
    override var name = "StreamTape"
    override var mainUrl = "https://streamtape.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.")
            ?: "streamtape.com"
        val id = url.trimEnd('/').substringAfterLast('/').substringBefore('?')
        if (id.isBlank()) return

        val page = runCatching {
            app.get("https://$host/v/$id", referer = referer, headers = mapOf("User-Agent" to UA)).text
        }.getOrNull() ?: return

        val norobot = Regex("""document\.getElementById\('norobotlink'\)\.innerHTML\s*=\s*(.*?);""")
            .find(page)?.groupValues?.get(1) ?: return
        val token = Regex("""token=([^&'"]+)""").find(norobot)?.groupValues?.get(1) ?: return
        val hidden = Regex("""<div id="ideoooolink"[^>]*>(.*?)</div>""")
            .find(page)?.groupValues?.get(1) ?: return

        val raw = hidden.replace("\\/", "/").trim()
        val base = when {
            raw.contains(host) -> "https://$host" + raw.substringAfter(host)
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http") -> raw
            else -> "https://$host/" + raw.trimStart('/')
        }
        if (base.isBlank()) return
        val finalUrl = if (base.contains("token=")) base
        else if (base.contains("?")) "$base&token=$token&dl=1"
        else "$base?token=$token&dl=1"

        callback(
            newExtractorLink(name, name, finalUrl, ExtractorLinkType.VIDEO) {
                this.referer = referer ?: "https://$host/"
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
