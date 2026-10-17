package com.cimawbas

import android.net.Uri
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper

/**
 * StreamHG (hgcloud.to) extractor for Cimawbas.
 *
 * `hgcloud.to/e/<code>` only serves a JS loader that bounces to the actual player
 * host `vibuxer.com/e/<code>`. That page hides its JWPlayer setup inside a
 * P.A.C.K.E.R.-obfuscated script; once decoded it reads:
 *
 *   var links = {"hls4":"…","hls3":"…","hls2":"…"};
 *   jwplayer("vplayer").setup({ sources:[{ file: links.hls4||links.hls3||links.hls2, type:"hls" }] });
 *
 * The site's own `hls4 || hls3 || hls2` preference is misleading for a scraper: on the
 * current player `hls4` points at a vibuxer `/stream/…` playlist whose segments are plain
 * PNG ad images (tiktokcdn), and `hls3` is frequently geo-blocked (403 / 404 segments),
 * while `hls2` carries the real video (`index-v1-a1.m3u8` with `video/MP2T` segments).
 * We therefore gather every candidate, fetch each master + its first child playlist and
 * pick the first one that is not an ad/image playlist, handing it to [M3u8Helper].
 */
class StreamHgExtractor : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "https://hgcloud.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embed = url.trim()
        val code = embed.trimEnd('/').substringAfterLast('/').substringBefore('?').substringBefore('#')
        if (code.isBlank()) return

        val host = try {
            Uri.parse(embed).host?.lowercase()
        } catch (_: Throwable) {
            null
        }
        val playerUrl = if (host != null && host.contains("vibuxer")) {
            embed
        } else {
            "$PLAYER_HOST/e/$code"
        }

        val html = try {
            app.get(playerUrl, headers = PAGE_HEADERS, referer = referer ?: "$mainUrl/").text
        } catch (e: Throwable) {
            Log.i(TAG, "fetch failed ${playerUrl.take(120)}: $e")
            return
        }

        val decoded = JsPacker.unpack(html)
        val candidates = collectCandidates(decoded).ifEmpty { collectCandidates(html) }
        if (candidates.isEmpty()) return
        val stream = pickWorking(candidates) ?: return
        emitM3u8(stream, callback)
    }

    /**
     * Collects the `hls…` sources, ordered by how likely they are to be the real stream:
     * `hls2` first, then `hls4`, then `hls3` and any other number.
     */
    private fun collectCandidates(text: String?): List<String> {
        if (text == null) return emptyList()
        val numbered = HLS_RE.findAll(text)
            .mapNotNull { m -> m.groupValues[1].toIntOrNull()?.let { it to normalize(m.groupValues[2]) } }
            .toList()

        val out = mutableListOf<String>()
        numbered.sortedBy { hlsPriority(it.first) }.forEach { if (it.second !in out) out.add(it.second) }
        PLAIN_HLS_RE.find(text)?.groupValues?.get(1)?.let { v ->
            normalize(v).let { if (it !in out) out.add(it) }
        }
        return out
    }

    private fun hlsPriority(number: Int): Int = when (number) {
        2 -> 0
        4 -> 1
        3 -> 2
        else -> 10 + number
    }

    /** Returns the first candidate whose master + first child playlist carry real video segments. */
    private suspend fun pickWorking(candidates: List<String>): String? {
        for (candidate in candidates) {
            val master = runCatching { app.get(candidate, headers = PLAY_HEADERS).text }.getOrNull()
                ?: continue
            if (!master.contains("#EXTM3U")) continue

            val firstChild = master.lineSequence().map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            if (firstChild == null) {
                // A media playlist directly: accept it unless its segments are ad images.
                if (!isAdPlaylist(master)) return candidate
                continue
            }

            val childUrl = if (firstChild.startsWith("http")) firstChild
            else candidate.substringBeforeLast('/') + "/" + firstChild
            val child = runCatching { app.get(childUrl, headers = PLAY_HEADERS).text }.getOrNull()
                ?: continue
            val firstSegment = child.lineSequence().map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            if (!isAdPlaylist(firstSegment)) return candidate
        }
        return candidates.firstOrNull()
    }

    /** True when a URL/playlist is a vibuxer ad run (tiktokcdn PNG images) instead of video. */
    private fun isAdPlaylist(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("tiktokcdn") || t.contains("ad-site") ||
            t.contains(".png") || t.contains(".jpg") || t.contains(".jpeg") ||
            t.contains(".gif") || t.contains(".webp") || t.contains(".image")
    }

    private fun normalize(stream: String): String = when {
        stream.startsWith("http") -> stream
        stream.startsWith("//") -> "https:$stream"
        stream.startsWith("/") -> "$PLAYER_HOST$stream"
        else -> "$PLAYER_HOST/$stream"
    }

    private suspend fun emitM3u8(m3u8: String, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf(
            "User-Agent" to BROWSER_UA,
            "Referer" to "$PLAYER_HOST/"
        )
        val generated = try {
            M3u8Helper.generateM3u8(name, m3u8, "$PLAYER_HOST/", headers = headers)
        } catch (_: Throwable) {
            emptyList()
        }
        if (generated.isNotEmpty()) {
            generated.forEach { callback(it) }
            return
        }
        callback(
            ExtractorLink(
                source = m3u8,
                name = name,
                url = m3u8,
                referer = "$PLAYER_HOST/",
                quality = -1,
                headers = headers,
                type = ExtractorLinkType.M3U8
            )
        )
    }

    companion object {
        private const val TAG = "CimawbasStreamHg"
        private const val PLAYER_HOST = "https://vibuxer.com"
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val PAGE_HEADERS = mapOf(
            "User-Agent" to BROWSER_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )

        private val PLAY_HEADERS = mapOf(
            "User-Agent" to BROWSER_UA,
            "Referer" to "$PLAYER_HOST/"
        )

        private val HLS_RE = Regex("""hls(\d+)"\s*:\s*"([^"]+)"""")
        private val PLAIN_HLS_RE = Regex(""""hls"\s*:\s*"([^"]+)"""")
    }
}
