package com.animezid

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URLDecoder

/**
 * MegaMax (megamax.me) embed host used by Animezid.
 *
 * The `/iframe/<hash>` page is only a protected player. The real value is the
 * download page which lists every quality as an "attachment", and each
 * attachment resolves to a set of mirrors (drivers). One of those drivers is
 * Streamtape, so for every quality we surface its Streamtape link and let
 * CloudStream's built-in Streamtape extractor turn it into a playable source.
 *
 * Flow:
 *   GET  /download/<hash>            -> props.attachments[] (hashId, label, resolution)
 *   POST /download/<hash> {attach}   -> props.download.data.mirrors[] (driver, link)
 *   pick driver == "streamtape"      -> https://streamtape.com/v/<id>/
 */
class AnimezidMegaMaxExtractor : ExtractorApi() {
    override var name = "MegaMax"
    override var mainUrl = "https://megamax.me"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val hash = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        if (hash.isBlank()) return
        val downloadUrl = "$mainUrl/download/$hash"

        val pageResp = try {
            app.get(
                downloadUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                ),
                referer = "$mainUrl/"
            )
        } catch (_: Exception) {
            return
        }

        val root = try {
            val data = pageResp.document.selectFirst("script[data-page=app]")?.data() ?: return
            JSONObject(data)
        } catch (_: Exception) {
            return
        }
        val attachments = root.optJSONObject("props")?.optJSONArray("attachments") ?: return
        val version = root.optString("version")

        // Laravel expects the URL-decoded XSRF-TOKEN cookie value. NiceHttp may
        // hand us the raw (percent-encoded) cookie, so only decode when needed.
        val xsrf = pageResp.cookies["XSRF-TOKEN"]?.let { raw ->
            if (raw.contains('%')) {
                runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            } else {
                raw
            }
        }
        val cookieHeader = pageResp.cookies.entries
            .filter { it.value.isNotBlank() }
            .joinToString("; ") { "${it.key}=${it.value}" }

        val headers = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html, application/xhtml+xml",
            "Content-Type" to "application/json",
            "X-Requested-With" to "XMLHttpRequest",
            "X-Inertia" to "true",
            "X-Inertia-Partial-Component" to "files/mirror/download",
            "X-Inertia-Partial-Data" to "download",
            "Origin" to mainUrl,
            "Referer" to downloadUrl,
        )
        if (!version.isNullOrBlank()) headers["X-Inertia-Version"] = version
        if (!xsrf.isNullOrBlank()) headers["X-XSRF-TOKEN"] = xsrf
        if (cookieHeader.isNotBlank()) headers["Cookie"] = cookieHeader

        for (i in 0 until attachments.length()) {
            val att = attachments.optJSONObject(i) ?: continue
            val attId = att.optString("hashId").ifBlank { continue }
            val label = att.optString("label").ifBlank { att.optString("resolution") }
            val quality = qualityFrom(att.optString("resolution"), label)

            val mirrors = try {
                val resp = app.post(
                    downloadUrl,
                    headers = headers,
                    json = mapOf("attach" to attId),
                    referer = downloadUrl
                )
                JSONObject(resp.text)
                    .optJSONObject("props")?.optJSONObject("download")
                    ?.optJSONObject("data")?.optJSONArray("mirrors")
            } catch (_: Exception) {
                null
            }
            if (mirrors == null) continue

            for (j in 0 until mirrors.length()) {
                val mirror = mirrors.optJSONObject(j) ?: continue
                val driver = mirror.optString("driver")
                val isStreamtape = driver.equals("streamtape", ignoreCase = true)
                val isByse = driver.equals("byse", ignoreCase = true)
                if (!isStreamtape && !isByse) continue
                val raw = mirror.optString("link")
                if (raw.isBlank()) continue
                val streamUrl = if (raw.startsWith("//")) "https:$raw" else raw
                val driverLabel = if (isByse) "Byse" else "Streamtape"
                // Byse resolves the concrete quality itself and reports it to us;
                // Streamtape returns an unqualified link so we tag the attachment's.
                val displayName = if (isByse) "$name $driverLabel" else "$name $driverLabel $label"

                val resolved = mutableListOf<ExtractorLink>()
                try {
                    loadExtractor(streamUrl, "$mainUrl/", subtitleCallback) { resolved.add(it) }
                } catch (_: Exception) {
                    // ignore and continue with the next quality
                }

                resolved.forEach { link ->
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = displayName.trim(),
                            url = link.url,
                            type = link.type
                        ) {
                            this.referer = link.referer
                            this.headers = link.headers
                            this.quality = if (link.quality != Qualities.Unknown.value) link.quality else quality
                        }
                    )
                }
            }
        }
    }

    private fun qualityFrom(resolution: String?, label: String?): Int {
        val height = Regex("(\\d{3,4})")
            .findAll(resolution ?: "")
            .mapNotNull { it.value.toIntOrNull() }
            .lastOrNull()
            ?: Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE)
                .find(label ?: "")?.groupValues?.get(1)?.toIntOrNull()
        return when {
            height == null -> Qualities.Unknown.value
            height >= 2160 -> Qualities.P2160.value
            height >= 1440 -> Qualities.P1440.value
            height >= 1080 -> Qualities.P1080.value
            height >= 720 -> Qualities.P720.value
            height >= 480 -> Qualities.P480.value
            height >= 360 -> Qualities.P360.value
            height >= 240 -> Qualities.P240.value
            else -> Qualities.P144.value
        }
    }
}
