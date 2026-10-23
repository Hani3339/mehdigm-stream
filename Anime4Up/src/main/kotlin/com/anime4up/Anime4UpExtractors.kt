package com.anime4up

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.parser.Parser
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

// =========================================================================
// Shared helpers & smart dispatcher for Watch + Download servers
// =========================================================================

internal object Anime4UpExtractorUtils {
    const val UA = Anime4UpNetwork.DESKTOP_UA

    fun parseQualityValue(hint: String?, urlOrName: String = ""): Int {
        val combined = "${hint.orEmpty()} $urlOrName".uppercase()
        return when {
            combined.contains("2160") || combined.contains("4K") || combined.contains("UHD") -> Qualities.P2160.value
            combined.contains("1080") || Regex("""\bFHD\b""").containsMatchIn(combined) -> Qualities.P1080.value
            combined.contains("720") || Regex("""\bHD\b""").containsMatchIn(combined) -> Qualities.P720.value
            combined.contains("480") || Regex("""\bSD\b""").containsMatchIn(combined) -> Qualities.P480.value
            combined.contains("360") -> Qualities.P360.value
            combined.contains("240") -> Qualities.P240.value
            else -> getQualityFromName(hint.orEmpty())
        }
    }

    fun formatQualityTag(qualityValue: Int): String = when (qualityValue) {
        Qualities.P2160.value -> "2160p"
        Qualities.P1080.value -> "1080p"
        Qualities.P720.value -> "720p"
        Qualities.P480.value -> "480p"
        Qualities.P360.value -> "360p"
        Qualities.P240.value -> "240p"
        else -> ""
    }

    fun originOf(url: String): String? = runCatching {
        val u = URI(url)
        if (u.host.isNullOrBlank()) null else "${u.scheme}://${u.host}"
    }.getOrNull()

    fun collectCookies(response: okhttp3.Response): LinkedHashMap<String, String> {
        val chain = ArrayList<okhttp3.Response>()
        var cur: okhttp3.Response? = response
        while (cur != null) {
            chain.add(cur)
            cur = cur.priorResponse
        }
        val jar = LinkedHashMap<String, String>()
        for (res in chain.asReversed()) {
            for (raw in res.headers("Set-Cookie")) {
                val pair = raw.substringBefore(';')
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val k = pair.substring(0, eq).trim()
                    val v = pair.substring(eq + 1).trim()
                    if (k.isNotEmpty()) jar[k] = v
                }
            }
        }
        return jar
    }

    fun formatCookieHeader(cookies: Map<String, String>): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    suspend fun emitHls(
        source: String,
        label: String,
        m3u8Url: String,
        referer: String,
        headers: Map<String, String> = mapOf("User-Agent" to UA, "Referer" to referer),
        fallbackQuality: Int = Qualities.Unknown.value,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val explicitQuality = fallbackQuality.takeIf { it > 0 }
        val generated = runCatching {
            M3u8Helper.generateM3u8(source, m3u8Url, referer, quality = explicitQuality, headers = headers)
        }.getOrDefault(emptyList())

        if (generated.isNotEmpty()) {
            generated.forEach { link ->
                if (link.quality <= 0 && fallbackQuality > 0) {
                    callback(
                        newExtractorLink(link.source, link.name, link.url, link.type) {
                            this.referer = link.referer
                            this.quality = fallbackQuality
                            this.headers = link.headers
                            this.extractorData = link.extractorData
                        }
                    )
                } else {
                    callback(link)
                }
            }
            return true
        }

        callback(
            newExtractorLink(source, label, m3u8Url, ExtractorLinkType.M3U8) {
                this.referer = referer
                this.quality = fallbackQuality
                this.headers = headers
            }
        )
        return true
    }

    suspend fun emitVideo(
        source: String,
        label: String,
        videoUrl: String,
        referer: String,
        quality: Int = Qualities.Unknown.value,
        headers: Map<String, String> = mapOf("User-Agent" to UA, "Referer" to referer),
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            newExtractorLink(source, label, videoUrl, ExtractorLinkType.VIDEO) {
                this.referer = referer
                this.quality = quality
                this.headers = headers
            }
        )
    }

    fun unpackAllScripts(html: String): String {
        val sb = StringBuilder(html)
        val evalRegex = Regex("""eval\(function\(p,a,c,k,e,[dr]\).*?\)\)\s*;?""", RegexOption.DOT_MATCHES_ALL)
        for (m in evalRegex.findAll(html)) {
            runCatching { JsUnpacker(m.value).unpack() }.getOrNull()?.let { unpacked ->
                sb.append('\n').append(unpacked)
            }
        }
        return sb.toString()
    }
}

/**
 * Unified dispatcher invoked by [Anime4Up.loadLinks] for every Watch and Download candidate.
 * Routes known providers (including rotating domains & download-path variants) directly to
 * dedicated extractors while preserving quality hints from the episode page, and falls back
 * cleanly to CloudStream's built-in [loadExtractor].
 */
object Anime4UpExtractorSuite {
    internal val SKIP_HOSTS = listOf(
        "mega.nz",          // client-side AES-CTR browser-only decryption without SDK
        "vikingfile.",      // interactive Cloudflare Turnstile gate
        "vik1ngfile.",
        "workupload.com",   // interactive bot captcha on API
        "file-upload.com",  // multi-step countdown captcha
        "file-upload.org",
        "nitroflare.",      // premium/captcha-only filelocker
        "rapidgator.",      // premium/captcha-only filelocker
        "fileserve.com",    // video mod disabled + countdown captcha
        "clicknupload."     // multi-step captcha filelocker
    )

