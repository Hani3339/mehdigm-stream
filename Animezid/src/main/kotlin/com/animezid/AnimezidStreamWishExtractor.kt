package com.animezid

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

/**
 * StreamWish family as served by Animezid, which resolves to mirrors like `vibuxer.com`.
 * Cloudstream's built-in StreamWish extractor only covers streamwish.to / .site, so we cover
 * the mirror domains ourselves.
 *
 * The embed page is a Dean Edwards packed player; after unpacking it exposes an HLS master
 * playlist that is expanded through M3u8Helper so every advertised quality is emitted.
 */
open class AnimezidStreamWishExtractor : ExtractorApi() {
    override var name = "StreamWish"
    override var mainUrl = "https://vibuxer.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = "https://" + url.substringAfter("://").substringBefore("/")

        val page = try {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*",
                    "Referer" to (referer ?: "$origin/")
                ),
                referer = referer ?: "$origin/"
            ).text
        } catch (_: Exception) {
            return
        }
        if (page.isBlank()) return

        val unpacked = JsUnpacker(page).unpack() ?: page
        val texts = listOf(page, unpacked)

        val linkHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Referer" to "$origin/"
        )

        val m3u8 = texts.asSequence()
            .flatMap { Regex("""https?://[^"'<>\s]+\.m3u8[^"'<>\s]*""").findAll(it).map { m -> m.value } }
            .firstOrNull { it.isNotBlank() }
        if (m3u8 != null) {
            val generated = try {
                M3u8Helper.generateM3u8(name, m3u8, "$origin/", headers = linkHeaders)
            } catch (_: Throwable) {
                emptyList()
            }
            if (generated.isNotEmpty()) {
                generated.forEach(callback)
                return
            }
        }

        val mp4 = texts.asSequence()
            .flatMap { Regex("""https?://[^"'<>\s]+\.mp4[^"'<>\s]*""").findAll(it).map { m -> m.value } }
            .firstOrNull { it.isNotBlank() } ?: return

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = mp4,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "$origin/"
                this.quality = Qualities.Unknown.value
                this.headers = linkHeaders
            }
        )
    }
}
