package com.tuktuk

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MegaMax four-channel player: encrypted leech streams (`/e`, `/d`) plus the
 * multi-provider mirror player (`/iframe`, `/download`). Mirror URLs are dispatched
 * through CloudStream's extractor registry, with TukTuk-specific extractors handled directly.
 */
class TukTukMegaMaxExtractor : ExtractorApi() {
    override var name = "MegaMax"
    override var mainUrl = "https://megamax.me"
    override val requiresReferer = false

    private data class InertiaSession(
        val url: String,
        val origin: String,
        val page: JSONObject,
        val version: String,
        val component: String,
        val cookieHeader: String,
        val xsrfToken: String,
    )

    private data class MirrorTask(
        val url: String,
        val driver: String,
        val qualityLabel: String,
        val referer: String,
        val origin: String,
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val hashId = TukTukMediaUtils.extractMegaMaxId(url) ?: return
        val preferredOrigin = TukTukMediaUtils.originOf(url, mainUrl)
        val parentReferer = referer?.takeIf { it.startsWith("http") } ?: "$preferredOrigin/"
        val origins = originCandidates(preferredOrigin)

        val emitted = linkedSetOf<String>()
        // MegaMax exposes two direct/leech channels as well as its mirror-player
        // channels. Keep all four: leech links can remain available when a mirror host
        // is offline, and the mirror list can contain providers not present in leech.
        fetchLeechHls(hashId, origins, parentReferer, subtitleCallback, emitted, callback)
        val directTasks = fetchLeechDirectMp4(hashId, origins, parentReferer)

        val tasks = (directTasks + fetchIframeMirrors(hashId, origins, parentReferer) +
            fetchDownloadMirrors(hashId, origins, parentReferer))
            .distinctBy { "${it.driver.lowercase()}|${it.url.lowercase()}|${it.qualityLabel.lowercase()}" }

        if (tasks.isEmpty() && emitted.isEmpty()) {
            Log.w(name, "No MegaMax streams or mirrors found for $hashId ($url)")
            return
        }

        for (task in tasks) {
            try {
                resolveMirror(task, subtitleCallback, emitted, callback)
            } catch (e: Exception) {
                Log.w(name, "Could not resolve ${task.driver} mirror ${task.url}", e)
            }
        }
        Log.i(name, "Resolved ${emitted.size} unique MegaMax link(s) for $hashId")
    }

    private fun originCandidates(preferredOrigin: String): List<String> = listOf(
        preferredOrigin,
        "https://share4max.com",
        "https://megamax.me",
    ).distinct()

    private suspend fun openInertiaSession(targetUrl: String, referer: String): InertiaSession? {
        val response = try {
            app.get(
                targetUrl,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Sec-Fetch-Dest" to "iframe",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "cross-site",
                ),
            )
        } catch (e: Exception) {
            Log.d(name, "MegaMax page request failed: $targetUrl (${e.message})")
            return null
        }