    fun canonicalDedupKey(rawUrl: String): String {
        val clean = rawUrl.trim()
        val low = clean.lowercase()
        // Deduplicate MegaMax & Share4Max watch/download/leech variants carrying the same hash
        Regex("""(?:megamax\.me|share4max\.com)/(?:iframe|d|download|e|f|mirror)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.get(1)?.let { return "megamax:$it" }
        // Deduplicate Mp4Upload /embed-ID.html and /ID
        Regex("""mp4upload\.com/(?:embed-)?([A-Za-z0-9]{10,})(?:\.html)?""", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.get(1)?.let { return "mp4upload:$it" }
        // Deduplicate Uqload /e/ID, /embed-ID.html, /ID.html across TLDs
        Regex("""uqload\.[a-z]+/(?:e/|embed-)?([A-Za-z0-9]{10,})(?:\.html)?""", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.get(1)?.let { return "uqload:$it" }
        // Deduplicate DoodStream family (/d/ID and /e/ID across playmogo, dsvplay, doodstream, etc.)
        Regex("""(?:playmogo|dsvplay|doodstream|d0000?d|dood|myvidplay|vide0)\.[a-z]+/(?:d|e)/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.get(1)?.let { return "dood:$it" }
        // Deduplicate VOE /e/ID and /ID
        Regex("""voe\.sx/(?:e/)?([A-Za-z0-9]{8,})""", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.get(1)?.let { return "voe:$it" }
        // Deduplicate Anime4up-S1 & Anime4up-S2 across 4x.c1oiijc.shop and w1.anime4up.rest mirrors
        Regex("""/(anime4up-s\d+/[^?#]+?)/?(?:[?#].*)?$""", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.get(1)?.lowercase()?.let { return "vnx:$it" }

        return low.removeSuffix("/")
    }

    suspend fun resolve(
        rawUrl: String,
        referer: String,
        qualityHint: String?,
        serverHint: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        sourcePrefix: String? = null
    ) {
        val url = when {
            rawUrl.startsWith("//") -> "https:$rawUrl"
            else -> rawUrl.trim()
        }
        if (!url.startsWith("http")) return

        val host = runCatching { URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
        if (host.isBlank() || SKIP_HOSTS.any { host.contains(it) }) return

        val bufferedLinks: MutableList<ExtractorLink>? = if (sourcePrefix.isNullOrBlank()) {
            null
        } else {
            java.util.Collections.synchronizedList(ArrayList<ExtractorLink>())
        }
        val effectiveCallback: (ExtractorLink) -> Unit = if (bufferedLinks == null) {
            callback
        } else {
            { link -> bufferedLinks.add(link) }
        }

        val hintLow = serverHint?.lowercase()?.trim().orEmpty()

        try {
            when {
                host.contains("megamax.me") || host.contains("share4max.com") -> {
                    Anime4UpMegaMaxExtractor().extractWithHint(url, referer, qualityHint, subtitleCallback, effectiveCallback)
                }
                host.contains("uns.bio") || host.contains("upns.") || host.contains("upnshare") || hintLow == "upnshare" -> {
                    Anime4UpUpnShareExtractor().extractWithHint(url, referer, qualityHint, effectiveCallback)
                }
                host.contains("strp2p.") || host.contains("streamp2p") || hintLow == "streamp2p" -> {
                    Anime4UpStreamP2PExtractor().extractWithHint(url, referer, qualityHint, effectiveCallback)
                }
                host.contains("rpmplay.") || host.contains("rpmshare") || host.contains("rpmvid.") ||
                    host.contains("rpmvip.") || hintLow == "rpmshare" -> {
                    Anime4UpRpmShareExtractor().extractWithHint(url, referer, qualityHint, effectiveCallback)
                }
                host.contains("firestream.") || hintLow == "firestream" -> {
                    Anime4UpFireStreamExtractor().extractWithHint(url, referer, qualityHint, subtitleCallback, effectiveCallback)
                }
                host.contains("vidara") || hintLow == "vidara" -> {
                    Anime4UpVidaraExtractor().extractWithHint(url, referer, qualityHint, subtitleCallback, effectiveCallback)
                }
                host.contains("morencius.") || host.contains("earnvids") || host.contains("movearnpre.") ||
                    host.contains("dintezuvio.") || host.contains("dingtezuni.") || host.contains("minochinos.") ||
                    hintLow == "earnvids" -> {
                    Anime4UpEarnVidsExtractor().extractWithHint(url, referer, qualityHint, effectiveCallback)
                }
                host.contains("mixdrop.") || host.contains("mxdrop.") || host.contains("m1xdrop.") ||
                    hintLow == "mixdrop" -> {
                    Anime4UpMixdropExtractor().extractWithHint(url, referer, qualityHint, effectiveCallback)
                }
                host.contains("mediafire.com") -> {
                    Anime4UpMediaFireExtractor().extractWithHint(url, referer, qualityHint, effectiveCallback)
                }
                host.contains("voe.sx") || hintLow.contains("voe") -> {
                    Anime4UpVoeExtractor().extractWithHint(url, referer, qualityHint, subtitleCallback, effectiveCallback)
                }
                host.contains("vkvideo.ru") || host == "vk.com" || host.endsWith(".vk.com") || host == "vk.ru" -> {
                    Anime4UpVkExtractor().getUrl(url, referer, subtitleCallback, effectiveCallback)
                }
                host.contains("mp4upload.com") || hintLow == "mp4upload" -> {
                    Anime4UpMp4UploadExtractor().extractWithHint(url, referer, qualityHint, effectiveCallback)
                }
                host.contains("uqload.") -> {
                    Anime4UpUqloadExtractor().extractWithHint(url, referer, qualityHint, effectiveCallback)
                }
                host.contains("playmogo.") || host.contains("dsvplay.") || host.contains("dood") ||
                    host.contains("d000") || host.contains("myvidplay.") || host.contains("vide0.") ||
                    hintLow.contains("dood") -> {
                    Anime4UpDoodExtractor().extractWithHint(url, referer, qualityHint, effectiveCallback)
                }
                url.contains("/Anime4up-S", ignoreCase = true) ||
                    host.contains("c1oiijc.shop") ||
                    hintLow.contains("anime4up") -> {
                    Anime4UpVnxPlayerExtractor().extractWithHint(url, referer, serverHint, qualityHint, subtitleCallback, effectiveCallback)
                }
                host.contains("byse") || hintLow == "byse" -> {
                    Anime4UpByseExtractor().extractWithHint(url, referer, qualityHint, effectiveCallback)
                }
                host.contains("lulustream") || host.contains("luluvdo") || hintLow == "lulustream" -> {
                    Anime4UpLuluStreamExtractor().extractWithHint(url, referer, qualityHint, effectiveCallback)
                }
                host.contains("savefiles.") || host.contains("streamhls.") || hintLow == "savefiles" -> {
                    Anime4UpSaveFilesExtractor().extractWithHint(url, referer, qualityHint, effectiveCallback)
                }
                host.contains("4shared.com") -> {
                    Anime4UpFourSharedExtractor().extractWithHint(url, referer, qualityHint, effectiveCallback)
                }
                host.contains("gofile.io") -> {
                    Anime4UpGoFileExtractor().extractWithHint(url, referer, qualityHint, effectiveCallback)
                }
                host.contains("moshahda.") -> {
                    Anime4UpMoshahdaExtractor().getUrl(url, referer, subtitleCallback, effectiveCallback)
                }
                else -> {
                    loadExtractor(url, referer, subtitleCallback, effectiveCallback)
                }
            }
        } catch (_: Throwable) {
            // Never allow one broken mirror to fail the remaining servers
        }

        if (bufferedLinks != null && !sourcePrefix.isNullOrBlank()) {
            for (link in bufferedLinks) {
                val pSource = if (link.source.startsWith(sourcePrefix, ignoreCase = true)) {
                    link.source
                } else {
                    "$sourcePrefix - ${link.source}"
                }
                val pName = if (link.name.startsWith(sourcePrefix, ignoreCase = true)) {
                    link.name
                } else {
                    "$sourcePrefix - ${link.name}"
                }
                callback(
                    newExtractorLink(pSource, pName, link.url, link.type) {
                        this.referer = link.referer
                        this.quality = link.quality
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }
}

// =========================================================================
// 1. MegaMax & Share4Max Complete Multi-Source Aggregator
//    - Leg 1: MegaMax Direct CDN777 HLS (`/e/<id>` -> Inertia `files/leech/video` + CryptoJS AES)
//    - Leg 2: MegaMax Direct CDN777 MP4 (`/d/<id>` -> Inertia `files/leech/download` POST)
//    - Leg 3: All Multi-Host Mirrors across 1080p / 720p / 480p / 360p
//             (`/iframe/<id>` `files/mirror/video` partial reload + `/download/<id>` POST)
// =========================================================================

open class Anime4UpMegaMaxExtractor : ExtractorApi() {
    override val name = "MegaMax"
    override val mainUrl = "https://megamax.me"
    override val requiresReferer = false

    private data class InertiaSession(
        val url: String,
        val origin: String,
        val page: JSONObject,
        val version: String,
        val component: String,
        val cookieHeader: String,
        val xsrfToken: String
    )

    private data class MirrorTask(
        val mirrorUrl: String,
        val driver: String,
        val qualityLabel: String
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extractWithHint(url, referer, null, subtitleCallback, callback)
    }

    suspend fun extractWithHint(
        url: String,
        referer: String?,
        qualityHint: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val hashId = Regex("""/(?:iframe|d|download|e|f|mirror)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1) ?: return
        val preferredOrigin = Anime4UpExtractorUtils.originOf(url) ?: "https://share4max.com"
        val refUrl = referer?.takeIf { it.startsWith("http") } ?: "$preferredOrigin/"

        val mirrorOrigins = listOf(preferredOrigin, "https://share4max.com", "https://megamax.me").distinct()
        val leechOrigins = listOf("https://megamax.me", "https://share4max.com", preferredOrigin).distinct()

        // Run MegaMax's native CDN777 HLS (/e/), native CDN777 MP4 (/d/), and mirror discovery concurrently
        val discoveredMirrors = coroutineScope {
            val leechHlsJob = async {
                extractLeechHls(hashId, leechOrigins, refUrl, subtitleCallback, callback)
            }
            val leechMp4Job = async {
                extractLeechDirectMp4(hashId, leechOrigins, refUrl, callback)
            }
            val iframeMirrorsJob = async {
                fetchIframeMirrors(hashId, mirrorOrigins, refUrl, qualityHint)
            }
            val downloadMirrorsJob = async {
                fetchDownloadMirrors(hashId, leechOrigins, refUrl, qualityHint)
            }

            leechHlsJob.await()
            leechMp4Job.await()
            Pair(iframeMirrorsJob.await(), downloadMirrorsJob.await())
        }

        val (iframeResult, downloadTasks) = discoveredMirrors
        val activeIframeUrl = iframeResult.first ?: "$preferredOrigin/iframe/$hashId"
        val mergedTasks = ArrayList<MirrorTask>()
        val seenIdentities = HashSet<String>()

        // Prefer `/iframe/<id>` streaming URLs first (includes FireStream & Vidara + clean embed paths),
        // then append any additional mirrors uniquely present on `/download/<id>`.
        for (task in iframeResult.second + downloadTasks) {
            val host = runCatching { URI(task.mirrorUrl).host?.lowercase().orEmpty() }.getOrDefault("")
            if (host.isBlank() || Anime4UpExtractorSuite.SKIP_HOSTS.any { host.contains(it) }) continue
            val identity = mirrorIdentityKey(task.driver, task.mirrorUrl, task.qualityLabel)
            if (seenIdentities.add(identity)) {
                mergedTasks.add(task)
            }
        }

        if (mergedTasks.isEmpty()) return

        val sem = Semaphore(6)
        coroutineScope {
            mergedTasks.map { task ->
                async {
                    sem.withPermit {
                        val tierHint = task.qualityLabel
                            .takeIf { it.isNotBlank() && !it.contains("متعدد") }
                            ?: qualityHint?.takeIf { !it.contains("متعدد") }
                        Anime4UpExtractorSuite.resolve(
                            rawUrl = task.mirrorUrl,
                            referer = activeIframeUrl,
                            qualityHint = tierHint,
                            serverHint = task.driver,
                            subtitleCallback = subtitleCallback,
                            callback = callback,
                            sourcePrefix = "MegaMax"
                        )
                    }
                }
            }.awaitAll()
        }
    }

    // ---- Inertia session & partial-reload helpers ---------------------------

    private suspend fun openInertiaSession(targetUrl: String, referer: String): InertiaSession? = runCatching {
        val res = app.get(
            targetUrl,
            referer = referer,
            headers = mapOf(
                "User-Agent" to Anime4UpExtractorUtils.UA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
        )
        val doc = res.document
        val rawPage = doc.selectFirst("script[data-page=app]")?.html()?.trim()
            ?: doc.selectFirst("#app[data-page]")?.attr("data-page")?.let { Parser.unescapeEntities(it, false) }
            ?: return null
        val pageJson = JSONObject(rawPage)
        val cookies = Anime4UpExtractorUtils.collectCookies(res.okhttpResponse)
        val cookieHeader = Anime4UpExtractorUtils.formatCookieHeader(cookies)
        val xsrf = cookies["XSRF-TOKEN"]?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }.orEmpty()
        val finalUrl = res.url.ifBlank { targetUrl }
        val finalOrigin = Anime4UpExtractorUtils.originOf(finalUrl)
            ?: Anime4UpExtractorUtils.originOf(targetUrl)
            ?: mainUrl
        InertiaSession(
            url = finalUrl,
            origin = finalOrigin,
            page = pageJson,
            version = pageJson.optString("version"),
            component = pageJson.optString("component"),
            cookieHeader = cookieHeader,
            xsrfToken = xsrf
        )
    }.getOrNull()

    private fun buildInertiaHeaders(
        sess: InertiaSession,
        partialComponent: String,
        partialData: String,
        isPost: Boolean = false
    ): Map<String, String> = LinkedHashMap<String, String>().apply {
        put("User-Agent", Anime4UpExtractorUtils.UA)
        put("Accept", "text/html, application/xhtml+xml")
        put("Content-Type", "application/json")
        put("Referer", sess.url)
        if (isPost) put("Origin", sess.origin)
        put("X-Requested-With", "XMLHttpRequest")
        put("X-Inertia", "true")
        if (sess.version.isNotBlank()) put("X-Inertia-Version", sess.version)
        put("X-Inertia-Partial-Component", partialComponent)
        put("X-Inertia-Partial-Data", partialData)
        if (sess.cookieHeader.isNotBlank()) put("Cookie", sess.cookieHeader)
        if (sess.xsrfToken.isNotBlank()) put("X-XSRF-TOKEN", sess.xsrfToken)
    }

    // ---- Leg 1: MegaMax Direct HLS (`/e/<id>` -> `files/leech/video`) -------

    private suspend fun extractLeechHls(
        hashId: String,
        origins: List<String>,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        for (base in origins) {
            val sess = openInertiaSession("$base/e/$hashId", referer) ?: continue
            val comp = sess.component.ifBlank { "files/leech/video" }
            val partialText = runCatching {
                app.get(
                    sess.url,
                    headers = buildInertiaHeaders(sess, comp, "streams,subtitles")
                ).text
            }.getOrNull() ?: continue

            val props = runCatching { JSONObject(partialText).optJSONObject("props") }.getOrNull() ?: continue

            // Subtitles if attached on MegaMax's native player
            props.optJSONArray("subtitles")?.let { subs ->
                for (i in 0 until subs.length()) {
                    val s = subs.optJSONObject(i) ?: continue
                    val subUrl = s.optString("file").takeIf { it.startsWith("http") } ?: continue
                    val lang = s.optString("label").ifBlank { "Arabic" }
                    subtitleCallback(newSubtitleFile(lang, subUrl))
                }
            }

            val dataRaw = props.optJSONObject("streams")?.optString("data").orEmpty()
            if (dataRaw.isBlank()) continue

            val plain = if (dataRaw.contains("\"ct\"")) {
                decryptCryptoJsAes(dataRaw, LEECH_AES_PASSPHRASE)
            } else {
                dataRaw
            } ?: continue

            val streamHeaders = mapOf(
                "User-Agent" to Anime4UpExtractorUtils.UA,
                "Referer" to "${sess.origin}/",
                "Origin" to sess.origin
            )
            var emitted = false
            val trimmed = plain.trim()
            if (trimmed.startsWith("[")) {
                val arr = runCatching { JSONArray(trimmed) }.getOrNull()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        if (emitLeechSourceObj(item, sess.origin, streamHeaders, callback)) emitted = true
                    }
                }
            } else if (trimmed.startsWith("{")) {
                val obj = runCatching { JSONObject(trimmed) }.getOrNull()
                if (obj != null && emitLeechSourceObj(obj, sess.origin, streamHeaders, callback)) emitted = true
            }
            if (emitted) return
        }
    }

    private suspend fun emitLeechSourceObj(
        obj: JSONObject,
        origin: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fileUrl = obj.optString("file").takeIf { it.startsWith("http") } ?: return false
        val lbl = obj.optString("label")
        val qVal = Anime4UpExtractorUtils.parseQualityValue(lbl, fileUrl)
        return if (fileUrl.contains(".m3u8") || obj.optString("type").contains("mpegURL", ignoreCase = true)) {
            Anime4UpExtractorUtils.emitHls(
                source = "MegaMax Direct",
                label = "MegaMax Direct",
                m3u8Url = fileUrl,
                referer = "$origin/",
                headers = headers,
                fallbackQuality = qVal,
                callback = callback
            )
        } else {
            val qTag = Anime4UpExtractorUtils.formatQualityTag(qVal)
            val fullLabel = if (qTag.isNotEmpty()) "MegaMax Direct ($qTag)" else "MegaMax Direct"
            Anime4UpExtractorUtils.emitVideo(
                source = "MegaMax Direct",
                label = fullLabel,
                videoUrl = fileUrl,
                referer = "$origin/",
                quality = qVal,
                headers = headers,
                callback = callback
            )
            true
        }
    }

    // ---- Leg 2: MegaMax Direct MP4 (`/d/<id>` -> `files/leech/download`) ----

    private suspend fun extractLeechDirectMp4(
        hashId: String,
        origins: List<String>,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        for (base in origins) {
            val sess = openInertiaSession("$base/d/$hashId", referer) ?: continue
            val attachments = sess.page.optJSONObject("props")?.optJSONArray("attachments") ?: continue
            if (attachments.length() == 0) continue

            val comp = sess.component.ifBlank { "files/leech/download" }
            val headers = buildInertiaHeaders(sess, comp, "download", isPost = true)
            var emitted = false

            for (i in 0 until attachments.length()) {
                val att = attachments.optJSONObject(i) ?: continue
                val attachId = att.optString("hashId").takeIf { it.isNotBlank() } ?: continue
                val attLabel = att.optString("label").ifBlank { att.optString("name") }
                val attRes = att.optString("resolution")
                val body = JSONObject().put("attach", attachId).toString()
                    .toRequestBody(JSON_MEDIA_TYPE)

                val postText = runCatching {
                    app.post(sess.url, headers = headers, requestBody = body).text
                }.getOrNull() ?: continue

                val directUrl = runCatching {
                    JSONObject(postText)
                        .optJSONObject("props")
                        ?.optJSONObject("download")
                        ?.optJSONObject("data")
                        ?.optString("url")
                }.getOrNull()?.takeIf { it.startsWith("http") } ?: continue

                val qVal = Anime4UpExtractorUtils.parseQualityValue(attLabel, attRes)
                val qTag = Anime4UpExtractorUtils.formatQualityTag(qVal)
                val label = if (qTag.isNotEmpty()) "MegaMax Direct MP4 ($qTag)" else "MegaMax Direct MP4"

                Anime4UpExtractorUtils.emitVideo(
                    source = "MegaMax Direct",
                    label = label,
                    videoUrl = directUrl,
                    referer = "${sess.origin}/",
                    quality = qVal,
                    headers = mapOf(
                        "User-Agent" to Anime4UpExtractorUtils.UA,
                        "Referer" to "${sess.origin}/"
                    ),
                    callback = callback
                )
                emitted = true
            }
            if (emitted) return
        }
    }

    // ---- Leg 3a: `/iframe/<id>` (`files/mirror/video`) ----------------------

    private suspend fun fetchIframeMirrors(
        hashId: String,
        origins: List<String>,
        referer: String,
        qualityHint: String?
    ): Pair<String?, List<MirrorTask>> {
        for (base in origins) {
            val sess = openInertiaSession("$base/iframe/$hashId", referer) ?: continue
            var tiers = parseStreamTiers(sess.page.optJSONObject("props")?.optJSONObject("streams"))
            if (tiers == null || tiers.length() == 0) {
                val comp = sess.component.ifBlank { "files/mirror/video" }
                val reloadText = runCatching {
                    app.get(sess.url, headers = buildInertiaHeaders(sess, comp, "streams")).text
                }.getOrNull()
                if (!reloadText.isNullOrBlank()) {
                    tiers = runCatching {
                        parseStreamTiers(JSONObject(reloadText).optJSONObject("props")?.optJSONObject("streams"))
                    }.getOrNull()
                }
            }
            if (tiers != null && tiers.length() > 0) {
                val list = ArrayList<MirrorTask>()
                for (i in 0 until tiers.length()) {
                    val tier = tiers.optJSONObject(i) ?: continue
                    val tierLabel = tier.optString("label", qualityHint.orEmpty())
                    val mirrors = tier.optJSONArray("mirrors") ?: continue
                    collectMirrorsInto(mirrors, tierLabel, list)
                }
                if (list.isNotEmpty()) return Pair(sess.url, list)
            }
        }
        return Pair(null, emptyList())
    }

    private fun parseStreamTiers(streamsObj: JSONObject?): JSONArray? {
        if (streamsObj == null) return null
        streamsObj.optJSONArray("data")?.let { return it }
        return streamsObj.optJSONObject("data")?.optJSONArray("arr")
    }

    // ---- Leg 3b: `/download/<id>` (`files/mirror/download`) -----------------

    private suspend fun fetchDownloadMirrors(
        hashId: String,
        origins: List<String>,
        referer: String,
        qualityHint: String?
    ): List<MirrorTask> {
        for (base in origins) {
            val sess = openInertiaSession("$base/download/$hashId", referer) ?: continue
            val attachments = sess.page.optJSONObject("props")?.optJSONArray("attachments") ?: continue
            if (attachments.length() == 0) continue

            val comp = sess.component.ifBlank { "files/mirror/download" }
            val headers = buildInertiaHeaders(sess, comp, "download", isPost = true)
            val tasks = ArrayList<MirrorTask>()

            for (i in 0 until attachments.length()) {
                val att = attachments.optJSONObject(i) ?: continue
                val attachId = att.optString("hashId").takeIf { it.isNotBlank() } ?: continue
                val tierLabel = att.optString("label")
                    .ifBlank { att.optString("name") }
                    .ifBlank { att.optString("resolution") }
                    .ifBlank { qualityHint.orEmpty() }

                val body = JSONObject().put("attach", attachId).toString()
                    .toRequestBody(JSON_MEDIA_TYPE)
                val postText = runCatching {
                    app.post(sess.url, headers = headers, requestBody = body).text
                }.getOrNull() ?: continue

                val mirrors = runCatching {
                    JSONObject(postText)
                        .optJSONObject("props")
                        ?.optJSONObject("download")
                        ?.optJSONObject("data")
                        ?.optJSONArray("mirrors")
                }.getOrNull() ?: continue

                collectMirrorsInto(mirrors, tierLabel, tasks)
            }
            if (tasks.isNotEmpty()) return tasks
        }
        return emptyList()
    }

    private fun collectMirrorsInto(mirrors: JSONArray, tierLabel: String, out: MutableList<MirrorTask>) {
        for (j in 0 until mirrors.length()) {
            val m = mirrors.optJSONObject(j) ?: continue
            val driver = m.optString("driver").lowercase().trim()
            var link = m.optString("link").trim()
            if (link.isBlank()) continue
            if (link.startsWith("//")) link = "https:$link"
            if (!link.startsWith("http")) continue
            out.add(MirrorTask(link, driver, tierLabel))
        }
    }

    private fun mirrorIdentityKey(driver: String, mirrorUrl: String, tierLabel: String): String {
        val d = driver.lowercase().trim()
        val hashFrag = mirrorUrl.substringAfterLast('#', "")
            .substringBefore('&')
            .substringBefore('?')
            .trim()
        if (hashFrag.isNotEmpty()) {
            return "$d:$hashFrag"
        }
        val cleanPath = mirrorUrl.substringBefore('?').substringBefore('#').trimEnd('/')
        val seg = cleanPath.substringAfterLast('/')
            .removePrefix("embed-")
            .removeSuffix(".html")
            .trim()
        return if (seg.isNotEmpty()) "$d:$seg" else "$d:$mirrorUrl:$tierLabel"
    }

    // ---- OpenSSL EVP_BytesToKey (MD5) + AES-256-CBC for `/e/<id>` -----------

    private fun decryptCryptoJsAes(jsonStr: String, passphrase: String): String? = runCatching {
        val obj = JSONObject(jsonStr)
        val ct = Base64.decode(obj.getString("ct"), Base64.DEFAULT)
        val iv = hexToBytes(obj.getString("iv"))
        val salt = hexToBytes(obj.getString("s"))
        val passBytes = passphrase.toByteArray(Charsets.UTF_8)
        val md5 = MessageDigest.getInstance("MD5")
        var derived = ByteArray(0)
        var block = ByteArray(0)
        while (derived.size < 48) {
            md5.reset()
            md5.update(block)
            md5.update(passBytes)
            md5.update(salt)
            block = md5.digest()
            derived += block
        }
        val key = derived.copyOfRange(0, 32)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        return ByteArray(clean.length / 2) { idx ->
            clean.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
        }
    }

    companion object {
        private const val LEECH_AES_PASSPHRASE = "k7v2m4x9bqwpnj6t"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

class Anime4UpShare4MaxExtractor : Anime4UpMegaMaxExtractor() {
    override val name = "Share4Max"
    override val mainUrl = "https://share4max.com"
}

// =========================================================================
// 2. MediaFire Direct MP4 Extractor (1080p FHD / 720p HD / 480p SD)
// =========================================================================

class Anime4UpMediaFireExtractor : ExtractorApi() {
    override val name = "MediaFire"
    override val mainUrl = "https://www.mediafire.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extractWithHint(url, referer, null, callback)
    }

    suspend fun extractWithHint(
        url: String,
        referer: String?,
        qualityHint: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url, referer = referer, headers = mapOf("User-Agent" to Anime4UpExtractorUtils.UA)).text
        }.getOrNull() ?: return

        val directUrl = extractDirectLink(html) ?: return
        val qVal = Anime4UpExtractorUtils.parseQualityValue(qualityHint, directUrl)
        val qTag = Anime4UpExtractorUtils.formatQualityTag(qVal)
        val label = if (qTag.isNotEmpty()) "$name ($qTag)" else name

        Anime4UpExtractorUtils.emitVideo(
            source = name,
            label = label,
            videoUrl = directUrl,
            referer = url,
            quality = qVal,
            headers = mapOf("User-Agent" to Anime4UpExtractorUtils.UA),
            callback = callback
        )
    }

    private fun extractDirectLink(html: String): String? {
        Regex("""data-scrambled-url="([^"]+)"""").find(html)?.groupValues?.get(1)?.let { b64 ->
            val decoded = runCatching { String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
            if (!decoded.isNullOrBlank() && decoded.startsWith("http")) return decoded
        }

        Regex("""href="(https?://download\d*\.mediafire\.com/[^"]+)"""").find(html)?.groupValues?.get(1)?.let {
            return it.replace("&amp;", "&")
        }

        return Regex("""https?://download\d*\.mediafire\.com/[^"'\s<>\\]+""").find(html)?.value
    }
}

// =========================================================================
// 3. VOE Extractor (JS redirect + rot13/markers/base64/charShift decryptor)
// =========================================================================

class Anime4UpVoeExtractor : ExtractorApi() {
    override val name = "VOE"
    override val mainUrl = "https://voe.sx"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extractWithHint(url, referer, null, subtitleCallback, callback)
    }

    suspend fun extractWithHint(
        url: String,
        referer: String?,
        qualityHint: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val normalized = Regex("""voe\.sx/(?:e/)?([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)?.let { "https://voe.sx/e/$it" } ?: url

        var currentUrl = normalized
        var html = ""
        repeat(4) {
            val res = runCatching {
                app.get(
                    currentUrl,
                    referer = referer ?: "https://voe.sx/",
                    headers = mapOf("User-Agent" to Anime4UpExtractorUtils.UA)
                )
            }.getOrNull() ?: return
            currentUrl = res.url
            html = res.text

            val nextRedirect = Regex("""window\.location\.href\s*=\s*['"](https?://[^'"]+)['"]""").find(html)?.groupValues?.get(1)
                ?: Regex("""location\.replace\(\s*['"](https?://[^'"]+)['"]\s*\)""").find(html)?.groupValues?.get(1)
                ?: Regex("""http-equiv=["']refresh["'][^>]*url=['"]?(https?://[^'">\s]+)""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)

            if (nextRedirect != null && nextRedirect != currentUrl) {
                currentUrl = nextRedirect
            } else {
                return@repeat
            }
        }

        if (html.isBlank()) return
        val origin = Anime4UpExtractorUtils.originOf(currentUrl) ?: mainUrl
        val headers = mapOf(
            "User-Agent" to Anime4UpExtractorUtils.UA,
            "Referer" to "$origin/",
            "Origin" to origin
        )
        val qFallback = Anime4UpExtractorUtils.parseQualityValue(qualityHint)

        // 1.Encrypted <script type="application/json">["..."] payload (2025-2026 Voe template)
        var emitted = false
        val appJsonScripts = Regex(
            """<script[^>]*type=["']application/json["'][^>]*>\s*\[\s*"([^"]+)"\s*\]\s*</script>""",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(html)

        for (match in appJsonScripts) {
            val decoded = decryptVoePayload(match.groupValues[1]) ?: continue
            val hls = decoded.optString("source").takeIf { it.startsWith("http") }
            val mp4 = decoded.optString("file").takeIf { it.startsWith("http") }
            val direct = decoded.optJSONArray("direct_access_url")?.optString(0)?.takeIf { it.startsWith("http") }
                ?: decoded.optString("direct_access_url").takeIf { it.startsWith("http") }

            if (hls != null) {
                Anime4UpExtractorUtils.emitHls(name, name, hls, "$origin/", headers, qFallback, callback)
                emitted = true
            }
            if (mp4 != null && mp4 != hls) {
                Anime4UpExtractorUtils.emitVideo(name, "$name MP4", mp4, "$origin/", qFallback, headers, callback)
                emitted = true
            }
            if (direct != null && direct != mp4) {
                Anime4UpExtractorUtils.emitVideo(name, "$name Direct", direct, "$origin/", qFallback, headers, callback)
                emitted = true
            }
        }

        if (emitted) return

        // 2. Fallback: base64 or plaintext 'hls' / 'mp4' fields
        Regex("""['"]hls['"]\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)?.let { raw ->
            val hlsUrl = if (raw.startsWith("http")) raw else runCatching {
                String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()
            if (!hlsUrl.isNullOrBlank() && hlsUrl.startsWith("http")) {
                Anime4UpExtractorUtils.emitHls(name, name, hlsUrl, "$origin/", headers, qFallback, callback)
                return
            }
        }

        Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").find(html)?.value?.let { hlsUrl ->
            Anime4UpExtractorUtils.emitHls(name, name, hlsUrl, "$origin/", headers, qFallback, callback)
        }
    }

    private fun decryptVoePayload(encoded: String): JSONObject? = runCatching {
        // Step 1: ROT13 on ASCII letters
        val rot = buildString(encoded.length) {
            for (c in encoded) {
                when (c) {
                    in 'a'..'z' -> append('a' + (c - 'a' + 13) % 26)
                    in 'A'..'Z' -> append('A' + (c - 'A' + 13) % 26)
                    else -> append(c)
                }
            }
        }
        // Step 2: Strip Voe obfuscation markers
        var cleaned = rot
        for (marker in listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")) {
            cleaned = cleaned.replace(marker, "_")
        }
        cleaned = cleaned.replace("_", "")
        // Step 3: Base64 decode
        val step3 = String(Base64.decode(cleaned, Base64.DEFAULT), Charsets.ISO_8859_1)
        // Step 4: Shift each character code back by 3 & reverse
        val step4 = buildString(step3.length) {
            for (i in step3.length - 1 downTo 0) {
                append((step3[i].code - 3).toChar())
            }
        }
        // Step 5: Final Base64 decode -> JSON
        val jsonText = String(Base64.decode(step4, Base64.DEFAULT), Charsets.UTF_8)
        JSONObject(jsonText)
    }.getOrNull()
}

// =========================================================================
// 4. VK Video Extractor (vkvideo.ru / vk.com / vk.ru — 1080p..240p + HLS)
// =========================================================================

open class Anime4UpVkExtractor : ExtractorApi() {
    override val name = "VK Video"
    override val mainUrl = "https://vkvideo.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = Anime4UpExtractorUtils.originOf(url) ?: mainUrl
        val headers = mapOf(
            "User-Agent" to Anime4UpExtractorUtils.UA,
            "Referer" to "$origin/",
            "Accept-Language" to "en-US,en;q=0.9"
        )
        val html = runCatching {
            app.get(url, headers = headers).text
        }.getOrNull() ?: return

        val seen = HashSet<String>()
        for ((field, qVal, qLabel) in listOf(
            Triple("url2160", Qualities.P2160.value, "2160p"),
            Triple("url1440", Qualities.P1440.value, "1440p"),
            Triple("url1080", Qualities.P1080.value, "1080p"),
            Triple("url720", Qualities.P720.value, "720p"),
            Triple("url480", Qualities.P480.value, "480p"),
            Triple("url360", Qualities.P360.value, "360p"),
            Triple("url240", Qualities.P240.value, "240p")
        )) {
            val mp4 = Regex(""""$field"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
                ?.replace("\\/", "/")
                ?.replace("\\u0026", "&")
                ?: continue
            if (mp4.startsWith("http") && seen.add(mp4)) {
                Anime4UpExtractorUtils.emitVideo(
                    source = name,
                    label = "$name ($qLabel)",
                    videoUrl = mp4,
                    referer = "$origin/",
                    quality = qVal,
                    headers = headers,
                    callback = callback
                )
            }
        }

        Regex(""""hls"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.replace("\\u0026", "&")
            ?.takeIf { it.startsWith("http") }
            ?.let { hls ->
                Anime4UpExtractorUtils.emitHls(name, "$name HLS", hls, "$origin/", headers, Qualities.Unknown.value, callback)
            }
    }
}

class Anime4UpVkComExtractor : Anime4UpVkExtractor() {
    override val mainUrl = "https://vk.com"
}

// =========================================================================
// 5. Mp4Upload Extractor (handles /embed-ID.html and /ID download URLs)
// =========================================================================

open class Anime4UpMp4UploadExtractor : ExtractorApi() {
    override val name = "Mp4Upload"
    override val mainUrl = "https://www.mp4upload.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extractWithHint(url, referer, null, callback)
    }

    suspend fun extractWithHint(
        url: String,
        referer: String?,
        qualityHint: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = Regex("""mp4upload\.com/(?:embed-)?([A-Za-z0-9]{10,})""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)
        val embedUrl = if (id != null) "https://www.mp4upload.com/embed-$id.html" else url

        val rawHtml = runCatching {
            app.get(
                embedUrl,
                referer = referer ?: "$mainUrl/",
                headers = mapOf("User-Agent" to Anime4UpExtractorUtils.UA)
            ).text
        }.getOrNull() ?: return

        val combined = Anime4UpExtractorUtils.unpackAllScripts(rawHtml)
        val src = SRC_REGEX.find(combined)?.groupValues?.get(1)
            ?: MP4_URL_REGEX.find(combined)?.value
            ?: return

        val heightFromPage = HEIGHT_REGEX.find(combined)?.groupValues?.get(1)?.let { "${it}p" }
        val qVal = Anime4UpExtractorUtils.parseQualityValue(qualityHint ?: heightFromPage, src)
        val qTag = Anime4UpExtractorUtils.formatQualityTag(qVal)
        val label = if (qTag.isNotEmpty()) "$name ($qTag)" else name

        val playHeaders = mapOf(
            "Referer" to embedUrl,
            "Origin" to "https://www.mp4upload.com",
            "User-Agent" to Anime4UpExtractorUtils.UA
        )
        Anime4UpExtractorUtils.emitVideo(
            source = name,
            label = label,
            videoUrl = src,
            referer = embedUrl,
            quality = qVal,
            headers = playHeaders,
            callback = callback
        )
    }

    companion object {
        private val SRC_REGEX = Regex("""player\.src\(\s*\{\s*type\s*:\s*["']video/mp4["']\s*,\s*src\s*:\s*["']([^"']+)["']""")
        private val MP4_URL_REGEX = Regex("""https?://[a-z0-9.-]*mp4upload\.com(?::\d+)?/[^\s"'<>\\]+\.mp4""", RegexOption.IGNORE_CASE)
        private val HEIGHT_REGEX = Regex("""\bheight\s*=\s*(\d{3,4})\b""")
    }
}

class Anime4UpMp4UploadBareExtractor : Anime4UpMp4UploadExtractor() {
    override val mainUrl = "https://mp4upload.com"
}

// =========================================================================
// 6. Uqload Extractor (normalizes /e/ID & /ID.html -> /embed-ID.html on .vc/.is)
// =========================================================================

open class Anime4UpUqloadExtractor : ExtractorApi() {
    override val name = "Uqload"
    override val mainUrl = "https://uqload.vc"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extractWithHint(url, referer, null, callback)
    }

    suspend fun extractWithHint(
        url: String,
        referer: String?,
        qualityHint: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = Regex("""uqload\.[a-z]+/(?:e/|embed-)?([A-Za-z0-9]{10,})""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)

        val candidates = if (id != null) {
            listOf(
                "https://uqload.vc/embed-$id.html",
                "https://uqload.is/embed-$id.html",
                "https://uqload.to/embed-$id.html"
            )
        } else {
            listOf(url)
        }

        for (embedCandidate in candidates) {
            val res = runCatching {
                app.get(
                    embedCandidate,
                    referer = referer ?: "$mainUrl/",
                    headers = mapOf("User-Agent" to Anime4UpExtractorUtils.UA)
                )
            }.getOrNull() ?: continue

            val finalUrl = res.url
            val origin = Anime4UpExtractorUtils.originOf(finalUrl) ?: mainUrl
            val combined = Anime4UpExtractorUtils.unpackAllScripts(res.text)
            if (combined.contains("File was deleted", ignoreCase = true)) return

            val qVal = Anime4UpExtractorUtils.parseQualityValue(qualityHint)
            val qTag = Anime4UpExtractorUtils.formatQualityTag(qVal)
            val label = if (qTag.isNotEmpty()) "$name ($qTag)" else name
            val headers = mapOf(
                "Referer" to "$origin/",
                "Origin" to origin,
                "User-Agent" to Anime4UpExtractorUtils.UA
            )

            val sourcesBlock = Regex("""sources\s*:\s*\[([^\]]+)]""").find(combined)?.groupValues?.get(1) ?: combined
            val urls = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").findAll(sourcesBlock)
                .map { it.groupValues[1] }
                .distinct()
                .toList()

            if (urls.isNotEmpty()) {
                for (mediaUrl in urls) {
                    if (mediaUrl.contains(".m3u8")) {
                        Anime4UpExtractorUtils.emitHls(name, label, mediaUrl, "$origin/", headers, qVal, callback)
                    } else {
                        Anime4UpExtractorUtils.emitVideo(name, label, mediaUrl, "$origin/", qVal, headers, callback)
                    }
                }
                return
            }
        }
    }
}

class Anime4UpUqloadIsExtractor : Anime4UpUqloadExtractor() { override val mainUrl = "https://uqload.is" }
class Anime4UpUqloadComExtractor : Anime4UpUqloadExtractor() { override val mainUrl = "https://uqload.com" }
class Anime4UpUqloadToExtractor : Anime4UpUqloadExtractor() { override val mainUrl = "https://uqload.to" }

// =========================================================================
// 7. DoodStream Family Extractor (playmogo, dsvplay, doodstream, myvidplay)
// =========================================================================

open class Anime4UpDoodExtractor : ExtractorApi() {
    override val name = "DoodStream"
    override val mainUrl = "https://dsvplay.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extractWithHint(url, referer, null, callback)
    }

    suspend fun extractWithHint(
        url: String,
        referer: String?,
        qualityHint: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = Regex("""/(?:e|d)/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1)
        val initialOrigin = Anime4UpExtractorUtils.originOf(url) ?: mainUrl
        val candidates = buildList {
            if (id != null) {
                add("$initialOrigin/e/$id")
                add("https://dsvplay.com/e/$id")
                add("https://myvidplay.com/e/$id")
                add("https://d000d.com/e/$id")
            } else {
                add(url.replace("/d/", "/e/"))
            }
        }.distinct()

        for (candidate in candidates) {
            val res = runCatching {
                app.get(
                    candidate,
                    referer = referer ?: "$initialOrigin/",
                    headers = mapOf("User-Agent" to Anime4UpExtractorUtils.UA)
                )
            }.getOrNull() ?: continue

            val html = res.text
            val passMd5Path = Regex("""/pass_md5/[^'"]+""").find(html)?.value ?: continue
            val activeOrigin = Anime4UpExtractorUtils.originOf(res.url) ?: initialOrigin
            val token = passMd5Path.substringAfterLast("/")

            val md5Base = runCatching {
                app.get(
                    "$activeOrigin$passMd5Path",
                    referer = res.url,
                    headers = mapOf(
                        "User-Agent" to Anime4UpExtractorUtils.UA,
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                ).text.trim()
            }.getOrNull() ?: continue

            if (!md5Base.startsWith("http")) continue

            val randomSuffix = (1..10).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
            val finalVideoUrl = "$md5Base$randomSuffix?token=$token&expiry=${System.currentTimeMillis()}"
            val qVal = Anime4UpExtractorUtils.parseQualityValue(qualityHint, res.document.title())
            val qTag = Anime4UpExtractorUtils.formatQualityTag(qVal)
            val label = if (qTag.isNotEmpty()) "$name ($qTag)" else name

            Anime4UpExtractorUtils.emitVideo(
                source = name,
                label = label,
                videoUrl = finalVideoUrl,
                referer = "$activeOrigin/",
                quality = qVal,
                headers = mapOf(
                    "User-Agent" to Anime4UpExtractorUtils.UA,
                    "Referer" to "$activeOrigin/"
                ),
                callback = callback
            )
            return
        }
    }
}

class Anime4UpPlaymogoExtractor : Anime4UpDoodExtractor() { override val mainUrl = "https://playmogo.com" }
class Anime4UpDoodStreamComExtractor : Anime4UpDoodExtractor() { override val mainUrl = "https://doodstream.com" }

// =========================================================================
// 8. Byse AES-GCM + PoW Extractor (used by MegaMax 1080p/720p/480p mirrors)
// =========================================================================

open class Anime4UpByseExtractor : ExtractorApi() {
    override val name = "Byse"
    override val mainUrl = "https://byseraguci.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extractWithHint(url, referer, null, callback)
    }

    suspend fun extractWithHint(
        url: String,
        referer: String?,
        qualityHint: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = Regex("""/(?:e|d|v)/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1)
            ?: url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
                .takeIf { it.isNotBlank() }
            ?: return
        val origin = Anime4UpExtractorUtils.originOf(url) ?: mainUrl
        val embedReferer = "$origin/e/$code"

        val details = fetchJsonGet("$origin/api/videos/$code/embed/details", embedReferer)
        val embedFrameUrl = details?.optString("embed_frame_url")?.takeIf { it.startsWith("http") }
        val embedDomain = embedFrameUrl?.let { Anime4UpExtractorUtils.originOf(it) } ?: origin
        val playbackCode = embedFrameUrl
            ?.let { Regex("""/(?:e|d|v)/([A-Za-z0-9]+)""").find(it)?.groupValues?.get(1) }
            ?: details?.optString("code")?.takeIf { it.isNotBlank() }
            ?: code

        // Primary flow (`byseraguci.com` -> `n1mwq.org`): POST `/api/videos/<code>/embed/playback`
        // with forged browser fingerprint + custom 512-word PoW captcha if `captcha_required`.
        val playbackObj = postEmbedPlayback(embedDomain, playbackCode, embedFrameUrl ?: "$embedDomain/e/$playbackCode")
            ?: details?.optJSONObject("playback")
            ?: (if (embedDomain != origin) fetchJsonGet("$embedDomain/api/videos/$playbackCode/embed/details", "$embedDomain/e/$playbackCode")?.optJSONObject("playback") else null)
            ?: fetchJsonGet("$origin/api/videos/$code", embedReferer)?.optJSONObject("playback")
            ?: return

        val decryptedStr = decryptPlayback(playbackObj) ?: return
        val decrypted = runCatching { JSONObject(decryptedStr) }.getOrNull() ?: return
        val sources = decrypted.optJSONArray("sources") ?: return
        val edgeAuth = solveSha256EdgePow(playbackObj.optJSONObject("edge_challenge"))

        val headers = LinkedHashMap<String, String>().apply {
            put("Referer", "$embedDomain/")
            put("Origin", embedDomain)
            put("User-Agent", Anime4UpExtractorUtils.UA)
            if (edgeAuth != null) put("X-Edge-Auth", edgeAuth)
        }

        val hintQuality = Anime4UpExtractorUtils.parseQualityValue(qualityHint)
        for (i in 0 until sources.length()) {
            val s = sources.optJSONObject(i) ?: continue
            val streamUrl = (s.optString("url").ifBlank { s.optString("file") })
                .takeIf { it.startsWith("http") } ?: continue
            val qVal = Anime4UpExtractorUtils.parseQualityValue(
                s.optString("label").ifBlank { s.optString("quality") },
                s.optInt("height", 0).takeIf { it > 0 }?.let { "${it}p" }.orEmpty()
            ).let { if (it > 0) it else hintQuality }

            if (streamUrl.contains(".m3u8")) {
                Anime4UpExtractorUtils.emitHls(name, name, streamUrl, "$embedDomain/", headers, qVal, callback)
            } else {
                Anime4UpExtractorUtils.emitVideo(name, name, streamUrl, "$embedDomain/", qVal, headers, callback)
            }
        }
    }

    private suspend fun fetchJsonGet(apiUrl: String, referer: String, extraHeaders: Map<String, String> = emptyMap()): JSONObject? =
        runCatching {
            val origin = Anime4UpExtractorUtils.originOf(referer) ?: mainUrl
            val headers = LinkedHashMap<String, String>().apply {
                put("Accept", "application/json")
                put("User-Agent", Anime4UpExtractorUtils.UA)
                put("Origin", origin)
                putAll(extraHeaders)
            }
            val text = app.get(apiUrl, referer = referer, headers = headers).text
            JSONObject(text)
        }.getOrNull()

    private suspend fun postEmbedPlayback(embedOrigin: String, code: String, embedPageUrl: String): JSONObject? =
        runCatching {
            val baseHeaders = mapOf(
                "User-Agent" to Anime4UpExtractorUtils.UA,
                "Accept" to "*/*",
                "Origin" to embedOrigin,
                "Referer" to embedPageUrl,
                "X-Embed-Origin" to (runCatching { URI(mainUrl).host }.getOrNull() ?: "byseraguci.com"),
                "X-Embed-Parent" to embedPageUrl
            )
            val settings = runCatching {
                JSONObject(app.get("$embedOrigin/api/videos/$code/embed/settings", headers = baseHeaders).text)
            }.getOrNull()

            val body = JSONObject().put("fingerprint", buildByseFingerprint(embedPageUrl))

            if (settings?.optBoolean("captcha_required", false) == true) {
                val capQuery = URLEncoder.encode("""{"video_code":"$code"}""", "UTF-8")
                val captcha = runCatching {
                    JSONObject(app.get("$embedOrigin/api/videos/embed/captcha?d=$capQuery", headers = baseHeaders).text)
                }.getOrNull()
                if (captcha != null) {
                    val challenge = captcha.optString("challenge")
                    val difficulty = captcha.optInt("difficulty", 2)
                    val token = captcha.optString("token")
                    if (challenge.isNotBlank() && token.isNotBlank()) {
                        val solved = withContext(Dispatchers.Default) {
                            solveByseCustomPow(challenge, difficulty)
                        }
                        if (solved != null) {
                            body.put("captcha_token", token)
                            body.put("captcha_hash", solved)
                        }
                    }
                }
            }

            val postHeaders = HashMap(baseHeaders).apply {
                put("Content-Type", "application/json")
            }
            val resText = app.post(
                "$embedOrigin/api/videos/$code/embed/playback",
                headers = postHeaders,
                requestBody = body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            ).text
            val root = JSONObject(resText)
            root.optJSONObject("playback") ?: if (root.has("payload")) root else null
        }.getOrNull()

    private fun buildByseFingerprint(embedUrl: String): JSONObject {
        val now = System.currentTimeMillis()
        val rand = SecureRandom()
        val sidBytes = ByteArray(9).also { rand.nextBytes(it) }
        val sid = Base64.encodeToString(sidBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).take(12)
        return JSONObject().apply {
            put("bot", JSONObject().apply {
                put("automation", JSONObject().apply {
                    put("webdriver", false)
                    put("phantom", false)
                    put("nightmare", false)
                    put("selenium", false)
                    put("cypress", false)
                    put("playwright", false)
                    put("puppeteer", false)
                    put("domAutomation", false)
                    put("cdcProps", false)
                })
                put("environment", JSONObject().apply {
                    put("chrome", true)
                    put("permissions", true)
                    put("languages", 2)
                    put("plugins", 5)
                    put("pluginNames", JSONArray(listOf("PDF Viewer", "Chrome PDF Viewer", "Chromium PDF Viewer")))
                    put("outerDimensions", true)
                    put("iframe", true)
                })
                put("graphics", JSONObject().apply {
                    put("webgl", true)
                    put("vendor", "Google Inc. (Intel)")
                    put("renderer", "ANGLE (Intel, Intel(R) Iris(R) Xe Graphics Direct3D11 vs_5_0 ps_5_0, D3D11)")
                    put("swiftshader", false)
                    put("llvmpipe", false)
                    put("mesa", false)
                    put("canvasHash", 1482910421)
                })
                put("probe", JSONObject().apply {
                    put("raf", true)
                    put("rafDelta", 16.6)
                    put("setTimeoutDelta", 4.1)
                    put("mathConsistent", true)
                    put("stackDepth", 11240)
                    put("perfTiming", true)
                    put("touchSupport", 0)
                })
                put("media", JSONObject().apply {
                    put("audioContext", true)
                    put("speechVoices", 4)
                    put("mediaDevices", true)
                    put("RTC", true)
                })
                put("screen", JSONObject().apply {
                    put("width", 1920)
                    put("height", 1080)
                    put("availWidth", 1920)
                    put("availHeight", 1040)
                    put("colorDepth", 24)
                    put("devicePixelRatio", 1)
                })
                put("browser", JSONObject().apply {
                    put("ua", Anime4UpExtractorUtils.UA)
                    put("language", "en-US")
                    put("platform", "Win32")
                    put("hardwareConcurrency", 8)
                    put("deviceMemory", 8)
                })
                put("ts", now)
            })
            put("beh", JSONObject().apply {
                put("dur", 1245)
                put("mv", 4)
                put("dist", 142)
                put("velAvg", 0.35)
                put("velMax", 1.12)
                put("cl", 0)
                put("tch", 0)
                put("scr", 0)
                put("key", 0)
                put("vis", 0)
                put("foc", 1)
                put("raf", 60)
                put("jank", 0)
                put("tFirst", 210)
            })
            put("ctx", JSONObject().apply {
                put("sid", sid)
                put("seq", 1)
                put("t0", now - 1300)
                put("url", embedUrl.take(200))
                put("ref", "$mainUrl/")
                put("vw", 1280)
                put("vh", 720)
                put("iframe", true)
                put("tz", "UTC")
            })
        }
    }

    private fun solveByseCustomPow(challenge: String, difficulty: Int): String? {
        val targetPrefix = "0".repeat(difficulty.coerceIn(1, 6))
        val prefix = "$challenge:"
        for (nonce in 0L..5_000_000L) {
            val h = byseWordHash(prefix + nonce.toString())
            if (h.startsWith(targetPrefix)) {
                return "$h:$nonce"
            }
        }
        return null
    }

    private fun byseWordHash(input: String): String {
        val state = IntArray(512) { idx -> (idx * 2654435761L).toInt() }
        val bytes = input.toByteArray(Charsets.UTF_8)
        var s = 0
        var c = 1315423911
        for (elem in bytes) {
            val u = elem.toInt() and 0xFF
            val d = (s + u) and 511
            var f = state[d]
            f = f xor ((u * 65599) + (f ushr 5) + (f shl 2))
            val l = kotlin.math.sin((u + (s and 0xFF)).toDouble()) * 10000.0
            val p = ((l - kotlin.math.floor(l)) * 65535.0).toInt()
            f = (f + p) or 0
            c = c xor ((c shl 5) + f + (c ushr 2))
            state[d] = f
            state[(d + 1) and 511] = state[(d + 1) and 511] xor (f ushr 3)
            s = (s + 7) and 511
        }
        for (idx in 0 until 512) {
            val u = state[idx]
            val d = state[(idx + 7) and 511]
            state[idx] = (u + (d shl 3)) xor (u ushr 5) xor c
            c = (c + state[idx]) or 0
        }
        val out = IntArray(8)
        for (idx in 0 until 512) {
            out[idx and 7] = out[idx and 7] xor state[idx]
        }
        val sb = StringBuilder(64)
        for (w in out) {
            sb.append((w.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0'))
        }
        return sb.toString()
    }

    private fun b64UrlDecode(s: String): ByteArray {
        var padded = s.trim().replace('-', '+').replace('_', '/')
        val rem = padded.length % 4
        if (rem != 0) padded += "=".repeat(4 - rem)
        return Base64.decode(padded, Base64.DEFAULT)
    }

    private fun decryptPlayback(pb: JSONObject): String? {
        val keyParts = pb.optJSONArray("key_parts")
        if (keyParts != null && keyParts.length() >= 2) {
            val total = keyParts.length()
            val version = pb.optInt("version", 1)
            val iv1 = runCatching { b64UrlDecode(pb.optString("iv")) }.getOrNull()
            val payload1 = runCatching { b64UrlDecode(pb.optString("payload")) }.getOrNull()
            if (iv1 != null && payload1 != null) {
                // Version-indexed key pair (`parts[v-1] + parts[total - v]`) + [0]+[1] fallback
                val candidatePairs = LinkedHashSet<Pair<Int, Int>>()
                val idxA = (version - 1).coerceIn(0, total - 1)
                val idxB = (total - version).coerceIn(0, total - 1)
                candidatePairs.add(idxA to idxB)
                candidatePairs.add(0 to 1)
                for ((a, b) in candidatePairs) {
                    val key = b64UrlDecode(keyParts.optString(a)) + b64UrlDecode(keyParts.optString(b))
                    aesGcmDecrypt(key, iv1, payload1)?.let { plain ->
                        if (plain.contains("sources")) return plain
                    }
                }
            }
        }

        val p2 = pb.optString("payload2")
        val iv2s = pb.optString("iv2")
        if (p2.isNotBlank() && iv2s.isNotBlank()) {
            val decryptKeys = pb.optJSONObject("decrypt_keys")
            val edge1 = decryptKeys?.optString("edge_1")?.takeIf { it.isNotBlank() }
            val edge2 = decryptKeys?.optString("edge_2")?.takeIf { it.isNotBlank() }
            if (edge1 != null && edge2 != null) {
                val key2 = b64UrlDecode(edge1) + b64UrlDecode(edge2)
                aesGcmDecrypt(key2, b64UrlDecode(iv2s), b64UrlDecode(p2))?.let { return it }
            }
        }
        return null
    }

    private fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, cipherBytes: ByteArray): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }.getOrNull()

    private fun solveSha256EdgePow(challenge: JSONObject?): String? {
        if (challenge == null || !challenge.optBoolean("enabled", false)) return null
        val token = challenge.optString("token").takeIf { it.isNotBlank() } ?: return null
        val ts = challenge.optLong("timestamp", 0L)
        val difficulty = challenge.optInt("difficulty", 0)
        if (difficulty <= 0 || difficulty > 24) return null
        val fullBytes = difficulty / 8
        val remBits = difficulty % 8
        val mask = if (remBits == 0) 0 else (0xFF shl (8 - remBits)) and 0xFF
        val md = MessageDigest.getInstance("SHA-256")
        for (nonce in 0 until 2_000_000) {
            val digest = md.digest("$token$ts$nonce".toByteArray(Charsets.UTF_8))
            var ok = true
            for (b in 0 until fullBytes) {
                if (digest[b].toInt() != 0) {
                    ok = false
                    break
                }
            }
            if (ok && remBits > 0 && (digest[fullBytes].toInt() and mask) != 0) ok = false
            if (ok) return "$token.$ts.$nonce"
        }
        return null
    }
}

// =========================================================================
// 9. LuluStream & SaveFiles Extractors (used by MegaMax mirrors)
// =========================================================================

class Anime4UpLuluStreamExtractor : ExtractorApi() {
    override val name = "LuluStream"
    override val mainUrl = "https://lulustream.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extractWithHint(url, referer, null, callback)
    }

    suspend fun extractWithHint(
        url: String,
        referer: String?,
        qualityHint: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = url.substringBefore('?').substringBefore('#').trimEnd('/')
            .substringAfterLast('/')
            .removePrefix("embed-")
            .removeSuffix(".html")
        if (code.isBlank()) return
        val qVal = Anime4UpExtractorUtils.parseQualityValue(qualityHint)
        for (host in listOf("luluvdo.com", "lulustream.com")) {
            val embedUrl = "https://$host/e/$code"
            val html = runCatching {
                app.get(
                    embedUrl,
                    referer = referer ?: "https://$host/",
                    headers = mapOf("User-Agent" to Anime4UpExtractorUtils.UA)
                ).text
            }.getOrNull() ?: continue

            val combined = Anime4UpExtractorUtils.unpackAllScripts(html)
            val m3u8 = Regex("""["'](https?://[^"']+(?:\.m3u8|/master\.txt)[^"']*)["']""")
                .find(combined)?.groupValues?.get(1) ?: continue
            val headers = mapOf(
                "User-Agent" to Anime4UpExtractorUtils.UA,
                "Referer" to embedUrl,
                "Origin" to "https://$host"
            )
            Anime4UpExtractorUtils.emitHls(
                source = name,
                label = name,
                m3u8Url = m3u8,
                referer = embedUrl,
                headers = headers,
                fallbackQuality = qVal,
                callback = callback
            )
            return
        }
    }
}

