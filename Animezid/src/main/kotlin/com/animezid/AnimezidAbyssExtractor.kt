package com.animezid

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Abyss player (player.abyssplayer.com / *.abyss.to) used by Animezid.
 *
 * The embed page inlines `datas = "<base64>"`. Decoding it (as a latin-1 JSON blob)
 * yields `{slug, md5_id, user_id, media}`. The `media` field is an AES-CTR encrypted
 * string:
 *
 *   key     = MD5("user_id:slug:md5_id") as lowercase hex  (32 ASCII bytes)
 *   counter = first 16 bytes of the key
 *   cipher  = AES-256-CTR
 *
 * The plaintext is JSON of the form `{"mp4":{"sources":[{label,url,path,...}]}}` and each
 * entry is a direct MP4 (url + "/" + path).
 */
class AnimezidAbyssExtractor : ExtractorApi() {
    override var name = "Abyss"
    override var mainUrl = "https://player.abyssplayer.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = url.trim()
        if (embedUrl.isBlank()) return

        val page = try {
            app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.9"
                ),
                referer = referer
            ).text
        } catch (_: Exception) {
            return
        }
        if (page.isBlank()) return

        val encoded = Regex("""datas\s*=\s*"([^"]+)"""").find(page)?.groupValues?.get(1)
            ?: return

        val root = try {
            val raw = Base64.decode(encoded, Base64.DEFAULT)
            JSONObject(String(raw, Charsets.ISO_8859_1))
        } catch (_: Exception) {
            return
        }

        val slug = root.optString("slug")
        val userId = root.optString("user_id")
        val md5Id = root.optString("md5_id")
        val media = root.optString("media")
        if (media.isBlank() || slug.isBlank()) return

        val plain = decryptMedia(userId, slug, md5Id, media) ?: return
        val json = try {
            JSONObject(plain)
        } catch (_: Exception) {
            return
        }

        val host = embedUrl.substringAfter("://").substringBefore("/")
        val linkReferer = "https://$host/"

        val keys = json.keys()
        while (keys.hasNext()) {
            val container = json.optJSONObject(keys.next()) ?: continue
            val sources = container.optJSONArray("sources") ?: continue
            for (i in 0 until sources.length()) {
                val src = sources.optJSONObject(i) ?: continue
                val label = src.optString("label")
                val base = src.optString("url").trim().trimEnd('/')
                val path = src.optString("path").trim().trimStart('/')
                val full = when {
                    base.isBlank() -> continue
                    path.isBlank() -> base
                    else -> "$base/$path"
                }
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = full,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = linkReferer
                        this.quality = qualityFromLabel(label)
                        this.headers = mapOf("User-Agent" to USER_AGENT)
                    }
                )
            }
        }
    }

    private fun decryptMedia(
        userId: String,
        slug: String,
        md5Id: String,
        media: String
    ): String? {
        return try {
            val keyBytes = md5Hex("$userId:$slug:$md5Id").toByteArray(Charsets.US_ASCII)
            if (keyBytes.size < 16) return null
            val counter = keyBytes.copyOfRange(0, 16)
            val data = ByteArray(media.length) { media[it].code.toByte() }
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(counter)
            )
            cipher.doFinal(data).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append(Character.forDigit(v shr 4, 16))
            sb.append(Character.forDigit(v and 0x0f, 16))
        }
        return sb.toString()
    }

    private fun qualityFromLabel(label: String): Int {
        val digits = Regex("""(\d{3,4})""").find(label)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            digits == null -> Qualities.Unknown.value
            digits >= 2160 -> Qualities.P2160.value
            digits >= 1440 -> Qualities.P1440.value
            digits >= 1080 -> Qualities.P1080.value
            digits >= 720 -> Qualities.P720.value
            digits >= 480 -> Qualities.P480.value
            digits >= 360 -> Qualities.P360.value
            digits >= 240 -> Qualities.P240.value
            else -> Qualities.P144.value
        }
    }
}
