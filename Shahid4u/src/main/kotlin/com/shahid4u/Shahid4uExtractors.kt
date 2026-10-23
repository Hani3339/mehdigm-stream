package com.shahid4u

import android.util.Base64
import android.util.Log
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.math.BigInteger
import java.net.URI
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Comprehensive suite of modern video & download host extractors for Shahid4u (v12).
 *
 * Covers every active watch & multi-quality download provider on `shaahidd4u.net`:
 *  - Vinovo (`vinovo.to`, `vinovo.si`) — `/e/<id>` & `/d/<id>` -> `/api/file/url/<id>` MP4/HLS
 *  - Vidaraa / Vidara (`vidaraa.cc`, `vidara.so`, `vidara.to`) — `/e/<id>` & `/v/<id>` -> `/api/stream` HLS
 *  - EarnVids / FastVid (`fastvid.cam`, `dingtezuni.com`, `earnvids.*`, `vidhide.*`, `dintezuvio.*`, `minochinos.*`)
 *  - Byse (`bysevepoin.com`, `byseqekaho.com`, `bysesukior.com`, `bysezejataos.com`, `n1mwq.org`) — ECDSA PoW + AES-GCM
 *  - MixDrop (`m1xdrop.bz`, `mxdrop.click`, `mixdrop.top`, `mixdrop.*`, `mdy48tn97.com`) — `/e/<id>` & `/f/<id>`
 *  - DoodStream / Playmogo (`playmogo.com`, `dood.*`, `dsvplay.com`, `myvidplay.com`, `d000d.com`, `vide0.net`, `do7go.com`)
 *  - VOE (`voe.sx` + JS redirect mirrors like `derricknationtop.com`) — 7-step JSON decoder (HLS + MP4)
 *  - Uqload (`uqload.vc`, `uqload.is`, `uqload.to`, `uqload.com`, `uqload.net`) — XFS `POST /dl` HLS + legacy MP4
 *  - SaveFiles / StreamHLS (`streamhls.to`, `savefiles.com`) — direct & `POST /dl` HLS
 *  - Vids.st (`vids.st`) — TrustAll SSL + ArtPlayer `const url` MP4/HLS + subtitles
 *  - Upzur (`upzur.com`) — `POST op=download2` direct multi-quality MP4
 *  - Ok.ru (`ok.ru`, `odnoklassniki.ru`) — `data-options` HLS + multi-quality MP4s
 *  - Vidstack / UPNShare (`upns.pro`, `rpmplay.me`, `streamp2p.com`) — AES-128-CBC `/api/v1/video`
 */
object Shahid4uExtractors {