        val page = parseInertiaPage(response.text) ?: return null
        val finalUrl = response.url.ifBlank { targetUrl }
        val origin = TukTukMediaUtils.originOf(finalUrl, TukTukMediaUtils.originOf(targetUrl, mainUrl))
        val cookies = response.cookies.filterValues { it.isNotBlank() }
        val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val xsrf = cookies["XSRF-TOKEN"]?.let { raw ->
            if (raw.contains('%')) runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw) else raw
        }.orEmpty()

        return InertiaSession(
            url = finalUrl,
            origin = origin,
            page = page,
            version = page.optString("version").takeUnless { it == "null" }.orEmpty(),
            component = page.optString("component").takeUnless { it == "null" }.orEmpty(),
            cookieHeader = cookieHeader,
            xsrfToken = xsrf,
        )
    }

    private fun parseInertiaPage(html: String): JSONObject? {
        val document = Jsoup.parse(html)
        val candidates = listOfNotNull(
            document.selectFirst("script[data-page=app]")?.data()?.trim(),
            document.selectFirst("script[data-page]")?.data()?.trim(),
            document.selectFirst("[data-page]")?.attr("data-page")
                ?.let { Parser.unescapeEntities(it, false).trim() },
        )
        for (candidate in candidates) {
            if (!candidate.startsWith("{")) continue
            val parsed = runCatching { JSONObject(candidate) }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun buildInertiaHeaders(
        session: InertiaSession,
        partialComponent: String,
        partialData: String,
        isPost: Boolean = false,
    ): Map<String, String> = LinkedHashMap<String, String>().apply {
        put("User-Agent", USER_AGENT)
        put(
            "Accept",
            if (isPost) "application/json, text/plain, */*" else "text/html, application/xhtml+xml"
        )
        put("Content-Type", "application/json")
        put("Referer", session.url)
        if (isPost) put("Origin", session.origin)
        put("X-Requested-With", "XMLHttpRequest")
        put("X-Inertia", "true")
        if (session.version.isNotBlank()) put("X-Inertia-Version", session.version)
        put("X-Inertia-Partial-Component", partialComponent)
        put("X-Inertia-Partial-Data", partialData)
        if (session.cookieHeader.isNotBlank()) put("Cookie", session.cookieHeader)
        if (session.xsrfToken.isNotBlank()) put("X-XSRF-TOKEN", session.xsrfToken)
    }

    /** Channel 1: `/e/<id>` publishes encrypted direct HLS sources and subtitles. */
    private suspend fun fetchLeechHls(
        hashId: String,
        origins: List<String>,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit,
    ) {
        for (base in origins) {
            val session = openInertiaSession("$base/e/$hashId", referer) ?: continue
            val component = session.component.ifBlank { "files/leech/video" }
            val body = try {
                app.get(
                    session.url,
                    headers = buildInertiaHeaders(session, component, "streams,subtitles"),
                    referer = session.url,
                ).text
            } catch (e: Exception) {
                Log.d(name, "MegaMax leech reload failed: ${session.url} (${e.message})")
                continue
            }
            val props = parseInertiaResponse(body)?.optJSONObject("props") ?: continue

            props.optJSONArray("subtitles")?.let { subtitles ->
                for (i in 0 until subtitles.length()) {
                    val subtitle = subtitles.optJSONObject(i) ?: continue
                    val rawUrl = subtitle.optString("file").ifBlank { subtitle.optString("url") }
                    val subtitleUrl = TukTukMediaUtils.resolveMediaUrl(session.url, rawUrl) ?: continue
                    val language = subtitle.optString("label").ifBlank { "Arabic" }
                    subtitleCallback(newSubtitleFile(language, subtitleUrl))
                }
            }

            val dataRaw = props.optJSONObject("streams")?.optString("data").orEmpty().trim()
            if (dataRaw.isBlank()) continue
            val plain = when {
                dataRaw.contains("\"ct\"") -> decryptCryptoJsJson(dataRaw)
                dataRaw.startsWith("U2FsdGVkX1") -> decryptOpenSslAes(dataRaw)
                else -> dataRaw
            }?.trim() ?: continue

            val before = emitted.size
            val streamObjects = if (plain.startsWith("[")) {
                val array = runCatching { JSONArray(plain) }.getOrNull()
                (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }
            } else if (plain.startsWith("{")) {
                listOfNotNull(runCatching { JSONObject(plain) }.getOrNull())
            } else {
                emptyList()
            }
            for (stream in streamObjects) {
                emitLeechObject(stream, session, emitted, callback)
            }
            if (emitted.size > before) return
        }
    }

    private suspend fun emitLeechObject(
        stream: JSONObject,
        session: InertiaSession,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit,
    ) {
        val rawUrl = stream.optString("file").ifBlank { stream.optString("url") }
        val mediaUrl = TukTukMediaUtils.resolveMediaUrl(session.url, rawUrl) ?: return
        val label = stream.optString("label").ifBlank { stream.optString("quality") }
        val quality = TukTukMediaUtils.qualityHint("$label ${stream.optString("height")} $mediaUrl")
        val forceHls = stream.optString("type").contains("mpegurl", ignoreCase = true)
        emitDirect(
            url = mediaUrl,
            task = MirrorTask(
                url = mediaUrl,
                driver = "Direct",
                qualityLabel = label,
                referer = "${session.origin}/",
                origin = session.origin,
            ),
            qualityHint = quality,
            emitted = emitted,
            callback = callback,
            forceHls = forceHls,
        )
    }

    /** Channel 2: `/d/<id>` returns direct downloadable files per attachment. */
    private suspend fun fetchLeechDirectMp4(
        hashId: String,
        origins: List<String>,
        referer: String,
    ): List<MirrorTask> {
        for (base in origins) {
            val session = openInertiaSession("$base/d/$hashId", referer) ?: continue
            val attachments = session.page.optJSONObject("props")?.optJSONArray("attachments") ?: continue
            if (attachments.length() == 0) continue
            val component = session.component.ifBlank { "files/leech/download" }
            val headers = buildInertiaHeaders(session, component, "download", isPost = true)
            val tasks = ArrayList<MirrorTask>()

            for (i in 0 until attachments.length()) {
                val attachment = attachments.optJSONObject(i) ?: continue
                val attachmentId = attachment.optString("hashId").ifBlank { attachment.optString("id") }
                if (attachmentId.isBlank()) continue
                val body = try {
                    app.post(
                        session.url,
                        headers = headers,
                        json = mapOf("attach" to attachmentId),
                        referer = session.url,
                    ).text
                } catch (e: Exception) {
                    Log.d(name, "MegaMax direct attachment lookup failed ($attachmentId): ${e.message}")
                    continue
                }
                val download = parseInertiaResponse(body)?.optJSONObject("props")?.optJSONObject("download")
                val data = download?.optJSONObject("data")
                val rawUrl = data?.optString("url").orEmpty()
                    .ifBlank { data?.optString("file").orEmpty() }
                    .ifBlank { download?.optString("url").orEmpty() }
                val directUrl = TukTukMediaUtils.resolveMediaUrl(session.url, rawUrl) ?: continue
                val quality = attachment.optString("label")
                    .ifBlank { attachment.optString("name") }
                    .ifBlank { attachment.optString("resolution") }
                tasks.add(
                    MirrorTask(
                        url = directUrl,
                        driver = "MegaMax Direct",
                        qualityLabel = quality,
                        referer = "${session.origin}/",
                        origin = session.origin,
                    ),
                )
            }
            if (tasks.isNotEmpty()) return tasks
        }
        return emptyList()
    }

    private suspend fun fetchIframeMirrors(
        hashId: String,
        origins: List<String>,
        referer: String,
    ): List<MirrorTask> {
        for (base in origins) {
            val session = openInertiaSession("$base/iframe/$hashId", referer) ?: continue
            var tiers = parseStreamTiers(session.page)
            if (tiers == null || tiers.length() == 0) {
                val component = session.component.ifBlank { "files/mirror/video" }
                val response = try {
                    app.get(
                        session.url,
                        headers = buildInertiaHeaders(session, component, "streams"),
                        referer = session.url,
                    ).text
                } catch (e: Exception) {
                    Log.d(name, "MegaMax iframe reload failed: ${session.url} (${e.message})")
                    ""
                }
                tiers = parseStreamTiers(parseInertiaResponse(response))
            }

            val tasks = ArrayList<MirrorTask>()
            for (i in 0 until (tiers?.length() ?: 0)) {
                val tier = tiers?.optJSONObject(i) ?: continue
                val label = tier.optString("label")
                    .ifBlank { tier.optString("resolution") }
                    .ifBlank { tier.optString("quality") }
                val mirrors = tier.optJSONArray("mirrors") ?: tier.optJSONArray("servers") ?: continue
                collectMirrors(mirrors, label, session, tasks)
            }
            if (tasks.isNotEmpty()) return tasks
        }
        return emptyList()
    }

    private suspend fun fetchDownloadMirrors(
        hashId: String,
        origins: List<String>,
        referer: String,
    ): List<MirrorTask> {
        for (base in origins) {
            val session = openInertiaSession("$base/download/$hashId", referer) ?: continue
            val attachments = session.page.optJSONObject("props")?.optJSONArray("attachments") ?: continue
            if (attachments.length() == 0) continue

            val component = session.component.ifBlank { "files/mirror/download" }
            val headers = buildInertiaHeaders(session, component, "download", isPost = true)
            val tasks = ArrayList<MirrorTask>()
            for (i in 0 until attachments.length()) {
                val attachment = attachments.optJSONObject(i) ?: continue
                val attachmentId = attachment.optString("hashId")
                    .ifBlank { attachment.optString("id") }
                if (attachmentId.isBlank()) continue
                val label = attachment.optString("label")
                    .ifBlank { attachment.optString("name") }
                    .ifBlank { attachment.optString("resolution") }

                val body = try {
                    app.post(
                        session.url,
                        headers = headers,
                        json = mapOf("attach" to attachmentId),
                        referer = session.url,
                    ).text
                } catch (e: Exception) {
                    Log.d(name, "MegaMax attachment lookup failed ($attachmentId): ${e.message}")
                    continue
                }
                val response = parseInertiaResponse(body) ?: continue
                val download = response.optJSONObject("props")?.optJSONObject("download")
                val data = download?.optJSONObject("data")
                val directRawUrl = data?.optString("url").orEmpty()
                    .ifBlank { data?.optString("file").orEmpty() }
                    .ifBlank { download?.optString("url").orEmpty() }
                val directUrl = TukTukMediaUtils.resolveMediaUrl(session.url, directRawUrl)
                if (directUrl != null) {
                    tasks.add(
                        MirrorTask(
                            url = directUrl,
                            driver = "MegaMax Direct",
                            qualityLabel = label,
                            referer = "${session.origin}/",
                            origin = session.origin,
                        ),
                    )
                }
                val mirrors = data?.optJSONArray("mirrors")
                    ?: download?.optJSONArray("mirrors")
                    ?: response.optJSONObject("props")?.optJSONArray("mirrors")
                if (mirrors != null) collectMirrors(mirrors, label, session, tasks)
            }
            if (tasks.isNotEmpty()) return tasks
        }
        return emptyList()
    }

    private fun parseStreamTiers(page: JSONObject?): JSONArray? {
        val streams = page?.optJSONObject("props")?.opt("streams")
        return when (streams) {
            is JSONArray -> streams
            is JSONObject -> streams.optJSONArray("data")
                ?: streams.optJSONObject("data")?.optJSONArray("arr")
            is String -> runCatching { JSONObject(streams).optJSONArray("data") }.getOrNull()
            else -> null
        }
    }

    private fun parseInertiaResponse(body: String): JSONObject? {
        if (body.isBlank()) return null
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) return runCatching { JSONObject(trimmed) }.getOrNull()
        return parseInertiaPage(trimmed)
    }

    private fun collectMirrors(
        mirrors: JSONArray,
        qualityLabel: String,
        session: InertiaSession,
        out: MutableList<MirrorTask>,
    ) {
        for (i in 0 until mirrors.length()) {
            val mirror = mirrors.optJSONObject(i) ?: continue
            val driver = mirror.optString("driver")
                .ifBlank { mirror.optString("name") }
                .ifBlank { mirror.optString("symbol") }
                .trim()
            val rawUrl = mirror.optString("link")
                .ifBlank { mirror.optString("url") }
                .ifBlank { mirror.optString("href") }
                .trim()
            val absoluteUrl = TukTukMediaUtils.resolveMediaUrl(session.url, rawUrl) ?: continue
            out.add(
                MirrorTask(
                    url = absoluteUrl,
                    driver = driver,
                    qualityLabel = qualityLabel,
                    referer = session.url,
                    origin = session.origin,
                ),
            )
        }
    }

    private suspend fun resolveMirror(
        task: MirrorTask,
        subtitleCallback: (SubtitleFile) -> Unit,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit,
    ) {
        val quality = TukTukMediaUtils.qualityHint(task.qualityLabel)
        val link = TukTukMediaUtils.resolveMediaUrl(task.referer, task.url) ?: return
        if (TukTukMediaUtils.isMegaMaxUrl(link, task.driver)) return

        val isHls = TukTukMediaUtils.isHls(link)
        val isDash = link.substringBefore('?').endsWith(".mpd", ignoreCase = true)
        val isDirectVideo = Regex("\\.(?:mp4|m4v|webm)(?:$|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(link)
        if (isHls || isDash || isDirectVideo) {
            emitDirect(link, task, quality, emitted, callback)
            return
        }

        var resolvedAny = false
        val extractedLinks = ArrayList<ExtractorLink>()
        val wrappedCallback: (ExtractorLink) -> Unit = { extracted -> extractedLinks.add(extracted) }

        try {
            when {
                task.driver.contains("byse", ignoreCase = true) || link.contains("byseraguci", ignoreCase = true) ->
                    TukTukByseExtractor().getUrl(link, task.referer, subtitleCallback, wrappedCallback)
                task.driver.contains("lulu", ignoreCase = true) || link.contains("luluvdo", ignoreCase = true) ->
                    TukTukLuluExtractor().getUrl(link, task.referer, subtitleCallback, wrappedCallback)
                task.driver.contains("upn", ignoreCase = true) || link.contains("upns.one", ignoreCase = true) ->
                    TukTukUpnExtractor().getUrl(link, task.referer, subtitleCallback, wrappedCallback)
                task.driver.contains("videoland", ignoreCase = true) || link.contains("videoland", ignoreCase = true) ->
                    VideolandExtractor().getUrl(link, task.referer, subtitleCallback, wrappedCallback)
                task.driver.contains("tuktukvip", ignoreCase = true) ||
                    link.contains("lolololu", ignoreCase = true) ->
                    TukTukVipExtractor().getUrl(link, task.referer, subtitleCallback, wrappedCallback)
                else -> loadExtractor(link, task.referer, subtitleCallback, wrappedCallback)
            }
        } catch (e: Exception) {
            Log.w(name, "Extractor dispatch failed for ${task.driver}: $link", e)
        }

        for (extracted in extractedLinks) {
            if (!emitted.add(extracted.url)) continue
            resolvedAny = true
            val finalQuality = extracted.quality.takeIf { it != Qualities.Unknown.value }
                ?: quality.takeIf { it > 0 }
                ?: Qualities.Unknown.value
            val driverName = displayDriver(task.driver, link)
            val qualitySuffix = task.qualityLabel.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name — $driverName$qualitySuffix",
                    url = extracted.url,
                    type = extracted.type,
                ) {
                    this.referer = extracted.referer.ifBlank { task.referer }
                    this.quality = finalQuality
                    this.headers = extracted.headers
                    this.extractorData = extracted.extractorData
                },
            )
        }

        // Some MegaMax mirror pages expose sources in packed HTML rather than through
        // a registered extractor; try the common media URL patterns as a final fallback.
        if (!resolvedAny) {
            val html = try {
                app.get(
                    link,
                    headers = mapOf("User-Agent" to USER_AGENT),
                    referer = task.referer,
                ).text
            } catch (_: Exception) {
                ""
            }
            val unpacked = JsUnpacker(html).unpack() ?: html
            for (mediaUrl in TukTukMediaUtils.mediaUrls(link, unpacked)) {
                emitDirect(mediaUrl, task, quality, emitted, callback)
            }
        }
    }

    private suspend fun emitDirect(
        url: String,
        task: MirrorTask,
        qualityHint: Int,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit,
        forceHls: Boolean = false,
    ) {
        val mediaUrl = TukTukMediaUtils.resolveMediaUrl(task.referer, url) ?: return
        val origin = TukTukMediaUtils.originOf(mediaUrl, task.origin)
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to task.referer,
            "Origin" to origin,
        )
        val isHls = forceHls || TukTukMediaUtils.isHls(mediaUrl)
        if (isHls) {
            val expanded = try {
                M3u8Helper.generateM3u8(
                    name,
                    mediaUrl,
                    task.referer,
                    quality = qualityHint.takeIf { it > 0 },
                    headers = headers,
                )
            } catch (e: Exception) {
                Log.w(name, "Could not expand MegaMax HLS playlist: $mediaUrl", e)
                emptyList()
            }
            for (link in expanded) {
                if (emitted.add(link.url)) {
                    val finalQuality = link.quality.takeIf { it != Qualities.Unknown.value }
                        ?: qualityHint.takeIf { it > 0 }
                        ?: Qualities.Unknown.value
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name — ${displayDriver(task.driver, mediaUrl)} ${qualityLabel(qualityHint)}".trim(),
                            url = link.url,
                            type = link.type,
                        ) {
                            this.referer = link.referer.ifBlank { task.referer }
                            this.quality = finalQuality
                            this.headers = link.headers.ifEmpty { headers }
                            this.extractorData = link.extractorData
                        },
                    )
                }
            }
            if (expanded.isNotEmpty()) return
        }

        if (emitted.add(mediaUrl)) {
            val type = when {
                isHls -> ExtractorLinkType.M3U8
                mediaUrl.substringBefore('?').endsWith(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name — ${displayDriver(task.driver, mediaUrl)} ${qualityLabel(qualityHint)}".trim(),
                    url = mediaUrl,
                    type = type,
                ) {
                    this.referer = task.referer
                    this.quality = qualityHint.takeIf { it > 0 } ?: Qualities.Unknown.value
                    this.headers = headers
                },
            )
        }
    }

    private fun decryptCryptoJsJson(encryptedJson: String): String? = runCatching {
        val obj = JSONObject(encryptedJson)
        val cipherText = Base64.decode(obj.getString("ct"), Base64.DEFAULT)
        val iv = hexToBytes(obj.getString("iv"))
        val salt = hexToBytes(obj.getString("s"))
        evpDecrypt(cipherText, salt, iv)
    }.getOrNull()

    private fun decryptOpenSslAes(payload: String): String? = runCatching {
        val raw = Base64.decode(payload.trim(), Base64.DEFAULT)
        if (raw.size <= 16 || String(raw, 0, 8, Charsets.US_ASCII) != "Salted__") return null
        val salt = raw.copyOfRange(8, 16)
        val cipherText = raw.copyOfRange(16, raw.size)
        evpDecrypt(cipherText, salt, null)
    }.getOrNull()

    private fun evpDecrypt(cipherText: ByteArray, salt: ByteArray, explicitIv: ByteArray?): String {
        require(salt.size == 8)
        val digest = MessageDigest.getInstance("MD5")
        val passphrase = LEECH_AES_PASSPHRASE.toByteArray(Charsets.UTF_8)
        var derived = ByteArray(0)
        var previous = ByteArray(0)
        while (derived.size < 48) {
            digest.reset()
            if (previous.isNotEmpty()) digest.update(previous)
            digest.update(passphrase)
            digest.update(salt)
            previous = digest.digest()
            derived += previous
        }
        val key = derived.copyOfRange(0, 32)
        val iv = explicitIv ?: derived.copyOfRange(32, 48)
        require(iv.size == 16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }

    private fun hexToBytes(value: String): ByteArray {
        val clean = value.trim()
        require(clean.length % 2 == 0)
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun qualityLabel(value: Int): String = when (value) {
        Qualities.P2160.value -> "2160p"
        Qualities.P1440.value -> "1440p"
        Qualities.P1080.value -> "1080p"
        Qualities.P720.value -> "720p"
        Qualities.P480.value -> "480p"
        Qualities.P360.value -> "360p"
        Qualities.P240.value -> "240p"
        else -> ""
    }

    private fun displayDriver(driver: String, url: String): String {
        val value = driver.trim().ifBlank {
            runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
        }
        return value.ifBlank { "Mirror" }.replaceFirstChar { it.uppercase() }
    }

    companion object {
        private const val LEECH_AES_PASSPHRASE = "k7v2m4x9bqwpnj6t"

        fun isMegaMaxUrl(url: String, serverHint: String = ""): Boolean =
            TukTukMediaUtils.isMegaMaxUrl(url, serverHint)

        fun extractHashId(url: String): String? = TukTukMediaUtils.extractMegaMaxId(url)
    }
}
