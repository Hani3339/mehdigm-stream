package com.animezid

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class AnimezidUqloadToExtractor : AnimezidUqloadExtractor() {
    override var mainUrl = "https://uqload.to"
}

/**
 * Uqload on the domains Animezid serves (uqload.vc / uqload.to). Cloudstream's built-in Uqload
 * extractor only covers uqload.com / .co / .cx / .bz, so we handle these domains ourselves.
 *
 * The embed page is a Dean Edwards packed player holding an HLS master playlist; it is unpacked
 * and expanded through M3u8Helper so every advertised quality is emitted.
 */
open class AnimezidUqloadExtractor : ExtractorApi() {
    override var name = "Uqload"
    override var mainUrl = "https://uqload.vc"
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

        // Prefer an HLS master playlist (expanded to all qualities).
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

        // Fallback: direct mp4.
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
