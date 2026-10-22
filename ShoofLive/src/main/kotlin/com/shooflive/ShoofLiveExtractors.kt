package com.shooflive

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

/**
 * ============================================================================
 *  ShoofLive — extractor suite
 * ============================================================================
 *  شوف لايف لا يستضيف الفيديو بنفسه: صفحة الحلقة/الفيلم تحوي مشغّل
 *  "AlbaPlayer" على `shooof.online/albaplayer/<slug>`، وAlbaPlayer يعرض قائمة
 *  سيرفرات (AnaFast / Voe / MP4Plus / VidSpeed / Vk …) كل واحد منها صفحة
 *  تضمين مستقلة يجب تفكيكها.
 *
 *  Handled providers
 *  -----------------
 *   1. AnaFast / MP4Plus family .... plain `sources: [{file:"…"}]` block
 *   2. VidSpeed (P.A.C.K.E.R.) ...... JWPlayer payload inside `eval(function(p,a,c,k…`
 *   3. VOE (voe.sx network) ......... rot13 → base64 → charShift → reverse → base64
 *   4. VK ......................... `url720`/`hls`/`dash_sep` player params
 *   5. YouTube / Dailymotion / ok.ru  delegated to CloudStream's built-ins
 *   6. Everything else .............. generic scan (m3u8/mp4/`file:`) + built-ins
 */
object ShoofLiveExtractors {

    private const val TAG = "ShoofLiveExtract"

    const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    data class Embed(val name: String, val url: String)

    fun pageHeaders(referer: String? = null): MutableMap<String, String> {
        val h = mutableMapOf(
            "User-Agent" to BROWSER_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "ar,en;q=0.9"
        )
        if (!referer.isNullOrBlank()) h["Referer"] = referer
        return h
    }

    fun hostOf(url: String?): String =
        runCatching { java.net.URI(url?.trim().orEmpty()).host?.lowercase() }.getOrNull().orEmpty()

    private fun isHost(url: String, vararg needles: String): Boolean {
        val h = hostOf(url)
        return needles.any { h.contains(it, ignoreCase = true) }
    }

    // ------------------------------------------------------------------ generic helpers

    /** `label:"1080p"` / `res:1080` style quality attached to a source. */
    private fun heightOf(label: String?): Int? =
        label?.replace(Regex("[^0-9]"), "")?.takeIf { it.isNotBlank() }?.toIntOrNull()