class Anime4UpSaveFilesExtractor : ExtractorApi() {
    override val name = "SaveFiles"
    override val mainUrl = "https://savefiles.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extractWithHint(url, referer, null, callback)
    }

    suspend fun extractWithHint(
        url: String,
        referer: String?,
        qualityHint: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileCode = Regex("""(?:savefiles\.com|streamhls\.to)/(?:e/|d/)?([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1) ?: return
        val origin = Anime4UpExtractorUtils.originOf(url) ?: mainUrl
        val embedUrl = "$origin/e/$fileCode"
        val html = runCatching {
            app.post(
                url = "$origin/dl",
                headers = mapOf(
                    "Referer" to embedUrl,
                    "Origin" to origin,
                    "User-Agent" to Anime4UpExtractorUtils.UA,
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                data = mapOf(
                    "op" to "embed",
                    "file_code" to fileCode,
                    "auto" to "1",
                    "referer" to (referer ?: "$origin/")
                )
            ).text
        }.getOrNull() ?: return

        val combined = Anime4UpExtractorUtils.unpackAllScripts(html)
        val m3u8 = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(combined)?.groupValues?.get(1) ?: return
        val qVal = Anime4UpExtractorUtils.parseQualityValue(qualityHint)
        Anime4UpExtractorUtils.emitHls(
            source = name,
            label = name,
            m3u8Url = m3u8,
            referer = "$origin/",
            headers = mapOf(
                "User-Agent" to Anime4UpExtractorUtils.UA,
                "Referer" to "$origin/",
                "Origin" to origin
            ),
            fallbackQuality = qVal,
            callback = callback
        )
    }
}

// =========================================================================
// 10. Anime4Up Native Players: anime4up1 (Anime4up-S1) & anime4up2 (Anime4up-S2)
//     (4x.c1oiijc.shop -> w1.anime4up.rest VnxPlayer + cdn1/cdn2 HLS + VTT)
// =========================================================================

/**
 * Lightweight loopback HTTP subtitle bridge (`127.0.0.1:<ephemeralPort>`) so Arabic `.vtt`
 * tracks fetched through [Anime4UpNetwork] (bypassing Cloudflare 403 challenges on
 * `w1.anime4up.rest/vnx-subtitle/...` and `/wp-content/uploads/...`) load reliably inside
 * ExoPlayer regardless of the viewer's IP/VPN state.
 */
internal object Anime4UpSubtitleServer {
    private const val MAX_ENTRIES = 16
    private val store = object : LinkedHashMap<String, ByteArray>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var boundPort: Int = 0

    @Synchronized
    private fun ensureStarted(): Int {
        if (boundPort > 0 && serverSocket?.isClosed == false) return boundPort
        return runCatching {
            val sock = ServerSocket(0, 24, InetAddress.getByName("127.0.0.1"))
            serverSocket = sock
            boundPort = sock.localPort
            Thread({
                while (!sock.isClosed) {
                    val client = runCatching { sock.accept() }.getOrNull() ?: break
                    Thread({ handleClient(client) }, "Anime4UpSubConn").apply {
                        isDaemon = true
                        start()
                    }
                }
            }, "Anime4UpSubServer").apply {
                isDaemon = true
                start()
            }
            boundPort
        }.getOrDefault(0)
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            runCatching {
                sock.soTimeout = 5000
                val reader = sock.getInputStream().bufferedReader(Charsets.UTF_8)
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val hdr = reader.readLine() ?: break
                    if (hdr.isEmpty()) break
                }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0)?.uppercase().orEmpty()
                val rawPath = parts.getOrNull(1).orEmpty().substringBefore('?')
                val key = rawPath.substringAfterLast('/').removeSuffix(".vtt")
                val out = sock.getOutputStream()

                if (method == "OPTIONS") {
                    val cors = "HTTP/1.1 204 No Content\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n" +
                        "Connection: close\r\n\r\n"
                    out.write(cors.toByteArray(Charsets.UTF_8))
                    out.flush()
                    return
                }

                val payload = synchronized(store) { store[key] }
                if (payload == null) {
                    val notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    out.write(notFound.toByteArray(Charsets.UTF_8))
                    out.flush()
                    return
                }

                val headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/vtt; charset=utf-8\r\n" +
                    "Content-Length: ${payload.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(headers.toByteArray(Charsets.UTF_8))
                if (method != "HEAD") {
                    out.write(payload)
                }
                out.flush()
            }
        }
    }

    fun registerVtt(vttContent: String): String? {
        val normalized = vttContent.removePrefix("\uFEFF").trim()
        if (normalized.isEmpty() || !normalized.contains("-->")) return null
        val finalVtt = if (normalized.startsWith("WEBVTT", ignoreCase = true)) {
            "$normalized\n"
        } else {
            "WEBVTT\n\n$normalized\n"
        }
        val bytes = finalVtt.toByteArray(Charsets.UTF_8)
        val sha1 = MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        synchronized(store) {
            store[sha1] = bytes
        }
        val port = ensureStarted()
        if (port <= 0) return null
        return "http://127.0.0.1:$port/sub/$sha1.vtt"
    }
}

open class Anime4UpVnxPlayerExtractor : ExtractorApi() {
    override val name = "Anime4Up"
    override val mainUrl = "https://4x.c1oiijc.shop"
    override val requiresReferer = true

    private data class HlsVariant(
        val url: String,
        val quality: Int,
        val bandwidth: Long
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extractWithHint(url, referer, null, null, subtitleCallback, callback)
    }

    suspend fun extractWithHint(
        url: String,
        referer: String?,
        serverHint: String?,
        qualityHint: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val siteOrigin = referer?.let { Anime4UpExtractorUtils.originOf(it) }
            ?.takeIf { it.contains("anime4up", ignoreCase = true) }
            ?: "https://w1.anime4up.rest"
        val episodeReferer = referer?.takeIf { it.startsWith("http") } ?: "$siteOrigin/"

        // Rewrite `4x.c1oiijc.shop/Anime4up-S1/...` or `Anime4up-S2/...` directly onto
        // `$siteOrigin/Anime4up-S1|S2/...` with `vnx_no_cache` so W3 Total Cache never
        // serves stale unauthorized pages or expired CDN stream tokens.
        val vnxPathMatch = Regex("""/(Anime4up-S(\d+)/[^?#]+)""", RegexOption.IGNORE_CASE).find(url)
        val serverIndex = vnxPathMatch?.groupValues?.get(2)
        val canonicalPlayerUrl = if (vnxPathMatch != null) {
            val cleanPath = vnxPathMatch.groupValues[1].trimStart('/')
            "$siteOrigin/$cleanPath"
        } else {
            url.substringBefore('?')
        }
        val fetchTargetUrl = if (canonicalPlayerUrl.contains('?')) {
            "$canonicalPlayerUrl&vnx_no_cache=${System.currentTimeMillis()}"
        } else {
            "$canonicalPlayerUrl?vnx_no_cache=${System.currentTimeMillis()}"
        }

        val displaySource = resolveServerLabel(serverHint, serverIndex)
        val iframeHeaders = mapOf(
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-origin"
        )

        val html = Anime4UpNetwork.fetchHtml(
            url = fetchTargetUrl,
            referer = episodeReferer,
            extraHeaders = iframeHeaders,
            requireSiteSignature = false,
            validator = { body ->
                body.length > 500 &&
                    !body.contains("هذا الدومين غير مصرح") &&
                    !body.contains("Domain Restricted", ignoreCase = true) &&
                    (body.contains("streamUrl") || body.contains("VnxPlayer") || body.contains(".m3u8"))
            }
        ) ?: return

        val combined = Anime4UpExtractorUtils.unpackAllScripts(html)

        // 1. Extract & register external Arabic WebVTT subtitles (`const tracks = [...];`)
        extractAndEmitSubtitles(combined, canonicalPlayerUrl, subtitleCallback)

        // 2. Extract primary tokenized CDN HLS stream (`let streamUrl = "https://cdn1|2.7p4bm32.shop/?token=..."`)
        val streamCandidates = LinkedHashSet<String>()
        Regex("""(?:let|var|const)\s+streamUrl\s*=\s*["']([^"']+)["']""").findAll(combined).forEach { m ->
            val u = unescapeJsUrl(m.groupValues[1])
            if (u.startsWith("http")) streamCandidates.add(u)
        }
        Regex("""(https?://cdn\d*\.[A-Za-z0-9.-]+/\?token=[A-Za-z0-9_-]+)""").findAll(combined).forEach { m ->
            streamCandidates.add(unescapeJsUrl(m.groupValues[1]))
        }
        Regex("""["'](https?://[^"'\s]+\.m3u8[^"'\s]*)["']""").findAll(combined).forEach { m ->
            streamCandidates.add(unescapeJsUrl(m.groupValues[1]))
        }

        val cdnHeaders = mapOf(
            "User-Agent" to Anime4UpExtractorUtils.UA,
            "Accept" to "application/vnd.apple.mpegurl, application/x-mpegURL, */*",
            "Referer" to "$siteOrigin/",
            "Origin" to siteOrigin
        )
        val fallbackQuality = Anime4UpExtractorUtils.parseQualityValue(qualityHint)

        var emittedAny = false
        for (streamUrl in streamCandidates) {
            if (emitValidatedVnxHls(displaySource, streamUrl, siteOrigin, cdnHeaders, fallbackQuality, callback)) {
                emittedAny = true
            }
        }

        // 3. Direct MP4 fallback if present
        Regex("""["'](https?://[^"'\s]+\.mp4[^"'\s]*)["']""").findAll(combined)
            .map { unescapeJsUrl(it.groupValues[1]) }
            .distinct()
            .forEach { mp4 ->
                Anime4UpExtractorUtils.emitVideo(
                    source = displaySource,
                    label = displaySource,
                    videoUrl = mp4,
                    referer = "$siteOrigin/",
                    quality = fallbackQuality,
                    headers = cdnHeaders,
                    callback = callback
                )
                emittedAny = true
            }

        // 4. Nested iframe fallback if `iframeOnly` mode is ever enabled on an episode
        if (!emittedAny) {
            Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE).findAll(combined)
                .map { unescapeJsUrl(it.groupValues[1]) }
                .filterNot { it.equals(url, ignoreCase = true) || it.equals(canonicalPlayerUrl, ignoreCase = true) }
                .distinct()
                .forEach { nested ->
                    loadExtractor(nested, canonicalPlayerUrl, subtitleCallback, callback)
                }
        }
    }

    private fun resolveServerLabel(serverHint: String?, serverIndex: String?): String {
        val cleanedHint = serverHint
            ?.replace("متعدد الجودات", "")
            ?.trim()
            .orEmpty()
        if (cleanedHint.matches(Regex("""anime4up\s*\d+""", RegexOption.IGNORE_CASE))) {
            return cleanedHint.replace(Regex("""\s+"""), "").lowercase()
        }
        if (!serverIndex.isNullOrBlank()) {
            return "anime4up$serverIndex"
        }
        return cleanedHint.ifBlank { name }
    }

    private fun unescapeJsUrl(raw: String): String =
        raw.trim()
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

    private suspend fun fetchDirectText(url: String, headers: Map<String, String>): Pair<Int, String>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val reqBuilder = Request.Builder().url(url)
                headers.forEach { (k, v) -> reqBuilder.header(k, v) }
                val client = app.baseClient.newBuilder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(12, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()
                client.newCall(reqBuilder.build()).execute().use { res ->
                    res.code to res.body?.string().orEmpty()
                }
            }.getOrNull()
        }

    /**
     * Fetches and validates the VnxPlayer HLS playlist (retrying transient CDN hiccups just like
     * VnxPlayer's `resolvePlayableStream()`, without mutating the single `?token=` query string).
     *
     * Handles both playlist topologies used by `cdn1` and `cdn2`:
     *  - Demuxed multi-audio Master Playlists (`#EXT-X-MEDIA:TYPE=AUDIO` with `URI=`): emits the
     *    Master Playlist URL directly so ExoPlayer keeps separate Japanese/English audio tracks.
     *  - Muxed multi-resolution Master Playlists (`#EXT-X-STREAM-INF` without separate audio URI):
     *    emits individual 1080p / 720p / 480p / 360p HLS links sorted from highest to lowest.
     */
    private suspend fun emitValidatedVnxHls(
        source: String,
        streamUrl: String,
        siteOrigin: String,
        cdnHeaders: Map<String, String>,
        fallbackQuality: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var playlistBody: String? = null
        for (attempt in 0 until 2) {
            val res = fetchDirectText(streamUrl, cdnHeaders)
            if (res != null && res.first in 200..299) {
                val normalized = res.second.removePrefix("\uFEFF").trim()
                if (normalized.startsWith("#EXTM3U", ignoreCase = true)) {
                    playlistBody = normalized
                    break
                }
            }
            if (attempt == 0) delay(450)
        }
        val body = playlistBody ?: return false

        val hasSeparateAudioTrack = Regex(
            """#EXT-X-MEDIA:[^\r\n]*TYPE=AUDIO[^\r\n]*URI=["'][^"']+["']""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(body)

        val variants = parseMasterVariants(body, streamUrl)

        if (hasSeparateAudioTrack || variants.isEmpty()) {
            val bestQuality = variants.maxOfOrNull { it.quality }
                ?.takeIf { it > Qualities.Unknown.value }
                ?: fallbackQuality
            callback(
                newExtractorLink(source, source, streamUrl, ExtractorLinkType.M3U8) {
                    this.referer = "$siteOrigin/"
                    this.quality = bestQuality
                    this.headers = cdnHeaders
                }
            )
            return true
        }

        variants.sortedWith(compareByDescending<HlsVariant> { it.quality }.thenByDescending { it.bandwidth })
            .distinctBy { it.url }
            .forEach { variant ->
                val qVal = if (variant.quality > Qualities.Unknown.value) variant.quality else fallbackQuality
                callback(
                    newExtractorLink(source, source, variant.url, ExtractorLinkType.M3U8) {
                        this.referer = "$siteOrigin/"
                        this.quality = qVal
                        this.headers = cdnHeaders
                    }
                )
            }
        return true
    }

    private fun parseMasterVariants(playlist: String, baseUrl: String): List<HlsVariant> {
        val lines = playlist.lines()
        val variants = ArrayList<HlsVariant>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) {
                val attrs = line.substringAfter(':')
                val bw = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val nameAttr = Regex("""NAME=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groupValues?.get(1)
                val heightAttr = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groupValues?.get(1)?.toIntOrNull()

                val qVal = qualityFromStreamInf(nameAttr, heightAttr)

                var j = i + 1
                while (j < lines.size) {
                    val next = lines[j].trim()
                    if (next.isEmpty() || next.startsWith("#")) {
                        if (next.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) break
                        j++
                        continue
                    }
                    val resolvedUrl = resolveRelativeUrl(baseUrl, next)
                    if (resolvedUrl.startsWith("http")) {
                        variants.add(HlsVariant(resolvedUrl, qVal, bw))
                    }
                    break
                }
                i = j
            }
            i++
        }
        return variants
    }

    private fun qualityFromStreamInf(nameAttr: String?, height: Int?): Int {
        if (!nameAttr.isNullOrBlank()) {
            val byName = Anime4UpExtractorUtils.parseQualityValue(nameAttr)
            if (byName > Qualities.Unknown.value) return byName
        }
        return when {
            height == null -> Qualities.Unknown.value
            height >= 2000 -> Qualities.P2160.value
            height >= 1000 -> Qualities.P1080.value
            height >= 700 -> Qualities.P720.value
            height >= 440 -> Qualities.P480.value
            height >= 320 -> Qualities.P360.value
            height > 0 -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    private fun resolveRelativeUrl(baseUrl: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return runCatching { URI(baseUrl).resolve(ref).toString() }.getOrDefault(ref)
    }

    private suspend fun extractAndEmitSubtitles(
        combinedScript: String,
        playerPageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        data class SubTrackSpec(val langLabel: String, val urls: List<String>)

        val specs = ArrayList<SubTrackSpec>()
        Regex("""const\s+tracks\s*=\s*(\[[\s\S]*?\])\s*;""").find(combinedScript)?.groupValues?.get(1)?.let { rawJson ->
            runCatching {
                val arr = JSONArray(rawJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val candidates = LinkedHashSet<String>()
                    obj.optString("file").takeIf { it.isNotBlank() }?.let { candidates.add(unescapeJsUrl(it)) }
                    obj.optJSONArray("fallbacks")?.let { fbArr ->
                        for (j in 0 until fbArr.length()) {
                            val fb = fbArr.optString(j)
                            if (fb.isNotBlank()) {
                                val cleanFb = unescapeJsUrl(fb)
                                candidates.add(cleanFb)
                                if (cleanFb.contains('?')) {
                                    candidates.add(cleanFb.substringBefore('?'))
                                }
                            }
                        }
                    }
                    val srclang = obj.optString("srclang").lowercase()
                    val rawLabel = obj.optString("label").trim()
                    val lang = if (srclang == "ar" || rawLabel.contains("عرب") || rawLabel.isBlank()) {
                        "Arabic"
                    } else {
                        rawLabel
                    }
                    if (candidates.isNotEmpty()) {
                        specs.add(SubTrackSpec(lang, candidates.toList()))
                    }
                }
            }
        }

        // Fallback regex if `tracks` variable syntax changes
        if (specs.isEmpty()) {
            val fallbackUrls = Regex("""https?://[^"'\s\\]+\.(?:vtt|srt|ass)(?:\?[^"'\s\\]*)?""")
                .findAll(combinedScript.replace("\\/", "/"))
                .map { unescapeJsUrl(it.value) }
                .distinct()
                .toList()
            if (fallbackUrls.isNotEmpty()) {
                specs.add(SubTrackSpec("Arabic", fallbackUrls))
            }
        }

        val subHeaders = mapOf(
            "Accept" to "text/vtt,text/plain,application/x-subrip,*/*"
        )

        for (spec in specs) {
            var servedLocally = false
            for (candUrl in spec.urls) {
                val vttText = Anime4UpNetwork.fetchHtml(
                    url = candUrl,
                    referer = playerPageUrl,
                    extraHeaders = subHeaders,
                    requireSiteSignature = false,
                    validator = { body ->
                        !body.contains("<html", ignoreCase = true) && body.contains("-->")
                    }
                )
                if (!vttText.isNullOrBlank()) {
                    val localUrl = Anime4UpSubtitleServer.registerVtt(vttText)
                    if (localUrl != null) {
                        subtitleCallback(newSubtitleFile(spec.langLabel, localUrl))
                        servedLocally = true
                        break
                    }
                }
            }
            if (!servedLocally) {
                spec.urls.firstOrNull()?.let { remoteUrl ->
                    subtitleCallback(newSubtitleFile(spec.langLabel, remoteUrl))
                }
            }
        }
    }
}

class Anime4UpVnxDirectExtractor : Anime4UpVnxPlayerExtractor() {
    override val name = "Anime4Up Direct"
    override val mainUrl = "https://w1.anime4up.rest"
}

class Anime4UpFourSharedExtractor : ExtractorApi() {
    override val name = "4Shared"
    override val mainUrl = "https://www.4shared.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extractWithHint(url, referer, null, callback)
    }

    suspend fun extractWithHint(
        url: String,
        referer: String?,
        qualityHint: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = Regex("""4shared\.com/(?:video|file|s)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?.let { "https://www.4shared.com/web/embed/file/$it" }
            ?: url

        val doc = runCatching {
            app.get(embedUrl, referer = referer, headers = mapOf("User-Agent" to Anime4UpExtractorUtils.UA)).document
        }.getOrNull() ?: return

        val src = doc.selectFirst("video source[src], source[type*='video'][src]")?.attr("src")
            ?: Regex("""["'](https?://[^"']*4shared\.com[^"']+\.mp4[^"']*)["']""").find(doc.html())?.groupValues?.get(1)
            ?: return

        val qVal = Anime4UpExtractorUtils.parseQualityValue(qualityHint, src)
        Anime4UpExtractorUtils.emitVideo(
            source = name,
            label = name,
            videoUrl = src,
            referer = embedUrl,
            quality = qVal,
            callback = callback
        )
    }
}

class Anime4UpGoFileExtractor : ExtractorApi() {
    override val name = "GoFile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extractWithHint(url, referer, null, callback)
    }

    suspend fun extractWithHint(
        url: String,
        referer: String?,
        qualityHint: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val contentId = Regex("""/d/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1) ?: return
        val accText = runCatching {
            app.post("https://api.gofile.io/accounts", headers = mapOf("User-Agent" to Anime4UpExtractorUtils.UA)).text
        }.getOrNull() ?: return
        val token = runCatching { JSONObject(accText).optJSONObject("data")?.optString("token") }.getOrNull()
        if (token.isNullOrBlank()) return

        val globalJs = runCatching {
            app.get("https://gofile.io/dist/js/config.js", headers = mapOf("User-Agent" to Anime4UpExtractorUtils.UA)).text
        }.getOrDefault("")
        val wt = Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""").find(globalJs)?.groupValues?.get(1) ?: "4fd6sg89d7s6"

        val resText = runCatching {
            app.get(
                "https://api.gofile.io/contents/$contentId?wt=$wt",
                headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "User-Agent" to Anime4UpExtractorUtils.UA
                )
            ).text
        }.getOrNull() ?: return

        val root = runCatching { JSONObject(resText) }.getOrNull() ?: return
        if (root.optString("status") != "ok") return
        val children = root.optJSONObject("data")?.optJSONObject("children") ?: return
        val keys = children.keys()
        while (keys.hasNext()) {
            val child = children.optJSONObject(keys.next()) ?: continue
            val link = child.optString("link").takeIf { it.startsWith("http") } ?: continue
            val fileName = child.optString("name")
            val qVal = Anime4UpExtractorUtils.parseQualityValue(qualityHint, fileName)
            Anime4UpExtractorUtils.emitVideo(
                source = name,
                label = name,
                videoUrl = link,
                referer = "https://gofile.io/",
                quality = qVal,
                headers = mapOf(
                    "Cookie" to "accountToken=$token",
                    "User-Agent" to Anime4UpExtractorUtils.UA
                ),
                callback = callback
            )
        }
    }
}

