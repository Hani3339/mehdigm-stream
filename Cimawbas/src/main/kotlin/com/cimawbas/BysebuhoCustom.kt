package com.cimawbas

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
 * Bysebuho (bysebuho.com) extractor for Cimawbas.
 *
 * The embed page `https://bysebuho.com/e/<code>` is a React SPA; the real data comes
 * from the same-origin JSON API `GET /api/videos/<code>`. Its `playback` object carries
 * an AES-256-GCM encrypted payload:
 *
 *   playback = { algorithm:"AES-256-GCM", iv, payload, key_parts:[...], version }
 *
 * The 32-byte key is rebuilt client-side: two of the `key_parts` (picked by `version`,
 * see [keyPartIndices]) are base64url-decoded and concatenated, the `iv`/`payload` are
 * base64url-decoded and decrypted with AES/GCM. The plaintext JSON holds
 * `sources:[{url,mime_type,quality,label,height}]` — usually a tokenised `.m3u8`.
 */
class BysebuhoCustom : ExtractorApi() {
    override val name = "Bysebuho"
    override val mainUrl = "https://bysebuho.com"
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
            val file = src.optString("url").ifBlank { src.optString("file") }.trim()
            if (file.isBlank()) continue
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
        if (file.contains(".m3u8")) {
            val generated = try {
                M3u8Helper.generateM3u8(name, file, "$origin/", quality = height, headers = headers)
            } catch (_: Throwable) {
                emptyList()
            }
            if (generated.isNotEmpty()) {
                generated.forEach(callback)
                return
            }
            callback(
                ExtractorLink(
                    source = name,
                    name = label.ifBlank { name },
                    url = file,
                    referer = "$origin/",
                    quality = height ?: -1,
                    headers = headers,
                    type = ExtractorLinkType.M3U8
                )
            )
        } else {
            callback(
                ExtractorLink(
                    source = name,
                    name = label.ifBlank { name },
                    url = file,
                    referer = "$origin/",
                    quality = height ?: -1,
                    headers = headers,
                    type = ExtractorLinkType.VIDEO
                )
            )
        }
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

    /** Mirrors the site's `Qa`/`Ea`: version n maps to 1-based parts [n, 31 - n]. */
    private fun keyPartIndices(version: Int, count: Int): List<Int> {
        if (version < 1 || version > 20) return emptyList()
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
        private const val TAG = "CimawbasBysebuho"
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val API_HEADERS = mapOf(
            "User-Agent" to BROWSER_UA,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "en-US,en;q=0.9"
        )
    }
}