    private suspend fun emitStream(
        url: String,
        label: String,
        referer: String?,
        quality: Int?,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        val clean = url.replace("&amp;", "&").replace("\\/", "/").trim()
        if (!clean.startsWith("http")) return
        if (clean.contains(".m3u8")) {
            val generated = runCatching {
                M3u8Helper.generateM3u8(
                    label,
                    clean,
                    referer ?: "",
                    headers = headers + mapOf("Referer" to (referer ?: ""))
                )
            }.getOrNull()
            if (!generated.isNullOrEmpty()) {
                generated.forEach { callback(it) }
                return
            }
        }
        callback(
            newExtractorLink(
                label,
                label,
                clean,
                if (clean.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                if (!referer.isNullOrBlank()) this.referer = referer
                if (headers.isNotEmpty()) this.headers = headers.toMutableMap()
                if (quality != null) this.quality = quality
            }
        )
    }

    // ------------------------------------------------------------------ AnaFast / MP4Plus family

    /**
     * anafast.cyou / mp4plus.cyou / … ship a plain JWPlayer style block:
     * `sources: [{file:"https://…master.m3u8?…"}, {file:"…/v.mp4",label:"480p"}]`
     * so the media link can be read straight out of the embed page.
     */
    private suspend fun emitSourcesBlock(
        link: String,
        label: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = fetchPage(link, referer) ?: return false
        val found = scanSourceBlock(html, label, link, callback)
        if (found) return true
        return emitGenericPage(link, label, referer, callback)
    }

    /** Extract every `file:"…"` (+ optional `label:"…"`) pair of a page. */
    private suspend fun scanSourceBlock(
        html: String,
        label: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val seen = mutableSetOf<String>()
        for ((url, rawLabel) in ShoofLiveParser.sourcePairs(html)) {
            if (!seen.add(url)) continue
            val q = heightOf(rawLabel)
            val name = if (q != null) "$label ${q}p" else label
            emitStream(url, name, referer, q, emptyMap(), callback)
            found = true
        }
        return found
    }

    // ------------------------------------------------------------------ VidSpeed (P.A.C.K.E.R.)

    private suspend fun emitPacker(
        link: String,
        label: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = fetchPage(link, referer) ?: return false
        val script = ShoofLiveParser.unPacker(html)
        if (script.isNullOrBlank()) return emitGenericPage(link, label, referer, callback)
        val found = scanSourceBlock(script, label, link, callback)
        if (found) return true
        val m3u8 = Regex("""https?://[^"'\s]+?\.m3u8[^"'\s]*""").find(script)?.value
        if (m3u8 != null) {
            emitStream(m3u8, label, link, null, emptyMap(), callback)
            return true
        }
        return emitGenericPage(link, label, referer, callback)
    }

    // ------------------------------------------------------------------ VOE

    private suspend fun emitVoe(
        link: String,
        label: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var html = fetchPage(link, referer) ?: return false

        // The voe.sx entry page is a tiny JS redirect to the real (network) host.
        val redirect = ShoofLiveParser.voeRedirect(html)
        if (!redirect.isNullOrBlank()) {
            html = fetchPage(redirect, referer ?: link) ?: return false
        }

        val encoded = ShoofLiveParser.voePayload(html)
        if (encoded.isNullOrBlank()) return emitGenericPage(link, label, referer, callback)

        val json = ShoofLiveParser.decryptVoe(encoded)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return emitGenericPage(link, label, referer, callback)

        var found = false
        val siteName = json.optString("site_name").ifBlank { hostOf(link) }

        val source = json.optString("source")
        if (source.startsWith("http")) {
            emitStream(
                source, "$label HLS", siteName, null,
                mapOf("Origin" to "https://$siteName/"), callback
            )
            found = true
        }

        json.optJSONArray("fallback")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: arr.optString(i)
                val file = if (item is JSONObject) item.optString("file") else item.toString()
                if (file.startsWith("http")) {
                    emitStream(file, "$label MP4", siteName, null, emptyMap(), callback)
                    found = true
                }
            }
        }

        val direct = json.optString("direct_access_url")
        if (direct.startsWith("http")) {
            emitStream(direct, "$label (تحميل)", siteName, null, emptyMap(), callback)
            found = true
        }