class Anime4UpMoshahdaExtractor : ExtractorApi() {
    override val name = "Moshahda"
    override val mainUrl = "https://moshahda.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = if (url.contains("embed-")) url else {
            val code = url.substringAfterLast("/").removeSuffix(".html")
            "$mainUrl/embed-$code.html"
        }
        val html = runCatching {
            app.get(embedUrl, referer = referer ?: "$mainUrl/", headers = mapOf("User-Agent" to Anime4UpExtractorUtils.UA)).text
        }.getOrNull() ?: return
        val combined = Anime4UpExtractorUtils.unpackAllScripts(html)

        Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").findAll(combined)
            .map { it.groupValues[1] }
            .distinct()
            .forEach { m3u8 ->
                Anime4UpExtractorUtils.emitHls(name, name, m3u8, embedUrl, callback = callback)
            }
    }
}

// =========================================================================
// 11. Vidstack AES-128-CBC SPA Extractors (UPNShare / StreamP2P / RPMShare)
//     (`megamax.uns.bio`, `megamax.strp2p.site`, `rpmplay.me` -> `megamax.rpmplay.me`)
// =========================================================================

abstract class Anime4UpVidstackExtractor : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extractWithHint(url, referer, null, callback)
    }

    suspend fun extractWithHint(
        url: String,
        referer: String?,
        qualityHint: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = url.substringAfterLast('#', "")
            .substringBefore('&')
            .substringBefore('?')
            .trim()
            .ifEmpty {
                url.substringBefore('?').substringBefore('#').trimEnd('/')
                    .substringAfterLast('/')
                    .takeIf { it.isNotBlank() && !it.contains('.') }
                    .orEmpty()
            }
        if (videoId.isBlank()) return

        val rawHost = runCatching { URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
        val candidateOrigins = buildList {
            // Bare Vidstack domains (`rpmplay.me`, `uns.bio`, `strp2p.site`) reject requests without
            // the tenant subdomain (`400 {"message": "player is not recognized"}`).
            if (rawHost == "rpmplay.me" || rawHost == "www.rpmplay.me") add("https://megamax.rpmplay.me")
            if (rawHost == "uns.bio" || rawHost == "www.uns.bio") add("https://megamax.uns.bio")
            if (rawHost == "strp2p.site" || rawHost == "www.strp2p.site") add("https://megamax.strp2p.site")
            Anime4UpExtractorUtils.originOf(url)?.let { add(it) }
            add(mainUrl)
        }.distinct()

        val qVal = Anime4UpExtractorUtils.parseQualityValue(qualityHint)

        for (origin in candidateOrigins) {
            val apiUrl = "$origin/api/v1/video?id=$videoId"
            val reqHeaders = mapOf(
                "User-Agent" to Anime4UpExtractorUtils.UA,
                "Referer" to "$origin/",
                "Origin" to origin,
                "Accept" to "*/*"
            )
            val rawHex = runCatching {
                app.get(apiUrl, headers = reqHeaders).text.trim().trim('"')
            }.getOrNull() ?: continue

            if (rawHex.length < 32 || rawHex.startsWith("{")) continue

            val decrypted = decryptVidstackHex(rawHex) ?: continue
            val root = runCatching { JSONObject(decrypted) }.getOrNull() ?: continue

            val streamUrls = LinkedHashSet<String>()
            // 1. `cfNative` (`master.*.m3u8?k=...`) — multi-quality 200 OK master playlist
            collectVidstackUrls(root.opt("cfNative"), origin, streamUrls)
            // 2. `source` — primary origin HLS master playlist
            collectVidstackUrls(root.opt("source"), origin, streamUrls)
            // 3. Fallback `cf` only if neither `cfNative` nor `source` is present
            if (streamUrls.isEmpty()) {
                collectVidstackUrls(root.opt("cf"), origin, streamUrls)
            }

            if (streamUrls.isEmpty()) continue

            val streamHeaders = mapOf(
                "User-Agent" to Anime4UpExtractorUtils.UA,
                "Referer" to "$origin/",
                "Origin" to origin
            )
            for (hlsUrl in streamUrls) {
                Anime4UpExtractorUtils.emitHls(
                    source = name,
                    label = name,
                    m3u8Url = hlsUrl,
                    referer = "$origin/",
                    headers = streamHeaders,
                    fallbackQuality = qVal,
                    callback = callback
                )
            }
            return
        }
    }

    private fun collectVidstackUrls(node: Any?, origin: String, out: MutableSet<String>) {
        when (node) {
            is String -> {
                val u = normalizeVidstackUrl(node, origin)
                if (u != null) out.add(u)
            }
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val v = node.optString(keys.next())
                    val u = normalizeVidstackUrl(v, origin)
                    if (u != null) out.add(u)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectVidstackUrls(node.opt(i), origin, out)
                }
            }
        }
    }

    private fun normalizeVidstackUrl(raw: String, origin: String): String? {
        val clean = raw.trim()
        if (clean.isEmpty()) return null
        return when {
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "$origin$clean"
            else -> null
        }
    }

    private fun decryptVidstackHex(hexCipher: String): String? = runCatching {
        val clean = hexCipher.replace(Regex("""\s+"""), "")
        if (clean.length % 2 != 0) return null
        val cipherBytes = ByteArray(clean.length / 2) { idx ->
            clean.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(VIDSTACK_AES_KEY.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(VIDSTACK_AES_IV.toByteArray(Charsets.UTF_8))
        )
        val plain = String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        plain.trim { it <= ' ' || it.code < 0x20 }
    }.getOrNull()

    companion object {
        private const val VIDSTACK_AES_KEY = "kiemtienmua911ca"
        private const val VIDSTACK_AES_IV = "1234567890oiuytr"
    }
}