    private const val TAG = "Shahid4uExt"

    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private class EphemeralCookieJar : CookieJar {
        private val map = ConcurrentHashMap<String, MutableList<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val list = map.getOrPut(url.host.lowercase()) { mutableListOf() }
            synchronized(list) {
                for (c in cookies) {
                    list.removeAll { it.name == c.name }
                    list.add(c)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val list = map[url.host.lowercase()] ?: return emptyList()
            synchronized(list) { return list.toList() }
        }
    }

    fun newSessionClient(): OkHttpClient =
        app.baseClient.newBuilder()
            .cookieJar(EphemeralCookieJar())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

    fun originOf(url: String): String? = runCatching {
        val u = URI(url)
        if (u.scheme != null && u.host != null) "${u.scheme}://${u.host}" else null
    }.getOrNull()

    fun hostOf(url: String): String = runCatching {
        URI(url).host?.lowercase()?.removePrefix("www.").orEmpty()
    }.getOrDefault("")

    fun detectQualityFromText(text: String?, fallback: Int = Qualities.Unknown.value): Int {
        if (text.isNullOrBlank()) return fallback
        val m = Regex("""(?<!\d)(2160|1440|1080|720|480|360|240)p?(?!\d)""", RegexOption.IGNORE_CASE).find(text)
        if (m != null) {
            return m.groupValues[1].toIntOrNull() ?: fallback
        }
        val q = getQualityFromName(text)
        return if (q != Qualities.Unknown.value) q else fallback
    }

    /**
     * Canonical `(providerFamily, fileCode)` key so identical videos from Watch + Download 1080p
     * are extracted only once, while distinct 720p / 480p transcodes are preserved!
     */
    fun canonicalVideoKey(rawUrl: String): String {
        val clean = rawUrl.trim()
        val host = hostOf(clean)
        val path = runCatching { URI(clean).path.orEmpty() }.getOrDefault("")
        val family = when {
            host.contains("vinovo") -> "vinovo"
            host.contains("vidara") -> "vidara"
            host.contains("fastvid") || host.contains("earnvids") || host.contains("dingtezuni") ||
                host.contains("dintezuvio") || host.contains("vidhide") || host.contains("minochinos") ||
                host.contains("movearnpre") || host.contains("smoothpre") -> "earnvids"
            host.contains("byse") || host == "n1mwq.org" -> "byse"
            host.contains("m1xdrop") || host.contains("mxdrop") || host.contains("mixdrop") ||
                host.contains("mdy48tn97") || host.contains("mdbekjwqa") || host.contains("mdfx9dc8n") -> "mixdrop"
            host.contains("playmogo") || host.contains("dood") || host.contains("dsvplay") ||
                host.contains("myvidplay") || host.contains("d000") || host.contains("vide0") ||
                host.contains("do7go") -> "dood"
            host.contains("voe.") || host.contains("derricknationtop") -> "voe"
            host.contains("uqload") -> "uqload"
            host.contains("savefiles") || host.contains("streamhls") -> "savefiles"
            host == "vids.st" || host.endsWith(".vids.st") -> "vidsst"
            host.contains("upzur") -> "upzur"
            host == "ok.ru" || host.endsWith(".ok.ru") || host.contains("odnoklassniki") -> "okru"
            else -> host
        }
        val seg = path.trim('/')
            .substringAfterLast('/')
            .removePrefix("embed-")
            .removeSuffix(".html")
            .trim()
        return if (seg.length >= 5) "$family:$seg" else clean
    }

    /**
     * Returns true if this host has a supported direct video/HLS extractor (used to prioritize
     * `/download/<slug>` `/m/<hash>` resolution without wasting requests on captcha-locked file lockers).
     */
    fun isSupportedHostName(serverLabel: String): Boolean {
        val s = serverLabel.trim().lowercase()
        return s.contains("vinovo") ||
            s.contains("vidara") ||
            s.contains("fastvid") ||
            s.contains("earnvids") ||
            s.contains("byse") ||
            s.contains("mixdrop") ||
            s.contains("m1xdrop") ||
            s.contains("dood") ||
            s.contains("playmogo") ||
            s.contains("voe") ||
            s.contains("uqload") ||
            s.contains("savefiles") ||
            s.contains("streamhls") ||
            s.contains("vids") ||
            s.contains("upzur") ||
            s == "ok" || s.contains("ok.ru") ||
            s.contains("streamwish") ||
            s.contains("filemoon") ||
            s.contains("vidmoly") ||
            s.contains("lulustream") ||
            s.contains("mp4upload")
    }

    /**
     * Master dispatcher supporting optional `qualityHint` (e.g., `1080`, `720`, `480` from `/download/<slug>`).
     */
    suspend fun extractAny(
        rawUrl: String,
        referer: String?,
        qualityHint: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = rawUrl.trim().takeIf { it.startsWith("http") } ?: return false
        val host = hostOf(url)
        var emitted = false
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            emitted = true
            callback(link)
        }

        try {
            when {
                host.contains("vinovo") ->
                    extractVinovo(url, referer, qualityHint, subtitleCallback, wrappedCallback)

                host.contains("vidara") ->
                    extractVidara(url, referer, qualityHint, subtitleCallback, wrappedCallback)

                host.contains("fastvid") || host.contains("earnvids") || host.contains("dingtezuni") ||
                    host.contains("dintezuvio") || host.contains("vidhide") || host.contains("minochinos") ||
                    host.contains("movearnpre") || host.contains("smoothpre") ->
                    extractEarnVids(url, referer, qualityHint, subtitleCallback, wrappedCallback)

                host.contains("byse") || host == "n1mwq.org" ->
                    extractByse(url, referer, qualityHint, subtitleCallback, wrappedCallback)

                host.contains("m1xdrop") || host.contains("mxdrop") || host.contains("mixdrop") ||
                    host.contains("mdy48tn97") || host.contains("mdbekjwqa") || host.contains("mdfx9dc8n") ->
                    extractMixDrop(url, referer, qualityHint, subtitleCallback, wrappedCallback)

                host.contains("playmogo") || host.contains("dood") || host.contains("dsvplay") ||
                    host.contains("myvidplay") || host.contains("d000") || host.contains("vide0") ||
                    host.contains("do7go") ->
                    extractDood(url, referer, qualityHint, wrappedCallback)

                host.contains("voe.") || host.contains("derricknationtop") ->
                    extractVoe(url, referer, qualityHint, subtitleCallback, wrappedCallback)

                host.contains("uqload") ->
                    extractUqload(url, referer, qualityHint, wrappedCallback)

                host.contains("savefiles") || host.contains("streamhls") ->
                    extractSaveFiles(url, referer, qualityHint, wrappedCallback)

                host == "vids.st" || host.endsWith(".vids.st") ->
                    extractVidsSt(url, referer, qualityHint, subtitleCallback, wrappedCallback)

                host.contains("upzur") ->
                    extractUpzur(url, referer, qualityHint, wrappedCallback)

                host == "ok.ru" || host.endsWith(".ok.ru") || host.contains("odnoklassniki") ->
                    extractOkRu(url, referer, wrappedCallback)

                host.contains("upns.") || host.contains("uns.bio") || host.contains("rpmplay") ||
                    host.contains("streamp2p") || host.contains("p2pplay") ->
                    extractVidstackAes(url, referer, wrappedCallback)
            }

            if (!emitted) {
                loadExtractor(url, referer, subtitleCallback, wrappedCallback)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Log.d(TAG, "extractAny failed on $url: ${e.message}")
        }

        return emitted
    }

    // ── 1. Vinovo (`vinovo.to`, `vinovo.si`) ─────────────────────────────────

    suspend fun extractVinovo(
        url: String,
        referer: String?,
        qualityHint: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = withContext(Dispatchers.IO) {
        val origin = originOf(url) ?: "https://vinovo.to"
        val fileCode = URI(url).path.trim('/').substringAfterLast('/').trim()
        if (fileCode.isBlank()) return@withContext
        val embedUrl = "$origin/e/$fileCode"

        val client = newSessionClient()
        val html = runCatching {
            val req = Request.Builder()
                .url(embedUrl)
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", referer ?: "$origin/")
                .build()
            client.newCall(req).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
        }.getOrDefault("")
        if (html.isBlank()) return@withContext

        val doc = Jsoup.parse(html, embedUrl)
        val metaToken = doc.selectFirst("meta[name=token]")?.attr("content")?.trim().orEmpty()
        val videoEl = doc.selectFirst("video#player, video[data-base]")
        val dataBase = videoEl?.attr("data-base")?.trim()?.takeIf { it.startsWith("http") } ?: origin
        val titleQuality = detectQualityFromText(doc.title(), qualityHint ?: Qualities.P1080.value)
        val finalQual = qualityHint ?: titleQuality

        // Subtitles if present inside <video><track ...>
        doc.select("track[src]").forEach { tr ->
            val subSrc = tr.absUrl("src").ifBlank { tr.attr("src") }
            if (subSrc.startsWith("http")) {
                val label = tr.attr("label").ifBlank { tr.attr("srclang").ifBlank { "Arabic" } }
                subtitleCallback(newSubtitleFile(label, subSrc))
            }
        }

        if (metaToken.isBlank()) return@withContext

        val apiBody = FormBody.Builder()
            .add("recaptcha", "")
            .add("token", metaToken)
            .build()

        val apiJson = runCatching {
            val req = Request.Builder()
                .url("$origin/api/file/url/$fileCode")
                .post(apiBody)
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Origin", origin)
                .header("Referer", embedUrl)
                .build()
            client.newCall(req).execute().use { res ->
                if (res.isSuccessful) JSONObject(res.body?.string().orEmpty()) else null
            }
        }.getOrNull() ?: return@withContext

        val streamTok = apiJson.optString("token").trim()
        if (streamTok.isBlank()) return@withContext

        val decodedTok = runCatching { URLDecoder.decode(streamTok, "UTF-8") }.getOrDefault(streamTok)
        val streamUrl = if (decodedTok.startsWith("http")) {
            decodedTok
        } else {
            "${dataBase.trimEnd('/')}/stream/${decodedTok.trimStart('/')}"
        }

        val headers = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "$origin/"
        )

        val linkName = if (qualityHint != null) "Vinovo" else "Vinovo"
        if (streamUrl.contains(".m3u8", ignoreCase = true)) {
            M3u8Helper.generateM3u8(linkName, streamUrl, "$origin/", quality = qualityHint, headers = headers)
                .forEach(callback)
        } else {
            callback(
                newExtractorLink(
                    source = linkName,
                    name = linkName,
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$origin/"
                    this.quality = finalQual
                    this.headers = headers
                }
            )
        }
    }

    // ── 2. Vidaraa / Vidara (`vidaraa.cc`, `vidara.so`, `vidara.to`) ─────────

    suspend fun extractVidara(
        url: String,
        referer: String?,
        qualityHint: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = withContext(Dispatchers.IO) {
        val fileCode = URI(url).path.trim('/')
            .substringAfterLast('/')
            .substringBefore('?')
            .trim()
        if (fileCode.isBlank()) return@withContext

        val candidateOrigins = linkedSetOf<String>().apply {
            originOf(url)?.let { if (!it.contains("vidaraa.so")) add(it) }
            add("https://vidaraa.cc")
            add("https://vidara.so")
            add("https://vidara.to")
        }

        val jsonPayload = JSONObject().put("filecode", fileCode).toString()
        var streamJson: JSONObject? = null
        var workingOrigin = "https://vidaraa.cc"

        for (candOrigin in candidateOrigins) {
            val resObj = runCatching {
                val req = Request.Builder()
                    .url("$candOrigin/api/stream")
                    .post(jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("User-Agent", DESKTOP_UA)
                    .header("Accept", "application/json")
                    .header("Origin", candOrigin)
                    .header("Referer", "$candOrigin/e/$fileCode")
                    .build()
                app.baseClient.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) null
                    else JSONObject(res.body?.string().orEmpty()).takeIf {
                        it.optString("streaming_url").startsWith("http")
                    }
                }
            }.getOrNull()
            if (resObj != null) {
                streamJson = resObj
                workingOrigin = candOrigin
                break
            }
        }

        val data = streamJson ?: return@withContext
        val streamUrl = data.optString("streaming_url").trim()
        if (!streamUrl.startsWith("http")) return@withContext

        val titleQual = detectQualityFromText(data.optString("title"), qualityHint ?: Qualities.Unknown.value)
        val effQual = qualityHint ?: titleQual.takeIf { it != Qualities.Unknown.value }

        val headers = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "$workingOrigin/",
            "Origin" to workingOrigin
        )

        if (streamUrl.contains(".m3u8", ignoreCase = true)) {
            val generated = M3u8Helper.generateM3u8(
                source = "Vidara",
                streamUrl = streamUrl,
                referer = "$workingOrigin/",
                quality = effQual,
                headers = headers
            )
            if (generated.isNotEmpty()) {
                generated.forEach(callback)
            } else {
                callback(
                    newExtractorLink("Vidara", "Vidara", streamUrl, ExtractorLinkType.M3U8) {
                        this.referer = "$workingOrigin/"
                        this.quality = effQual ?: Qualities.P1080.value
                        this.headers = headers
                    }
                )
            }
        } else {
            callback(
                newExtractorLink("Vidara", "Vidara", streamUrl, ExtractorLinkType.VIDEO) {
                    this.referer = "$workingOrigin/"
                    this.quality = effQual ?: Qualities.P1080.value
                    this.headers = headers
                }
            )
        }

        // Subtitles endpoint
        runCatching {
            val subReq = Request.Builder()
                .url("$workingOrigin/api/subtitles?filecode=$fileCode")
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", "$workingOrigin/e/$fileCode")
                .build()
            app.baseClient.newCall(subReq).execute().use { res ->
                if (!res.isSuccessful) return@use
                val arr = JSONArray(res.body?.string().orEmpty())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val subUrl = obj.optString("file").ifBlank { obj.optString("url") }
                    if (subUrl.startsWith("http")) {
                        val label = obj.optString("label").ifBlank { obj.optString("lang").ifBlank { "Arabic" } }
                        subtitleCallback(newSubtitleFile(label, subUrl))
                    }
                }
            }
        }
    }

    // ── 3. EarnVids / FastVid (`fastvid.cam`, `dingtezuni.com`, etc.) ────────

    suspend fun extractEarnVids(
        url: String,
        referer: String?,
        qualityHint: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = withContext(Dispatchers.IO) {
        val origin = originOf(url) ?: return@withContext
        val path = URI(url).path.trim('/')
        val fileCode = path.substringAfterLast('/').removePrefix("embed-").removeSuffix(".html").trim()
        val candidates = linkedSetOf<String>().apply {
            if (path.startsWith("embed/") || path.startsWith("e/")) add(url)
            if (fileCode.isNotEmpty()) {
                add("$origin/embed/$fileCode")
                add("$origin/e/$fileCode")
            }
            add(url)
        }

        var html = ""
        var finalUrl = url
        for (cand in candidates) {
            val fetched = runCatching {
                val req = Request.Builder()
                    .url(cand)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Referer", referer ?: "$origin/")
                    .build()
                app.baseClient.newCall(req).execute().use { res ->
                    if (res.isSuccessful) res.request.url.toString() to res.body?.string().orEmpty() else null
                }
            }.getOrNull()
            if (fetched != null && (fetched.second.contains("eval(function(p,a,c,k,e") || fetched.second.contains(".m3u8"))) {
                finalUrl = fetched.first
                html = fetched.second
                break
            }
        }
        if (html.isBlank()) return@withContext

        val finalOrigin = originOf(finalUrl) ?: origin
        val combined = buildString {
            append(html)
            Regex("""eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\('.*?',\s*\d+,\s*\d+,\s*'.*?'\.split\('\|'\).*?\)\)""", RegexOption.DOT_MATCHES_ALL)
                .findAll(html)
                .forEach { m ->
                    JsUnpacker(m.value).unpack()?.let {
                        append('\n').append(it)
                    }
                }
        }

        val m3u8Candidates = LinkedHashSet<String>()
        // Extract `"hls4":"..."`, `"hls3":"..."`, `"hls2":"..."`
        for (key in listOf("hls4", "hls3", "hls2")) {
            Regex(""""$key"\s*:\s*"([^"]+)"""").findAll(combined).forEach { m ->
                val raw = m.groupValues[1].replace("\\/", "/").trim()
                if (raw.contains(".m3u8") || raw.startsWith("/stream/")) {
                    val full = when {
                        raw.startsWith("http") -> raw
                        raw.startsWith("//") -> "https:$raw"
                        raw.startsWith("/") -> "$finalOrigin$raw"
                        else -> "$finalOrigin/$raw"
                    }
                    m3u8Candidates.add(full)
                }
            }
        }
        // Fallback generic file:"...m3u8..."
        Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").findAll(combined).forEach {
            m3u8Candidates.add(it.groupValues[1].replace("\\/", "/"))
        }

        val headers = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "$finalOrigin/",
            "Origin" to finalOrigin
        )

        var emittedAny = false
        for (m3u8 in m3u8Candidates) {
            val links = M3u8Helper.generateM3u8(
                source = "FastVid",
                streamUrl = m3u8,
                referer = "$finalOrigin/",
                quality = qualityHint,
                headers = headers
            )
            if (links.isNotEmpty()) {
                links.forEach(callback)
                emittedAny = true
                break
            }
        }

        if (!emittedAny && m3u8Candidates.isNotEmpty()) {
            val first = m3u8Candidates.first()
            callback(
                newExtractorLink("FastVid", "FastVid", first, ExtractorLinkType.M3U8) {
                    this.referer = "$finalOrigin/"
                    this.quality = qualityHint ?: Qualities.P1080.value
                    this.headers = headers
                }
            )
        }

        // Subtitles if present
        Regex("""\{\s*["']?file["']?\s*:\s*["'](https?://[^"']+\.(?:vtt|srt)[^"']*)["']\s*,\s*["']?label["']?\s*:\s*["']([^"']+)["']""")
            .findAll(combined)
            .forEach { m ->
                subtitleCallback(newSubtitleFile(m.groupValues[2], m.groupValues[1]))
            }
    }

    // ── 4. Byse (`bysevepoin.com`, `n1mwq.org`, etc.) — ECDSA PoW + AES-GCM ──

    suspend fun extractByse(
        url: String,
        referer: String?,
        qualityHint: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = withContext(Dispatchers.IO) {
        val initialOrigin = originOf(url) ?: return@withContext
        val code = URI(url).path.trim('/').substringAfterLast('/').substringBefore('?').trim()
        if (code.isBlank()) return@withContext

        var apiOrigin = initialOrigin
        var videoCode = code

        // Follow `embed_frame_url` (e.g. `bysevepoin.com` -> `https://n1mwq.org/e/<code>`)
        runCatching {
            val detailsReq = Request.Builder()
                .url("$initialOrigin/api/videos/$code/embed/details")
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", "$initialOrigin/e/$code")
                .build()
            app.baseClient.newCall(detailsReq).execute().use { res ->
                if (res.isSuccessful) {
                    val det = JSONObject(res.body?.string().orEmpty())
                    val frameUrl = det.optString("embed_frame_url").trim()
                    if (frameUrl.startsWith("http")) {
                        originOf(frameUrl)?.let { apiOrigin = it }
                        val fCode = URI(frameUrl).path.trim('/').substringAfterLast('/').trim()
                        if (fCode.isNotEmpty()) videoCode = fCode
                    }
                }
            }
        }

        val embedReferer = "$apiOrigin/e/$videoCode"
        val playbackUrl = "$apiOrigin/api/videos/$videoCode/playback"

        // Attempt 1: direct playback JSON (sometimes open without PoW)
        var playbackJson = fetchBysePlayback(playbackUrl, embedReferer, apiOrigin, captchaToken = null)

        // Attempt 2: Full 3-step ECDSA P-256 + SHA-256 PoW `/access/challenge` -> `/access/attest`
        if (playbackJson == null || (!playbackJson.has("playback") && !playbackJson.has("sources"))) {
            val token = solveByseAccessChallenge(apiOrigin, embedReferer)
            if (!token.isNullOrBlank()) {
                playbackJson = fetchBysePlayback(playbackUrl, embedReferer, apiOrigin, captchaToken = token)
            }
        }

        val root = playbackJson ?: return@withContext

        // Decrypt AES-GCM `playback` object if encrypted, else read `sources` directly
        val decryptedObj: JSONObject = if (root.has("playback")) {
            decryptBysePlaybackObject(root.optJSONObject("playback")) ?: return@withContext
        } else {
            root
        }

        val headers = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "$apiOrigin/",
            "Origin" to apiOrigin
        )

        val sources = decryptedObj.optJSONArray("sources") ?: JSONArray()
        for (i in 0 until sources.length()) {
            val sObj = sources.optJSONObject(i) ?: continue
            val sUrl = sObj.optString("url").ifBlank { sObj.optString("file") }.trim()
            if (!sUrl.startsWith("http")) continue
            val labelQual = detectQualityFromText(sObj.optString("label"), qualityHint ?: Qualities.Unknown.value)
            val effQual = qualityHint ?: labelQual.takeIf { it != Qualities.Unknown.value }

            if (sUrl.contains(".m3u8", ignoreCase = true)) {
                val links = M3u8Helper.generateM3u8("Byse", sUrl, "$apiOrigin/", quality = effQual, headers = headers)
                if (links.isNotEmpty()) {
                    links.forEach(callback)
                } else {
                    callback(
                        newExtractorLink("Byse", "Byse", sUrl, ExtractorLinkType.M3U8) {
                            this.referer = "$apiOrigin/"
                            this.quality = effQual ?: Qualities.P1080.value
                            this.headers = headers
                        }
                    )
                }
            } else {
                callback(
                    newExtractorLink("Byse", "Byse", sUrl, ExtractorLinkType.VIDEO) {
                        this.referer = "$apiOrigin/"
                        this.quality = effQual ?: Qualities.P1080.value
                        this.headers = headers
                    }
                )
            }
        }

        val tracks = decryptedObj.optJSONArray("tracks") ?: root.optJSONArray("tracks") ?: JSONArray()
        for (i in 0 until tracks.length()) {
            val tObj = tracks.optJSONObject(i) ?: continue
            val kind = tObj.optString("kind", "captions").lowercase()
            if (kind == "thumbnails") continue
            val tUrl = tObj.optString("url").ifBlank { tObj.optString("file") }.trim()
            if (tUrl.startsWith("http")) {
                val label = tObj.optString("label").ifBlank { "Arabic" }
                subtitleCallback(newSubtitleFile(label, tUrl))
            }
        }
    }

    private fun fetchBysePlayback(
        playbackUrl: String,
        referer: String,
        origin: String,
        captchaToken: String?
    ): JSONObject? = runCatching {
        val rb = Request.Builder()
            .url(playbackUrl)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "application/json")
            .header("Referer", referer)
            .header("Origin", origin)
            .header("X-Embed-Parent", referer)
        if (!captchaToken.isNullOrBlank()) {
            rb.header("X-Captcha-Token", captchaToken)
        }
        app.baseClient.newCall(rb.build()).execute().use { res ->
            if (!res.isSuccessful) null else JSONObject(res.body?.string().orEmpty())
        }
    }.getOrNull()

    private fun solveByseAccessChallenge(origin: String, referer: String): String? = runCatching {
        // Generate ephemeral P-256 ECDSA keypair -> uncompressed 65-byte raw public key
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        val pub = kp.public as ECPublicKey
        val rawPub = ByteArray(65)
        rawPub[0] = 0x04
        val xBytes = toFixed32Bytes(pub.w.affineX)
        val yBytes = toFixed32Bytes(pub.w.affineY)
        System.arraycopy(xBytes, 0, rawPub, 1, 32)
        System.arraycopy(yBytes, 0, rawPub, 33, 32)
        val pubB64Url = b64UrlEncode(rawPub)

        val chalBody = JSONObject()
            .put("purpose", "playback")
            .put("public_key", pubB64Url)
            .toString()

        val chalJson = app.baseClient.newCall(
            Request.Builder()
                .url("$origin/access/challenge")
                .post(chalBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "application/json")
                .header("Origin", origin)
                .header("Referer", referer)
                .build()
        ).execute().use { res ->
            if (!res.isSuccessful) return@runCatching null
            JSONObject(res.body?.string().orEmpty())
        }

        val challengeId = chalJson.optString("challenge_id")
        val nonce = chalJson.optString("nonce")
        val difficulty = chalJson.optInt("difficulty", 12)
        if (challengeId.isBlank() || nonce.isBlank()) return@runCatching null

        // PoW: find integer `counter` where SHA-256("$nonce$counter") has >= `difficulty` leading zero bits
        val md = MessageDigest.getInstance("SHA-256")
        var counter = 0L
        while (counter < 5_000_000L) {
            val candidate = "$nonce$counter"
            val hash = md.digest(candidate.toByteArray(Charsets.UTF_8))
            if (hasLeadingZeroBits(hash, difficulty)) break
            counter++
        }

        // Sign "$challengeId.$nonce.$counter" with ECDSA SHA256 (convert DER -> raw 64-byte P1363 R||S)
        val messageBytes = "$challengeId.$nonce.$counter".toByteArray(Charsets.UTF_8)
        val derSig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(kp.private)
            update(messageBytes)
        }.sign()
        val rawSig64 = derEcdsaToP1363(derSig)
        val sigB64Url = b64UrlEncode(rawSig64)

        val attestBody = JSONObject()
            .put("challenge_id", challengeId)
            .put("pow_nonce", counter)
            .put("signature", sigB64Url)
            .put("signals", JSONObject().apply {
                put("webdriver", false)
                put("languages", 2)
                put("plugins", 5)
            })
            .toString()

        app.baseClient.newCall(
            Request.Builder()
                .url("$origin/access/attest")
                .post(attestBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "application/json")
                .header("Origin", origin)
                .header("Referer", referer)
                .build()
        ).execute().use { res ->
            if (!res.isSuccessful) null
            else JSONObject(res.body?.string().orEmpty()).optString("token").takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    private fun decryptBysePlaybackObject(pb: JSONObject?): JSONObject? {
        if (pb == null) return null
        val keyParts = pb.optJSONArray("key_parts")
        if (keyParts != null && keyParts.length() >= 2) {
            val k0 = b64UrlDecode(keyParts.optString(0))
            val k1 = b64UrlDecode(keyParts.optString(1))
            val fullKey = k0 + k1
            // Try primary `iv` + `payload`
            runCatching {
                val iv = b64UrlDecode(pb.optString("iv"))
                val cipherBytes = b64UrlDecode(pb.optString("payload"))
                val plain = aesGcmDecrypt(fullKey, iv, cipherBytes)
                return JSONObject(String(plain, Charsets.UTF_8))
            }
            // Fallback `iv2` + `payload2`
            runCatching {
                val iv2 = b64UrlDecode(pb.optString("iv2"))
                val cipher2 = b64UrlDecode(pb.optString("payload2"))
                val plain2 = aesGcmDecrypt(fullKey, iv2, cipher2)
                return JSONObject(String(plain2, Charsets.UTF_8))
            }
        }
        return null
    }

    private fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, ciphertextWithTag: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertextWithTag)
    }

    private fun hasLeadingZeroBits(bytes: ByteArray, bits: Int): Boolean {
        val fullBytes = bits / 8
        val remBits = bits % 8
        for (i in 0 until fullBytes) {
            if (i >= bytes.size || bytes[i].toInt() != 0) return false
        }
        if (remBits > 0) {
            if (fullBytes >= bytes.size) return false
            val mask = (0xFF shl (8 - remBits)) and 0xFF
            if ((bytes[fullBytes].toInt() and mask) != 0) return false
        }
        return true
    }

    private fun toFixed32Bytes(bi: BigInteger): ByteArray {
        val raw = bi.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32).also { System.arraycopy(raw, 0, it, 32 - raw.size, raw.size) }
        }
    }

    private fun derEcdsaToP1363(der: ByteArray): ByteArray {
        var idx = 2
        if (der[1].toInt() and 0x80 != 0) idx += (der[1].toInt() and 0x7F)
        idx++ // 0x02
        val rLen = der[idx++].toInt() and 0xFF
        val rBytes = der.copyOfRange(idx, idx + rLen)
        idx += rLen
        idx++ // 0x02
        val sLen = der[idx++].toInt() and 0xFF
        val sBytes = der.copyOfRange(idx, idx + sLen)

        val out = ByteArray(64)
        val rClean = if (rBytes.size > 32) rBytes.copyOfRange(rBytes.size - 32, rBytes.size) else rBytes
        val sClean = if (sBytes.size > 32) sBytes.copyOfRange(sBytes.size - 32, sBytes.size) else sBytes
        System.arraycopy(rClean, 0, out, 32 - rClean.size, rClean.size)
        System.arraycopy(sClean, 0, out, 64 - sClean.size, sClean.size)
        return out
    }

    private fun b64UrlEncode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun b64UrlDecode(str: String): ByteArray {
        val clean = str.trim().replace('-', '+').replace('_', '/')
        val pad = (4 - clean.length % 4) % 4
        return Base64.decode(clean + "=".repeat(pad), Base64.DEFAULT)
    }

    // ── 5. MixDrop (`m1xdrop.bz`, `mxdrop.click`, `mixdrop.top`, etc.) ───────

    suspend fun extractMixDrop(
        url: String,
        referer: String?,
        qualityHint: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = withContext(Dispatchers.IO) {
        val fileCode = URI(url).path.trim('/').substringAfterLast('/').removeSuffix(".html").trim()
        if (fileCode.isBlank()) return@withContext
        val orig = originOf(url) ?: "https://mxdrop.click"

        val candidates = linkedSetOf(
            "$orig/e/$fileCode",
            "https://mxdrop.click/e/$fileCode",
            "https://m1xdrop.bz/e/$fileCode"
        )

        var unpackedJs: String? = null
        var finalOrigin = orig

        for (cand in candidates) {
            val fetched = runCatching {
                val req = Request.Builder()
                    .url(cand)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Referer", referer ?: "$orig/")
                    .build()
                app.baseClient.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) null
                    else res.request.url.toString() to res.body?.string().orEmpty()
                }
            }.getOrNull() ?: continue

            val html = fetched.second
            val packed = Regex(
                """eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\('.*?',\s*\d+,\s*\d+,\s*'.*?'\.split\('\|'\).*?\)\)""",
                RegexOption.DOT_MATCHES_ALL
            ).findAll(html).mapNotNull { JsUnpacker(it.value).unpack() }.firstOrNull { it.contains("MDCore") }

            if (packed != null) {
                unpackedJs = packed
                originOf(fetched.first)?.let { finalOrigin = it }
                break
            }
        }

        val js = unpackedJs ?: return@withContext
        val wurl = Regex("""MDCore\.wurl\s*=\s*["']([^"']+)["']""").find(js)?.groupValues?.get(1)?.trim().orEmpty()
        if (wurl.isBlank()) return@withContext

        val fullStream = when {
            wurl.startsWith("http") -> wurl
            wurl.startsWith("//") -> "https:$wurl"
            else -> "https://${wurl.trimStart('/')}"
        }

        val headers = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "$finalOrigin/"
        )

        callback(
            newExtractorLink(
                source = "MixDrop",
                name = "MixDrop",
                url = fullStream,
                type = if (fullStream.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "$finalOrigin/"
                this.quality = qualityHint ?: Qualities.P1080.value
                this.headers = headers
            }
        )

        Regex("""MDCore\.remotesub\s*=\s*["'](https?://[^"']+)["']""").find(js)?.groupValues?.get(1)?.let { sub ->
            if (sub.isNotBlank()) subtitleCallback(newSubtitleFile("Subtitle", URLDecoder.decode(sub, "UTF-8")))
        }
    }

    // ── 6. DoodStream / Playmogo (`playmogo.com`, `dood.*`, `dsvplay.com`) ───

    suspend fun extractDood(
        url: String,
        referer: String?,
        qualityHint: Int?,
        callback: (ExtractorLink) -> Unit
    ) = withContext(Dispatchers.IO) {
        val fileCode = URI(url).path.trim('/').substringAfterLast('/').trim()
        if (fileCode.isBlank()) return@withContext
        val orig = originOf(url) ?: "https://playmogo.com"
        val embedUrl = "$orig/e/$fileCode"

        val client = newSessionClient()
        val (finalEmbedUrl, html) = runCatching {
            val req = Request.Builder()
                .url(embedUrl)
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", referer ?: "$orig/")
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) null
                else res.request.url.toString() to res.body?.string().orEmpty()
            }
        }.getOrNull() ?: return@withContext

        val finalOrigin = originOf(finalEmbedUrl) ?: orig
        val passMd5Path = Regex("""/pass_md5/[^'"\s<>]+""").find(html)?.value ?: return@withContext
        val token = passMd5Path.substringAfterLast('/')

        val mediaBase = runCatching {
            val req = Request.Builder()
                .url("$finalOrigin$passMd5Path")
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", finalEmbedUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            client.newCall(req).execute().use { res ->
                if (res.isSuccessful) res.body?.string()?.trim().orEmpty() else ""
            }
        }.getOrDefault("")
        if (!mediaBase.startsWith("http")) return@withContext

        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val rand10 = (1..10).map { alphabet.random() }.joinToString("")
        val directMp4 = "$mediaBase$rand10?token=$token&expiry=${System.currentTimeMillis()}"

        val pageTitle = Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
        val effQual = qualityHint ?: detectQualityFromText(pageTitle, Qualities.P1080.value)

        val headers = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "$finalOrigin/"
        )

        callback(
            newExtractorLink(
                source = "DoodStream",
                name = "DoodStream",
                url = directMp4,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "$finalOrigin/"
                this.quality = effQual
                this.headers = headers
            }
        )
    }

    // ── 7. VOE (`voe.sx` + rotating JS redirect mirrors) ─────────────────────

    suspend fun extractVoe(
        url: String,
        referer: String?,
        qualityHint: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = withContext(Dispatchers.IO) {
        val code = URI(url).path.trim('/').substringAfterLast('/').trim()
        var currentUrl = if (URI(url).path.contains("/e/")) url else "${originOf(url) ?: "https://voe.sx"}/e/$code"
        var html = ""
        var finalOrigin = originOf(currentUrl) ?: "https://voe.sx"

        // Follow up to 3 JS `window.location.href` / meta-refresh hops
        repeat(4) {
            val fetched = runCatching {
                val req = Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Referer", referer ?: "$finalOrigin/")
                    .build()
                app.baseClient.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) null else res.request.url.toString() to res.body?.string().orEmpty()
                }
            }.getOrNull() ?: return@withContext

            currentUrl = fetched.first
            originOf(currentUrl)?.let { finalOrigin = it }
            html = fetched.second

            val jsNext = Regex("""window\.location\.href\s*=\s*['"](https?://[^'"]+)['"]""").find(html)?.groupValues?.get(1)
                ?: Regex("""location\.replace\(\s*['"](https?://[^'"]+)['"]\s*\)""").find(html)?.groupValues?.get(1)
            if (jsNext != null && !html.contains("""type="application/json"""")) {
                currentUrl = jsNext
            } else {
                return@repeat
            }
        }

        if (html.isBlank()) return@withContext

        val headers = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "$finalOrigin/",
            "Origin" to finalOrigin
        )

        // 7-step `<script type="application/json">["..."]</script>` VOE payload decoder
        val jsonTagMatch = Regex("""<script\s+type=["']application/json["']>\s*\[\s*["']([^"']+)["']\s*\]\s*</script>""")
            .find(html)
        if (jsonTagMatch != null) {
            val decoded = decodeVoePayload(jsonTagMatch.groupValues[1])
            if (decoded != null) {
                val hlsUrl = decoded.optString("source").trim()
                if (hlsUrl.startsWith("http")) {
                    val m3u8Links = M3u8Helper.generateM3u8("VOE", hlsUrl, "$finalOrigin/", quality = qualityHint, headers = headers)
                    if (m3u8Links.isNotEmpty()) {
                        m3u8Links.forEach(callback)
                    } else {
                        callback(
                            newExtractorLink("VOE", "VOE", hlsUrl, ExtractorLinkType.M3U8) {
                                this.referer = "$finalOrigin/"
                                this.quality = qualityHint ?: Qualities.P1080.value
                                this.headers = headers
                            }
                        )
                    }
                }
                val mp4Url = decoded.optString("direct_access_url").trim()
                if (mp4Url.startsWith("http")) {
                    callback(
                        newExtractorLink("VOE MP4", "VOE MP4", mp4Url, ExtractorLinkType.VIDEO) {
                            this.referer = "$finalOrigin/"
                            this.quality = qualityHint ?: Qualities.P720.value
                            this.headers = headers
                        }
                    )
                }
                decoded.optJSONArray("captions")?.let { caps ->
                    for (i in 0 until caps.length()) {
                        val c = caps.optJSONObject(i) ?: continue
                        val cFile = c.optString("file").trim()
                        if (cFile.isNotEmpty()) {
                            val fullSub = if (cFile.startsWith("http")) cFile else "$finalOrigin/${cFile.trimStart('/')}"
                            val label = c.optString("label").ifBlank { "Subtitle" }
                            subtitleCallback(newSubtitleFile(label, fullSub))
                        }
                    }
                }
                return@withContext
            }
        }

        // Legacy fallback (`'hls': '...'` / `'mp4': '...'`)
        Regex("""['"]hls['"]\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)?.let { raw ->
            val hls = if (raw.startsWith("http")) raw else runCatching {
                String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrDefault("")
            if (hls.startsWith("http")) {
                M3u8Helper.generateM3u8("VOE", hls, "$finalOrigin/", quality = qualityHint, headers = headers)
                    .forEach(callback)
            }
        }
    }

    private fun decodeVoePayload(encoded: String): JSONObject? = runCatching {
        // Step 1: ROT13
        val rot = buildString(encoded.length) {
            for (ch in encoded) {
                when (ch) {
                    in 'a'..'z' -> append('a' + (ch - 'a' + 13) % 26)
                    in 'A'..'Z' -> append('A' + (ch - 'A' + 13) % 26)
                    else -> append(ch)
                }
            }
        }
        // Step 2: Strip junk markers
        var cleaned = rot
        for (pat in listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")) {
            cleaned = cleaned.replace(pat, "")
        }
        // Step 3: Base64 decode (ISO-8859-1)
        val step3 = String(Base64.decode(cleaned, Base64.DEFAULT), Charsets.ISO_8859_1)
        // Step 4 & 5: Shift char code -3 & reverse
        val shiftedReversed = buildString(step3.length) {
            for (i in step3.length - 1 downTo 0) {
                append((step3[i].code - 3).toChar())
            }
        }
        // Step 6 & 7: Base64 decode -> UTF-8 JSON
        val jsonStr = String(Base64.decode(shiftedReversed, Base64.DEFAULT), Charsets.UTF_8)
        JSONObject(jsonStr)
    }.getOrNull()

    // ── 8. Uqload (`uqload.vc`, `uqload.is`, `uqload.to`, etc.) ──────────────

    suspend fun extractUqload(
        url: String,
        referer: String?,
        qualityHint: Int?,
        callback: (ExtractorLink) -> Unit
    ) = withContext(Dispatchers.IO) {
        val origin = originOf(url) ?: "https://uqload.vc"
        val fileCode = URI(url).path.trim('/')
            .substringAfterLast('/')
            .removePrefix("embed-")
            .removeSuffix(".html")
            .trim()
        if (fileCode.isBlank()) return@withContext

        val headers = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "$origin/",
            "Origin" to origin
        )

        // 1. Modern XFS `POST /dl` (`op=embed&file_code=<id>&auto=1`) -> Packer HLS `.urlset/master.m3u8`
        val dlHtml = runCatching {
            val form = FormBody.Builder()
                .add("op", "embed")
                .add("file_code", fileCode)
                .add("auto", "1")
                .add("referer", referer ?: "$origin/")
                .build()
            val req = Request.Builder()
                .url("$origin/dl")
                .post(form)
                .header("User-Agent", DESKTOP_UA)
                .header("Origin", origin)
                .header("Referer", "$origin/e/$fileCode.html")
                .build()
            app.baseClient.newCall(req).execute().use { res ->
                if (res.isSuccessful) res.body?.string().orEmpty() else ""
            }
        }.getOrDefault("")

        if (dlHtml.isNotBlank()) {
            val combined = buildString {
                append(dlHtml)
                Regex(
                    """eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\('.*?',\s*\d+,\s*\d+,\s*'.*?'\.split\('\|'\).*?\)\)""",
                    RegexOption.DOT_MATCHES_ALL
                ).findAll(dlHtml).forEach { m ->
                    JsUnpacker(m.value).unpack()?.let { append('\n').append(it) }
                }
            }
            val m3u8 = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(combined)?.value
            if (m3u8 != null) {
                val links = M3u8Helper.generateM3u8("Uqload", m3u8, "$origin/", quality = qualityHint, headers = headers)
                if (links.isNotEmpty()) {
                    links.forEach(callback)
                    return@withContext
                }
                callback(
                    newExtractorLink("Uqload", "Uqload", m3u8, ExtractorLinkType.M3U8) {
                        this.referer = "$origin/"
                        this.quality = qualityHint ?: Qualities.P1080.value
                        this.headers = headers
                    }
                )
                return@withContext
            }
        }

        // 2. Legacy Uqload `/embed-<code>.html` / `/e/<code>.html` direct MP4 fallback
        for (embedPath in listOf("$origin/embed-$fileCode.html", "$origin/e/$fileCode.html")) {
            val html = runCatching {
                val req = Request.Builder()
                    .url(embedPath)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Referer", referer ?: "$origin/")
                    .build()
                app.baseClient.newCall(req).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
            }.getOrDefault("")
            val mp4 = Regex("""https?://[^\s"'<>]+/v\.mp4[^\s"'<>]*""").find(html)?.value
                ?: Regex("""sources\s*:\s*\[\s*["'](https?://[^"']+\.mp4[^"']*)["']""").find(html)?.groupValues?.get(1)
            if (mp4 != null) {
                callback(
                    newExtractorLink("Uqload", "Uqload", mp4, ExtractorLinkType.VIDEO) {
                        this.referer = "$origin/"
                        this.quality = qualityHint ?: Qualities.P720.value
                        this.headers = headers
                    }
                )
                return@withContext
            }
        }
    }

    // ── 9. SaveFiles / StreamHLS (`streamhls.to`, `savefiles.com`) ───────────

    suspend fun extractSaveFiles(
        url: String,
        referer: String?,
        qualityHint: Int?,
        callback: (ExtractorLink) -> Unit
    ) = withContext(Dispatchers.IO) {
        val origin = originOf(url) ?: "https://streamhls.to"
        val fileCode = URI(url).path.trim('/')
            .substringAfterLast('/')
            .removePrefix("embed-")
            .removeSuffix(".html")
            .trim()
        if (fileCode.isBlank()) return@withContext

        val headers = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "$origin/",
            "Origin" to origin
        )

        var m3u8Url: String? = null

        // Check GET page first (savefiles.com/<code> embeds master.m3u8 directly!)
        val getHtml = runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", referer ?: "$origin/")
                .build()
            app.baseClient.newCall(req).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
        }.getOrDefault("")

        if (getHtml.isNotBlank()) {
            val combinedGet = buildString {
                append(getHtml)
                Regex(
                    """eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\('.*?',\s*\d+,\s*\d+,\s*'.*?'\.split\('\|'\).*?\)\)""",
                    RegexOption.DOT_MATCHES_ALL
                ).findAll(getHtml).forEach { JsUnpacker(it.value).unpack()?.let { u -> append('\n').append(u) } }
            }
            m3u8Url = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(combinedGet)?.value
        }

        // Fallback: `POST /dl` (`op=embed&file_code=<code>&auto=1`) used by `streamhls.to`
        if (m3u8Url == null) {
            val postHtml = runCatching {
                val form = FormBody.Builder()
                    .add("op", "embed")
                    .add("file_code", fileCode)
                    .add("auto", "1")
                    .add("referer", referer ?: "$origin/")
                    .build()
                val req = Request.Builder()
                    .url("$origin/dl")
                    .post(form)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Origin", origin)
                    .header("Referer", "$origin/e/$fileCode")
                    .build()
                app.baseClient.newCall(req).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
            }.getOrDefault("")

            if (postHtml.isNotBlank()) {
                val combinedPost = buildString {
                    append(postHtml)
                    Regex(
                        """eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\('.*?',\s*\d+,\s*\d+,\s*'.*?'\.split\('\|'\).*?\)\)""",
                        RegexOption.DOT_MATCHES_ALL
                    ).findAll(postHtml).forEach { JsUnpacker(it.value).unpack()?.let { u -> append('\n').append(u) } }
                }
                m3u8Url = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(combinedPost)?.value
            }
        }

        val stream = m3u8Url ?: return@withContext
        val links = M3u8Helper.generateM3u8("SaveFiles", stream, "$origin/", quality = qualityHint, headers = headers)
        if (links.isNotEmpty()) {
            links.forEach(callback)
        } else {
            callback(
                newExtractorLink("SaveFiles", "SaveFiles", stream, ExtractorLinkType.M3U8) {
                    this.referer = "$origin/"
                    this.quality = qualityHint ?: Qualities.P1080.value
                    this.headers = headers
                }
            )
        }
    }

    // ── 10. Vids.st (`vids.st`) ──────────────────────────────────────────────

    suspend fun extractVidsSt(
        url: String,
        referer: String?,
        qualityHint: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = withContext(Dispatchers.IO) {
        val origin = originOf(url) ?: "https://vids.st"
        val html = runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", referer ?: "$origin/")
                .build()
            Shahid4uNetwork.trustAllClient.newCall(req).execute().use { res ->
                if (res.isSuccessful) res.body?.string().orEmpty() else ""
            }
        }.getOrDefault("")
        if (html.isBlank()) return@withContext

        val rawStream = Regex("""const\s+url\s*=\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)?.replace("\\/", "/")?.trim()
            ?: return@withContext
        if (!rawStream.startsWith("http")) return@withContext

        val isHls = Regex("""const\s+isHls\s*=\s*(true|false)""").find(html)?.groupValues?.get(1) == "true" ||
            rawStream.contains(".m3u8", ignoreCase = true)

        val titleText = Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
        val effQual = qualityHint ?: detectQualityFromText(titleText ?: rawStream, Qualities.P1080.value)

        val headers = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "$origin/"
        )

        callback(
            newExtractorLink(
                source = "Vids.st",
                name = "Vids.st",
                url = rawStream,
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "$origin/"
                this.quality = effQual
                this.headers = headers
            }
        )

        // Parse `playerConfig = {...}` subtitles
        Regex("""const\s+playerConfig\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.let { cfgStr ->
            runCatching {
                val cfg = JSONObject(cfgStr)
                val singleSub = cfg.optString("subtitleUrl").trim()
                if (singleSub.startsWith("http")) {
                    subtitleCallback(newSubtitleFile(cfg.optString("subtitleLabel", "Arabic"), singleSub))
                }
                val tracks = cfg.optJSONArray("subtitleTracks") ?: JSONArray()
                for (i in 0 until tracks.length()) {
                    val tr = tracks.optJSONObject(i) ?: continue
                    val u = tr.optString("url").trim()
                    if (u.startsWith("http")) {
                        subtitleCallback(newSubtitleFile(tr.optString("label", "Subtitle"), u))
                    }
                }
            }
        }
    }

    // ── 11. Upzur (`upzur.com`) — Direct Multi-Quality MP4 ───────────────────

    suspend fun extractUpzur(
        url: String,
        referer: String?,
        qualityHint: Int?,
        callback: (ExtractorLink) -> Unit
    ) = withContext(Dispatchers.IO) {
        val origin = originOf(url) ?: "https://upzur.com"
        val fileId = URI(url).path.trim('/').substringAfterLast('/').removeSuffix(".html").trim()
        if (fileId.isBlank()) return@withContext

        val client = newSessionClient()
        // Prime cookies on GET first
        val getHtml = runCatching {
            val req = Request.Builder()
                .url("$origin/$fileId")
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", referer ?: "$origin/")
                .build()
            client.newCall(req).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
        }.getOrDefault("")

        val postHtml = runCatching {
            val form = FormBody.Builder()
                .add("op", "download2")
                .add("id", fileId)
                .add("rand", "")
                .add("referer", referer ?: "")
                .add("method_free", "")
                .add("method_premium", "")
                .build()
            val req = Request.Builder()
                .url("$origin/$fileId")
                .post(form)
                .header("User-Agent", DESKTOP_UA)
                .header("Origin", origin)
                .header("Referer", "$origin/$fileId")
                .build()
            client.newCall(req).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
        }.getOrDefault("")

        val directUrl = Jsoup.parse(postHtml, origin).selectFirst("a#direct_link[href], a[href*=/d/]")?.absUrl("href")
            ?.takeIf { it.startsWith("http") }
            ?: Regex("""https?://[a-zA-Z0-9.-]*upzur\.com/d/[^\s"'<>]+""").find(postHtml)?.value
            ?: return@withContext

        val effQual = qualityHint ?: detectQualityFromText(directUrl.ifBlank { getHtml }, Qualities.Unknown.value)
        val headers = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "$origin/"
        )

        callback(
            newExtractorLink(
                source = "Upzur",
                name = "Upzur",
                url = directUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "$origin/"
                this.quality = effQual
                this.headers = headers
            }
        )
    }

    // ── 12. Odnoklassniki (`ok.ru`, `odnoklassniki.ru`) ──────────────────────

    suspend fun extractOkRu(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ) = withContext(Dispatchers.IO) {
        val videoId = URI(url).path.trim('/').substringAfterLast('/').trim()
        val embedUrl = if (url.contains("videoembed")) url else "https://ok.ru/videoembed/$videoId"

        val html = runCatching {
            val req = Request.Builder()
                .url(embedUrl)
                .header("User-Agent", DESKTOP_UA)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", referer ?: "https://ok.ru/")
                .build()
            app.baseClient.newCall(req).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
        }.getOrDefault("")
        if (html.isBlank() || html.contains("vp_video_stub")) return@withContext

        val doc = Jsoup.parse(html, embedUrl)
        val optionsRaw = doc.selectFirst("[data-options]")?.attr("data-options").orEmpty()
        if (optionsRaw.isBlank()) return@withContext

        val optionsJson = runCatching { JSONObject(optionsRaw) }.getOrNull() ?: return@withContext
        val metadataStr = optionsJson.optJSONObject("flashvars")?.optString("metadata").orEmpty()
        if (metadataStr.isBlank()) return@withContext
        val meta = runCatching { JSONObject(metadataStr) }.getOrNull() ?: return@withContext

        val headers = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "https://ok.ru/",
            "Origin" to "https://ok.ru"
        )

        val hls = meta.optString("hlsManifestUrl").ifBlank { meta.optString("ondemandHls") }.trim()
        if (hls.startsWith("http")) {
            M3u8Helper.generateM3u8("Ok.ru", hls, "https://ok.ru/", headers = headers).forEach(callback)
        }

        val qualMap = mapOf(
            "mobile" to 144,
            "lowest" to 240,
            "low" to 360,
            "sd" to 480,
            "hd" to 720,
            "full" to 1080,
            "quad" to 1440,
            "ultra" to 2160
        )

        val videos = meta.optJSONArray("videos") ?: JSONArray()
        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            if (v.optBoolean("disallowed", false)) continue
            val vUrl = v.optString("url").trim()
            if (!vUrl.startsWith("http")) continue
            val qName = v.optString("name").lowercase()
            val qVal = qualMap[qName] ?: Qualities.Unknown.value
            callback(
                newExtractorLink("Ok.ru", "Ok.ru", vUrl, ExtractorLinkType.VIDEO) {
                    this.referer = "https://ok.ru/"
                    this.quality = qVal
                    this.headers = headers
                }
            )
        }
    }

    // ── 13. Vidstack / UPNShare AES-128-CBC (`upns.pro`, `rpmplay.me`, etc.) ─

    suspend fun extractVidstackAes(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ) = withContext(Dispatchers.IO) {
        val origin = originOf(url) ?: return@withContext
        val hashId = url.substringAfter('#', "").substringBefore('&').trim()
            .ifBlank { url.toHttpUrlOrNull()?.queryParameter("id").orEmpty() }
        if (hashId.isBlank()) return@withContext

        val hexResp = runCatching {
            val req = Request.Builder()
                .url("$origin/api/v1/video?id=$hashId")
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", referer ?: "$origin/")
                .build()
            app.baseClient.newCall(req).execute().use { if (it.isSuccessful) it.body?.string()?.trim().orEmpty() else "" }
        }.getOrDefault("")
        if (hexResp.length < 32) return@withContext

        val keyBytes = "kiemtienmua911ca".toByteArray(Charsets.UTF_8)
        val ivCandidates = listOf("1234567890oiuytr", "0123456789abcdef")
        val cipherBytes = runCatching {
            ByteArray(hexResp.length / 2) { idx ->
                hexResp.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull() ?: return@withContext

        for (ivStr in ivCandidates) {
            val dec = runCatching {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(keyBytes, "AES"),
                    IvParameterSpec(ivStr.toByteArray(Charsets.UTF_8))
                )
                JSONObject(String(cipher.doFinal(cipherBytes), Charsets.UTF_8))
            }.getOrNull() ?: continue

            val src = dec.optString("source").trim()
            if (src.startsWith("http")) {
                val headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "$origin/", "Origin" to origin)
                M3u8Helper.generateM3u8("Vidstack", src, "$origin/", headers = headers).forEach(callback)
                return@withContext
            }
        }
    }
}

