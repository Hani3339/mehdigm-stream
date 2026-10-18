package com.animezid

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

/**
 * VidTube (vidtube.one, an XVideoSharing/XFileSharing host) embed used by Animezid.
 *
 * The embed (`/e/<code>.html`) contains a JWPlayer config. The `file` URL is a
 * raw CDN link that must be normalised into a playable URL (see [fixMp4Link]),
 * otherwise the server 403s. Ported from the working docchipl extractor.
 */
class AnimezidVidTubeExtractor : ExtractorApi() {
    override var name = "VidTube"
    override var mainUrl = "https://vidtube.one"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = url.trim()
        if (embedUrl.isBlank()) return

        val page = try {
            app.get(
                embedUrl,
                headers = mapOf("User-Agent" to USER_AGENT),
                referer = "$mainUrl/"
            ).text
        } catch (_: Exception) {
            return
        }
        if (page.isBlank()) return

        val data = if (Regex("""eval\(function\(p,a,c,k,e""").containsMatchIn(page)) {
            JsUnpacker(page).unpack() ?: page
        } else {
            page
        }

        val sourceRegex = Regex("""\{[^{}]*?file\s*:\s*"([^"]+)"[^{}]*?\}""", RegexOption.DOT_MATCHES_ALL)
        val labelRegex = Regex("""label\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)

        var found = false
        for (m in sourceRegex.findAll(data)) {
            val raw = m.groupValues[1].trim()
            if (raw.isBlank()) continue
            val label = labelRegex.find(m.value)?.groupValues?.get(1)

            val streamUrl = fixMp4Link(raw)
            if (streamUrl.isBlank()) continue

            found = true
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = getQualityFromName(label)
                    this.headers = mapOf("User-Agent" to USER_AGENT)
                }
            )
        }

        if (found) return

        // Fallback: plain URL scrape (in case the page exposes the media directly).
        val fallback = listOf(
            Regex("""file\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(data)?.groupValues?.get(1),
            Regex("""https?://[^"'<>\s]+\.m3u8[^"'<>\s]*""").find(data)?.value,
            Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*"(https?://[^"]+)"""", RegexOption.DOT_MATCHES_ALL)
                .find(data)?.groupValues?.get(1)
        ).firstOrNull { !it.isNullOrBlank() } ?: return

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = fallback,
                type = if (fallback.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to USER_AGENT)
            }
        )
    }

    /**
     * The raw XVideoSharing `file` URL carries its auth params as keyless query
     * entries (`?=t&=s&=e&=f`). This rebuilds the query in the order the player
     * expects, appends `i`/`sp`, rewrites the `/.mp4` extension and injects `/v/`.
     */
    private fun fixMp4Link(link: String): String {
        val paramOrder = listOf("t", "s", "e", "f")
        val params = Regex("""[?&]([^=]*)=([^&]*)""").findAll(link)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

        val paramDict = LinkedHashMap<String, String>()
        val extra = LinkedHashMap<String, String>()
        params.forEachIndexed { index, (key, value) ->
            if (key.isEmpty()) {
                if (index < paramOrder.size) paramDict[paramOrder[index]] = value
            } else {
                extra[key] = value
            }
        }

        extra["i"] = "0.0"
        extra["sp"] = "30000"

        val baseUrl = link.substringBefore("?")
        var fixed = baseUrl + "?" + paramOrder
            .filter { paramDict.containsKey(it) }
            .joinToString("&") { "$it=${paramDict[it]}" }
        if (extra.isNotEmpty()) {
            fixed += "&" + extra.entries.joinToString("&") { "${it.key}=${it.value}" }
        }

        val marker = "/.mp4"
        val extIndex = fixed.indexOf(marker)
        if (extIndex >= 0) {
            val filenameEndSlash = fixed.lastIndexOf('/', extIndex)
            val filenamePart = fixed.substring(filenameEndSlash + 1, extIndex)
            if (filenamePart.isNotEmpty()) {
                val extracted = filenamePart.last()
                fixed = fixed.replaceFirst(marker, "/$extracted.mp4")
            }
        }

        val parts = fixed.split("//", limit = 3)
        if (parts.size == 3) {
            fixed = parts[0] + "//" + parts[1] + "/v/" + parts[2]
        }

        return fixed
    }
}