class Anime4UpUpnShareExtractor : Anime4UpVidstackExtractor() {
    override val name = "UPNShare"
    override val mainUrl = "https://megamax.uns.bio"
}

class Anime4UpStreamP2PExtractor : Anime4UpVidstackExtractor() {
    override val name = "StreamP2P"
    override val mainUrl = "https://megamax.strp2p.site"
}

class Anime4UpRpmShareExtractor : Anime4UpVidstackExtractor() {
    override val name = "RPMShare"
    override val mainUrl = "https://megamax.rpmplay.me"
}

// =========================================================================
// 12. FireStream Extractor (`firestream.to/e/<slug>` -> `firestream.site` `/resolve`)
// =========================================================================

class Anime4UpFireStreamExtractor : ExtractorApi() {
    override val name = "FireStream"
    override val mainUrl = "https://firestream.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extractWithHint(url, referer, null, subtitleCallback, callback)
    }

    suspend fun extractWithHint(
        url: String,
        referer: String?,
        qualityHint: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val slug = Regex("""/(?:e|d|v)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
                .takeIf { it.isNotBlank() }
            ?: return
        val initialOrigin = Anime4UpExtractorUtils.originOf(url) ?: mainUrl
        val embedUrl = "$initialOrigin/e/$slug"

        val res = runCatching {
            app.get(
                embedUrl,
                referer = referer ?: "$initialOrigin/",
                headers = mapOf("User-Agent" to Anime4UpExtractorUtils.UA)
            )
        }.getOrNull() ?: return

        val finalUrl = res.url.ifBlank { embedUrl }
        val finalOrigin = Anime4UpExtractorUtils.originOf(finalUrl) ?: "https://firestream.site"
        val cookieHeader = Anime4UpExtractorUtils.formatCookieHeader(
            Anime4UpExtractorUtils.collectCookies(res.okhttpResponse)
        )
        val doc = res.document

        // Optional external subtitles inside `<script id="video-data" type="application/json">`
        doc.selectFirst("script#video-data")?.let { scriptEl ->
            val rawVideoData = scriptEl.data().ifBlank { scriptEl.html() }.trim()
            runCatching {
                val subs = JSONObject(rawVideoData).optJSONObject("video")?.optJSONArray("subtitles")
                if (subs != null) {
                    for (i in 0 until subs.length()) {
                        val s = subs.optJSONObject(i) ?: continue
                        val subUrl = (s.optString("url").ifBlank { s.optString("file") })
                            .takeIf { it.startsWith("http") } ?: continue
                        val lang = s.optString("label").ifBlank { s.optString("language").ifBlank { "Arabic" } }
                        subtitleCallback(newSubtitleFile(lang, subUrl))
                    }
                }
            }
        }

        val tokenBlob = doc.selectFirst("script#token-blob")?.let { el ->
            el.data().ifBlank { el.html() }.trim()
        }?.takeIf { it.isNotBlank() } ?: return

        val postHeaders = LinkedHashMap<String, String>().apply {
            put("User-Agent", Anime4UpExtractorUtils.UA)
            put("Accept", "application/json")
            put("Content-Type", "application/json")
            put("Referer", finalUrl)
            put("Origin", finalOrigin)
            if (cookieHeader.isNotBlank()) put("Cookie", cookieHeader)
        }
        val postBody = JSONObject().put("blob", tokenBlob).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val resolveJson = runCatching {
            val txt = app.post("$finalOrigin/api/videos/$slug/resolve", headers = postHeaders, requestBody = postBody).text
            JSONObject(txt)
        }.getOrNull() ?: return

        val primaryUrl = resolveJson.optString("signedVideoUrl").takeIf { it.startsWith("http") }
        val sdUrl = resolveJson.optString("signedVideoSdUrl").takeIf { it.startsWith("http") }
        val qVal = Anime4UpExtractorUtils.parseQualityValue(qualityHint)

        val streamHeaders = mapOf(
            "User-Agent" to Anime4UpExtractorUtils.UA,
            "Referer" to "$finalOrigin/",
            "Origin" to finalOrigin
        )

        if (primaryUrl != null) {
            emitFireStreamMedia(primaryUrl, finalOrigin, streamHeaders, qVal, callback)
        }
        if (sdUrl != null && sdUrl != primaryUrl) {
            val sdQuality = if (qVal in 1..Qualities.P480.value) qVal else Qualities.P480.value
            emitFireStreamMedia(sdUrl, finalOrigin, streamHeaders, sdQuality, callback)
        }
    }

    private suspend fun emitFireStreamMedia(
        mediaUrl: String,
        origin: String,
        headers: Map<String, String>,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        if (mediaUrl.contains(".m3u8", ignoreCase = true)) {
            Anime4UpExtractorUtils.emitHls(
                source = name,
                label = name,
                m3u8Url = mediaUrl,
                referer = "$origin/",
                headers = headers,
                fallbackQuality = quality,
                callback = callback
            )
        } else {
            val qTag = Anime4UpExtractorUtils.formatQualityTag(quality)
            val label = if (qTag.isNotEmpty()) "$name ($qTag)" else name
            Anime4UpExtractorUtils.emitVideo(
                source = name,
                label = label,
                videoUrl = mediaUrl,
                referer = "$origin/",
                quality = quality,
                headers = headers,
                callback = callback
            )
        }
    }
}

// =========================================================================
// 13. Vidara Extractor (`vidaraa.cc/e/<filecode>` / `vidara.so` / `vidara.to`)
// =========================================================================

class Anime4UpVidaraExtractor : ExtractorApi() {
    override val name = "Vidara"
    override val mainUrl = "https://vidaraa.cc"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extractWithHint(url, referer, null, subtitleCallback, callback)
    }

    suspend fun extractWithHint(
        url: String,
        referer: String?,
        qualityHint: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val filecode = Regex("""/(?:e|d|v)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
                .takeIf { it.isNotBlank() }
            ?: return
        val initialOrigin = Anime4UpExtractorUtils.originOf(url) ?: mainUrl
        val embedUrl = "$initialOrigin/e/$filecode"

        val pageRes = runCatching {
            app.get(
                embedUrl,
                referer = referer ?: "$initialOrigin/",
                headers = mapOf("User-Agent" to Anime4UpExtractorUtils.UA)
            )
        }.getOrNull()

        val finalEmbedUrl = pageRes?.url?.takeIf { it.isNotBlank() } ?: embedUrl
        val finalOrigin = Anime4UpExtractorUtils.originOf(finalEmbedUrl) ?: initialOrigin
        val cookieHeader = pageRes?.okhttpResponse?.let {
            Anime4UpExtractorUtils.formatCookieHeader(Anime4UpExtractorUtils.collectCookies(it))
        }.orEmpty()

        val apiHeaders = LinkedHashMap<String, String>().apply {
            put("User-Agent", Anime4UpExtractorUtils.UA)
            put("Accept", "application/json")
            put("Content-Type", "application/json")
            put("Referer", finalEmbedUrl)
            put("Origin", finalOrigin)
            if (cookieHeader.isNotBlank()) put("Cookie", cookieHeader)
        }
        val payload = JSONObject()
            .put("filecode", filecode)
            .put("device", "web")
            .put("codecs", JSONArray())
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val streamJson = runCatching {
            val txt = app.post("$finalOrigin/api/stream", headers = apiHeaders, requestBody = payload).text
            JSONObject(txt)
        }.getOrNull() ?: return

        streamJson.optJSONArray("subtitles")?.let { subs ->
            for (i in 0 until subs.length()) {
                val s = subs.optJSONObject(i) ?: continue
                if (s.optInt("type", 0) != 0) continue
                val subUrl = s.optString("file_path").takeIf { it.startsWith("http") } ?: continue
                val lang = s.optString("language").ifBlank { s.optString("label").ifBlank { "Arabic" } }
                subtitleCallback(newSubtitleFile(lang, subUrl))
            }
        }

        val streamingUrl = streamJson.optString("streaming_url").takeIf { it.startsWith("http") } ?: return
        val qVal = Anime4UpExtractorUtils.parseQualityValue(qualityHint, streamJson.optString("title"))
        val streamHeaders = mapOf(
            "User-Agent" to Anime4UpExtractorUtils.UA,
            "Referer" to "$finalOrigin/",
            "Origin" to finalOrigin
        )
        Anime4UpExtractorUtils.emitHls(
            source = name,
            label = name,
            m3u8Url = streamingUrl,
            referer = "$finalOrigin/",
            headers = streamHeaders,
            fallbackQuality = qVal,
            callback = callback
        )
    }
}