// ── Registered CloudStream `ExtractorApi` Wrappers ───────────────────────────

open class Shahid4uVinovoApi(override val mainUrl: String = "https://vinovo.to") : ExtractorApi() {
    override val name = "Vinovo"
    override val requiresReferer = true
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Shahid4uExtractors.extractVinovo(url, referer, null, subtitleCallback, callback)
    }
}

class Shahid4uVinovoSiApi : Shahid4uVinovoApi("https://vinovo.si")

open class Shahid4uVidaraApi(override val mainUrl: String = "https://vidaraa.cc") : ExtractorApi() {
    override val name = "Vidara"
    override val requiresReferer = true
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Shahid4uExtractors.extractVidara(url, referer, null, subtitleCallback, callback)
    }
}

class Shahid4uVidaraSoApi : Shahid4uVidaraApi("https://vidara.so")
class Shahid4uVidaraToApi : Shahid4uVidaraApi("https://vidara.to")

open class Shahid4uFastVidApi(override val mainUrl: String = "https://fastvid.cam") : ExtractorApi() {
    override val name = "FastVid"
    override val requiresReferer = true
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Shahid4uExtractors.extractEarnVids(url, referer, null, subtitleCallback, callback)
    }
}

class Shahid4uDingtezuniApi : Shahid4uFastVidApi("https://dingtezuni.com")
class Shahid4uDintezuvioApi : Shahid4uFastVidApi("https://dintezuvio.com")
class Shahid4uMinochinosApi : Shahid4uFastVidApi("https://minochinos.com")

