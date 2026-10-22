package com.shooflive

import java.io.ByteArrayOutputStream
import java.net.URLDecoder

/**
 * Pure parsing/decoding helpers for شوف لايف.
 *
 * This object has **no Android and no CloudStream dependency** on purpose: the
 * exact same code that ships inside the plugin can be compiled and executed on
 * a plain JVM, which allows the site parsing to be regression tested against
 * the live website without an emulator.
 */
object ShoofLiveParser {

    const val BASE = "https://shooflive.watch"
    const val ALBA_HOST = "shooof.online"

    // ------------------------------------------------------------------ models

    data class Card(
        val type: String,
        val url: String,
        val title: String,
        val poster: String?,
        val episode: Int?
    )

    data class Server(val name: String, val href: String)

    // ------------------------------------------------------------------ regexes

    private val ARTICLE_RE = Regex("<article[^>]*>(.*?)</article>", RegexOption.DOT_MATCHES_ALL)
    private val ARTICLE_CLASS_RE = Regex("class=\"([^\"]*)\"")
    private val CARD_LINK_RE = Regex("href=\"(https://shooflive\\.watch/(series|movies|episode)/[^\"#?]+)\"")
    private val TITLE_ATTR_RE = Regex("title=\"([^\"]*)\"")
    private val TITLE_DIV_RE = Regex("<div class=\"title\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
    private val DATA_SRC_RE = Regex("data-src=\"([^\"]+)\"")
    private val DATA_SRC_UNQUOTED_RE = Regex("data-src=([^\\s>]+)")
    private val SRCSET_RE = Regex("srcset=\"([^\"]+)\"")
    private val IMG_SRC_RE = Regex("<img[^>]+src=\"([^\"]+)\"")
    private val EPISODE_BOX_RE = Regex("episodeNum[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
    private val SPAN_NUMBER_RE = Regex("<span[^>]*>(\\d+)</span>")
    private val H1_RE = Regex("<h1[^>]*>(.*?)</h1>", RegexOption.DOT_MATCHES_ALL)
    private val SINGLE_SERIES_RE =
        Regex("<div class=\"singleSeries\"[^>]*>(.*?)</div>\\s*</div>", RegexOption.DOT_MATCHES_ALL)
    private val LD_JSON_RE = Regex(
        "<script[^>]*type=\"application/ld\\+json\"[^>]*>(.*?)</script>",
        RegexOption.DOT_MATCHES_ALL
    )
    private val THUMBNAIL_RE = Regex("\"thumbnailUrl\"\\s*:\\s*\\[?\\s*\"([^\"]+)\"")
    private val LD_IMAGE_RE = Regex("\"image\"\\s*:\\s*\\[?\\s*\"([^\"]+)\"")
    private val LD_EMBED_RE = Regex("\"embedUrl\"\\s*:\\s*\"?([^\",]+)\"?")
    private val IFRAME_RE = Regex("<iframe[^>]*src=\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL)
    private val ALBA_MENU_RE = Regex(
        "<a[^>]*class=\"[^\"]*aplr-link[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
        RegexOption.DOT_MATCHES_ALL
    )
    private val SEARCH_CARD_RE = Regex(
        "<a([^>]*class=\"[^\"]*slv2-card[^\"]*\"[^>]*)>(.*?)</a>",
        RegexOption.DOT_MATCHES_ALL
    )
    private val SEARCH_NAME_RE = Regex("<div class=\"slv2-name\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
    private val SEASON_RE = Regex("الموسم\\s*(\\d+)")
    private val EPISODE_TITLE_RE = Regex("الحلقة\\s*(\\d+)")
    private val LEGACY_ITEM_RE =
        Regex("<li[^>]*data-server=\"([^\"]*)\"[^>]*>(.*?)</li>", RegexOption.DOT_MATCHES_ALL)
    private val PACKER_EVAL_RE = Regex(
        """(?s)\}\s*\('(.*)',\s*(.*?),\s*(\d+),\s*'(.*?)'\.split\('\|'\)"""
    )
    private val PACKER_WORD_RE = Regex("\\b[a-zA-Z0-9_]+\\b")
    private val VOE_PAYLOAD_RE = Regex("\\[\\s*\"([^\"]{40,})\"\\s*]")
    private val VOE_REDIRECT_RE = Regex("window\\.location\\.href\\s*=\\s*'([^']+)';")

    // ------------------------------------------------------------------ text helpers

    fun decodeEntities(input: String): String {
        var out = input
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        out = Regex("&#(\\d+);").replace(out) { m ->
            runCatching { m.groupValues[1].toInt().toChar().toString() }.getOrDefault(m.value)
        }
        out = Regex("&#x([0-9a-fA-F]+);").replace(out) { m ->
            runCatching { m.groupValues[1].toInt(16).toChar().toString() }.getOrDefault(m.value)
        }
        return out
    }

    fun clean(input: String): String =
        decodeEntities(input.replace(Regex("<[^>]*>"), " ")).replace(Regex("\\s+"), " ").trim()

    /**
     * Normalises a URL taken out of the markup.
     *
     * The theme sometimes leaves stray whitespace (spaces, newlines) inside the
     * `src` attribute — e.g. `https://anafast.cyou/embed-xxx.html\n\n`. OkHttp
     * refuses such a URL (illegal characters), so the request would fail with
     * an exception instead of a normal 404. Every whitespace character is
     * therefore removed before the URL is used.
     */
    fun normalizeUrl(input: String): String {
        val cleaned = decodeEntities(input).filterNot { it.isWhitespace() }
        return if (cleaned.startsWith("//")) "https:$cleaned" else cleaned
    }

    fun slugToTitle(url: String): String {
        val slug = url.substringAfterLast("/").trimEnd('/')
        return runCatching { URLDecoder.decode(slug, "UTF-8") }
            .getOrDefault(slug)
            .replace("-", " ")
            .replace("_", " ")
            .trim()
    }

    // ------------------------------------------------------------------ cards

    /** Cards of the home page, the archives and the series pages. */
    fun parseCards(html: String): List<Card> {
        val out = mutableListOf<Card>()
        for (m in ARTICLE_RE.findAll(html)) {
            val block = m.groupValues[1]
            val cls = ARTICLE_CLASS_RE.find(block)?.groupValues?.get(1).orEmpty()
            if (cls.contains("slx-")) continue

            val link = CARD_LINK_RE.find(block) ?: continue
            val url = decodeEntities(link.groupValues[1])
            val type = link.groupValues[2]

            val titleAttr = TITLE_ATTR_RE.find(block)?.groupValues?.get(1)?.let { clean(it) }
            val titleDiv = TITLE_DIV_RE.find(block)?.let { clean(it.groupValues[1]) }
            var title = titleAttr?.takeIf { it.isNotBlank() }
                ?: titleDiv?.takeIf { it.isNotBlank() }
                ?: ""
            if (title.isBlank()) title = slugToTitle(url)

            out += Card(
                type = type,
                url = url,
                title = title,
                poster = extractPoster(block),
                episode = extractEpisodeNumber(block)
            )
        }
        return out
    }

    /** Cards of the "smart search" result page (`a.slv2-card`). */
    fun parseSearchCards(html: String): List<Card> {
        val out = mutableListOf<Card>()
        for (m in SEARCH_CARD_RE.findAll(html)) {
            val attrs = m.groupValues[1]
            val body = m.groupValues[2]
            val link = CARD_LINK_RE.find(attrs) ?: continue
            val url = decodeEntities(link.groupValues[1])
            val type = link.groupValues[2]
            val name = SEARCH_NAME_RE.find(body)?.let { clean(it.groupValues[1]) }
                ?: TITLE_ATTR_RE.find(attrs)?.groupValues?.get(1)?.let { clean(it) }
                ?: slugToTitle(url)
            out += Card(type, url, name, extractPoster(body), null)
        }
        return out
    }

    fun extractPoster(block: String): String? {
        val candidates = mutableListOf<String>()
        DATA_SRC_RE.findAll(block).forEach { candidates += it.groupValues[1] }
        DATA_SRC_UNQUOTED_RE.findAll(block).forEach { candidates += it.groupValues[1].trim('"') }
        SRCSET_RE.findAll(block).forEach { raw ->
            raw.groupValues[1].split(",").forEach { part ->
                candidates += part.trim().substringBefore(" ")
            }
        }
        IMG_SRC_RE.findAll(block).forEach { candidates += it.groupValues[1] }
        return candidates.firstOrNull {
            it.startsWith("http") && !it.contains("data:") &&
                    !it.contains("placeholder") && !it.contains("wp-content/themes")
        }
    }

    fun extractEpisodeNumber(block: String): Int? {
        val box = EPISODE_BOX_RE.find(block)?.groupValues?.get(1) ?: return null
        return SPAN_NUMBER_RE.findAll(box).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
    }

    fun seasonOf(text: String): Int? = SEASON_RE.find(text)?.groupValues?.get(1)?.toIntOrNull()

    fun episodeOf(text: String): Int? =
        EPISODE_TITLE_RE.find(text)?.groupValues?.get(1)?.toIntOrNull()

    // ------------------------------------------------------------------ page helpers

    fun firstH1(html: String): String =
        H1_RE.find(html)?.let { clean(it.groupValues[1]) }.orEmpty()

    fun seriesHeader(html: String): String =
        SINGLE_SERIES_RE.find(html)?.groupValues?.get(1).orEmpty()

    fun ldJson(html: String): String? =
        LD_JSON_RE.find(html)?.groupValues?.get(1)?.trim()

    fun ldThumbnail(html: String): String? {
        val raw = ldJson(html) ?: return null
        val thumb = THUMBNAIL_RE.find(raw)?.groupValues?.get(1)
            ?: LD_IMAGE_RE.find(raw)?.groupValues?.get(1)
        return thumb
            ?.replace("\\/", "/")
            ?.takeIf { it.startsWith("http") }
    }

    fun firstIframe(html: String): String? =
        IFRAME_RE.find(html)?.groupValues?.get(1)?.let { normalizeUrl(it) }
            ?.takeIf { it.startsWith("http") }

    /** Every iframe of the page (in document order). */
    fun allIframes(html: String): List<String> =
        IFRAME_RE.findAll(html)
            .map { normalizeUrl(it.groupValues[1]) }
            .filter { it.startsWith("http") }
            .toList()

    /** The `embedUrl` of the JSON-LD block (`/embed/<id>/` of the site). */
    fun ldEmbedUrl(html: String): String? =
        ldJson(html)?.let { LD_EMBED_RE.find(it)?.groupValues?.get(1) }
            ?.replace("\\/", "/")
            ?.takeIf { it.startsWith("http") }

    /** The AlbaPlayer iframe of an item page. */
    fun albaBase(html: String): String? {
        for (m in IFRAME_RE.findAll(html)) {
            val src = normalizeUrl(m.groupValues[1])
            if (src.contains(ALBA_HOST) && src.contains("/albaplayer/")) return src
        }
        return null
    }

    /** The server menu of an AlbaPlayer page. */
    fun parseAlbaMenu(html: String): List<Server> =
        ALBA_MENU_RE.findAll(html)
            .map {
                Server(
                    clean(it.groupValues[2]).ifBlank { "سيرفر" },
                    normalizeUrl(it.groupValues[1])
                )
            }
            .toList()

    fun voeRedirect(html: String): String? = VOE_REDIRECT_RE.find(html)?.groupValues?.get(1)

    fun voePayload(html: String): String? = VOE_PAYLOAD_RE.find(html)?.groupValues?.get(1)

    /**
     * The legacy server list of the theme (`serversList li[data-server]`).
     * Kept for compatibility in case the template renders its own list again.
     */
    fun legacyServers(html: String): List<Server> {
        val out = mutableListOf<Server>()
        for (m in LEGACY_ITEM_RE.findAll(html)) {
            val serverId = m.groupValues[1]
            val block = m.groupValues[2]
            val serverName = Regex("data-name=\"([^\"]*)\"").find(block)?.groupValues?.get(1).orEmpty()
            val url = when (serverName) {
                "youtube" -> "https://www.youtube.com/watch?v=$serverId"
                "express" -> serverId
                "dailymotion" -> Regex("src=\"([^\"]+)\"").find(block)?.groupValues?.get(1).orEmpty()
                "Multi HD" -> "https://w.shadwo.pro/albaplayer/-$serverId/"
                "estream" -> "https://arabveturk.com/embed-$serverId.html"
                "box" -> "https://youdboox.com/embed-$serverId.html"
                "now" -> "https://extreamnow.org/embed-$serverId.html"
                "ok" -> "https://ok.ru/videoembed/$serverId"
                "Red HD" -> "https://redload.co/e/$serverId"
                "Pro HD" -> "https://embedo.co/e/$serverId"
                else -> "https://shooof.online/albaplayer/$serverId"
            }
            if (url.startsWith("http")) out += Server(serverName.ifBlank { "سيرفر" }, url)
        }
        return out
    }

    // ------------------------------------------------------------------ P.A.C.K.E.R.

    /**
     * Unpacks the P.A.C.K.E.R. payload used by vidspeed/uqload: every base-N
     * word of the script is replaced by the matching symbol table entry.
     */
    fun unPacker(html: String?): String? {
        if (html.isNullOrBlank()) return null
        val match = PACKER_EVAL_RE.find(html) ?: return null
        val payload = match.groupValues[1].replace("\\'", "'")
        val radix = match.groupValues[2].toIntOrNull() ?: return null
        val count = match.groupValues[3].toIntOrNull() ?: return null
        val symtab = match.groupValues[4].split("|")
        if (symtab.size != count) return null

        val out = StringBuilder(payload)
        var offset = 0
        for (word in PACKER_WORD_RE.findAll(payload)) {
            val x = runCatching { word.value.toInt(radix) }.getOrNull() ?: continue
            val value = symtab.getOrNull(x)?.takeIf { it.isNotEmpty() } ?: continue
            val start = word.range.first + offset
            val end = word.range.last + 1 + offset
            if (start < 0 || end > out.length) continue
            out.replace(start, end, value)
            offset += value.length - word.value.length
        }
        return out.toString()
    }

    /** Every `file:"…"` (+ optional `label:"…"`) pair inside a JWPlayer block. */
    fun sourcePairs(script: String): List<Pair<String, String?>> {
        val regex = Regex(
            """file\s*:\s*["'](https?://[^"']+)["'](?:\s*,\s*label\s*:\s*["']([^"']*)["'])?"""
        )
        return regex.findAll(script).map { it.groupValues[1] to it.groupValues.getOrNull(2) }
            .filter { it.first.isNotBlank() }
            .toList()
    }

    // ------------------------------------------------------------------ VOE

    fun rot13(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            sb.append(
                when (c) {
                    in 'a'..'z' -> ('a' + ((c - 'a' + 13) % 26))
                    in 'A'..'Z' -> ('A' + ((c - 'A' + 13) % 26))
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    fun base64DecodeBytes(input: String): ByteArray? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val clean = input.trim().filter { it != '=' && it != '\n' && it != '\r' && it != ' ' }
        if (clean.isEmpty()) return null
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = alphabet.indexOf(c)
            if (v < 0) continue
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    fun base64Decode(input: String): String? =
        base64DecodeBytes(input)?.let { String(it, Charsets.ISO_8859_1) }

    /**
     * VOE hides the stream inside `<script type="application/json">["…"]`.
     * Chain: rot13 → markers become `_` → drop `_` → base64 → shift chars by
     * -3 → reverse → base64 → JSON.
     */
    fun decryptVoe(encoded: String): String? {
        return try {
            var s = rot13(encoded)
            for (p in listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")) s = s.replace(p, "_")
            s = s.replace("_", "")
            val stage1 = base64DecodeBytes(s) ?: return null
            val shifted = String(stage1, Charsets.ISO_8859_1)
                .map { c -> (c.code - 3).toChar() }
                .joinToString("")
                .reversed()
            val stage2 = base64DecodeBytes(shifted) ?: return null
            String(stage2, Charsets.UTF_8)
        } catch (e: Throwable) {
            null
        }
    }
}