// =========================================================================
// 14. EarnVids Extractor (`morencius.com/v/<code>`, `movearnpre`, `dintezuvio`, etc.)
// =========================================================================

class Anime4UpEarnVidsExtractor : ExtractorApi() {
    override val name = "EarnVids"
    override val mainUrl = "https://morencius.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extractWithHint(url, referer, null, callback)
    }

    suspend fun extractWithHint(
        url: String,
        referer: String?,
        qualityHint: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = Regex("""/(?:v|d|e|embed|file|f)/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)
            ?: url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
                .removeSuffix(".html")
                .takeIf { it.isNotBlank() }
            ?: return
        val initialOrigin = Anime4UpExtractorUtils.originOf(url) ?: mainUrl

        // Note: on `morencius.com`, `/e/<code>` returns 404 and `/d/<code>` is the download page;
        // `/v/<code>` (and `/embed/<code>`) serve the packed JWPlayer stream links.
        for (pathPrefix in listOf("v", "embed")) {
            val candidateUrl = "$initialOrigin/$pathPrefix/$code"
            val res = runCatching {
                app.get(
                    candidateUrl,
                    referer = referer ?: "$initialOrigin/",
                    headers = mapOf("User-Agent" to Anime4UpExtractorUtils.UA)
                )
            }.getOrNull() ?: continue

            if (res.code >= 400) continue
            val finalEmbedUrl = res.url.ifBlank { candidateUrl }
            val finalOrigin = Anime4UpExtractorUtils.originOf(finalEmbedUrl) ?: initialOrigin

            // Two unpack passes in case a script is double-packed
            val pass1 = Anime4UpExtractorUtils.unpackAllScripts(res.text)
            val combined = Anime4UpExtractorUtils.unpackAllScripts(pass1)

            val candidateStreams = LinkedHashSet<String>()
            Regex("""var\s+links\s*=\s*(\{[^;]+\})""").find(combined)?.groupValues?.get(1)?.let { rawLinks ->
                runCatching {
                    val obj = JSONObject(rawLinks)
                    for (key in listOf("hls4", "hls2", "hls3", "hls")) {
                        val v = obj.optString(key).trim()
                        resolveEarnVidsStreamUrl(v, finalOrigin)?.let { candidateStreams.add(it) }
                    }
                }
            }
            if (candidateStreams.isEmpty()) {
                for (key in listOf("hls4", "hls2", "hls3", "hls")) {
                    Regex("""["']$key["']\s*:\s*["']([^"']+)["']""").findAll(combined).forEach { m ->
                        resolveEarnVidsStreamUrl(m.groupValues[1], finalOrigin)?.let { candidateStreams.add(it) }
                    }
                }
            }
            if (candidateStreams.isEmpty()) {
                Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").findAll(combined).forEach { m ->
                    candidateStreams.add(m.groupValues[1])
                }
            }

            if (candidateStreams.isEmpty()) continue

            val qVal = Anime4UpExtractorUtils.parseQualityValue(qualityHint)
            val streamHeaders = mapOf(
                "User-Agent" to Anime4UpExtractorUtils.UA,
                "Referer" to finalEmbedUrl,
                "Origin" to finalOrigin
            )
            for (hlsUrl in candidateStreams) {
                Anime4UpExtractorUtils.emitHls(
                    source = name,
                    label = name,
                    m3u8Url = hlsUrl,
                    referer = finalEmbedUrl,
                    headers = streamHeaders,
                    fallbackQuality = qVal,
                    callback = callback
                )
            }
            return
        }
    }

    private fun resolveEarnVidsStreamUrl(raw: String, origin: String): String? {
        val clean = raw.trim().replace("\\/", "/")
        if (clean.isEmpty()) return null
        return when {
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "$origin$clean"
            else -> null
        }
    }
}

