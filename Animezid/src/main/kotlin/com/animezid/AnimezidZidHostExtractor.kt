package com.animezid

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AnimezidRpmHostExtractor : AnimezidZidHostExtractor() {
    override var name = "RPMShare"
    override var mainUrl = "https://zid.rpmhub.site"
}

class AnimezidUpnShareExtractor : AnimezidZidHostExtractor() {
    override var name = "UPNShare"
    override var mainUrl = "https://zid.upns.online"
}

class AnimezidStreamP2PExtractor : AnimezidZidHostExtractor() {
    override var name = "StreamP2P"
    override var mainUrl = "https://zid.streamcasthub.store"
}

class AnimezidEasyVidPlayExtractor : AnimezidZidHostExtractor() {
    override var name = "EasyVidPlay"
    override var mainUrl = "https://zid.vidplayer.live"
}

/**
 * "Zid" / SmartPlayer family used by Animezid (RPMShare / UPNShare / StreamP2P / EasyVidPlay).
 *
 * The player is a SPA whose mirror link looks like `https://<host>/#<id>`. Its API returns an
 * hex encoded AES-CBC blob:
 *
 *   GET https://<host>/api/v1/video?id=<id>  ->  hex(ciphertext)
 *   plaintext = AES-CBC(key="kiemtienmua911ca", iv="1234567890oiuytr", hexDecoded)
 *
 * The decrypted JSON holds the master playlists:
 *   cfNative (host-proxied master.m3u8 with a token), source (direct master.m3u8), cf (Cloudflare .txt)
 *
 * Every usable master playlist is expanded through M3u8Helper so all advertised qualities are emitted.
 */
open class AnimezidZidHostExtractor : ExtractorApi() {
    override var name = "RPMShare"
    override var mainUrl = "https://zid.rpmhub.site"
    override val requiresReferer = false

    private val secretKey = SecretKeySpec("kiemtienmua911ca".toByteArray(Charsets.UTF_8), "AES")
    private val iv = IvParameterSpec("1234567890oiuytr".toByteArray(Charsets.UTF_8))

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val host = when {
            url.contains("://") -> url.substringAfter("://").substringBefore("/")
            url.startsWith("//") -> url.substring(2).substringBefore("/")
            else -> mainUrl.substringAfter("://").substringBefore("/")
        }
        val origin = "https://$host"

        val id = url.substringAfterLast('#', "")
            .substringBefore('&')
            .substringBefore('?')
            .trim()
            .ifBlank {
                url.substringAfterLast("id=", "")
                    .substringBefore('&')
                    .trim()
            }
        if (id.isBlank()) return

        val apiHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json, text/plain, */*",
            "Referer" to "$origin/",
            "Origin" to origin
        )

        val raw = try {
            app.get("$origin/api/v1/video?id=$id", headers = apiHeaders, referer = origin).text
        } catch (_: Exception) {
            return
        }
        if (raw.isBlank()) return

        val json = decryptHex(raw) ?: return

        val linkHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Referer" to "$origin/",
            "Origin" to origin
        )

        // Host-proxied master playlist -> expanded to every quality.
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

        // Direct source + Cloudflare master playlists.
        listOf("source", "cf").forEach { key ->
            val link = json.optString(key)
            if (link.isNotBlank()) emitRaw(link, origin, linkHeaders, callback)
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
                source = name,
                name = name,
                url = link,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$origin/"
                this.quality = Qualities.Unknown.value
                this.headers = headers
            }
        )
    }

    private fun decryptHex(hex: String): JSONObject? {
        return try {
            val clean = hex.trim().trim('"')
                .let { if (it.length % 2 != 0) it.dropLast(1) else it }
            if (clean.isEmpty()) return null
            val data = ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, iv)
            val plain = cipher.doFinal(data).toString(Charsets.UTF_8)
            val start = plain.indexOf('{')
            val end = plain.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            JSONObject(plain.substring(start, end + 1))
        } catch (_: Exception) {
            null
        }
    }
}
