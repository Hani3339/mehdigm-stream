package com.egydead

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Uqload
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

/**
 * EgyDead custom file hosts.
 *
 * EgyDead mixes two kinds of sources:
 *
 *  * the rotating `https://<random>.top/iframe/<hash>` player (resolved by
 *    [EgyDead] itself through the Inertia `streams` payload), and
 *  * plain file-host embeds whose domains are not covered by CloudStream's
 *    built-ins (or whose built-ins lag behind the host's current JS loader).
 *
 * Three host families are handled here:
 *
 *  1. StreamWish/StreamHG family (`streamwish.to`, `hgcloud.to`, `vibuxer.com`,
 *     `morencius.com`, `vidhidevip.com`/`callistanise.com`): a
 *     p.a.c.k.e.r-obfuscated jwplayer setup exposing `hls4 || hls3 || hls2`.
 *  2. StreamRuby (`stmruby.com`, `streamruby.net`, `rubyvidhub.com`): the same
 *     packer but with a plain `file: ".../master.m3u8"`.
 *  3. `uqload.vc` and `byseraguci.com`: mirrors of hosts whose built-in
 *     extractors already implement the full handshake, so thin subclasses just
 *     re-declare [mainUrl] and reuse that proven logic.
 */

/**
 * Resolves the packer-based StreamWish/VidHide/StreamRuby players.
 *
 * The embed page is either a p.a.c.k.e.r payload directly ([mainUrl] === real
 * host) or only a JS loader that must be bounced to [bounceHost] using the same
 * file code.
 */
open class EgyDeadPackerExtractor : ExtractorApi() {
    override var name = "StreamWish"
    override var mainUrl = "https://streamwish.to"
    override val requiresReferer = false

    /** Host the loader redirects to (StreamWish family). `null` when not needed. */
    open val bounceHost: String? = null

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = codeOf(url)
        if (code.isBlank()) return

        var pageUrl = url
        var text = fetch(url)
        if (text != null && !text.contains("eval(function") && !bounceHost.isNullOrBlank()) {
            pageUrl = "${bounceHost!!.trimEnd('/')}/e/$code"
            text = fetch(pageUrl)
        }
        if (text.isNullOrBlank()) return

        val decoded = JsUnpacker(text).unpack() ?: text
        val stream = pickStream(decoded, pageUrl) ?: pickStream(text, pageUrl) ?: return

        callback.invoke(
            newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
                this.referer = "$pageUrl/"
                this.quality = Qualities.Unknown.value
            }
        )
    }

    protected suspend fun fetch(target: String): String? = try {
        app.get(
            target,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            ),
            referer = refererHeader(target)
        ).text
    } catch (e: Throwable) {
        Log.i(TAG, "fetch failed $target -> $e")
        null
    }

    /** Last path segment without extension/query, e.g. `/e/abc.html` -> `abc`. */
    private fun codeOf(url: String): String {
        var code = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        if (code.contains('.')) code = code.substringBefore('.')
        return code
    }

    /**
     * The setup references `hls4 || hls3 || hls2`. `hls4` is a relative
     * `/stream/...` path that only works resolved against the embed host; when it
     * is missing the absolute `hls2` is used. Falls back to a plain `file:` m3u8
     * (StreamRuby).
     */
    private fun pickStream(text: String, pageUrl: String): String? {
        val candidates = linkedMapOf<Int, String>()
        HLS_RE.findAll(text).forEach { m ->
            m.groupValues[1].toIntOrNull()?.let { candidates.putIfAbsent(it, m.groupValues[2]) }
        }
        val picked = (listOf(4, 2, 3, 1).firstOrNull { candidates.containsKey(it) }
            ?: candidates.keys.maxOrNull())?.let { candidates[it] }
            ?: FILE_RE.find(text)?.groupValues?.get(1)
        return picked?.let { absolute(it, pageUrl) }
    }

    private fun absolute(link: String, pageUrl: String): String {
        val fixed = link.replace("\\/", "/")
        return when {
            fixed.startsWith("//") -> "https:$fixed"
            fixed.startsWith("/") -> {
                val origin = Regex("""^https?://[^/]+""").find(pageUrl)?.value
                    ?: mainUrl.trimEnd('/')
                "$origin$fixed"
            }
            else -> fixed
        }
    }

    private fun refererHeader(target: String): String =
        Regex("""^https?://[^/]+""").find(target)?.value?.let { "$it/" } ?: "$mainUrl/"

    protected companion object {
        const val TAG = "EgyDeadExtractor"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

        val HLS_RE = Regex("""hls(\d+)"\s*:\s*"([^"]+)"""")
        val FILE_RE = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
    }
}

