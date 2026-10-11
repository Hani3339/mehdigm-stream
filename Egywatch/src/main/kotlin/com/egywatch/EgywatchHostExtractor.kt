package com.egywatch

import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/**
 * Resolves the third-party embeds the Egywatch app can play.
 *
 * The app does not ship per-host extractors for these hosts; instead it fetches the embed page
 * and forwards the HTML to its own scraper at `https://mawdhou3.com`, which returns the finished
 * playable links. The mapping below was derived from captured traffic (host -> scraper route):
 *
 *   minochinos / hanerix / morencius / vidspeed / stmruby / streamwish -> scrapefinal/earnvids.php
 *   forafile                                                          -> recuperepost.php
 *   upzur                                                             -> scrapefinal/upzur.php
 *   vidaraa                                                           -> scrapefinal/vidaara.php
 *   luluvdo                                                           -> scrapefinal/extractm3u8.php
 *   vidtube.one                                                       -> scrapefinal/vidtubepost.php
 *   seriesmp4                                                         -> scrapefinal/dramaramadanPost.php
 *   uqload                                                            -> scrapefinal/uqload.php?api=<url>   (GET)
 *   fasel-hd                                                          -> scripttestfasel.php?api=<url>      (GET)
 *   vidoba / film77 / okhd                                            -> scrapefinal/turki.php?api=<url>    (GET)
 */
object EgywatchHostExtractor {
    private const val TAG = "EgywatchHost"
    private const val SCRAPER = "https://mawdhou3.com/"
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

    /** POST form (`content` = embed page HTML, `url` = embed link). */
    private val POST_ENDPOINTS = mapOf(
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
        "vidtube" to "scrapefinal/vidtubepost.php",
        "seriesmp4" to "scrapefinal/dramaramadanPost.php"
    )

    /** GET scraper (`?api` = embed link). */
    private val GET_ENDPOINTS = mapOf(
        "uqload" to "scrapefinal/uqload.php",
        "fasel-hd" to "scripttestfasel.php",
        "vidoba" to "scrapefinal/turki.php",
        "film77" to "scrapefinal/turki.php",
        "okhd" to "scrapefinal/turki.php"
    )

    suspend fun extract(
        link: String,
        server: String,
        referer: String?,
        userAgent: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val host = hostOf(link)
        val fetchHeaders = mapOf("User-Agent" to (userAgent ?: BROWSER_UA))

        // GET-style scrapers take the embed URL directly.
        GET_ENDPOINTS.entries.firstOrNull { host.contains(it.key) }?.let { (_, endpoint) ->
            val api = SCRAPER + endpoint + "?api=" + URLEncoder.encode(link, "UTF-8")
            val body = runCatching {
                app.get(api, headers = fetchHeaders, referer = SCRAPER).text
            }.getOrNull()
            if (emitFrom(body, server, referer, callback)) return true
        }

        // POST-style scrapers expect the fetched embed page HTML.
        val endpoint = POST_ENDPOINTS.entries.firstOrNull { host.contains(it.key) }?.value
            ?: "scrapefinal/earnvids.php"
        val html = runCatching {
            app.get(link, headers = fetchHeaders, referer = referer, allowRedirects = true).text
        }.getOrNull() ?: return false

        val body = runCatching {
            app.post(
                SCRAPER + endpoint,
                data = mapOf("content" to html, "url" to link),
                referer = SCRAPER
            ).text
        }.getOrNull()
        return emitFrom(body, server, referer, callback)
    }

    private suspend fun emitFrom(
        body: String?,
        server: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (body.isNullOrBlank()) return false
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return false
        if (json.optString("status") != "success") return false
        val content = json.optJSONArray("filtered_content") ?: return false
        val quality = json.optJSONArray("Quality")
        var found = false
        for (i in 0 until content.length()) {
            val url = content.optString(i).takeIf { it.startsWith("http") } ?: continue
            val height = quality?.optString(i)
                ?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
            callback(
                newExtractorLink(
                    server,
                    server,
                    url,
                    if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer ?: SCRAPER
                    if (height != null) this.quality = height
                }
            )
            found = true
        }
        if (!found) Log.i(TAG, "scraper returned no links for $server")
        return found
    }

    private fun hostOf(url: String): String =
        runCatching { URI(url).host?.lowercase() }.getOrNull() ?: url.lowercase()
}
