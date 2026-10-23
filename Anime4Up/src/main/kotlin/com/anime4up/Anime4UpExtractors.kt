package com.anime4up

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.jsoup.parser.Parser
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

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
        "${u.scheme}://${u.host}"
    }.getOrNull()

    suspend fun emitHls(
        source: String,
        label: String,
        m3u8Url: String,
        referer: String,
        headers: Map<String, String> = mapOf("User-Agent" to UA, "Referer" to referer),
        fallbackQuality: Int = Qualities.Unknown.value,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val generated = runCatching {
            M3u8Helper.generateM3u8(source, m3u8Url, referer, headers = headers)
        }.getOrDefault(emptyList())

        if (generated.isNotEmpty()) {
            generated.forEach(callback)
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
    private val SKIP_HOSTS = listOf(
        "mega.nz",          // client-side AES-CTR browser-only decryption without SDK
        "vikingfile.",      // interactive Cloudflare Turnstile gate
        "vik1ngfile.",
        "workupload.com",   // interactive bot captcha on API
        "file-upload.com",  // multi-step countdown captcha
        "file-upload.org"
    )

    fun canonicalDedupKey(rawUrl: String): String {
        val clean = rawUrl.trim()
        val low = clean.lowercase()
        // Deduplicate MegaMax & Share4Max watch/download variants carrying the same hash
        Regex("""(?:megamax\.me|share4max\.com)/(?:iframe|d|download|e)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
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

        return low.removeSuffix("/")
    }

    suspend fun resolve(
        rawUrl: String,
        referer: String,
        qualityHint: String?,
        serverHint: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = when {
            rawUrl.startsWith("//") -> "https:$rawUrl"
            else -> rawUrl.trim()
        }
        if (!url.startsWith("http")) return

        val host = runCatching { URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
        if (host.isBlank() || SKIP_HOSTS.any { host.contains(it) }) return

        try {
            when {
                host.contains("megamax.me") || host.contains("share4max.com") -> {
                    Anime4UpMegaMaxExtractor().extractWithHint(url, referer, qualityHint, subtitleCallback, callback)
                }
                host.contains("mediafire.com") -> {
                    Anime4UpMediaFireExtractor().extractWithHint(url, referer, qualityHint, callback)
                }
                host.contains("voe.sx") || serverHint?.contains("voe", ignoreCase = true) == true -> {
                    Anime4UpVoeExtractor().extractWithHint(url, referer, qualityHint, subtitleCallback, callback)
                }
                host.contains("vkvideo.ru") || host == "vk.com" || host.endsWith(".vk.com") || host == "vk.ru" -> {
                    Anime4UpVkExtractor().getUrl(url, referer, subtitleCallback, callback)
                }
                host.contains("mp4upload.com") -> {
                    Anime4UpMp4UploadExtractor().extractWithHint(url, referer, qualityHint, callback)
                }
                host.contains("uqload.") -> {
                    Anime4UpUqloadExtractor().extractWithHint(url, referer, qualityHint, callback)
                }
                host.contains("playmogo.") || host.contains("dsvplay.") || host.contains("dood") ||
                    host.contains("d000") || host.contains("myvidplay.") || host.contains("vide0.") ||
                    serverHint?.contains("dood", ignoreCase = true) == true -> {
                    Anime4UpDoodExtractor().extractWithHint(url, referer, qualityHint, callback)
                }
                host.contains("c1oiijc.shop") || serverHint?.contains("anime4up", ignoreCase = true) == true -> {
                    Anime4UpVnxPlayerExtractor().extractWithHint(url, referer, serverHint, qualityHint, subtitleCallback, callback)
                }
                host.contains("byse") -> {
                    Anime4UpByseExtractor().getUrl(url, referer, subtitleCallback, callback)
                }
                host.contains("lulustream") || host.contains("luluvdo") -> {
                    Anime4UpLuluStreamExtractor().extractWithHint(url, referer, qualityHint, callback)
                }
                host.contains("savefiles.") -> {
                    Anime4UpSaveFilesExtractor().extractWithHint(url, referer, qualityHint, callback)
                }
                host.contains("4shared.com") -> {
                    Anime4UpFourSharedExtractor().extractWithHint(url, referer, qualityHint, callback)
                }
                host.contains("gofile.io") -> {
                    Anime4UpGoFileExtractor().extractWithHint(url, referer, qualityHint, callback)
                }
                host.contains("moshahda.") -> {
                    Anime4UpMoshahdaExtractor().getUrl(url, referer, subtitleCallback, callback)
                }
                else -> {
                    loadExtractor(url, referer, subtitleCallback, callback)
                }
            }
        } catch (_: Throwable) {
            // Never allow one broken mirror to fail the remaining servers
        }
    }
}

// =========================================================================
// 1. MegaMax & Share4Max Multi-Host Aggregator (1080p / 720p / 480p / 360p)
// =========================================================================

open class Anime4UpMegaMaxExtractor : ExtractorApi() {
    override val name = "MegaMax"
    override val mainUrl = "https://megamax.me"
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
        // Normalize /d/<id> and /download/<id> -> /iframe/<id> so we get every mirror & quality at once
        val id = Regex("""/(?:iframe|d|download|e)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
        val origin = Anime4UpExtractorUtils.originOf(url) ?: mainUrl
        val iframeUrls = buildList {
            if (id != null) {
                add("$origin/iframe/$id")
                if (!origin.contains("megamax.me")) add("https://megamax.me/iframe/$id")
                if (!origin.contains("share4max.com")) add("https://share4max.com/iframe/$id")
            } else {
                add(url)
            }
        }

        var pageJsonStr: String? = null
        var activeIframeUrl = iframeUrls.first()
        for (candidate in iframeUrls) {
            val doc = runCatching {
                app.get(
                    candidate,
                    referer = referer ?: "$origin/",
                    headers = mapOf("User-Agent" to Anime4UpExtractorUtils.UA)
                ).document
            }.getOrNull() ?: continue

            val raw = doc.selectFirst("script[data-page=app]")?.html()?.trim()
                ?: doc.selectFirst("#app[data-page]")?.attr("data-page")?.let { Parser.unescapeEntities(it, false) }
            if (!raw.isNullOrBlank()) {
                pageJsonStr = raw
                activeIframeUrl = candidate
                break
            }
        }

        val dataPage = pageJsonStr ?: return
        val streams = runCatching {
            JSONObject(dataPage)
                .optJSONObject("props")
                ?.optJSONObject("streams")
                ?.optJSONObject("data")
                ?.optJSONArray("arr")
        }.getOrNull() ?: return

        data class MirrorTask(val mirrorUrl: String, val driver: String, val qualityLabel: String)
        val tasks = ArrayList<MirrorTask>()
        val seenUrls = HashSet<String>()

        for (i in 0 until streams.length()) {
            val tier = streams.optJSONObject(i) ?: continue
            val tierLabel = tier.optString("label", qualityHint.orEmpty())
            val mirrors = tier.optJSONArray("mirrors") ?: continue
            for (j in 0 until mirrors.length()) {
                val m = mirrors.optJSONObject(j) ?: continue
                val driver = m.optString("driver").lowercase().trim()
                var link = m.optString("link").trim()
                if (link.isBlank()) continue
                if (link.startsWith("//")) link = "https:$link"
                if (!link.startsWith("http") || !seenUrls.add(link)) continue
                tasks.add(MirrorTask(link, driver, tierLabel))
            }
        }

        val sem = Semaphore(4)
        coroutineScope {
            tasks.map { task ->
                async {
                    sem.withPermit {
                        val qHint = task.qualityLabel.ifBlank { qualityHint }
                        Anime4UpExtractorSuite.resolve(
                            rawUrl = task.mirrorUrl,
                            referer = activeIframeUrl,
                            qualityHint = qHint,
                            serverHint = task.driver,
                            subtitleCallback = subtitleCallback,
                            callback = callback
                        )
                    }
                }
            }.awaitAll()
        }
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
        val code = Regex("""/(?:e|d|v)/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1) ?: return
        val origin = Anime4UpExtractorUtils.originOf(url) ?: mainUrl
        val embedReferer = "$origin/e/$code"

        val details = fetchDetails(origin, code, embedReferer) ?: return
        val embedDomain = details.optString("embed_frame_url")
            .takeIf { it.isNotBlank() }
            ?.let { Anime4UpExtractorUtils.originOf(it) }
            ?: origin

        val playbackObj = if (embedDomain != origin) {
            fetchDetails(embedDomain, code, "$embedDomain/e/$code")?.optJSONObject("playback")
                ?: details.optJSONObject("playback")
        } else {
            details.optJSONObject("playback")
        } ?: return

        val decryptedStr = decryptPlayback(playbackObj) ?: return
        val decrypted = runCatching { JSONObject(decryptedStr) }.getOrNull() ?: return
        val sources = decrypted.optJSONArray("sources") ?: return
        val edgeAuth = solvePow(playbackObj.optJSONObject("edge_challenge"))

        val headers = LinkedHashMap<String, String>().apply {
            put("Referer", "$embedDomain/")
            put("Origin", embedDomain)
            put("User-Agent", Anime4UpExtractorUtils.UA)
            if (edgeAuth != null) put("X-Edge-Auth", edgeAuth)
        }

        for (i in 0 until sources.length()) {
            val s = sources.optJSONObject(i) ?: continue
            val streamUrl = s.optString("url").takeIf { it.startsWith("http") } ?: continue
            val qVal = Anime4UpExtractorUtils.parseQualityValue(
                s.optString("label").ifBlank { s.optString("quality") },
                s.optInt("height", 0).takeIf { it > 0 }?.let { "${it}p" }.orEmpty()
            )
            if (streamUrl.contains(".m3u8")) {
                Anime4UpExtractorUtils.emitHls(name, name, streamUrl, "$embedDomain/", headers, qVal, callback)
            } else {
                Anime4UpExtractorUtils.emitVideo(name, name, streamUrl, "$embedDomain/", qVal, headers, callback)
            }
        }
    }

    private suspend fun fetchDetails(base: String, code: String, referer: String): JSONObject? = runCatching {
        val text = app.get(
            "$base/api/videos/$code/embed/details",
            referer = referer,
            headers = mapOf("Accept" to "application/json", "User-Agent" to Anime4UpExtractorUtils.UA)
        ).text
        JSONObject(text)
    }.getOrNull()

    private fun b64UrlDecode(s: String): ByteArray {
        var padded = s.replace('-', '+').replace('_', '/')
        val rem = padded.length % 4
        if (rem != 0) padded += "=".repeat(4 - rem)
        return Base64.decode(padded, Base64.DEFAULT)
    }

    private fun decryptPlayback(pb: JSONObject): String? {
        val keyParts = pb.optJSONArray("key_parts") ?: return null
        if (keyParts.length() < 2) return null
        val key1 = b64UrlDecode(keyParts.optString(0)) + b64UrlDecode(keyParts.optString(1))
        val iv1 = b64UrlDecode(pb.optString("iv"))
        val payload1 = b64UrlDecode(pb.optString("payload"))
        aesGcmDecrypt(key1, iv1, payload1)?.let { return it }

        val p2 = pb.optString("payload2")
        val iv2s = pb.optString("iv2")
        if (p2.isNotBlank() && iv2s.isNotBlank()) {
            val decryptKeys = pb.optJSONObject("decrypt_keys")
            val edge1 = decryptKeys?.optString("edge_1")?.takeIf { it.isNotBlank() }
            val edge2 = decryptKeys?.optString("edge_2")?.takeIf { it.isNotBlank() }
            if (edge1 != null && edge2 != null) {
                val key2 = b64UrlDecode(edge1) + b64UrlDecode(edge2)
                return aesGcmDecrypt(key2, b64UrlDecode(iv2s), b64UrlDecode(p2))
            }
        }
        return null
    }

    private fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, cipherBytes: ByteArray): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }.getOrNull()

    private fun solvePow(challenge: JSONObject?): String? {
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
        val code = url.trimEnd('/').substringAfterLast('/')
        if (code.isBlank()) return
        val qVal = Anime4UpExtractorUtils.parseQualityValue(qualityHint)
        for (host in listOf("luluvdo.com", "lulustream.com")) {
            val embedUrl = "https://$host/e/$code"
            val html = runCatching {
                app.get(
                    embedUrl,
                    referer = referer ?: "$mainUrl/",
                    headers = mapOf("User-Agent" to Anime4UpExtractorUtils.UA)
                ).text
            }.getOrNull() ?: continue

            val combined = Anime4UpExtractorUtils.unpackAllScripts(html)
            val m3u8 = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(combined)?.groupValues?.get(1) ?: continue
            Anime4UpExtractorUtils.emitHls(
                source = name,
                label = name,
                m3u8Url = m3u8,
                referer = embedUrl,
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
        val fileCode = Regex("""savefiles\.com/(?:e/|d/)?([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1) ?: return
        val embedUrl = "$mainUrl/e/$fileCode"
        val html = runCatching {
            app.post(
                url = "$mainUrl/dl",
                headers = mapOf(
                    "Referer" to embedUrl,
                    "Origin" to mainUrl,
                    "User-Agent" to Anime4UpExtractorUtils.UA,
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                data = mapOf(
                    "op" to "embed",
                    "file_code" to fileCode,
                    "auto" to "1",
                    "referer" to (referer ?: "$mainUrl/")
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
            referer = "$mainUrl/",
            fallbackQuality = qVal,
            callback = callback
        )
    }
}

// =========================================================================
// 10. Anime4Up Native Player (4x.c1oiijc.shop / VnxPlayer) & Secondary Hosts
// =========================================================================

class Anime4UpVnxPlayerExtractor : ExtractorApi() {
    override val name = "Anime4Up Server"
    override val mainUrl = "https://4x.c1oiijc.shop"
    override val requiresReferer = true

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
        val siteOrigin = referer?.let { Anime4UpExtractorUtils.originOf(it) } ?: "https://w1.anime4up.rest"
        val html = runCatching {
            app.get(
                url,
                referer = "$siteOrigin/",
                headers = mapOf(
                    "User-Agent" to Anime4UpExtractorUtils.UA,
                    "Referer" to "$siteOrigin/",
                    "Sec-Fetch-Dest" to "iframe",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "cross-site"
                )
            ).text
        }.getOrNull() ?: return

        if (html.contains("Domain Restricted", ignoreCase = true) || html.length < 200) return

        val combined = Anime4UpExtractorUtils.unpackAllScripts(html)
        val displaySource = serverHint?.takeIf { it.isNotBlank() } ?: name
        val qVal = Anime4UpExtractorUtils.parseQualityValue(qualityHint)
        val selfOrigin = Anime4UpExtractorUtils.originOf(url) ?: mainUrl
        val playHeaders = mapOf(
            "User-Agent" to Anime4UpExtractorUtils.UA,
            "Referer" to url,
            "Origin" to selfOrigin
        )

        // Direct m3u8 / mp4 streams inside VnxPlayer
        Regex("""["'](https?://[^"'\s]+\.m3u8[^"'\s]*)["']""").findAll(combined)
            .map { it.groupValues[1].replace("\\/", "/") }
            .distinct()
            .forEach { m3u8 ->
                Anime4UpExtractorUtils.emitHls(displaySource, displaySource, m3u8, url, playHeaders, qVal, callback)
            }

        Regex("""["'](https?://[^"'\s]+\.mp4[^"'\s]*)["']""").findAll(combined)
            .map { it.groupValues[1].replace("\\/", "/") }
            .distinct()
            .forEach { mp4 ->
                Anime4UpExtractorUtils.emitVideo(displaySource, displaySource, mp4, url, qVal, playHeaders, callback)
            }

        // Subtitle tracks if embedded
        Regex("""["']file["']\s*:\s*["'](https?://[^"']+\.(?:vtt|srt|ass)[^"']*)["']""").findAll(combined)
            .map { it.groupValues[1].replace("\\/", "/") }
            .distinct()
            .forEach { subUrl ->
                subtitleCallback(SubtitleFile("Arabic", subUrl))
            }

        // Nested iframe fallback
        Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE).findAll(combined)
            .map { it.groupValues[1] }
            .filterNot { it == url }
            .distinct()
            .forEach { nested ->
                loadExtractor(nested, url, subtitleCallback, callback)
            }
    }
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
