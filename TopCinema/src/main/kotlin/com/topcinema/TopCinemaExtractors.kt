package com.topcinema

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

private const val TC_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private val TC_HTML_HEADERS = mapOf(
    "User-Agent" to TC_UA,
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language" to "en-US,en;q=0.9"
)

/**
 * Shared helper for embed hosts that only expose their media URL through an
 * eval-packed / plain script (StreamWish-like, UpDown, OkStream, VidTube, VidHide).
 *
 * The page is fetched, unpacked when needed, and every candidate media URL is
 * harvested (hls master playlists, mp4 and subtitles) so no source/quality is lost.
 */
abstract class TopCinemaBaseExtractor : ExtractorApi() {
    override val requiresReferer = true

    /** Regexes applied to (un)packed HTML, first non empty group wins per family. */
    protected open val patterns: List<Regex> = DEFAULT_PATTERNS

    protected open val subtitlePatterns: List<Regex> = listOf(
        Regex("""https?://[^"'\\\s]+\.vtt[^"'\\\s]*"""),
        Regex("""https?://[^"'\\\s]+\.srt[^"'\\\s]*""")
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val host = mainUrl.removePrefix("http://").removePrefix("https://").trimEnd('/')
        val reqReferer = referer?.takeIf { it.contains(host) } ?: "$mainUrl/"
        val page = try {
            app.get(
                url,
                referer = reqReferer,
                headers = TC_HTML_HEADERS
            ).text
        } catch (t: Throwable) {
            Log.w(name, "fetch failed for $url: $t")
            return
        }

        val unpacked = try {
            JsUnpacker(page).unpack() ?: page
        } catch (t: Throwable) {
            page
        }
        val haystack = if (unpacked === page) page else "$page\n$unpacked"

        subtitlePatterns.flatMap { it.findAll(haystack).map { m -> m.value } }
            .distinct()
            .forEach { vtt ->
                subtitleCallback(SubtitleFile(vtt, "Subtitle"))
            }

        val found = LinkedHashSet<String>()
        for (p in patterns) {
            p.findAll(haystack).forEach { m ->
                val candidate = m.groupValues.drop(1).firstOrNull { it.isNotBlank() && it.contains("://") }
                    ?: m.value
                if (candidate.contains("://") && MEDIA_URL.containsMatchIn(candidate)) {
                    found.add(candidate)
                }
            }
            if (found.isNotEmpty()) break
        }

        if (found.isEmpty()) {
            Log.w(name, "no media url found for $url")
            return
        }

        val base = reqReferer
        for (media in found) {
            val isM3u8 = media.contains(".m3u8", true) || media.contains("master.txt", true)
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = media,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = base
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to TC_UA,
                        "Referer" to base
                    )
                }
            )
        }
    }

    companion object {
        private val MEDIA_URL = Regex(
            """(?i)\.(?:m3u8|mpd|mp4|mkv|mov|flv)(?:[?#]|$)|master\.(?:txt|m3u8)(?:[?#]|$)"""
        )

        private val DEFAULT_PATTERNS = listOf(
            // hls master playlists (vidhide / earnings family: /hls2/<..>/master.txt|.m3u8)
            Regex("""https?://[^"'\\\s]+/hls\d*/[^"'\\\s]+/master\.(?:txt|m3u8)[^"'\\\s]*"""),
            Regex("""https?://[^"'\\\s]+?/master\.(?:m3u8|txt)[^"'\\\s]*"""),
            Regex("""https?://[^"'\\\s]+\.m3u8[^"'\\\s]*"""),
            Regex("""(?:file|src)\s*[:=]\s*["'](https?://[^"']+)["']"""),
            Regex("""https?://[^"'\\\s]+\.mp4[^"'\\\s]*""")
        )
    }
}

/** StreamWish embeds on this site use the streamwish.fun mirror. */
class TCStreamWish : TopCinemaBaseExtractor() {
    override var mainUrl = "https://streamwish.fun"
    override var name = "StreamWish"
}

/** DoodCloud custom domain used by the site. */
class TCDoodstream : DoodLaExtractor() {
    override var mainUrl = "https://d0o0d.com"
    override var name = "Doodstream"
}

class TCStreamTape : StreamTape() {
    override var mainUrl = "https://streamtape.cc"
    override var name = "Streamtape"
}

class TCUqload : TopCinemaBaseExtractor() {
    override var mainUrl = "https://uqload.net"
    override var name = "Uqload"
}

/**
 * VidTube host (down.vidtube.one). The page is eval-packed and exposes an hls/mp4 url.
 */
class TCVidTube : TopCinemaBaseExtractor() {
    override var name = "VidTube"
    override var mainUrl = "https://down.vidtube.one"
}

/** OkStream embed: packed page with `sources:[{file:"<master.m3u8>"}]`. */
class TCOKStream : TopCinemaBaseExtractor() {
    override var name = "OkStream"
    override var mainUrl = "https://okstream.online"
}

/** UpDown embed: packed page with `file:"<video.mp4>"`. */
class TCUpDown : TopCinemaBaseExtractor() {
    override var name = "UpDown"
    override var mainUrl = "https://updown.icu"
}

/** Filelions / EarnVids (vidhide family) embed. */
class TCEarnVids : TopCinemaBaseExtractor() {
    override var name = "Filelions"
    override var mainUrl = "https://earnvids.xyz"
}