// =========================================================================
// 15. Mixdrop Extractor (`mixdrop.ag/e/<code>` -> `mxdrop.click` `MDCore.wurl`)
// =========================================================================

class Anime4UpMixdropExtractor : ExtractorApi() {
    override val name = "Mixdrop"
    override val mainUrl = "https://mixdrop.ag"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extractWithHint(url, referer, null, callback)
    }

    suspend fun extractWithHint(
        url: String,
        referer: String?,
        qualityHint: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = Regex("""/(?:e|f)/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)
            ?: url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
                .takeIf { it.isNotBlank() }
            ?: return
        val initialOrigin = Anime4UpExtractorUtils.originOf(url) ?: mainUrl
        val embedUrl = "$initialOrigin/e/$code"

        val res = runCatching {
            app.get(
                embedUrl,
                referer = referer ?: "$initialOrigin/",
                headers = mapOf("User-Agent" to Anime4UpExtractorUtils.UA)
            )
        }.getOrNull() ?: return

        val finalEmbedUrl = res.url.ifBlank { embedUrl }
        val finalOrigin = Anime4UpExtractorUtils.originOf(finalEmbedUrl) ?: initialOrigin
        val combined = Anime4UpExtractorUtils.unpackAllScripts(res.text)

        val rawWurl = Regex("""MDCore\.wurl\s*=\s*["']([^"']+)["']""")
            .find(combined)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return

        val videoUrl = when {
            rawWurl.startsWith("//") -> "https:$rawWurl"
            rawWurl.startsWith("http") -> rawWurl
            rawWurl.startsWith("/") -> "$finalOrigin$rawWurl"
            else -> "https://$rawWurl"
        }

        val qVal = Anime4UpExtractorUtils.parseQualityValue(qualityHint, videoUrl)
        val qTag = Anime4UpExtractorUtils.formatQualityTag(qVal)
        val label = if (qTag.isNotEmpty()) "$name ($qTag)" else name
        val headers = mapOf(
            "User-Agent" to Anime4UpExtractorUtils.UA,
            "Referer" to "$finalOrigin/",
            "Origin" to finalOrigin
        )
        Anime4UpExtractorUtils.emitVideo(
            source = name,
            label = label,
            videoUrl = videoUrl,
            referer = "$finalOrigin/",
            quality = qVal,
            headers = headers,
            callback = callback
        )
    }
}

