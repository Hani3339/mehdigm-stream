package com.egydead

import android.util.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * EgyDead (إيجي ديد) provider.
 *
 * WordPress site that renders movies at the root slug and series/seasons/episodes
 * under `/serie/`, `/season/`, `/episode/`. Detail pages hide the players behind a
 * `POST View=1` form. The single "MegaMax" server is a rotating file-host domain
 * (`https://<random>.top/iframe/<hash>`) whose real sources are exposed through an
 * Inertia partial reload (`X-Inertia-Partial-Data: streams`). Those sources are plain
 * embeds of well-known hosts (uqload, vidmoly, streamwish, mixdrop, doodstream,
 * vidara, byse, vidhide, voe, streamruby) and are resolved by CloudStream's
 * [loadExtractor] / our [EgyDeadStreamRuby].
 */
class EgyDead : MainAPI() {
    companion object {
        private const val TAG = "EgyDead"
        private const val IFRAME_HOST = "files/mirror/video"

        /** Hosts handled by CloudStream built-ins (or our own extractor). */
        private val EXTRACTOR_HOSTS = listOf(
            "uqload", "vidmoly", "streamwish", "streamhg", "mixdrop", "dood",
            "vidara", "byse", "vidhide", "earnvids", "voe", "streamruby",
            "stmruby", "ok.ru", "voe.sx"
        )

        private val SEASON_WORDS = listOf(
            "الاول", "الثاني", "الثالث", "الرابع", "الخامس", "السادس",
            "السابع", "الثامن", "التاسع", "العاشر", "الحادي عشر", "الثاني عشر"
        )
    }