open class Shahid4uByseApi(override val mainUrl: String = "https://bysevepoin.com") : ExtractorApi() {
    override val name = "Byse"
    override val requiresReferer = true
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Shahid4uExtractors.extractByse(url, referer, null, subtitleCallback, callback)
    }
}

class Shahid4uByseQekahoApi : Shahid4uByseApi("https://byseqekaho.com")
class Shahid4uByseN1mwqApi : Shahid4uByseApi("https://n1mwq.org")

open class Shahid4uMixDropApi(override val mainUrl: String = "https://m1xdrop.bz") : ExtractorApi() {
    override val name = "MixDrop"
    override val requiresReferer = true
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Shahid4uExtractors.extractMixDrop(url, referer, null, subtitleCallback, callback)
    }
}

class Shahid4uMxDropClickApi : Shahid4uMixDropApi("https://mxdrop.click")
class Shahid4uMixDropTopApi : Shahid4uMixDropApi("https://mixdrop.top")

open class Shahid4uPlaymogoApi(override val mainUrl: String = "https://playmogo.com") : ExtractorApi() {
    override val name = "DoodStream"
    override val requiresReferer = true
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Shahid4uExtractors.extractDood(url, referer, null, callback)
    }
}

open class Shahid4uVoeApi(override val mainUrl: String = "https://voe.sx") : ExtractorApi() {
    override val name = "VOE"
    override val requiresReferer = true
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Shahid4uExtractors.extractVoe(url, referer, null, subtitleCallback, callback)
    }
}

