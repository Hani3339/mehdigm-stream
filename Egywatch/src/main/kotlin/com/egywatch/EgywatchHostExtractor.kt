package com.egywatch

import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

object EgywatchHostExtractor {
    private const val TAG = "EgywatchHost"
    private const val SCRAPER_DEFAULT = "https://mawdhou3.com/"
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

    private val DEAD_HOSTS = listOf("ostvapp.cam")

    /**
     * Scraper endpoints that only answer to POST (embed HTML in `content`, link in `url`).
     * Everything else is queried with GET, appending the link to the urlsite's own query key
     * (`?api=`, `?url=`, `?apiurl=`); endpoints without a query default to `?api=`.
     * Verified live against mawdhou3.com.
     */
    private val POST_ENDPOINTS = setOf(
        "upzur.php", "vidaara.php", "extractm3u8.php", "earnvids.php",
        "dramaramadanPost.php", "okprimePost.php", "faselpost.php",
        "recuperepost.php"
    )

    private data class HostRule(
        val hostIds: List<String>,
        val domains: List<String>,
        val urlsite: String,
        val isPost: Boolean
    )

    private fun buildRules(): List<HostRule> {
        val rules = mutableListOf<HostRule>()
        fun add(
            hostIds: List<String>,
            domains: List<String>,
            urlsite: String
        ) {
            if (urlsite.isBlank()) return
            val us = urlsite.trim()
            // Method is decided by the endpoint filename: these scrapers only answer POST
            // regardless of an `?api=` suffix; everything else is a GET.
            val file = us.substringAfterLast('/').substringBefore('?')
            val isPost = file in POST_ENDPOINTS
            rules += HostRule(hostIds, domains, us, isPost = isPost)
        }
        // Generated from hosts_config.json (string urlsite)
        add(listOf("uqload"), listOf("uqload.com"), "https://mawdhou3.com/scrapefinal/uqload.php?api=")
        add(listOf("71stream"), listOf("71stream.com"), "https://mawdhou3.com/scrapefinal/71stream.php?api=")
        add(listOf("Upzur"), listOf("Upzur.com"), "https://mawdhou3.com/scrapefinal/upzur.php")
        add(listOf("Seriemp4"), listOf("Seriemp4.com"), "https://mawdhou3.com/scrapefinal/dramaramadanPost.php")
        add(listOf("ukrcdn"), listOf("ukrcdn.com"), "https://mawdhou3.com/scrapefinal/ukrcdn.php?api=")
        add(listOf("okprime"), listOf("okprime.com"), "https://mawdhou3.com/scrapefinal/okprimePost.php")
        add(listOf("vidsp"), listOf("vidsp.com"), "https://www.hrrejhp.com/scrapefinal/vidsp/post.php?api=")
        add(listOf("vidtubeJdid"), listOf("vidtubejdid.com"), "https://mawdhou3.com/scrapefinal/vidtubepost.php")
        add(listOf("faselpost"), listOf("faselpost.com"), "https://mawdhou3.com/scrapefinal/faselpost.php")
        add(listOf("ult4vid"), listOf("ult4vid.com"), "https://mawdhou3.com/scrapefinal/ult4vid.php?api=")
        add(listOf("hrrejhp"), listOf("hrrejhp.com"), "")
        add(listOf("stream.developer"), listOf("stream.developer.com"), "https://mawdhou3.com/scrapefinal/stream.developer.php?api=")
        add(listOf("google"), listOf("google.com"), "https://mawdhou3.com/aminegoogle/extract_qualite.php?url=")
        add(listOf("videasjdid"), listOf("videasjdid.com"), "https://mawdhou3.com/scrapefinal/vedasjdid.php?api=")
        add(listOf("fdewsdcPro"), listOf("fdewsdc.com"), "https://mawdhou3.com/test.php?api=")
        add(listOf("seriesmp4"), listOf("seriesmp4.com"), "https://mawdhou3.com/scrapefinal/dramaramadan.php?api=")
        add(listOf("fastream"), listOf("fastream.com"), "https://mawdhou3.com/scrapefinal/fastream.php?api=")
        add(listOf("vidaraa"), listOf("vidaraa.com"), "https://mawdhou3.com/scrapefinal/vidaara.php")
        add(listOf("stmruby"), listOf("stmruby.com"), "https://mawdhou3.com/scrapefinal/earnvids.php?api=")
        add(listOf("luluvdo"), listOf("luluvdo.com"), "https://mawdhou3.com/scrapefinal/extractm3u8.php")
        add(listOf("vidspeed"), listOf("vidspeeds.com"), "https://mawdhou3.com/scrapefinal/earnvids.php?api=")
        add(listOf("vidoba"), listOf("vidoba.com"), "https://mawdhou3.com/scrapefinal/turki.php")
        add(listOf("vidsrc"), listOf("vidsrc.com"), "")
        add(listOf("channel"), listOf("channel"), "https://mawdhou3.com/scrapefinal/channel.php?url=")
        add(listOf("anafast"), listOf("anafast.com"), "https://mawdhou3.com/test.php?api=")
        add(listOf("videoland"), listOf("videoland.com"), "")
        add(listOf("lulustream"), listOf("lulustream.com"), "https://mawdhou3.com/scrapefinal/extractm3u8.php")
        add(listOf("asiatvplayer"), listOf("asiatvplayer.com"), "https://mawdhou3.com/test.php?api=")
        add(listOf("liiivideo"), listOf("liiivideo.com"), "https://mawdhou3.com/scrapefinal/scriptalvid.php?api=")
        add(listOf("egybestvid"), listOf("egybestvid.com"), "https://mawdhou3.com/test.php?api=")
        add(listOf("faselapikey"), listOf("faselapikey.com"), "https://mawdhou3.com/scripttestfasel.php?api=")
        add(listOf("vidhidepre"), listOf("vidhidepre.com"), "https://mawdhou3.com/test.php?api=")
        add(listOf("vudeo"), listOf("vudeo.com"), "https://mawdhou3.com/test.php?api=")
        add(listOf("aparat"), listOf("aparat.com"), "https://mawdhou3.com/scriptapart.php?url=")
        add(listOf("Dnscheckr"), listOf("Dnschecktxt.com"), "https://mawdhou3.com/fasel/geturl.php?api=")
        add(listOf("cartoony"), listOf("cartoony.com"), "https://mawdhou3.com/decrypt_cartoony.php?apiurl=")
        add(listOf("updown"), listOf("updown.com"), "https://mawdhou3.com/scrapefinal/updown.php?api=")
        add(listOf("earnvids"), listOf("earnvids.com"), "https://mawdhou3.com/scrapefinal/earnvids.php?api=")
        add(listOf("videas"), listOf("videas.com"), "https://mawdhou3.com/scrapefinal/vedas.php?api=")
        add(listOf("turkiiiijdid"), listOf("turkiiiijdid.com"), "https://mawdhou3.com/scrapefinal/turki.php?api=")
        add(listOf("hdup20"), listOf("hdup20.com"), "https://mawdhou3.com/scrapefinal/hdup20.php?api=")
        add(listOf("vidnest"), listOf("vidnest.com"), "https://mawdhou3.com/scrapefinal/vidnest.php?api=")
        add(listOf("turbovidhls"), listOf("turbovidhls.com"), "https://mawdhou3.com/scrapefinal/turbovidhls.php?api=")
        add(listOf("arabseeeed.com"), listOf("arabseed"), "https://mawdhou3.com/test.php?api=")
        // Hosts seen live in captured Egywatch traffic but absent from hosts/config domain lists.
        add(listOf("minochinos"), listOf("minochinos.com"), "https://mawdhou3.com/scrapefinal/earnvids.php")
        add(listOf("callistanise"), listOf("callistanise.com"), "https://mawdhou3.com/scrapefinal/earnvids.php")
        add(listOf("hanerix"), listOf("hanerix.com"), "https://mawdhou3.com/scrapefinal/earnvids.php")
        add(listOf("morencius"), listOf("morencius.com"), "https://mawdhou3.com/scrapefinal/earnvids.php")
        add(listOf("streamwish"), listOf("streamwish.fun", "streamwish.com"), "https://mawdhou3.com/scrapefinal/earnvids.php")
        add(listOf("forafile"), listOf("forafile.com"), "https://mawdhou3.com/recuperepost.php")
        add(listOf("fasel-hd"), listOf("fasel-hd.co"), "https://mawdhou3.com/scripttestfasel.php?api=")
        add(listOf("mp4plus"), listOf("mp4plus.cyou"), "https://mawdhou3.com/scrapefinal/earnvids.php")
        // Remaining config hosts (GET via turki.php / post.php) and their live aliases.
        add(listOf("vidtube"), listOf("vidtube.one", "vidtube.com"), "https://mawdhou3.com/scrapefinal/vidtubepost.php")
        add(listOf("okhd"), listOf("okhd.site", "okhd.com"), "https://mawdhou3.com/scrapefinal/turki.php")
        add(listOf("film77"), listOf("film77.xyz", "film77.com"), "https://mawdhou3.com/scrapefinal/turki.php")
        add(listOf("laroza"), listOf("laroza.com"), "https://mawdhou3.com/scrapefinal/turki.php")
        add(listOf("plusfas"), listOf("plusfas.com"), "https://autumn-dust-1a31.flechlivraison.workers.dev/?url=")
        add(listOf("plusserie"), listOf("plusserie.com"), "https://proud-river-0a64.flechlivraison.workers.dev/?url=")
        return rules
    }

