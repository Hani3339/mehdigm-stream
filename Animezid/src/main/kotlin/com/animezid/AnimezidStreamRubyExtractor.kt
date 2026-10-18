package com.animezid

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class AnimezidStreamRubyComExtractor : AnimezidStreamRubyExtractor() {
    override var mainUrl = "https://streamruby.com"
}

/**
 * StreamRuby (rubyvidhub.com / streamruby.com) embed host used by Animezid.
 *
 * Flow:
 *   POST /dl   op=embed&file_code={code}&auto=1&referer=...
 *   decode packed jwplayer config -> sources[0].file = master .m3u8
 *
 * The master playlist is expanded through M3u8Helper so every advertised quality is emitted.
 */
open class AnimezidStreamRubyExtractor : ExtractorApi() {
    override var name = "StreamRuby"
    override var mainUrl = "https://rubyvidhub.com"
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
        // Some links look like /e/prefix-code.html ; keep the trailing 12-char code.
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
                    "auto" to "1",
                    "referer" to (referer ?: embedUrl),
                ),
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to embedUrl),
                referer = embedUrl
            ).text
        } catch (_: Exception) {
            return
        }
        if (dlText.isBlank()) return

        val unpacked = try {
            JsUnpacker(dlText).unpack() ?: dlText
        } catch (_: Exception) {
            dlText
        }

        val streamUrl = Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""")
            .find(unpacked)?.groupValues?.get(1)
            ?: Regex("""file:\s*["']([^"']+\.mp4[^"']*)["']""")
                .find(unpacked)?.groupValues?.get(1)
        if (streamUrl.isNullOrBlank()) return

        val linkHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Referer" to "$origin/",
            "Origin" to origin
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
