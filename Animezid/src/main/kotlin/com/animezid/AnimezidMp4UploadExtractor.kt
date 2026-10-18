package com.animezid

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

/**
 * MP4Upload (mp4upload.com) host used by Animezid via MegaMax mirrors.
 *
 * MegaMax hands us clean links such as `https://mp4upload.com/<id>` (no `/embed-`
 * prefix). The playable file lives on the embed page:
 *   GET https://www.mp4upload.com/embed-<id>.html
 *   player.src({ type: "video/mp4", src: "https://a4.mp4upload.com:183/d/.../video.mp4" })
 *
 * CloudStream's built-in Mp4Upload only matches `www.mp4upload.com` and its id
 * regex expects an `&mp4upload.com/...` query fragment, so it never selects these
 * links; this extractor mirrors its behaviour for the clean host.
 */
class AnimezidMp4UploadExtractor : ExtractorApi() {
    override var name = "Mp4Upload"
    override var mainUrl = "https://mp4upload.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = url.substringBefore('?').substringBefore('#')
            .trimEnd('/').substringAfterLast('/')
            .removePrefix("embed-").removeSuffix(".html")
        if (id.isBlank()) return

        val embedUrl = "https://www.mp4upload.com/embed-$id.html"
        val body = try {
            app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/",
                ),
                referer = "$mainUrl/"
            ).text
        } catch (_: Exception) {
            return
        }
        if (body.isBlank()) return

        val streamUrl = SRC_REGEX.find(body)?.groupValues?.get(1)
            ?: FALLBACK_REGEX.find(body)?.groupValues?.get(1)
            ?: return
        if (streamUrl.isBlank()) return

        val linkHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to embedUrl)
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = embedUrl
                this.quality = Qualities.Unknown.value
                this.headers = linkHeaders
            }
        )
    }

    companion object {
        private val SRC_REGEX = Regex("""player\.src\([\w\W]*?src:\s*"(.*?)"""")
        private val FALLBACK_REGEX = Regex("""type:\s*"video/mp4"[^}]*?src:\s*"(.*?)"""")
    }
}