        json.optJSONArray("captions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val file = o.optString("file")
                if (file.isBlank()) continue
                val url = if (file.startsWith("http")) file else "https://$siteName$file"
                Log.i(TAG, "voe caption: $url")
            }
        }

        return found || emitGenericPage(link, label, referer, callback)
    }

    // ------------------------------------------------------------------ VK

    private val VK_URL_RE = Regex(""""url(\d+)":"([^"]+)"""")
    private val VK_HLS_RE = Regex(""""hls":"([^"]+)"""")
    private val VK_DASH_RE = Regex(""""dash_sep":"([^"]+)"""")

    private suspend fun emitVk(
        link: String,
        label: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = fetchPage(link, referer) ?: return false
        var found = false
        for (m in VK_URL_RE.findAll(html)) {
            val url = m.groupValues[2].replace("\\", "")
            if (!url.startsWith("http")) continue
            val q = m.groupValues[1].toIntOrNull()
            emitStream(url, if (q != null) "$label ${q}p" else label, link, q, emptyMap(), callback)
            found = true
        }
        VK_HLS_RE.find(html)?.let {
            val url = it.groupValues[1].replace("\\", "")
            if (url.startsWith("http")) {
                emitStream(url, "$label HLS", link, null, emptyMap(), callback)
                found = true
            }
        }
        VK_DASH_RE.find(html)?.let {
            val url = it.groupValues[1].replace("\\", "")
            if (url.startsWith("http")) {
                emitStream(url, "$label DASH", link, null, emptyMap(), callback)
                found = true
            }
        }
        return found
    }

    // ------------------------------------------------------------------ generic page scan

    /**
     * Last resort for hosts without a dedicated handler: look for a media URL in
     * the page markup, then hand the page over to CloudStream's global extractor
     * chain (which knows YouTube, Dailymotion, ok.ru, Streamtape, …).
     */
    private suspend fun emitGenericPage(
        link: String,
        label: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = fetchPage(link, referer)
        var found = false
        if (html != null) {
            // Some providers (cdnplus.sbs, …) hide the stream in a P.A.C.K.E.R. block.
            val script = ShoofLiveParser.unPacker(html)
            if (script != null) {
                found = scanSourceBlock(script, label, link, callback)
                if (!found) {
                    val m3u8 = Regex("""https?://[^"'\s]+?\.m3u8[^"'\s]*""").find(script)?.value
                    if (m3u8 != null) {
                        emitStream(m3u8, label, link, null, emptyMap(), callback)
                        found = true
                    }
                }
                if (found) return true
            }

            val patterns = listOf(
                Regex("""file\s*:\s*["'](https?://[^"']+)["'](?:\s*,\s*label\s*:\s*["']([^"']*)["'])?"""),
                Regex(""""(?:file|url|src|source)"\s*:\s*"(https?://[^"]+\.(?:m3u8|mp4|webm|mkv)[^"]*)""""),
                Regex("""<(?:video|source)[^>]+src=["'](https?://[^"']+)["']""")
            )
            val seen = mutableSetOf<String>()
            for (p in patterns) {
                for (m in p.findAll(html)) {
                    val url = m.groupValues[1].replace("\\/", "/").replace("&amp;", "&")
                    if (!seen.add(url)) continue
                    val q = heightOf(m.groupValues.getOrNull(2))
                    emitStream(url, if (q != null) "$label ${q}p" else label, link, q, emptyMap(), callback)
                    found = true
                }
            }
            if (!found) {
                val bare = Regex("""(https?://[^"'\s<>\\]+?\.(?:m3u8|mp4|webm)[^"'\s<>\\]*)""")
                    .findAll(html)
                    .map { it.value }
                    .distinct()
                    .take(4)
                    .toList()
                for (url in bare) {
                    emitStream(url, label, link, null, emptyMap(), callback)
                    found = true
                }
            }
        }
        return found
    }

    // ------------------------------------------------------------------ HTTP

    private suspend fun fetchPage(url: String, referer: String?): String? {
        return try {
            val res = app.get(url, headers = pageHeaders(referer), allowRedirects = true)
            if (res.code !in 200..299) {
                Log.i(TAG, "fetch ${url.take(90)} -> HTTP ${res.code}")
                return null
            }
            res.text
        } catch (e: Throwable) {
            Log.i(TAG, "fetch ${url.take(90)} failed: $e")
            null
        }
    }

    // ------------------------------------------------------------------ entry point

    /**
     * Resolve a single embed into playable links.
     *
     * @param link      the embed URL taken from the AlbaPlayer (or from the site).
     * @param referer   the page that embedded it (used as playback Referer).
     * @param label     server name shown in the player.
     */
    suspend fun emit(
        link: String,
        referer: String?,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = link.trim()
        if (clean.isBlank()) return false
        return try {
            when {
                isHost(clean, "anafast", "mp4plus", "afast") ->
                    emitSourcesBlock(clean, label, referer, callback)

                isHost(clean, "vidspeed", "uqload", "vidspeeds", "cdnplus") ->
                    emitPacker(clean, label, referer, callback)

                isHost(clean, "voe") ->
                    emitVoe(clean, label, referer, callback)

                isHost(clean, "vk.com", "vkvideo", "vk.ru") ->
                    emitVk(clean, label, referer, callback) ||
                            delegate(clean, referer, subtitleCallback, callback)

                isHost(clean, "youtube", "youtu.be", "dailymotion", "dai.ly", "ok.ru", "odnoklassniki") ->
                    delegate(clean, referer, subtitleCallback, callback)

                else -> {
                    val direct = emitGenericPage(clean, label, referer, callback)
                    direct || delegate(clean, referer, subtitleCallback, callback)
                }
            }
        } catch (e: Throwable) {
            Log.i(TAG, "emit failed for ${clean.take(90)}: $e")
            false
        }
    }

    /** Hand the URL over to CloudStream's global extractor chain. */
    private suspend fun delegate(
        link: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            loadExtractor(link, referer, subtitleCallback, callback)
        } catch (e: Throwable) {
            Log.i(TAG, "delegate failed for ${link.take(90)}: $e")
            false
        }
    }
}
