package com.animezid

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

/**
 * VidTube (vidtube.one) embed host used by Animezid.
 *
 * It is an XVideoSharing/XFileSharing platform (same engine as StreamRuby):
 * the `/e/<code>.html` page is only a click-to-play shell, the real player is
 * returned by `POST /dl` (op=embed&file_code=..&auto=1). The response holds a
 * packed jwplayer config whose first `file:"..."` is either an HLS master
 * playlist (hls2) or a raw mp4 that needs normalising (see [fixMp4Link]).
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

        val origin = when {
            embedUrl.startsWith("http") ->
                embedUrl.substringBefore("://") + "://" + embedUrl.substringAfter("://").substringBefore("/")
            else -> mainUrl
        }

        var fileCode = embedUrl.substringBefore('?').substringBefore('#')
            .trimEnd('/').substringAfterLast('/').removeSuffix(".html")
        if (fileCode.contains("-")) fileCode = fileCode.substringAfterLast('-')
        fileCode = fileCode.trim()
        if (fileCode.isBlank()) return

        val playerHtml = try {
            app.post(
                "$origin/dl",
                data = mapOf(
                    "op" to "embed",
                    "file_code" to fileCode,
                    "auto" to "1",
                    "referer" to (referer ?: "$origin/"),
                ),
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to embedUrl, "Origin" to origin),
                referer = embedUrl
            ).text
        } catch (_: Exception) {
            return
        }
        if (playerHtml.isBlank()) return

        val data = try {
            JsUnpacker(playerHtml).unpack() ?: playerHtml
        } catch (_: Exception) {
            playerHtml
        }

        val files = Regex("""file\s*:\s*"([^"]+)"""").findAll(data)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() && !it.startsWith("/dl") }
            .toList()
        if (files.isEmpty()) return

        val linkHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to "$origin/", "Origin" to origin)

        for (raw in files) {
            val absolute = if (raw.startsWith("http")) raw else if (raw.startsWith("//")) "https:$raw" else "$origin${if (raw.startsWith("/")) raw else "/$raw"}"

            if (absolute.contains(".m3u8")) {
                val fixed = fixQuery(absolute)
                val generated = try {
                    M3u8Helper.generateM3u8(name, fixed, "$origin/", headers = linkHeaders)
                } catch (_: Throwable) {
                    emptyList()
                }
                if (generated.isNotEmpty()) {
                    generated.forEach(callback)
                } else {
                    callback.invoke(m3u8Link(fixed, linkHeaders))
                }
            } else {
                callback.invoke(videoLink(fixMp4Link(absolute), linkHeaders))
            }
        }
    }

    private suspend fun m3u8Link(url: String, headers: Map<String, String>) = newExtractorLink(
        source = name,
        name = name,
        url = url,
        type = ExtractorLinkType.M3U8
    ) {
        this.referer = "$mainUrl/"
        this.quality = Qualities.Unknown.value
        this.headers = headers
    }

    private suspend fun videoLink(url: String, headers: Map<String, String>) = newExtractorLink(
        source = name,
        name = name,
        url = url,
        type = ExtractorLinkType.VIDEO
    ) {
        this.referer = "$mainUrl/"
        this.quality = Qualities.Unknown.value
        this.headers = headers
    }

    /**
     * The raw XVideoSharing `file` URL carries its auth params as keyless query
     * entries (`?=t&=s&=e&=f`). The CDN only accepts them renamed and ordered as
     * `t/s/e/f`, followed by the other params plus `i`/`sp`. Without this the
     * media server returns 403.
     */
    private fun fixQuery(link: String): String {
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

        val baseUrl = link.substringBefore("?")
        var fixed = baseUrl + "?" + paramOrder
            .filter { paramDict.containsKey(it) }
            .joinToString("&") { "$it=${paramDict[it]}" }
        if (extra.isNotEmpty()) {
            fixed += "&" + extra.entries.joinToString("&") { "${it.key}=${it.value}" }
        }
        return fixed
    }

    /** [fixQuery] plus the mp4-only extension rewrite and `/v/` path injection. */
    private fun fixMp4Link(link: String): String {
        var fixed = fixQuery(link)
        if (!fixed.contains("sp=")) fixed += "&sp=30000"

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
