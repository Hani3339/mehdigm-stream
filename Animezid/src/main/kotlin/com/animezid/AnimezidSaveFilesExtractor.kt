package com.animezid

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

/**
 * SaveFiles (savefiles.com) host used by Animezid via MegaMax mirrors.
 *
 * MegaMax hands us links such as `https://savefiles.com/<id>`. The public
 * `/e/<id>` page is Cloudflare-protected, but the XFileSharing-style API is not:
 *
 *   POST /dl   op=embed&file_code=<id>&auto=0&referer=
 *   -> sources: [{file:"https://s1.savefiles.com/hls2/.../master.m3u8?...", ...}]
 *
 * The master playlist is expanded through M3u8Helper so every advertised quality
 * is emitted.
 */
class AnimezidSaveFilesExtractor : ExtractorApi() {
    override var name = "SaveFiles"
    override var mainUrl = "https://savefiles.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = url.trim().trimEnd('/')
        if (embedUrl.isBlank()) return

        var fileCode = embedUrl.substringAfterLast("/").substringBefore('?').substringBefore('#')
        // Links may look like /e/<id>, /<id> or /embed-<id>.html.
        if (fileCode.contains("-")) fileCode = fileCode.substringAfterLast('-')
        fileCode = fileCode.substringBefore('.').trim()
        if (fileCode.isBlank()) return

        val host = if (embedUrl.contains("://")) {
            embedUrl.substringAfter("://").substringBefore("/")
        } else {
            mainUrl.substringAfter("://").substringBefore("/")
        }
        val origin = "https://$host"

        val dlText = try {
            app.post(
                "$origin/dl",
                data = mapOf(
                    "op" to "embed",
                    "file_code" to fileCode,
                    "auto" to "0",
                    "referer" to "",
                ),
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$origin/",
                    "Origin" to origin,
                ),
                referer = "$origin/"
            ).text
        } catch (_: Exception) {
            return
        }
        if (dlText.isBlank()) return

        val streamUrl = Regex("""sources:\s*\[(?:{\s*file\s*:)?\s*['"]([^'"]+)['"]""")
            .find(dlText)?.groupValues?.get(1)
            ?: Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""")
                .find(dlText)?.groupValues?.get(1)
            ?: Regex("""file:\s*["']([^"']+\.mp4[^"']*)["']""")
                .find(dlText)?.groupValues?.get(1)
        if (streamUrl.isNullOrBlank()) return

        val linkHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Referer" to "$origin/",
            "Origin" to origin,
        )

        val generated = try {
            M3u8Helper.generateM3u8(name, streamUrl, "$origin/", headers = linkHeaders)
        } catch (_: Throwable) {
            emptyList()
        }
        if (generated.isNotEmpty()) {
            generated.forEach(callback)
            return
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "$origin/"
                this.quality = Qualities.Unknown.value
                this.headers = linkHeaders
            }
        )
    }
}
