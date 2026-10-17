package com.cimawbas

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

/**
 * Cimawbas (سيما وبس) provider.
 *
 * Backend: the "Melody / 3arbserv" PHP CMS used by the `cimawbas` / `mycima` mirror
 * network (vid.mycima.cc, cimawbas.tv, iegybest.cimawbas.tv …). Listings are plain
 * server-rendered `ul.pm-ul-browse-videos` grids; the detail page (`watch.php?vid=`)
 * carries the metadata and, for series, the season/episode tabs; the player page
 * (`play.php?vid=`) exposes one `li[data-embed]` per host. Embeds are either handed to
 * CloudStream's global [loadExtractor] (mixdrop, vidmoly, …) or resolved through the
 * site's own server-side scraper (liiivideo/minochinos/…).
 */
class Cimawbas : MainAPI() {
    companion object {
        private val HEADERS = mapOf(
            "User-Agent" to
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept-Language" to "ar,en;q=0.9"
        )
        private const val SOURCE_TIMEOUT_MS = 15_000L
    }

    override var name = "سيما وبس (Cimawbas)"
    override var lang = "ar"
    override var mainUrl = "https://vid.mycima.cc"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val usesWebView = false
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies.php?&page=" to "أحدث الأفلام",
        "$mainUrl/all-series.php?&page=" to "أحدث المسلسلات",
        "$mainUrl/episodes.php?&page=" to "أحدث الحلقات",
        "$mainUrl/topvideos.php?&page=" to "الأكثر مشاهدة",
        "$mainUrl/newvideos.php?&page=" to "جديد الموقع"
    )

    // ------------------------------------------------------------------ main page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val doc = app.get(request.data + page, headers = HEADERS).document
        val items = parseList(doc)
        val lastPage = doc.select("ul.pagination a[href*=page=]")
            .mapNotNull { Regex("""[?&]page=(\d+)""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull()
        val hasNext = lastPage != null && lastPage > page
        return newHomePageResponse(request.name, items, hasNext)
    }

    // --------------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val doc = app.get("$mainUrl/search.php?keywords=$q", headers = HEADERS).document
        return parseList(doc)
    }

    private fun parseList(doc: Document): List<SearchResponse> =
        doc.select("ul.pm-ul-browse-videos > li, ul#pm-grid > li").mapNotNull { li ->
            val anchor = li.selectFirst("div.caption h3 a[href]")
                ?: li.selectFirst("a[href*=watch.php]")
                ?: return@mapNotNull null
            val href = anchor.attr("href").trim()
            if (href.isBlank()) return@mapNotNull null
            val title = anchor.text().trim().ifBlank { anchor.attr("title").trim() }
            if (title.isBlank()) return@mapNotNull null
            val poster = li.selectFirst("img")?.let { img ->
                (img.attr("src").ifBlank { img.attr("data-src") }).trim().takeIf { it.startsWith("http") }
            }
            val isSeries = title.contains("مسلسل") || title.contains("الحلقة") ||
                li.selectFirst("div.ribon span.hot")?.text()?.contains("مكتمل") == true
            if (isSeries) {
                newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) { this.posterUrl = poster }
            } else {
                newMovieSearchResponse(title, fixUrl(href), TvType.Movie) { this.posterUrl = poster }
            }
        }.distinctBy { it.url }

    // ----------------------------------------------------------------------- load

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = HEADERS).document
        val title = doc.selectFirst("h1[itemprop=name]")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.title().trim()
        if (title.isBlank()) return null

        val poster = doc.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")?.trim()?.takeIf { it.startsWith("http") }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.takeIf { it.startsWith("http") }
        val plot = doc.selectFirst("[itemprop=description]")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("div.pm-video-description")?.text()?.trim()?.takeIf { it.isNotBlank() }
        val year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()
        val tags = doc.select("a[href*=category.php], a[href*=tag.php]")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        val playHref = doc.selectFirst("a.xtgo[href]")?.attr("href")?.let { fixUrl(it) }
        val isSeries = doc.selectFirst("div.SeasonsBox, div.SeasonsEpisodesMain, .seasons-sec, .SeasonsEpisodes") != null
        val episodes = if (isSeries) parseEpisodes(doc) else emptyList()

        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, playHref ?: url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    private fun parseEpisodes(doc: Document): List<Episode> {
        val out = mutableListOf<Episode>()

        // Variant A: div.SeasonsEpisodesMain .tabcontent#SeasonN > ul > a[href=watch.php?vid=]
        doc.select("div.SeasonsEpisodesMain .tabcontent").forEach { panel ->
            val season = panel.id().removePrefix("Season").toIntOrNull() ?: 1
            panel.select("a[href]").forEach { a ->
                addEpisode(out, a.attr("href"), a.text().trim(), season)
            }
        }

        // Variant B: .SeasonsEpisodes[data-season] > a[href]
        doc.select(".SeasonsEpisodes").forEach { grp ->
            val season = grp.attr("data-season").toIntOrNull() ?: 1
            grp.select("a[href]").forEach { a ->
                addEpisode(out, a.attr("href"), a.text().trim(), season)
            }
        }

        return out.distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
    }

    private fun addEpisode(out: MutableList<Episode>, href: String, label: String, season: Int) {
        if (href.isBlank()) return
        val abs = fixUrl(href)
        if (!abs.contains("watch.php") && !abs.contains("play.php")) return
        val episode = Regex("""\d+""").find(label)?.value?.toIntOrNull()
        out += newEpisode(abs) {
            this.name = label.ifBlank { "الحلقة" }
            this.season = season
            this.episode = episode
        }
    }

    // ----------------------------------------------------------------- loadLinks

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataDoc = runCatching { app.get(data, headers = HEADERS).document }.getOrNull() ?: return false

        var embeds = parseEmbeds(dataDoc)
        var referer = data
        if (embeds.isEmpty()) {
            val playHref = dataDoc.selectFirst("a.xtgo[href]")?.attr("href")?.let { fixUrl(it) }
            if (!playHref.isNullOrBlank()) {
                val pdoc = runCatching { app.get(playHref, headers = HEADERS).document }.getOrNull()
                if (pdoc != null) {
                    embeds = parseEmbeds(pdoc)
                    referer = playHref
                }
            }
        }

        val vid = Regex("""[?&]vid=([A-Za-z0-9]+)""").find(data)?.groupValues?.get(1)
        val downloads = if (vid != null) {
            runCatching {
                app.get("$mainUrl/downloads.php?vid=$vid", headers = HEADERS).document
                    .select("ul.downloadlist a[href], div#pm-download a[href], a.download-btn[href]")
                    .mapNotNull { a ->
                        val href = a.attr("href").trim()
                        if (!href.startsWith("http")) return@mapNotNull null
                        val label = a.selectFirst("strong")?.text()?.trim().orEmpty()
                        href to label
                    }.distinctBy { it.first }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val sources = embeds.map { Triple(it.first, it.second, referer) } +
            downloads.map { Triple(it.first, it.second.ifBlank { "تحميل" }, "$mainUrl/") }
        if (sources.isEmpty()) return false

        val lock = Any()
        var found = false
        coroutineScope {
            sources.map { (link, server, ref) ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                        val ok = resolve(link, server, ref, subtitleCallback) { l ->
                            synchronized(lock) { found = true }
                            callback(l)
                        }
                        if (!ok) return@withTimeoutOrNull
                    }
                }
            }.awaitAll()
        }
        return found
    }

    private fun parseEmbeds(doc: Document): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        doc.select("[data-embed]").forEach { el ->
            val embed = el.attr("data-embed").trim()
            if (embed.isBlank()) return@forEach
            Regex("""src\s*=\s*['"]([^'"]+)['"]""").find(embed)?.groupValues?.get(1)?.trim()?.let { src ->
                if (src.startsWith("http")) {
                    val label = el.selectFirst("strong")?.text()?.trim().orEmpty()
                        .ifBlank { el.attr("id").removePrefix("server_").ifBlank { "Server" } }
                    out.add(src to label)
                }
            }
        }
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.startsWith("http")) out.add(src to iframe.attr("title").trim().ifBlank { "Server" })
        }
        return out.distinctBy { it.first }
    }

    private suspend fun resolve(
        embed: String,
        server: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val host = runCatching { URI(embed).host?.lowercase() }.getOrNull() ?: ""
        val origin = runCatching {
            val u = URI(embed)
            "${u.scheme}://${u.host}"
        }.getOrNull() ?: mainUrl

        // Direct tokenised HLS (visitmycityfor365days.cc / liiivideo CDN) - play as-is.
        if (embed.contains(".m3u8")) {
            val headers = mapOf(
                "User-Agent" to HEADERS.getValue("User-Agent"),
                "Referer" to "$origin/",
                "Origin" to origin
            )
            val emitted = runCatching {
                M3u8Helper.generateM3u8(server, embed, "$origin/").forEach {
                    it.headers = headers.toMutableMap()
                    callback(it)
                }
                true
            }.getOrDefault(false)
            if (emitted) return true
        }

        // vipserver.liiivideo.com uses an obfuscated Playerjs setup the mawdhou3 scraper
        // cannot read ("Pas de lien m3u8 trouvé dans le JS"), so decode it locally.
        if (host.contains("vipserver.liiivideo.com")) {
            val done = runCatching {
                VipserverLiivideoExtractor().resolve(embed, referer, callback)
            }.getOrDefault(false)
            if (done) return true
        }

        val scraperHost = host.contains("liiivideo") || host.contains("minochinos") ||
            host.contains("vipserver") || host.contains("bysebuho") || host.contains("visitmycityfor365days")

        val doneLoad = runCatching {
            loadExtractor(embed, referer, subtitleCallback, callback)
        }.getOrDefault(false)
        if (doneLoad) return true

        if (scraperHost) {
            val done = runCatching {
                CimawbasHostExtractor.extract(embed, server, referer, null, null, callback)
            }.getOrDefault(false)
            if (done) return true
        }

        val doneHost = runCatching {
            CimawbasHostExtractor.extract(embed, server, referer, null, null, callback)
        }.getOrDefault(false)
        if (doneHost) return true

        // Fallback: fetch embed page and try to extract direct sources (VidMoly, Bysebuho, etc.)
        val embedPage = runCatching {
            app.get(embed, headers = HEADERS, referer = referer).text
        }.getOrNull() ?: return false

        val direct = extractFromHtml(embedPage)
        if (direct.isNotEmpty()) {
            direct.forEach { (url, qual) ->
                callback(
                    com.lagradost.cloudstream3.utils.newExtractorLink(
                        server,
                        server,
                        url,
                        if (url.contains(".m3u8")) com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8 else com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO
                    ) {
                        this.referer = embed
                        qual?.let { this.quality = it }
                    }
                )
            }
            return true
        }
        return false
    }

    private fun extractFromHtml(html: String): List<Pair<String, Int?>> {
        val out = mutableListOf<Pair<String, Int?>>()
        if (html.isBlank()) return out
        // JWPlayer sources: sources: [{ file: "..." }]
        Regex("""sources\s*:\s*\[\s*\{[^}]*file\s*:\s*["']([^"']+)["'][^}]*\}""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).forEach { m ->
                val u = m.groupValues[1]
                if (u.startsWith("http")) out.add(u to null)
            }
        // file: "https://...m3u8"
        Regex("""file\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""")
            .findAll(html).forEach { m ->
                val u = m.groupValues[1]
                if (u.startsWith("http")) out.add(u to null)
            }
        // master.m3u8, index.m3u8
        Regex("(https?://[^\"']+master\\.m3u8[^\"']*)").findAll(html).forEach { m ->
            out.add(m.groupValues[1] to null)
        }
        Regex("(https?://[^\"']+\\.m3u8)").findAll(html).forEach { m ->
            out.add(m.groupValues[1] to null)
        }
        Regex("hls\\d*['\"]\\s*:\\s*['\"]([^'\"]+)").findAll(html).forEach { m -> out.add(m.groupValues[1] to null) }
        Regex("\"file\"\\s*:\\s*\"([^\"]+)").findAll(html).forEach { m -> out.add(m.groupValues[1] to null) }
        Regex("'file'\\s*:\\s*'([^']+)").findAll(html).forEach { m -> out.add(m.groupValues[1] to null) }
        out.removeIf { !it.first.startsWith("http") }
        return out.distinctBy { it.first }
    }
}
