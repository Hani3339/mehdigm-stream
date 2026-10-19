package com.cimanow

import android.util.Base64
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
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
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * CimaNow (سيما ناو) provider.
 *
 * Backend: a WordPress CMS on its own `Cima Now New` theme. The home page mixes
 * carousel `section`s (each card is an `a` holding a `ul.info` + `li[aria-label=title]`);
 * search results live in `section[aria-label=posts] > article[aria-label=post]`; a series
 * page lists the episodes of one season in `ul#eps` and links every season through
 * `section[aria-label=seasons]`, while a movie is a single playable page.
 *
 * The player servers and the download mirrors are not part of the post markup: the
 * `شاهد` button points at `https://rm.freex2line.online/loadon/?link=<base64 of
 * "<postUrl>/watching/">`, and `#watch` on the post page only carries the trailer.
 * [loadLinks] therefore decodes that button (falling back to `<postUrl>/watching/`),
 * reads the servers/downloads there and hands every embed to [loadExtractor].
 */
class CimaNow : MainAPI() {
    companion object {
        private const val SOURCE_TIMEOUT_MS = 20_000L
    }

    override var name = "سيما ناو (CimaNow)"
    override var lang = "ar"
    override var mainUrl = "https://cimanow.cc"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val usesWebView = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/home" to "الرئيسية"
    )

    // ------------------------------------------------------------------ main page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val doc = app.get(request.data, headers = CN_HEADERS).document
        val lists = mutableListOf<HomePageList>()
        val seen = HashSet<String>()

        val hero = parseHero(doc).filter { seen.add(it.url) }
        if (hero.isNotEmpty()) lists.add(HomePageList("مميز", hero, isHorizontalImages = true))

        doc.select("section").filter { it.select("section").isEmpty() }.forEach { sec ->
            val items = parseCards(sec).filter { seen.add(it.url) }
            if (items.isEmpty()) return@forEach
            lists.add(HomePageList(sectionTitle(sec), items, isHorizontalImages = true))
        }

        if (lists.isEmpty()) return null
        return newHomePageResponse(lists)
    }

    private fun sectionTitle(sec: Element): String =
        sec.selectFirst(":scope > span")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
            ?: sec.selectFirst("h1, h2, h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: "أحدث الإضافات"

    private fun parseHero(doc: Document): List<SearchResponse> {
        val slider = doc.selectFirst("main-slider") ?: return emptyList()
        return slider.select("article").mapNotNull { art ->
            val a = art.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").trim()
            if (!href.startsWith("http")) return@mapNotNull null
            val img = art.selectFirst("img")
            val title = img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            toResult(art, href, title, posterOf(img))
        }.distinctBy { it.url }
    }

    // --------------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$q", headers = CN_HEADERS).document
        val root = doc.selectFirst("section[aria-label='posts']") ?: doc
        return parseCards(root)
    }

    private fun parseCards(root: Element): List<SearchResponse> {
        val anchors = LinkedHashSet<Element>()
        root.select("article[aria-label='post'] > a[href], div.owl-body > a[href]").forEach { anchors.add(it) }
        root.select("a[href]").forEach { a ->
            if (a.selectFirst("li[aria-label='title']") != null) anchors.add(a)
        }
        return anchors.mapNotNull { toSearchResult(it) }.distinctBy { it.url }
    }

    private fun toSearchResult(a: Element): SearchResponse? {
        val href = a.attr("href").trim()
        if (!href.startsWith("http")) return null
        val titleEl = a.selectFirst("li[aria-label='title']")
        val title = titleEl?.ownText()?.trim()?.takeIf { it.isNotBlank() }
            ?: titleEl?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: a.selectFirst("img[data-src]")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        return toResult(a, href, title, posterOf(a.selectFirst("img")))
    }

    private fun toResult(el: Element, href: String, title: String, poster: String?): SearchResponse {
        val type = typeOf(el, href, title)
        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    private fun typeOf(el: Element, href: String, title: String): TvType {
        val dec = runCatching { URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
        return when {
            dec.contains("فيلم") -> TvType.Movie
            href.contains("/selary/") -> TvType.TvSeries
            dec.contains("مسلسل") || dec.contains("الحلقة") || dec.contains("حلقة") -> TvType.TvSeries
            title.contains("مسلسل") -> TvType.TvSeries
            el.selectFirst("li[aria-label='episode']") != null -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun posterOf(img: Element?): String? {
        if (img == null) return null
        for (attr in listOf("data-src", "data-lazy-src", "data-original", "src")) {
            val v = img.attr(attr).trim()
            if (v.isBlank() || v.startsWith("data:") || v.endsWith("loading.svg")) continue
            return fixUrl(v)
        }
        return null
    }

    // ----------------------------------------------------------------------- load

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = CN_HEADERS).document
        val title = loadTitle(doc)
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?.takeIf { it.startsWith("http") }
            ?: posterOf(doc.selectFirst("article img[data-src], article img"))
        val plot = doc.selectFirst("li[aria-label='story'] p")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("ul#details li p")?.text()?.trim()?.takeIf { it.isNotBlank() }
        val year = doc.selectFirst("li a[href*=release-year], a[href*=release-year]")?.text()?.trim()?.toIntOrNull()
        val tags = doc.select("li a[href*=actor]").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        if (looksLikeSeries(doc, url)) {
            val episodes = collectEpisodes(doc, url)
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                }
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    private fun loadTitle(doc: Document): String {
        val og = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
        if (!og.isNullOrBlank()) return og
        return doc.title().trim()
    }

    private fun looksLikeSeries(doc: Document, url: String): Boolean {
        if (url.contains("/selary/")) return true
        val dec = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        if (dec.contains("مسلسل") || dec.contains("حلقة") || dec.contains("الحلقة")) return true
        return doc.selectFirst("ul#eps, ul#episodes, section[aria-label='seasons']") != null
    }

    private suspend fun collectEpisodes(doc: Document, url: String): List<Episode> {
        val currentSeason = seasonOf(doc, url)
        val own = parseEpisodesIn(doc, currentSeason)
        val extra = coroutineScope {
            seasonLinks(doc, url).map { (season, href) ->
                async(Dispatchers.IO) {
                    runCatching { app.get(href, headers = CN_HEADERS).document }.getOrNull()
                        ?.let { parseEpisodesIn(it, season) } ?: emptyList()
                }
            }.awaitAll().flatten()
        }
        return (own + extra).distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
    }

    private fun seasonLinks(doc: Document, url: String): List<Pair<Int, String>> {
        val self = fixUrl(url)
        return doc.select("section[aria-label='seasons'] ul li a[href]").mapNotNull { a ->
            val season = Regex("""الموسم\s*(\d+)""").find(a.text())?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            val href = fixUrl(a.attr("href").trim())
            if (href == self) null else season to href
        }.distinctBy { it.second }
    }

    private fun parseEpisodesIn(doc: Document, season: Int): List<Episode> {
        val out = mutableListOf<Episode>()
        doc.select("ul#eps li a[href], ul#episodes li a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (!href.startsWith("http")) return@forEach
            val label = a.attr("aria-label").trim().ifBlank { a.text().trim() }
            val number = a.selectFirst("em")?.text()?.trim()?.toIntOrNull()
                ?: Regex("""(?:الحلقة|حلقة)\s*(\d+)""").find(label)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""\d+""").find(a.text().trim())?.value?.toIntOrNull()
            out += newEpisode(fixUrl(href)) {
                this.name = label.ifBlank { if (number != null) "الحلقة $number" else "الحلقة" }
                this.season = season
                this.episode = number
            }
        }
        return out
    }

    private fun seasonOf(doc: Document, url: String): Int {
        doc.selectFirst("section[aria-label='seasons'] li.active a")?.text()?.let {
            Regex("""الموسم\s*(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull()?.let { s -> return s }
        }
        doc.selectFirst("span[aria-label='season-title']")?.text()?.let {
            Regex("""الموسم\s*(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull()?.let { s -> return s }
        }
        val dec = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        Regex("""-ج\s*(\d+)""").find(dec)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return 1
    }

    // ----------------------------------------------------------------- loadLinks

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val postDoc = runCatching { app.get(data, headers = CN_HEADERS).document }.getOrNull()
        val watchUrl = postDoc?.let { watchUrlFromDoc(it) } ?: watchingUrl(data)

        val players = LinkedHashSet<String>()
        val downloads = LinkedHashMap<String, String>()

        val watchDoc = runCatching {
            app.get(watchUrl, headers = CN_HEADERS, referer = data).document
        }.getOrNull()
        if (watchDoc != null) collectSources(watchDoc, watchUrl, players, downloads)

        // The watch page is sometimes gated and only serves the trailer; the post page may still
        // expose the same mirrors for a few hosts.
        if (players.isEmpty() && downloads.isEmpty()) {
            postDoc?.let { collectSources(it, data, players, downloads) }
        }

        if (players.isEmpty() && downloads.isEmpty()) return false

        val lock = Any()
        var found = false
        coroutineScope {
            players.map { embed ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                        resolveEmbed(embed, watchUrl, subtitleCallback) { link ->
                            synchronized(lock) { found = true }
                            callback(link)
                        }
                    }
                }
            }.awaitAll()
        }

        for ((url, label) in downloads) {
            callback(
                newExtractorLink(
                    source = "تحميل",
                    name = label.ifBlank { "تحميل" },
                    url = url,
                    type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = watchUrl
                    this.quality = qualityOf(label)
                    this.headers = mapOf("User-Agent" to CN_UA, "Referer" to watchUrl)
                }
            )
            found = true
        }

        return found
    }

    private fun collectSources(
        doc: Document,
        referer: String,
        players: MutableSet<String>,
        downloads: MutableMap<String, String>
    ) {
        doc.select("iframe[src], iframe[data-src]").forEach { frame ->
            val src = frame.attr("src").ifBlank { frame.attr("data-src") }.trim()
            if (src.startsWith("http") && !isTrailer(src) && !players.contains(src)) players.add(src)
        }
        doc.select("[data-embed]").forEach { el ->
            val src = Regex("""src\s*=\s*['"]([^'"]+)['"]""").find(el.attr("data-embed"))
                ?.groupValues?.get(1)?.trim()
            if (!src.isNullOrBlank() && src.startsWith("http") && !isTrailer(src) && !players.contains(src)) players.add(src)
        }
        // From HAR decoded page we see #watch li[data-index] and also direct links
        doc.select("#watch li, ul#watch li, .servers li").forEach { li ->
            val a = li.selectFirst("a[href]") ?: return@forEach
            val href = a.attr("href").trim()
            if (!href.startsWith("http") || isTrailer(href)) return@forEach
            val label = a.selectFirst("p")?.text()?.trim().orEmpty().ifBlank { a.text().trim() }
            if (isDownload(href)) downloads[href] = label else if (!players.contains(href)) players.add(href)
        }
        doc.select("li[aria-label='quality'] a[href], li[aria-label=download] a[href], .btns a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (!href.startsWith("http") || isTrailer(href)) return@forEach
            val label = a.selectFirst("p")?.text()?.trim().orEmpty().ifBlank { a.text().trim() }
            if (isDownload(href)) downloads[href] = label else if (!players.contains(href)) players.add(href)
        }
        doc.select(
            "a[href*=cimanowtv], a[href*=filespayouts], a[href*=worldcdn], " +
                "a[href*=mediafire], a[href*=mega.nz], a[href*=forafile], a[href*=upns], " +
                "a[href*=bysetayico], a[href*=streamhls], a[href*=jetload], a[href*=frdl], a[href*=vk.com]"
        ).forEach { a ->
            val href = a.attr("href").trim()
            if (!href.startsWith("http") || isTrailer(href)) return@forEach
            val label = a.selectFirst("p")?.text()?.trim().orEmpty().ifBlank { a.text().trim() }
            if (isDownload(href) || label.contains("جيجا") || label.contains("ميجا")) {
                downloads[href] = label
            } else {
                if (!players.contains(href)) players.add(href)
            }
        }
    }

    private suspend fun resolveEmbed(
        embed: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = false
        val wrapped: (ExtractorLink) -> Unit = { link -> emitted = true; callback(link) }
        try {
            val custom: ExtractorApi? = when {
                embed.contains("cnvids.com") -> CimaNowCnvids()
                embed.contains("fr.cimanow.cc") -> CimaNowFr()
                else -> null
            }
            if (custom != null) {
                custom.getUrl(embed, referer, subtitleCallback, wrapped)
            } else {
                loadExtractor(embed, referer, subtitleCallback, wrapped)
            }
        } catch (_: Throwable) {
        }
        if (emitted) return true

        // Last resort: fetch the embed page and harvest the media URL directly.
        val page = runCatching {
            app.get(embed, referer = referer, headers = CN_HEADERS).text
        }.getOrNull() ?: return false
        var any = false
        Regex("""https?://[^"'\s\\]+?\.(?:m3u8|mp4)[^"'\s\\]*""")
            .findAll(page).map { it.value }.distinct().forEach { media ->
                any = true
                callback(
                    newExtractorLink(
                        source = "CimaNow",
                        name = "CimaNow",
                        url = media,
                        type = if (media.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = embed
                        this.headers = mapOf("User-Agent" to CN_UA, "Referer" to embed)
                    }
                )
            }
        return any
    }

    private fun watchUrlFromDoc(doc: Document): String? {
        val href = doc.selectFirst("a.shine[href*=loadon]")?.attr("href")
            ?: doc.selectFirst("a.shine[href*=watching]")?.attr("href")
            ?: return null
        Regex("""[?&]link=([^&]+)""").find(href)?.groupValues?.get(1)?.let { raw ->
            val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            for (flags in listOf(Base64.URL_SAFE, Base64.DEFAULT)) {
                val value = runCatching { String(Base64.decode(decoded, flags)) }.getOrNull()
                if (value != null && value.startsWith("http")) return value.trim()
            }
        }
        return null
    }

    private fun watchingUrl(data: String): String =
        if (data.endsWith("/")) "${data}watching/" else "$data/watching/"

    private fun isTrailer(url: String): Boolean =
        url.contains("youtube.com") || url.contains("youtu.be")

    private fun isDownload(href: String): Boolean {
        val h = href.lowercase()
        return h.contains("cimanowtv.com") || h.contains("filespayouts") || h.contains("worldcdn.online") ||
            h.contains("mediafire") || h.contains("mega.nz") || h.contains("drive.google") ||
            h.contains(".mp4") || h.contains(".mkv") || h.contains(".zip") || h.contains("/download")
    }

    private fun qualityOf(label: String): Int =
        Regex("""(\d{3,4})\s*p""", RegexOption.IGNORE_CASE)
            .find(label)?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value
}