// ---------------------------------------------------------------- StreamWish

class EgyDeadStreamWish : EgyDeadPackerExtractor() {
    override var name = "StreamWish"
    override var mainUrl = "https://streamwish.to"
    override val bounceHost = "https://vibuxer.com"
}

class EgyDeadStreamWishSite : EgyDeadPackerExtractor() {
    override var name = "StreamWish"
    override var mainUrl = "https://streamwish.site"
    override val bounceHost = "https://vibuxer.com"
}

class EgyDeadHgCloud : EgyDeadPackerExtractor() {
    override var name = "StreamHG"
    override var mainUrl = "https://hgcloud.to"
    override val bounceHost = "https://vibuxer.com"
}

class EgyDeadHgLink : EgyDeadPackerExtractor() {
    override var name = "StreamHG"
    override var mainUrl = "https://hglink.to"
    override val bounceHost = "https://vibuxer.com"
}

// ------------------------------------------------------------------ EarnVids

class EgyDeadEarnVids : EgyDeadPackerExtractor() {
    override var name = "EarnVids"
    override var mainUrl = "https://morencius.com"
}

class EgyDeadVidHide : EgyDeadPackerExtractor() {
    override var name = "VidHide"
    override var mainUrl = "https://vidhidevip.com"
}

class EgyDeadVidHideAlt : EgyDeadPackerExtractor() {
    override var name = "VidHide"
    override var mainUrl = "https://callistanise.com"
}

// ---------------------------------------------------------------- StreamRuby

class EgyDeadStreamRubyCom : EgyDeadPackerExtractor() {
    override var name = "StreamRuby"
    override var mainUrl = "https://stmruby.com"
}

class EgyDeadStreamRubyNet : EgyDeadPackerExtractor() {
    override var name = "StreamRuby"
    override var mainUrl = "https://streamruby.net"
}

class EgyDeadStreamRubyVip : EgyDeadPackerExtractor() {
    override var name = "StreamRuby"
    override var mainUrl = "https://rubyvidhub.com"
}

// ---------------------------------------------------------------- Reuse built-ins

/** `uqload.vc` — the driver the EgyDead player returns (built-ins only ship other TLDs). */
class EgyDeadUqload : Uqload() {
    override var mainUrl = "https://uqload.vc"
}

// ---------------------------------------------------------------------- Vidara

/**
 * `vidaraa.cc` — the current player hides the stream behind `POST /api/stream`
 * (JSON `{filecode, device, codecs}`), unlike the older `/api/...` shape the built-in
 * extractor expects.
 */