class Shahid4uDerrickNationApi : Shahid4uVoeApi("https://derricknationtop.com")

open class Shahid4uUqloadApi(override val mainUrl: String = "https://uqload.vc") : ExtractorApi() {
    override val name = "Uqload"
    override val requiresReferer = true
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Shahid4uExtractors.extractUqload(url, referer, null, callback)
    }
}

class Shahid4uUqloadIsApi : Shahid4uUqloadApi("https://uqload.is")
class Shahid4uUqloadToApi : Shahid4uUqloadApi("https://uqload.to")

open class Shahid4uStreamHlsApi(override val mainUrl: String = "https://streamhls.to") : ExtractorApi() {
    override val name = "SaveFiles"
    override val requiresReferer = true
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Shahid4uExtractors.extractSaveFiles(url, referer, null, callback)
    }
}

class Shahid4uSaveFilesComApi : Shahid4uStreamHlsApi("https://savefiles.com")

class Shahid4uVidsStApi : ExtractorApi() {
    override val name = "Vids.st"
    override val mainUrl = "https://vids.st"
    override val requiresReferer = true
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Shahid4uExtractors.extractVidsSt(url, referer, null, subtitleCallback, callback)
    }
}

class Shahid4uUpzurApi : ExtractorApi() {
    override val name = "Upzur"
    override val mainUrl = "https://upzur.com"
    override val requiresReferer = true
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Shahid4uExtractors.extractUpzur(url, referer, null, callback)
    }
}

open class Shahid4uOkRuApi(override val mainUrl: String = "https://ok.ru") : ExtractorApi() {
    override val name = "Ok.ru"
    override val requiresReferer = false
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Shahid4uExtractors.extractOkRu(url, referer, callback)
    }
}
