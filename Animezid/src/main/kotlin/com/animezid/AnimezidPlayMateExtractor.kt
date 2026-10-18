package com.animezid

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

/**
 * PlayMate (playmate.to) embed host used by Animezid.
 *
 * The `/embed/<code>` page runs player-core which calls the JSON API:
 *   POST /api/s   body {"c":"<code>","d":"<device>"}
 * and returns `sx` = an HLS master playlist (often `.../master.txt`) which lists
 * every quality. Expanding it through M3u8Helper emits each quality as a source.
 */
class AnimezidPlayMateExtractor : ExtractorApi() {
    override var name = "PlayMate"
    override var mainUrl = "https://playmate.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = url.trim()
        if (embedUrl.isBlank()) return

        val code = embedUrl.substringBefore('?').substringBefore('#')
            .trimEnd('/').substringAfterLast('/').trim()
        if (code.isBlank()) return

        val body = try {
            app.post(
                "$mainUrl/api/s",
                json = mapOf("c" to code, "d" to "desktop"),
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json, text/plain, */*",
                    "Content-Type" to "application/json",
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/",
                ),
                referer = "$mainUrl/"
            ).text
        } catch (_: Exception) {
            return
        }
        if (body.isBlank()) return

        val streamUrl = try {
            JSONObject(body).optString("sx")
        } catch (_: Exception) {
            ""
        }
        if (streamUrl.isBlank()) return

        val linkHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")

        val generated = try {
            M3u8Helper.generateM3u8(name, streamUrl, "$mainUrl/", headers = linkHeaders)
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
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
                this.headers = linkHeaders
            }
        )
    }
}
