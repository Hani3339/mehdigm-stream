package com.topcinema

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

private const val TC_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private val TC_HTML_HEADERS = mapOf(
    "User-Agent" to TC_UA,
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language" to "en-US,en;q=0.9"
)

/** A real embed page: no Cloudflare interstitial, no "embedding disabled" stub. */
private fun tcUsablePage(page: String): Boolean {
    if (page.isBlank() || page.length < 200) return false
    val blocked = listOf(
        "Just a moment",
        "Enable JavaScript and cookies",
        "Attention Required",
        "Checking your browser",
        "Embeds disabled",
        "Video not found"
    )
    return blocked.none { page.contains(it, true) }
}

/**
 * Rotating public HTTP proxy pool used as a last resort for embed hosts that only
 * challenge data centre IPs. Direct fetch is always attempted first.
 */
private object TCProxyPool {
    private const val TAG = "TCEmbed"

    private val SOURCES = listOf(
        "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt",
        "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt"
    )
    private val PROXY_FORMAT = Regex("""\d{1,3}(?:\.\d{1,3}){3}:\d{2,5}""")
    private const val TTL_MS = 15 * 60 * 1000L
    private const val PARALLEL = 10
    private const val CONNECT_TIMEOUT_MS = 7000
    private const val READ_TIMEOUT_MS = 7000

    @Volatile
    private var cached: List<String> = emptyList()

    @Volatile
    private var cachedAt = 0L

    private suspend fun candidates(): List<String> {
        val now = System.currentTimeMillis()
        if (cached.isNotEmpty() && now - cachedAt < TTL_MS) return cached
        val found = linkedSetOf<String>()
        for (source in SOURCES) {
            val text = try {
                app.get(source, headers = mapOf("User-Agent" to TC_UA)).text
            } catch (t: Throwable) {
                Log.w(TAG, "proxy list failed: $t")
                continue
            }
            text.lineSequence()
                .map { it.trim() }
                .filter { PROXY_FORMAT.matches(it) }
                .forEach { found.add(it) }
            if (found.size >= 80) break
        }
        cached = found.toList()
        cachedAt = now
        return cached
    }

    suspend fun fetch(url: String, headers: Map<String, String>): String? {
        val list = candidates()
        if (list.isEmpty()) return null
        return coroutineScope {
            list.shuffled().take(PARALLEL).map { proxy ->
                async { directFetch(proxy, url, headers) }
            }.mapNotNull { it.await() }.firstOrNull()
        }
    }

    private fun directFetch(proxy: String, url: String, headers: Map<String, String>): String? = try {
        val split = proxy.lastIndexOf(':')
        val host = proxy.substring(0, split)
        val port = proxy.substring(split + 1).toInt()
        val conn = URL(url).openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)))
            as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        headers.forEach { (key, value) -> conn.setRequestProperty(key, value) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }
            .orEmpty()
        conn.disconnect()
        if (code == 200 && tcUsablePage(body)) body else null
    } catch (t: Throwable) {
        null
    }
}

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
        val page = fetchPage(url, referer) ?: run {
            Log.w(name, "no usable page for $url")
            return
        }
        emit(page, subtitleCallback, callback)
    }

    /** The embed host itself must be the referer of every media url it serves. */
    protected val mediaReferer: String
        get() = "$mainUrl/"

    protected suspend fun fetchPage(url: String, referer: String?): String? {
        val pageRef = referer?.takeIf { it.isNotBlank() } ?: mediaReferer
        return try {
            app.get(url, referer = pageRef, headers = TC_HTML_HEADERS).text
                .takeIf { tcUsablePage(it) }
        } catch (t: Throwable) {
            Log.w(name, "fetch failed for $url: $t")
            null
        }
    }

    protected suspend fun emit(
        page: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val unpacked = try {
            JsUnpacker(page).unpack() ?: page
        } catch (t: Throwable) {
            page
        }
        val haystack = if (unpacked === page) page else "$page\n$unpacked"

        subtitlePatterns.flatMap { it.findAll(haystack).map { m -> m.value } }
            .distinct()
            .forEach { vtt -> subtitleCallback(SubtitleFile(vtt, "Subtitle")) }

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
            Log.w(name, "no media url found")
            return
        }

        val base = mediaReferer
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

/**
 * VidTube is behind a Cloudflare interstitial for data centre IPs: the page must be
 * requested with the calling page as referer and, when that is still challenged,
 * through a rotating public proxy.
 */
class TCVidTube : TopCinemaBaseExtractor() {
    override var name = "VidTube"
    override var mainUrl = "https://down.vidtube.one"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageRef = referer?.takeIf { it.isNotBlank() } ?: mediaReferer
        val page = fetchPage(url, pageRef)
            ?: TCProxyPool.fetch(
                url,
                TC_HTML_HEADERS + mapOf("Referer" to pageRef)
            )
        if (page == null) {
            Log.w(name, "cloudflare blocked $url (direct and via proxy)")
            return
        }
        emit(page, subtitleCallback, callback)
    }
}

/** StreamWish mirror used by the site. */
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

/** Uqload mirror: packed player exposing an hls master playlist. */
class TCUqload : TopCinemaBaseExtractor() {
    override var mainUrl = "https://uqload.net"
    override var name = "Uqload"
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