    private val RULES by lazy { buildRules() }

    /** Registrable label, e.g. `mp5.okhd.site` / `okhd.com` -> `okhd`. */
    private fun baseName(value: String): String {
        val h = value.substringBefore(':').lowercase()
        val parts = h.split('.').filter { it.isNotBlank() }
        if (parts.isEmpty()) return h
        // Strip the public suffix-ish tail, then drop generic prefixes.
        val core = if (parts.size >= 2) parts[parts.size - 2] else parts[0]
        return core.removePrefix("www")
    }

    private fun matchRule(host: String): HostRule? {
        val hb = baseName(host)
        return RULES.firstOrNull { r ->
            r.domains.any { d -> baseName(d) == hb || host.contains(d.lowercase()) } ||
                r.hostIds.any { id -> baseName(id) == hb || host.contains(id.lowercase()) }
        }
    }

    /**
     * Append the embed link to the urlsite's own query key. `?api=`/`?url=`/`?apiurl=` are honoured;
     * a bare endpoint defaults to `?api=`.
     */
    private fun buildGetUrl(us: String, link: String): String {
        val encoded = URLEncoder.encode(link, "UTF-8")
        val q = us.indexOf('?')
        return if (q >= 0) {
            val base = us.substring(0, q)
            val key = us.substring(q + 1).substringBefore('=').ifBlank { "api" }
            "$base?$key=$encoded"
        } else {
            "$us?api=$encoded"
        }
    }

