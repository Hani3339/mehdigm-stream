package com.cimanow

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

internal const val CN_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

internal val CN_HEADERS = mapOf(
    "User-Agent" to CN_UA,
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language" to "ar,en;q=0.9"
)

/**
 * Generic embed resolver for the player hosts used by CimaNow (`web2.cnvids.com`,
 * which the site rewrites to `fr.cimanow.cc`, plus on-site `cimanow.cc` embeds).
 *
 * The embed page is fetched, unpacked when it is eval-packed, and every media URL
 * (hls master playlists, mp4 and subtitles) is harvested so no source is lost.
 */
abstract class CimaNowBaseExtractor : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val ref = referer?.takeIf { it.isNotBlank() } ?: "$mainUrl/"
        val page = runCatching {
            app.get(url, referer = ref, headers = CN_HEADERS).text
        }.getOrNull() ?: return
        emit(page, ref, subtitleCallback, callback)
    }

    protected suspend fun emit(
        page: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val unpacked = try {
            JsUnpacker(page).unpack() ?: page
        } catch (_: Throwable) {
            page
        }
        val haystack = if (unpacked === page) page else "$page\n$unpacked"

        for (p in SUBTITLE_PATTERNS) {
            p.findAll(haystack).map { it.value }.distinct().forEach { vtt ->
                subtitleCallback(newSubtitleFile("Subtitle", vtt) { })
            }
        }

        val found = LinkedHashSet<String>()
        for (p in MEDIA_PATTERNS) {
            p.findAll(haystack).forEach { m ->
                val candidate = m.groupValues.drop(1)
                    .firstOrNull { it.isNotBlank() && it.contains("://") }
                    ?: m.value
                if (candidate.contains("://") && MEDIA_URL.containsMatchIn(candidate)) {
                    found.add(candidate)
                }
            }
            if (found.isNotEmpty()) break
        }

        for (media in found) {
            val isM3u8 = media.contains(".m3u8", true) || media.contains("master.txt", true)
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = media,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to CN_UA,
                        "Referer" to referer
                    )
                }
            )
        }
    }

    companion object {
        private val MEDIA_URL = Regex(
            """(?i)\.(?:m3u8|mpd|mp4|mkv|mov|flv)(?:[?#]|$)|master\.(?:txt|m3u8)(?:[?#]|$)"""
        )

        private val SUBTITLE_PATTERNS = listOf(
            Regex("""https?://[^"'\\\s]+\.vtt[^"'\\\s]*"""),
            Regex("""https?://[^"'\\\s]+\.srt[^"'\\\s]*""")
        )

        private val MEDIA_PATTERNS = listOf(
            Regex("""https?://[^"'\\\s]+/hls\d*/[^"'\\\s]+/master\.(?:txt|m3u8)[^"'\\\s]*"""),
            Regex("""https?://[^"'\\\s]+?/master\.(?:m3u8|txt)[^"'\\\s]*"""),
            Regex("""https?://[^"'\\\s]+\.m3u8[^"'\\\s]*"""),
            Regex("""(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+)["']"""),
            Regex("""https?://[^"'\\\s]+\.mp4[^"'\\\s]*""")
        )
    }
}

/** `web2.cnvids.com` player host (as served in the page before the client-side rewrite). */
class CimaNowCnvids : CimaNowBaseExtractor() {
    override var name = "Cnvids"
    override var mainUrl = "https://cnvids.com"
}

/** `fr.cimanow.cc`, the public name the site rewrites every `web2.cnvids.com` embed to. */
class CimaNowFr : CimaNowBaseExtractor() {
    override var name = "CimaNow"
    override var mainUrl = "https://fr.cimanow.cc"
}

/** On-site embed pages served straight from the provider domain. */
class CimaNowCimaNow : CimaNowBaseExtractor() {
    override var name = "CimaNow"
    override var mainUrl = "https://cimanow.cc"
}