class EgyDeadVidara : ExtractorApi() {
    override var name = "Vidara"
    override var mainUrl = "https://vidaraa.cc"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        if (code.isBlank()) return
        val resp = try {
            app.post(
                "$mainUrl/api/stream",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36",
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/"
                ),
                json = mapOf(
                    "filecode" to code,
                    "device" to "web",
                    "codecs" to listOf("avc1.64001f", "hvc1.1.6.L153.B0", "mp4a.40.2")
                ),
                referer = "$mainUrl/"
            )
        } catch (e: Throwable) {
            Log.i("EgyDeadVidara", "vidara post failed $url -> $e")
            return
        }
        val stream = try {
            JSONObject(resp.text).optString("streaming_url")
        } catch (e: Throwable) {
            ""
        }
        if (stream.isBlank()) return
        callback.invoke(
            newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

// ------------------------------------------------------------------------ Byse

/**
 * Byse (`byseraguci.com`) embed host used by the EgyDead MegaMax player.
 *
 * A `/e/<code>` (or `/d/<code>`) link is the public page; the real player lives on a
 * rotating embed host whose only stable part is the host itself.
 *
 * Flow:
 *   GET  <origin>/api/videos/<code>/details            -> embed_frame_url
 *   GET  <embed>/api/videos/<code>/embed/settings      -> captcha_required
 *   (if captcha)
 *     POST <origin>/api/videos/<code>/embed/captcha         {fingerprint}
 *     solve proof-of-work over the returned pow_nonce
 *     POST <origin>/api/videos/<code>/embed/captcha/verify  {pow_token, solution, fingerprint}
 *   POST <embed>/api/videos/<code>/embed/playback       {fingerprint}
 *
 * The playback body is AES-GCM encrypted: key = concat(key_parts[version-1],
 * key_parts[len-version]), iv = decoded `iv`, ciphertext+tag = decoded `payload`.
 * The proof-of-work uses a custom 512-word hash (not SHA-256).
 */
class EgyDeadByse : ExtractorApi() {
    override var name = "Byse"
    override var mainUrl = "https://byseraguci.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val webUrl = url.trim()
        if (webUrl.isBlank()) return
        val origin = originOf(webUrl) ?: return
        val ref = "$origin/"
        val mediaId = codeFromUrl(webUrl)
        if (mediaId.isBlank()) return

        val baseHeaders = mutableMapOf(
            "User-Agent" to UA,
            "Accept" to "application/json, text/plain, */*",
            "Referer" to ref,
            "Origin" to origin,
        )

        val details = getJson("${ref}api/videos/$mediaId/details", baseHeaders)
            ?: getJson("${ref}api/videos/$mediaId/embed/details", baseHeaders)
            ?: return
        val embedUrl = details.optString("embed_frame_url").ifBlank { null }
        val embedOrigin = embedUrl?.let { originOf(it) }
        val embedRef = if (embedOrigin != null) "$embedOrigin/" else ref
        val embedPath = if (embedUrl != null) "embed/" else ""
        val playbackId = if (embedUrl != null) codeFromUrl(embedUrl) else mediaId

        val headers = baseHeaders.toMutableMap()
        if (embedOrigin != null) {
            headers["X-Embed-Parent"] = webUrl
            headers["Referer"] = embedRef
            headers["Origin"] = embedOrigin
        }

        val captchaRequired = getJson("${embedRef}api/videos/$playbackId/${embedPath}settings", headers)
            ?.optBoolean("captcha_required", false) ?: false

        val fingerprint = buildFingerprint()
        if (captchaRequired) {
            val captcha = postJson(
                "${ref}api/videos/$mediaId/${embedPath}captcha",
                headers,
                mapOf("fingerprint" to fingerprint)
            ) ?: return

            val nonce = captcha.optString("pow_nonce")
            val difficulty = captcha.optInt("pow_difficulty", 0)
            val powToken = captcha.optString("pow_token")
            if (nonce.isBlank() || powToken.isBlank()) return

            val solution = solvePow(nonce, difficulty) ?: return

            val verify = postJson(
                "${ref}api/videos/$mediaId/${embedPath}captcha/verify",
                headers,
                mapOf(
                    "pow_token" to powToken,
                    "solution" to solution,
                    "fingerprint" to fingerprint,
                )
            ) ?: return

            val captureToken = verify.optString("token")
            if (captureToken.isNotBlank()) headers["X-Captcha-Token"] = captureToken
        }

        val playback = postJson(
            "${embedRef}api/videos/$playbackId/${embedPath}playback",
            headers,
            mapOf("fingerprint" to fingerprint)
        ) ?: return

        val directSources = playback.optJSONArray("sources")
        if (directSources != null && directSources.length() > 0) {
            emit(directSources, embedRef, callback)
            return
        }

        val playbackObj = playback.optJSONObject("playback") ?: return
        val payload = playbackObj.optString("payload")
        if (payload.startsWith("http://") || payload.startsWith("https://")) {
            emitUrl(payload, null, 0, embedRef, callback)
            return
        }

        val decrypted = decryptPlayback(playbackObj) ?: return
        decrypted.optJSONArray("sources")?.let { emit(it, embedRef, callback) }
    }

    private suspend fun getJson(
        url: String,
        headers: Map<String, String>,
        referer: String? = null
    ): JSONObject? = try {
        JSONObject(app.get(url, headers = headers, referer = referer).text)
    } catch (_: Exception) {
        null
    }

    private suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: Map<String, Any>,
        referer: String? = null
    ): JSONObject? = try {
        JSONObject(app.post(url, headers = headers, json = body, referer = referer).text)
    } catch (_: Exception) {
        null
    }

    private suspend fun emit(sources: JSONArray, referer: String, callback: (ExtractorLink) -> Unit) {
        for (i in 0 until sources.length()) {
            val source = sources.optJSONObject(i) ?: continue
            val link = source.optString("url")
            if (link.isBlank()) continue
            val label = source.optString("label")
            val height = if (source.has("height") && !source.isNull("height")) source.optInt("height") else 0
            emitUrl(link, label, height, referer, callback)
        }
    }

    private suspend fun emitUrl(
        link: String,
        label: String?,
        height: Int,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = link,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                this.quality = qualityFrom(label, height)
                this.headers = mapOf("User-Agent" to UA, "Referer" to referer, "Origin" to referer.trimEnd('/'))
            }
        )
    }

    private fun decryptPlayback(playback: JSONObject): JSONObject? {
        return try {
            val algorithm = playback.optString("algorithm")
            val partsArray = playback.optJSONArray("key_parts") ?: return null
            val parts = (0 until partsArray.length()).map { partsArray.optString(it) }
            val fullKey = buildKey(parts, playback.opt("version"))
            val keyLen = when {
                algorithm.contains("256") -> 32
                algorithm.contains("128") -> 16
                else -> fullKey.size
            }
            val key = fullKey.copyOfRange(0, minOf(keyLen, fullKey.size))
            val iv = b64UrlDecode(playback.optString("iv"))
            val payload = playback.optString("payload")

            val ciphertext: ByteArray
            val tag: ByteArray
            val dot = payload.lastIndexOf('.')
            if (dot >= 0) {
                ciphertext = b64UrlDecode(payload.substring(0, dot))
                tag = b64UrlDecode(payload.substring(dot + 1))
            } else {
                val raw = b64UrlDecode(payload)
                if (raw.size <= 16) return null
                ciphertext = raw.copyOfRange(0, raw.size - 16)
                tag = raw.copyOfRange(raw.size - 16, raw.size)
            }

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(ciphertext + tag).toString(Charsets.UTF_8).removePrefix("\uFEFF")
            JSONObject(plain)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildKey(parts: List<String>, version: Any?): ByteArray {
        val v = when (version) {
            is Number -> version.toInt()
            is String -> version.toIntOrNull()
            else -> null
        }
        if (v != null && v in 1..parts.size) {
            return b64UrlDecode(parts[v - 1]) + b64UrlDecode(parts[parts.size - v])
        }
        return parts.fold(ByteArray(0)) { acc, part -> acc + b64UrlDecode(part) }
    }

    /** Forged viewer fingerprint; the server only needs a self-consistent token. */
    private fun buildFingerprint(): Map<String, Any> {
        val viewerId = randomHex(16)
        val deviceId = randomHex(16)
        val now = System.currentTimeMillis() / 1000
        val confidence = ((Math.random() * (0.94 - 0.83) + 0.83) * 100).roundToInt() / 100.0

        val payload = JSONObject()
        payload.put("viewer_id", viewerId)
        payload.put("device_id", deviceId)
        payload.put("confidence", confidence)
        payload.put("iat", now)
        payload.put("exp", now + 600)
        val data = b64UrlEncode(payload.toString().toByteArray(Charsets.UTF_8))
        val signature = b64UrlEncode(sha256(data.toByteArray(Charsets.UTF_8)))

        return linkedMapOf(
            "viewer_id" to viewerId,
            "device_id" to deviceId,
            "confidence" to confidence,
            "token" to "$data.$signature",
        )
    }

    // --- proof of work (custom 512-word hash) ---------------------------------

    private fun solvePow(token: String, difficulty: Int, timeoutMs: Long = 20_000L): String? {
        if (difficulty <= 0) return "0"
        val start = System.currentTimeMillis()
        val base = "$token:"
        var seed = 0L
        while (true) {
            for (i in 0 until 1024) {
                if (leadingZeroBits(gr("$base$seed".toByteArray(Charsets.UTF_8))) >= difficulty) {
                    return seed.toString()
                }
                seed++
            }
            if (System.currentTimeMillis() - start > timeoutMs) return null
        }
    }

    private fun rotate(value: Int, amount: Int): Int =
        (value shl amount) or (value ushr (32 - amount))

    private fun mix(t: IntArray) {
        t[0] += t[1]
        t[3] = rotate(t[3] xor t[0], 16)
        t[2] += t[3]
        t[1] = rotate(t[1] xor t[2], 12)
        t[0] += t[1]
        t[3] = rotate(t[3] xor t[0], 8)
        t[2] += t[3]
        t[1] = rotate(t[1] xor t[2], 7)
    }

    private fun gr(input: ByteArray): IntArray {
        val state = intArrayOf(
            1779033703,
            3144134277L.toInt(),
            1013904242,
            2773480762L.toInt(),
        )
        val width = 512
        val maskIndex = 511
        val rounds = 2
        val multiplier = 2654435761L.toInt()
        val xorMultiplier = 2246822519L.toInt()

        for (byte in input) {
            state[0] = state[0] + (byte.toInt() and 0xff)
            state[0] = rotate(state[0], 7)
            mix(state)
        }
        repeat(8) { mix(state) }

        val r = IntArray(width)
        for (i in 0 until width) {
            mix(state)
            r[i] = state[0] xor state[2]
        }

        repeat(rounds) {
            for (s in 0 until width) {
                val current = r[s]
                val next = r[current and maskIndex]
                var value = rotate(current + next, 13)
                value = value xor (r[(s + 1) and maskIndex] * multiplier)
                r[s] = value
                state[0] = state[0] xor value
                mix(state)
            }
        }

        val result = IntArray(8)
        val chunk = width / 8
        for (i in 0 until 8) {
            mix(state)
            var s = state[0]
            val offset = i * chunk
            for (c in 0 until chunk) {
                val d = r[offset + c]
                s += d
                s = rotate(s, 5)
                s = s xor (d * xorMultiplier)
            }
            result[i] = s xor state[2]
        }
        return result
    }

    private fun leadingZeroBits(words: IntArray): Int {
        var count = 0
        for (word in words) {
            if (word == 0) {
                count += 32
                continue
            }
            return count + Integer.numberOfLeadingZeros(word)
        }
        return count
    }

    // --- helpers --------------------------------------------------------------

    private fun originOf(url: String): String? {
        val index = url.indexOf("://")
        if (index < 0) return null
        val scheme = url.substring(0, index)
        val host = url.substring(index + 3)
            .substringBefore('/').substringBefore('?').substringBefore('#')
        if (host.isBlank()) return null
        return "$scheme://$host"
    }

    private fun codeFromUrl(url: String): String =
        url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')

    private fun qualityFrom(label: String?, height: Int): Int {
        if (height > 0) return qualityFromHeight(height)
        val digits = Regex("""(\d{3,4})""").find(label ?: "")?.groupValues?.get(1)?.toIntOrNull()
        return qualityFromHeight(digits ?: 0)
    }

    private fun qualityFromHeight(height: Int): Int = when {
        height >= 2160 -> Qualities.P2160.value
        height >= 1440 -> Qualities.P1440.value
        height >= 1080 -> Qualities.P1080.value
        height >= 720 -> Qualities.P720.value
        height >= 480 -> Qualities.P480.value
        height >= 360 -> Qualities.P360.value
        height >= 240 -> Qualities.P240.value
        height >= 144 -> Qualities.P144.value
        else -> Qualities.Unknown.value
    }

    private fun b64UrlDecode(value: String): ByteArray {
        val fixed = value.replace('-', '+').replace('_', '/')
        val padded = fixed + "=".repeat((4 - fixed.length % 4) % 4)
        return Base64.decode(padded, Base64.DEFAULT)
    }

    private fun b64UrlEncode(value: ByteArray): String =
        Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun randomHex(bytes: Int): String {
        val buffer = ByteArray(bytes)
        SecureRandom().nextBytes(buffer)
        val sb = StringBuilder(bytes * 2)
        for (b in buffer) {
            val v = b.toInt() and 0xff
            sb.append(Character.forDigit(v shr 4, 16))
            sb.append(Character.forDigit(v and 0x0f, 16))
        }
        return sb.toString()
    }

    private fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 10; TX6s) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    }
}