    suspend fun extract(
        link: String,
        server: String,
        referer: String?,
        userAgent: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val host = hostOf(link)
        if (DEAD_HOSTS.any { host.contains(it) }) return false
        val fetchHeaders = mapOf("User-Agent" to (userAgent ?: BROWSER_UA))

        val rule = matchRule(host)
        if (rule != null && rule.urlsite.isNotBlank()) {
            val us = rule.urlsite
            val scraperBase = runCatching {
                val uri = URI(us)
                "${uri.scheme}://${uri.host}${if (uri.port == -1) "" else ":" + uri.port}/"
            }.getOrNull() ?: SCRAPER_DEFAULT

            if (!rule.isPost) {
                val body = runCatching {
                    app.get(buildGetUrl(us, link), headers = fetchHeaders, referer = scraperBase).text
                }.getOrNull()
                if (emitFrom(body, server, referer, callback)) return true
                return false
            }
            // POST
            val full = if (us.startsWith("http")) us else SCRAPER_DEFAULT + us.removePrefix(SCRAPER_DEFAULT)
            val response = runCatching {
                app.get(link, headers = fetchHeaders, referer = referer, allowRedirects = true)
            }.getOrNull() ?: return false
            if (response.code !in 200..299) {
                Log.i(TAG, "embed $link -> HTTP ${response.code}, skipped")
                return false
            }
            val body = runCatching {
                app.post(
                    full,
                    data = mapOf("content" to response.text, "url" to link),
                    referer = scraperBase
                ).text
            }.getOrNull()
            return emitFrom(body, server, referer, callback)
        }

        // fallback
        val fallbackGet = mapOf(
            "uqload" to "scrapefinal/uqload.php",
            "fasel-hd" to "scripttestfasel.php",
            "vidoba" to "scrapefinal/turki.php",
            "film77" to "scrapefinal/turki.php",
            "okhd" to "scrapefinal/turki.php"
        )
        fallbackGet.entries.firstOrNull { host.contains(it.key) }?.let { (_, endpoint) ->
            val api = SCRAPER_DEFAULT + endpoint + "?api=" + URLEncoder.encode(link, "UTF-8")
            val body = runCatching { app.get(api, headers = fetchHeaders, referer = SCRAPER_DEFAULT).text }.getOrNull()
            if (emitFrom(body, server, referer, callback)) return true
        }
        val fallbackPost = mapOf(
            "minochinos" to "scrapefinal/earnvids.php",
            "callistanise" to "scrapefinal/earnvids.php",
            "hanerix" to "scrapefinal/earnvids.php",
            "morencius" to "scrapefinal/earnvids.php",
            "vidspeed" to "scrapefinal/earnvids.php",
            "stmruby" to "scrapefinal/earnvids.php",
            "streamwish" to "scrapefinal/earnvids.php",
            "forafile" to "recuperepost.php",
            "upzur" to "scrapefinal/upzur.php",
            "vidaraa" to "scrapefinal/vidaara.php",
            "luluvdo" to "scrapefinal/extractm3u8.php",
            "seriesmp4" to "scrapefinal/dramaramadanPost.php"
        )
        val fep = fallbackPost.entries.firstOrNull { host.contains(it.key) }?.value ?: "scrapefinal/earnvids.php"
        val fresp = runCatching { app.get(link, headers = fetchHeaders, referer = referer, allowRedirects = true) }.getOrNull() ?: return false
        if (fresp.code !in 200..299) return false
        val fbody = runCatching { app.post(SCRAPER_DEFAULT + fep, data = mapOf("content" to fresp.text, "url" to link), referer = SCRAPER_DEFAULT).text }.getOrNull()
        return emitFrom(fbody, server, referer, callback)
    }