    override var name = "EgyDead"
    override var mainUrl = "https://tv10.egydead.live"
    override var lang = "ar"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon, TvType.AnimeMovie
    )

    override val mainPage = mainPageOf(
        "/page/movies/" to "أحدث الأفلام",
        "/episode/" to "أحدث الحلقات",
        "/season/" to "أحدث المواسم",
        "/category/english-movies/" to "أفلام أجنبي",
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%b9%d8%b1%d8%a8%d9%8a/" to "أفلام عربي",
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%aa%d8%b1%d9%83%d9%8a%d8%a9/" to "أفلام تركية",
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d9%83%d8%b1%d8%aa%d9%88%d9%86/" to "أفلام كرتون",
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d9%86%d9%85%d9%8a/" to "أفلام انمي",
        "/series-category/english-series/" to "مسلسلات أجنبي",
        "/series-category/arabic-series/" to "مسلسلات عربي",
        "/series-category/turkish-series/" to "مسلسلات تركية",
        "/series-category/asian-series/" to "مسلسلات اسيوية",
        "/series-category/cartoon-series/" to "مسلسلات كرتون",
        "/series-category/anime-series/" to "مسلسلات انمي",
        "/series-category/anime-series-dubbed/" to "انمي مدبلج",
        "/series-category/anime-movies/" to "أفلام انمي"
    )

    // ------------------------------------------------------------------ main

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}?page=$page"
        val doc = try {
            app.get(url).document
        } catch (e: Throwable) {
            Log.i(TAG, "getMainPage failed $url -> $e")
            return newHomePageResponse(request.name, emptyList())
        }
        val items = parseCards(doc)
        val hasNext = doc.selectFirst(".pagination .next, .page-numbers .next, a.next") != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
        val doc = try {
            app.get(url).document
        } catch (e: Throwable) {
            Log.i(TAG, "search failed -> $e")
            return emptyList()
        }
        return parseCards(doc)
    }

    private fun parseCards(doc: Document): List<SearchResponse> =
        doc.select("ul.posts-list li.movieItem, div.EpsList li").mapNotNull { parseCard(it) }

    private fun parseCard(el: Element): SearchResponse? {
        val a = el.selectFirst("a[href]") ?: return null
        val href = a.attr("href").trim().takeIf { it.isNotBlank() } ?: return null
        val title = el.selectFirst("h1.BottomTitle, .BottomTitle")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: a.attr("title").trim().takeIf { it.isNotBlank() }
            ?: a.text().trim()
        val poster = el.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
        return newMovieSearchResponse(title, href, typeForUrl(href)) {
            this.posterUrl = poster
        }
    }

    private fun typeForUrl(url: String): TvType =
        if (url.contains("/serie/") || url.contains("/season/") || url.contains("/episode/")) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): com.lagradost.cloudstream3.LoadResponse {
        val doc = try {
            app.get(url).document
        } catch (e: Throwable) {
            Log.i(TAG, "load failed $url -> $e")
            return newMovieLoadResponse(url, url, TvType.Movie, "") { this.plot = "تعذّر تحميل الصفحة." }
        }
        val title = titleOf(doc)
        val poster = posterOf(doc)
        val plot = plotOf(doc)
        val year = Regex("(19|20)\\d{2}").find(title)?.value?.toIntOrNull()
        val tags = doc.select(".breadcrumbs-single a").map { it.text().trim() }
            .filter { it.isNotBlank() && it != "الرئيسيه" }

        return when {
            url.contains("/serie/") -> {
                val episodes = mutableListOf<Episode>()
                val seasons = doc.select("div.seasons-list li.movieItem a[href], ul.seasons-list li.movieItem a[href]")
                seasons.forEachIndexed { index, a ->
                    val seasonUrl = a.attr("href").trim().takeIf { it.isNotBlank() } ?: return@forEachIndexed
                    val seasonName = a.attr("title").ifBlank { a.text() }.trim()
                    val seasonNumber = arabicSeason(seasonName) ?: (index + 1)
                    try {
                        val sdoc = app.get(seasonUrl).document
                        episodes += parseEpisodeLinks(sdoc, seasonNumber, poster)
                    } catch (e: Throwable) {
                        Log.i(TAG, "season failed $seasonUrl -> $e")
                    }
                }
                episodes.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                }
            }

            url.contains("/season/") -> {
                val seasonNumber = arabicSeason(title) ?: 1
                val episodes = parseEpisodeLinks(doc, seasonNumber, poster)
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                }
            }

            url.contains("/episode/") -> {
                val episode = newEpisode(url) {
                    this.name = title
                    this.episode = numAfterHalka(title)
                    this.posterUrl = poster
                    this.description = plot
                }
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(episode)) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                }
            }

            else -> newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    private fun parseEpisodeLinks(doc: Document, season: Int, poster: String?): List<Episode> =
        doc.select("div.EpsList li a[href], ul.EpsList li a[href]").mapNotNull { a ->
            val epUrl = a.attr("href").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val epName = a.attr("title").ifBlank { a.text() }.trim()
            newEpisode(epUrl) {
                this.name = epName
                this.season = season
                this.episode = numAfterHalka(epName)
                this.posterUrl = poster
            }
        }

    private fun titleOf(doc: Document): String {
        doc.selectFirst(".singleTitle em, .infoBox .singleTitle em")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }?.let { return it }
        return doc.title().substringBefore("|").trim()
    }

    private fun posterOf(doc: Document): String? {
        doc.selectFirst(".singleCover")?.attr("style")
            ?.let { Regex("""url\((['"]?)([^'")]+)\1\)""").find(it)?.groupValues?.get(2) }
            ?.takeIf { it.isNotBlank() }?.let { return it }
        return doc.selectFirst(".single-thumbnail img, meta[property=og:image]")
            ?.let { if (it.tagName() == "meta") it.attr("content") else it.attr("src") }
            ?.takeIf { it.isNotBlank() }
    }

    private fun plotOf(doc: Document): String? =
        doc.selectFirst(".extra-content p")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst(".singleStory")?.text()?.trim()?.takeIf { it.isNotBlank() }

    private fun numAfterHalka(text: String): Int? {
        Regex("(?:حلقه|الحلقة|الحلقه)\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?.let { return it }
        return Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun arabicSeason(text: String): Int? {
        Regex("الموسم\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        SEASON_WORDS.forEachIndexed { i, w -> if (text.contains(w)) return i + 1 }
        Regex("(?i)S(\\d{1,2})").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    // ----------------------------------------------------------------- links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = try {
            app.post(data, data = mapOf("View" to "1"), referer = data).document
        } catch (e: Throwable) {
            Log.i(TAG, "View=1 failed $data -> $e")
            return false
        }

        val servers = doc.select("ul.serversList li[data-link]")
            .mapNotNull { it.attr("data-link").trim().takeIf { l -> l.isNotBlank() } }
        val downloads = doc.select("ul.donwload-servers-list a.ser-link[href]")
            .mapNotNull { it.attr("href").trim().takeIf { l -> l.isNotBlank() } }

        val allServers = (servers + downloads).distinct()
        if (allServers.isEmpty()) {
            Log.i(TAG, "no servers found on $data")
            return false
        }

        val seen = mutableSetOf<String>()
        val emit: (ExtractorLink) -> Unit = { if (seen.add(it.url)) callback(it) }

        var found = false
        for (server in allServers) {
            val abs = if (server.startsWith("//")) "https:$server" else server
            found = handleServer(abs, data, subtitleCallback, emit) || found
        }
        Log.i(TAG, "loadLinks $data servers=${allServers.size} found=$found")
        return found
    }

    private suspend fun handleServer(
        server: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        emit: (ExtractorLink) -> Unit
    ): Boolean {
        // The EgyDead player is a rotating file-host; its real sources must be resolved
        // through the Inertia payload. Known extractor hosts are handed straight over.
        if (!isExtractorHost(server)) {
            val mirrors = resolveMirrors(server)
            if (mirrors.isNotEmpty()) {
                var found = false
                for (mirror in mirrors) {
                    try {
                        if (loadExtractor(mirror, server, subtitleCallback, emit)) found = true
                    } catch (e: Throwable) {
                        Log.i(TAG, "mirror failed $mirror -> $e")
                    }
                }
                return found
            }
        }
        return try {
            loadExtractor(server, referer, subtitleCallback, emit)
        } catch (e: Throwable) {
            Log.i(TAG, "extractor failed $server -> $e")
            false
        }
    }

    private fun isExtractorHost(url: String): Boolean {
        val host = url.lowercase()
        return EXTRACTOR_HOSTS.any { host.contains(it) }
    }

    /**
     * Resolves `https://<host>/iframe/<hash>` into the list of real embed URLs by reading
     * the Inertia page version and requesting the lazy `streams` prop.
     */
    private suspend fun resolveMirrors(iframeUrl: String): List<String> {
        val page = try {
            app.get(iframeUrl, referer = mainUrl).text
        } catch (e: Throwable) {
            return emptyList()
        }
        if (!page.contains("data-page=\"app\"") || !page.contains(IFRAME_HOST)) return emptyList()
        val version = Regex("\"version\":\"([^\"]+)\"").find(page)?.groupValues?.get(1)
            ?: return emptyList()
        val body = try {
            app.get(
                iframeUrl,
                headers = mapOf(
                    "X-Inertia" to "true",
                    "X-Inertia-Version" to version,
                    "X-Inertia-Partial-Component" to IFRAME_HOST,
                    "X-Inertia-Partial-Data" to "streams",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "text/html, application/xhtml+xml"
                ),
                referer = iframeUrl
            ).text
        } catch (e: Throwable) {
            return emptyList()
        }
        return try {
            val streams = JSONObject(body).getJSONObject("props").getJSONObject("streams")
            val data = streams.optJSONArray("data") ?: return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until data.length()) {
                val mirrors = data.getJSONObject(i).optJSONArray("mirrors") ?: continue
                for (j in 0 until mirrors.length()) {
                    var link = mirrors.getJSONObject(j).optString("link").trim()
                    if (link.startsWith("//")) link = "https:$link"
                    if (link.startsWith("http")) out.add(link)
                }
            }
            out.distinct()
        } catch (e: Throwable) {
            emptyList()
        }
    }
}
