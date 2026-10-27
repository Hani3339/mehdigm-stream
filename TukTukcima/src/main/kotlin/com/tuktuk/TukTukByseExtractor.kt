package com.tuktuk

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * byse (byseraguci.com) extractor for TukTuk.
 *
 * The player mirror is `//byseraguci.com/e/<code>`. The same-origin JSON API
 * `GET <origin>/api/videos/<code>` returns a `playback` object carrying an
 * AES-256-GCM encrypted payload:
 *
 *   playback = { algorithm:"AES-256-GCM", iv, payload, key_parts:[...], version }
 *
 * The 32-byte key is rebuilt client-side from two of the `key_parts` selected by
 * `version`, namely the 1-based parts `[version, 31 - version]` (base64url-decoded
 * and concatenated). The `iv`/`payload` are base64url-decoded and decrypted with
 * AES/GCM. The plaintext JSON holds `sources:[{url,label,height,...}]` — usually a
 * tokenised `.m3u8`.
 */
class TukTukByseExtractor : ExtractorApi() {
    override val name = "byse"
    override var mainUrl = "https://byseraguci.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = originOf(url) ?: mainUrl
        val code = extractCode(url)
        if (code.isBlank()) return

        val body = try {
            app.get(
                "$origin/api/videos/$code",
                headers = API_HEADERS,
                referer = referer ?: "$origin/"
            ).text
        } catch (t: Throwable) {
            Log.i(TAG, "api fetch failed for $code: $t")
            return
        }

        val playback = try {
            JSONObject(body).optJSONObject("playback")
        } catch (t: Throwable) {
            Log.i(TAG, "invalid api json for $code: $t")
            null
        } ?: return

        val plain = try {
            decryptPlayback(playback)
        } catch (t: Throwable) {
            Log.i(TAG, "decrypt failed for $code: $t")
            return
        }

        val sources = plain.optJSONArray("sources") ?: return
        val headers = mapOf(
            "User-Agent" to BROWSER_UA,
            "Referer" to "$origin/",
            "Origin" to origin
        )
        for (i in 0 until sources.length()) {
            val src = sources.optJSONObject(i) ?: continue
            val rawFile = src.optString("url").ifBlank { src.optString("file") }.trim()
            if (rawFile.isBlank()) continue
            val file = TukTukMediaUtils.resolveMediaUrl(origin, rawFile) ?: rawFile
            val height = src.optInt("height", 0).takeIf { it > 0 }
            val label = src.optString("label").ifBlank { src.optString("quality") }.trim()
            emitSource(file, label, height, origin, headers, callback)
        }
    }

    private suspend fun emitSource(
        file: String,
        label: String,
        height: Int?,
        origin: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        val quality = height ?: TukTukMediaUtils.qualityHint(label)
        val isHls = TukTukMediaUtils.isHls(file)
        if (isHls) {
            val generated = try {
                M3u8Helper.generateM3u8(
                    name,
                    file,
                    "$origin/",
                    quality = quality.takeIf { it > 0 },
                    headers = headers
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not expand HLS source $file", e)
                emptyList()
            }
            if (generated.isNotEmpty()) {
                generated.forEach(callback)
                return
            }
        }

        val type = when {
            isHls -> ExtractorLinkType.M3U8
            file.substringBefore('?').endsWith(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        callback(
            ExtractorLink(
                source = name,
                name = label.ifBlank { name },
                url = file,
                referer = "$origin/",
                quality = quality.takeIf { it > 0 } ?: -1,
                headers = headers,
                type = type
            )
        )
    }

    // ---------------------------------------------------------------- decryption

    private fun decryptPlayback(playback: JSONObject): JSONObject {
        val keyParts = playback.optJSONArray("key_parts")
            ?: throw IllegalStateException("missing key_parts")
        val version = playback.optInt("version", 1)
        val key = buildKey(keyParts, version)
        val iv = b64url(playback.getString("iv"))
        val payload = b64url(playback.getString("payload"))

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, iv)
        )
        val plain = cipher.doFinal(payload)
        return JSONObject(String(plain, Charsets.UTF_8))
    }

    private fun buildKey(parts: JSONArray, version: Int): ByteArray {
        val all = (0 until parts.length()).map { parts.optString(it) }.filter { it.isNotEmpty() }
        val indices = keyPartIndices(version, all.size)
        val selected = if (indices.isEmpty()) all else indices.map { all[it - 1] }
        val out = java.io.ByteArrayOutputStream()
        selected.forEach { out.write(b64url(it)) }
        return out.toByteArray()
    }

    /** Mirrors the site's key derivation: version n maps to 1-based parts [n, 31 - n]. */
    private fun keyPartIndices(version: Int, count: Int): List<Int> {
        if (version < 1 || version > 30) return emptyList()
        val a = version
        val b = 31 - version
        if (a < 1 || b < 1 || a > count || b > count) return emptyList()
        return listOf(a, b)
    }

    private fun b64url(input: String): ByteArray {
        var s = input.trim().replace('-', '+').replace('_', '/')
        val pad = (4 - s.length % 4) % 4
        if (pad != 0) s += "=".repeat(pad)
        return Base64.decode(s, Base64.DEFAULT)
    }

    // ---------------------------------------------------------------- helpers

    private fun extractCode(url: String): String {
        val path = try {
            URI(url).path.orEmpty()
        } catch (_: Throwable) {
            url.substringAfter("//").substringAfter('/')
        }
        return path.trimEnd('/').substringAfterLast('/')
            .removeSuffix(".html")
            .substringBefore('?')
            .substringBefore('.')
    }

    private fun originOf(url: String): String? = try {
        val u = URI(url)
        if (u.host.isNullOrBlank()) null else "${u.scheme}://${u.host}"
    } catch (_: Throwable) {
        null
    }

    companion object {
        private const val TAG = "TukTukByse"
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val API_HEADERS = mapOf(
            "User-Agent" to BROWSER_UA,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "en-US,en;q=0.9"
        )
    }
}
