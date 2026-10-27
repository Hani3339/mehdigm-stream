package com.tuktuk

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * upnshare (tuktuk.upns.one) extractor for TukTuk.
 *
 * Mirror link looks like `//tuktuk.upns.one/#<id>`. The player is a SPA whose API
 * returns AES-CBC encrypted hex payloads:
 *
 *   GET https://tuktuk.upns.one/api/v1/video?id=<id>  ->  hex(ciphertext)
 *   plaintext = AES-CBC(key="kiemtienmua911ca", iv="1234567890oiuytr", hexDecoded)
 *
 * The decrypted JSON contains the master playlists:
 *   cfNative (proxied master.m3u8, all qualities), source, cf
 *
 * We emit every master playlist expanded to all its qualities for watching/download.
 */
class TukTukUpnExtractor : ExtractorApi() {
    override var name = "upnshare"
    override var mainUrl = "https://tuktuk.upns.one"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val id = url.substringAfterLast('#', "")
                .substringBefore('?')
                .trim()
                .ifBlank { url.substringAfterLast('/').trim() }
            val host = when {
                url.contains("://") -> url.substringAfter("://").substringBefore("/")
                url.startsWith("//") -> url.substring(2).substringBefore("/")
                else -> mainUrl.substringAfter("://").substringBefore("/")
            }
            val origin = "https://$host"
            Log.d(name, "Step 1: id=$id origin=$origin")

            val headers = mapOf(
                "User-Agent" to BROWSER_UA,
                "Accept" to "*/*",
                "Referer" to "$origin/",
                "Origin" to origin
            )
            val raw = app.get("$origin/api/v1/video?id=$id", headers = headers, referer = origin).text
            val decrypted = decryptHex(raw)
            if (decrypted.isNullOrBlank()) {
                Log.w(name, "Could not decrypt API response")
                return
            }
            Log.d(name, "Decrypted: ${decrypted.take(300)}")

            val json = try {
                JSONObject(decrypted)
            } catch (e: Exception) {
                Log.w(name, "Not a JSON payload", e)
                return
            }

            val linkHeaders = mapOf(
                "User-Agent" to BROWSER_UA,
                "Referer" to "$origin/",
                "Origin" to origin
            )

            val cfNative = json.optString("cfNative")
            if (cfNative.isNotBlank()) {
                val generated = try {
                    M3u8Helper.generateM3u8(name, cfNative, "$origin/", headers = linkHeaders)
                } catch (_: Throwable) {
                    emptyList()
                }
                if (generated.isNotEmpty()) {
                    generated.forEach(callback)
                } else {
                    emitRaw(cfNative, origin, linkHeaders, callback)
                }
            }

            listOf("source", "cf").forEach { key ->
                val link = json.optString(key)
                if (link.isNotBlank()) emitRaw(link, origin, linkHeaders, callback)
            }
        } catch (e: Exception) {
            Log.e(name, "Error in upnshare extractor", e)
        }
    }

    private suspend fun emitRaw(
        link: String,
        origin: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = link,
            ) {
                this.referer = "$origin/"
                quality = Qualities.Unknown.value
                type = ExtractorLinkType.M3U8
                this.headers = headers
            }
        )
    }

    private fun decryptHex(hex: String): String? {
        return try {
            val clean = hex.trim()
            val data = ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(KEY, "AES"),
                IvParameterSpec(IV)
            )
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(name, "decryptHex failed", e)
            null
        }
    }

    private companion object {
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val KEY = "kiemtienmua911ca".toByteArray(Charsets.UTF_8)
        val IV = "1234567890oiuytr".toByteArray(Charsets.UTF_8)
    }
}