    private suspend fun emitFrom(
        body: String?,
        server: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (body.isNullOrBlank()) return false
        val links = parseLinks(body)
        if (links.isEmpty()) return false
        val seen = HashSet<String>()
        var found = false
        for ((url, height) in links) {
            val key = if (url.contains(".m3u8")) url.substringBefore('?') else url
            if (!seen.add(key)) continue
            callback(
                newExtractorLink(
                    server,
                    server,
                    url,
                    if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer ?: SCRAPER_DEFAULT
                    if (height != null) this.quality = height
                }
            )
            found = true
        }
        return found
    }

    private fun parseLinks(body: String): List<Pair<String, Int?>> {
        val trimmed = body.trim()
        val result = mutableListOf<Pair<String, Int?>>()
        if (trimmed.startsWith("{")) {
            val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
            if (json.optString("status") != "success" && !json.has("filtered_content") && !json.has("sources") && !json.has("file")) {
                // still try
            }
            val content = json.optJSONArray("filtered_content") ?: json.optJSONArray("sources") ?: json.optJSONArray("links")
            val quality = json.optJSONArray("Quality") ?: json.optJSONArray("qualities")
            if (content != null) {
                for (i in 0 until content.length()) {
                    val item = content.opt(i)
                    val url = when {
                        item is String -> item
                        item is JSONObject -> item.optString("file").ifEmpty { item.optString("url") }
                        else -> null
                    }?.takeIf { it.startsWith("http") } ?: continue
                    val height = when {
                        quality?.opt(i) is String -> quality.optString(i)
                        item is JSONObject -> item.optString("label").ifEmpty { item.optString("quality") }
                        else -> null
                    }?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
                    result += url to height
                }
                if (result.isNotEmpty()) return result.distinctBy { it.first }
            }
            val file = json.optString("file", json.optString("url"))
            if (file.startsWith("http")) return listOf(file to null)
            return emptyList()
        }
        Regex("""file\s*:\s*"(https?://[^"]+)""", RegexOption.DOT_MATCHES_ALL).findAll(trimmed).forEach { m ->
            result += m.groupValues[1] to null
        }
        if (result.isNotEmpty()) return result.distinctBy { it.first }
        Regex("""sources.*?(https?://[^"']+)""", RegexOption.DOT_MATCHES_ALL).findAll(trimmed).forEach { m ->
            result += m.groupValues[1] to null
        }
        return result.distinctBy { it.first }
    }

    private fun hostOf(url: String): String =
        runCatching { URI(url).host?.lowercase() }.getOrNull() ?: url.lowercase()
}
