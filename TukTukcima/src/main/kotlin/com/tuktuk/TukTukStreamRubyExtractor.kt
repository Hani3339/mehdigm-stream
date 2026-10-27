package com.tuktuk

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

/** Resolves StreamRuby embeds returned by MegaMax/MegaTuktuk mirror lists. */
class TukTukStreamRubyExtractor : ExtractorApi() {
    override var name = "StreamRuby"
    override var mainUrl = "https://streamruby.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val embedUrl = url.trim()
        if (embedUrl.isBlank()) return
        val origin = TukTukMediaUtils.originOf(embedUrl, mainUrl)
        val headers = mapOf(
            "User-Agent" to TukTukMediaUtils.BROWSER_USER_AGENT,
            "Accept" to "*/*",
            "Referer" to "$origin/",
            "Origin" to origin,
        )

        val candidates = if (isMediaUrl(embedUrl)) {
            listOf(embedUrl)
        } else {
            val fileCode = fileCodeFromUrl(embedUrl) ?: return
            val response = try {
                app.post(
                    "$origin/dl",
                    data = mapOf(
                        "op" to "embed",
                        "file_code" to fileCode,
                        "auto" to "1",
                        "referer" to (referer?.takeIf { it.startsWith("http", true) } ?: embedUrl),
                    ),
                    headers = headers,
                    referer = embedUrl,
                ).text
            } catch (e: Exception) {
                Log.w(name, "StreamRuby request failed for $embedUrl", e)
                return
            }
            if (response.isBlank()) return
            val unpacked = runCatching { JsUnpacker(response).unpack() }.getOrNull() ?: response
            TukTukMediaUtils.mediaUrls(embedUrl, unpacked).filter(::isMediaUrl)
        }.mapNotNull { TukTukMediaUtils.resolveMediaUrl(embedUrl, it) }
            .distinct()

        if (candidates.isEmpty()) {
            Log.w(name, "No original media URL found in StreamRuby response for $embedUrl")
            return
        }

        val emitted = linkedSetOf<String>()
        for (mediaUrl in candidates) {
            if (TukTukMediaUtils.isHls(mediaUrl)) {
                val variants = try {
                    M3u8Helper.generateM3u8(name, mediaUrl, "$origin/", headers = headers)
                } catch (e: Exception) {
                    Log.w(name, "Could not expand StreamRuby playlist $mediaUrl", e)
                    emptyList()
                }
                if (variants.isNotEmpty()) {
                    variants.forEach { variant ->
                        if (emitted.add(variant.url)) callback(variant)
                    }
                    continue
                }
            }

            if (!emitted.add(mediaUrl)) continue
            val type = when {
                TukTukMediaUtils.isHls(mediaUrl) -> ExtractorLinkType.M3U8
                mediaUrl.substringBefore('?').endsWith(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = mediaUrl,
                    type = type,
                ) {
                    this.referer = "$origin/"
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                },
            )
        }
    }

    internal companion object {
        private val fileCodePattern = Regex("[A-Za-z0-9_-]{4,}")
        private val queryCodePattern = Regex("(?:^|&)(?:file_code|file|id)=([^&]+)", RegexOption.IGNORE_CASE)

        fun fileCodeFromUrl(url: String): String? {
            val uri = runCatching { URI(url) }.getOrNull()
            val queryCode = uri?.rawQuery?.let { queryCodePattern.find(it)?.groupValues?.getOrNull(1) }
                ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
                ?.takeIf { fileCodePattern.matches(it) }
            if (queryCode != null) return queryCode

            val segment = uri?.path.orEmpty().trimEnd('/').substringAfterLast('/')
                .substringBefore('?').substringBefore('#').substringBefore('.')
            val code = segment.substringAfterLast('-').trim()
            return code.takeIf { fileCodePattern.matches(it) }
        }

        fun isMediaUrl(url: String): Boolean = TukTukMediaUtils.isHls(url) ||
            Regex("\\.(?:mp4|m4v|webm|mpd)(?:$|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(url)
    }
}
