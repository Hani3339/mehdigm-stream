package com.egydead

import android.util.Log
import android.webkit.CookieManager
import com.lagradost.cloudstream3.CommonActivity
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
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.net.URL

/**
 * EgyDead (إيجي ديد) provider.
 *
 * WordPress site that renders movies at the root slug and series/seasons/episodes
 * under `/serie/`, `/season/`, `/episode/`. Detail pages hide the players behind a
 * `POST View=1` form. The single "MegaMax" server is a rotating file-host domain
 * (`https://<random>.top/iframe/<hash>`) whose real sources are exposed through an
 * Inertia partial reload (`X-Inertia-Partial-Data: streams`); `<random>.top/download/<hash>`
 * exposes the download mirrors through `props.attachments` + `props.download`. Those
 * sources are embeds of well-known hosts (uqload, vidmoly, streamwish/hgcloud, mixdrop,
 * doodstream, vidara, byse, vidhide/earnvids, voe, streamruby) resolved by CloudStream's
 * [loadExtractor] and the extractors in this plugin ([EgyDeadPackerExtractor] family,
 * [EgyDeadUqload], [EgyDeadByse]).
 */
class EgyDead : MainAPI() {
    companion object {
        private const val TAG = "EgyDead"
        private const val IFRAME_HOST = "files/mirror/video"
        private const val DOWNLOAD_COMPONENT = "files/mirror/download"

        /** Hosts handled by CloudStream built-ins (or our own extractor). */
        private val EXTRACTOR_HOSTS = listOf(
            "uqload", "vidmoly", "streamwish", "streamhg", "mixdrop", "dood",
            "vidara", "byse", "vidhide", "earnvids", "voe", "streamruby", "stmruby",
            "vibuxer", "rubyvidhub", "morencius", "callistanise", "byseraguci", "ok.ru"
        )

        private val SEASON_WORDS = listOf(
            "الثاني عشر" to 12, "الحادي عشر" to 11,
            "الاول" to 1, "الثاني" to 2, "الثالث" to 3, "الرابع" to 4,
            "الخامس" to 5, "السادس" to 6, "السابع" to 7, "الثامن" to 8,
            "التاسع" to 9, "العاشر" to 10
        )

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

        private val PAGE_HEADERS = mapOf("User-Agent" to USER_AGENT)
    }

    private var savedCookies: String? = null
    private val cfMutex = Mutex()

    private fun buildHeaders(referer: String?): MutableMap<String, String> {
        val headers = mutableMapOf("User-Agent" to USER_AGENT, "Referer" to (referer ?: mainUrl), "Accept-Language" to "ar,en-US;q=0.9")
        savedCookies?.let { headers["Cookie"] = it }
        return headers
    }

    private suspend fun cfGet(url: String, referer: String? = null): Document {
        var currentUrl = url
        var headers = buildHeaders(referer)
        var res = app.get(currentUrl, headers = headers, timeout = 30)
        if (res.code in listOf(403, 503, 429) || res.text.contains("Just a moment", true) || res.text.contains("Attention Required", true)) {
            cfMutex.withLock {
                val cm = CookieManager.getInstance()
                val current = cm.getCookie(currentUrl)
                if (current != null && current != savedCookies && current.contains("cf_clearance")) {
                    savedCookies = current
                } else {
                    val activity = CommonActivity.activity
                    if (activity != null) {
                        val solver = CloudflareSolver.solve(activity, currentUrl, USER_AGENT)
                        if (solver != null) {
                            if (!solver.cookies.isNullOrEmpty()) savedCookies = solver.cookies
                            if (solver.finalUrl != currentUrl) {
                                try {
                                    val nh = URL(solver.finalUrl).host
                                    mainUrl = "https://$nh"
                                } catch (_: Exception) {}
                                currentUrl = solver.finalUrl
                            }
                        }
                    }
                }
            }
            headers = buildHeaders(referer)
            res = app.get(currentUrl, headers = headers, timeout = 30)
        }
        return res.document
    }

    private suspend fun cfPost(url: String, data: Map<String, String>, referer: String? = null): Document {
        var currentUrl = url
        var headers = buildHeaders(referer).apply { put("X-Requested-With", "XMLHttpRequest") }
        var res = app.post(currentUrl, data = data, headers = headers, timeout = 30)
        if (res.code in listOf(403, 503, 429) || res.text.contains("Just a moment", true)) {
            cfMutex.withLock {
                val cm = CookieManager.getInstance()
                val current = cm.getCookie(currentUrl)
                if (current != null && current != savedCookies && current.contains("cf_clearance")) {
                    savedCookies = current
                } else {
                    val activity = CommonActivity.activity
                    if (activity != null) {
                        val solver = CloudflareSolver.solve(activity, currentUrl, USER_AGENT)
                        if (solver != null) {
                            if (!solver.cookies.isNullOrEmpty()) savedCookies = solver.cookies
                            if (solver.finalUrl != currentUrl) {
                                try {
                                    val nh = URL(solver.finalUrl).host
                                    mainUrl = "https://$nh"
                                } catch (_: Exception) {}
                                currentUrl = solver.finalUrl
                            }
                        }
                    }
                }
            }
            headers = buildHeaders(referer).apply { put("X-Requested-With", "XMLHttpRequest") }
            res = app.post(currentUrl, data = data, headers = headers, timeout = 30)
        }
        return res.document
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
            cfGet(url)
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
            cfGet(url)
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
            cfGet(url)
        } catch (e: Throwable) {
            Log.i(TAG, "load failed $url -> $e")
            return newMovieLoadResponse(url, url, TvType.Movie, "") { this.plot = "تعذّر تحميل الصفحة." }
        }

        if (!isSeriesUrl(url)) return movieResponse(url, doc)

        val anchor = anchorSeasonUrl(url, doc)
        val poster = posterOf(doc)
        val title = seriesDisplayName(url, doc, anchor)
        val plot = plotOf(doc)
        val year = Regex("(19|20)\\d{2}").find(title)?.value?.toIntOrNull()
        val tags = doc.select(".breadcrumbs-single a").map { it.text().trim() }
            .filter { it.isNotBlank() && it != "الرئيسيه" }

        val episodes = resolveSeriesEpisodes(url, doc, anchor, title, poster)

        if (episodes.isEmpty() && url.contains("/episode/")) {
            // No season structure found — expose just the single episode.
            val episode = newEpisode(url) {
                this.name = titleOf(doc)
                this.episode = numAfterHalka(titleOf(doc))
                this.posterUrl = poster
                this.description = plot
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(episode)) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    private fun isSeriesUrl(url: String): Boolean =
        url.contains("/serie/") || url.contains("/season/") || url.contains("/episode/")

    private suspend fun movieResponse(url: String, doc: Document): com.lagradost.cloudstream3.LoadResponse {
        val title = titleOf(doc)
        val year = Regex("(19|20)\\d{2}").find(title)?.value?.toIntOrNull()
        val tags = doc.select(".breadcrumbs-single a").map { it.text().trim() }
            .filter { it.isNotBlank() && it != "الرئيسيه" }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterOf(doc)
            this.plot = plotOf(doc)
            this.year = year
            this.tags = tags
        }
    }

    /**
     * Resolves every season of the series the given detail page belongs to.
     *
     * All three page types expose the available seasons through a `.seasons-list`
     * block, but that list mixes the real seasons with unrelated "related series"
     * links. The season URLs of the current show are filtered by the series name
     * taken from the `/serie/` breadcrumb.
     */
    private suspend fun resolveSeriesEpisodes(
        detailUrl: String,
        doc: Document,
        anchor: String?,
        seriesName: String,
        poster: String?
    ): List<Episode> {
        val anchorDoc = if (anchor != null && anchor != detailUrl) safeGet(anchor) ?: doc else doc

        val seasons = LinkedHashSet<String>()
        if (anchor != null) seasons += anchor
        val candidateText = mutableMapOf<String, String>()
        for (d in listOf(anchorDoc, doc)) {
            d.select(".seasons-list li.movieItem a[href], .seasons-list li a[href]").forEach { a ->
                val href = a.attr("href").trim()
                if (href.isNotBlank() && href.contains("/season/")) {
                    seasons += href
                    candidateText.putIfAbsent(href, a.attr("title").ifBlank { a.text() }.trim())
                }
            }
        }

        val anchorOrUrl = anchor ?: detailUrl
        val baseName = slugBaseName(anchorOrUrl)
        val keys = listOf(normKey(seriesName), normKey(seriesNameFromUrl(anchorOrUrl)), baseName)
            .filter { it.isNotBlank() }
            .distinct()

        val sameSeries = seasons.filter { href ->
            val byText = keys.any { key -> normKey(decode(candidateText[href].orEmpty())).contains(key) }
            val bySlug = baseName.isNotBlank() && slugBaseName(href) == baseName
            byText || bySlug
        }
        val finalSeasons = sameSeries.ifEmpty {
            if (anchor != null) listOf(anchor) else seasons.toList()
        }

        val episodes = mutableListOf<Episode>()
        finalSeasons.forEachIndexed { index, seasonUrl ->
            val sdoc = if (seasonUrl == detailUrl) doc else safeGet(seasonUrl) ?: return@forEachIndexed
            val seasonNumber = arabicSeason(seasonTitleOf(sdoc, seasonUrl)) ?: (index + 1)
            episodes += parseEpisodeLinks(sdoc, seasonNumber, poster)
        }

        return episodes
            .distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    }

    private suspend fun safeGet(target: String): Document? = try {
        cfGet(target)
    } catch (e: Throwable) {
        Log.i(TAG, "season fetch failed $target -> $e")
        null
    }

    private fun anchorSeasonUrl(detailUrl: String, doc: Document): String? = when {
        detailUrl.contains("/season/") -> detailUrl
        detailUrl.contains("/episode/") ->
            doc.selectFirst(".breadcrumbs-single a[href*='/season/']")
                ?.attr("href")?.trim()?.takeIf { it.isNotBlank() }
        else -> null
    }

    /**
     * The site slugs encode the series name, e.g.
     * `/season/مسلسل-into-the-badlands-الموسم-الثالث-مترجم-كامل/`. Stripping the known
     * decorations yields a clean, stable series name that is also used to tell the
     * current series' seasons apart from the unrelated "related series" cards.
     */
    private fun seriesNameFromUrl(rawUrl: String): String {
        var s = decode(rawUrl).substringBefore("?")
        s = when {
            s.contains("/serie/") -> s.substringAfterLast("/serie/")
            s.contains("/season/") -> s.substringAfterLast("/season/")
            s.contains("/episode/") -> s.substringAfterLast("/episode/")
            else -> s
        }
        s = s.trim('/')
        s = s.replace("جميع-مواسم", " ").replace("جميع مواسم", " ").trim()
        s = s.substringAfter("مسلسل", s).trim('-')
        s = s.substringBefore("الموسم").substringBefore("موسم")
            .substringBefore("مترجم").substringBefore("مدبلج")
            .substringBefore("الحلقه").substringBefore("الحلقة")
            .trim('-')
        return s.replace('-', ' ').replace(Regex("\\s+"), " ").trim()
    }

    private fun seasonTitleOf(doc: Document, seasonUrl: String): String =
        "${titleOf(doc)} ${decode(seasonUrl)}".trim()

    /**
     * Stable key that identifies the series a season/episode URL belongs to, regardless of
     * how the site names it. Handles both style of slugs used by the site:
     *   `/season/mobland-s02/`                                  -> `mobland`
     *   `/serie/mobland-2025/`                                  -> `mobland`
     *   `/season/مسلسل-badlands-الموسم-الثالث-مترجم-كامل/`      -> `badlands`
     */
    private fun slugBaseName(rawUrl: String): String {
        var s = decode(rawUrl).substringBefore("?")
        s = when {
            s.contains("/serie/") -> s.substringAfterLast("/serie/")
            s.contains("/season/") -> s.substringAfterLast("/season/")
            s.contains("/episode/") -> s.substringAfterLast("/episode/")
            else -> s
        }
        s = s.trim('/')
        listOf("الموسم", "موسم", "مترجم", "مدبلج", "الحلقة", "الحلقه").forEach { s = s.substringBefore(it) }
        s = s.replace("جميع-مواسم", " ").replace("جميع مواسم", " ").replace("مسلسل", " ")
        s = s.replace(Regex("(?i)[-_ ]?e\\d+$"), "")
        s = s.replace(Regex("(?i)[-_ ]?season[-_ ]?\\d+$"), "")
        s = s.replace(Regex("(?i)[-_ ]?s\\d+$"), "")
        s = s.replace(Regex("[-_ ]?(19|20)\\d{2}$"), "")
        return normKey(s)
    }

    private fun seriesDisplayName(detailUrl: String, doc: Document, anchor: String?): String {
        if (detailUrl.contains("/episode/")) {
            return seriesNameFromUrl(anchor ?: detailUrl).ifBlank { cleanSeriesName(titleOf(doc)) }
        }
        // The season/series breadcrumb holds the real series crumb, but a bare `/serie/`
        // entry is just the listing ("المسلسلات") and must be ignored.
        val crumb = doc.selectFirst(".breadcrumbs-single a[href*='/serie/']")
            ?.takeIf { it.attr("href").trimEnd('/').substringAfter(mainUrl).trimEnd('/') != "/serie" }
            ?.text()?.trim()
        val raw = crumb?.takeIf { it.isNotBlank() } ?: doc.title().substringBefore("|").trim()
        return cleanSeriesName(raw).ifBlank { seriesNameFromUrl(anchor ?: detailUrl) }
    }

    private fun cleanSeriesName(raw: String): String {
        var t = raw.substringBefore("|").trim()
        listOf(
            "جميع مواسم", "جميع المواسم", "مشاهدة", "مسلسل", "انمي", "أنمي",
            "مترجمة", "مترجم", "مدبلجة", "مدبلج", "كامل", "الموسم", "موسم"
        ).forEach { t = t.replace(it, " ") }
        SEASON_WORDS.forEach { (w, _) -> t = t.replace(w, " ") }
        t = t.replace(Regex("\\s+"), " ").trim()
        return t.ifBlank { raw.trim() }
    }

    private fun decode(value: String): String = try {
        java.net.URLDecoder.decode(value, "UTF-8")
    } catch (_: Throwable) {
        value
    }

    private fun normKey(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

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

    /**
     * The site is inconsistent with Arabic orthography: the same word appears with a
     * final `ي` or `ى`, and with `أ`/`إ`/`آ` or bare `ا` (e.g. "الثاني" vs "الثانى",
     * "الأول" vs "الاول"). Normalising removes diacritics and unifies those letters so
     * every spelling matches the canonical SEASON_WORDS entries.
     */
    private fun normalizeAr(text: String): String = text
        .replace('ى', 'ي')
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")

    private fun arabicSeason(text: String): Int? {
        val t = normalizeAr(text)
        Regex("(?:الموسم|موسم)[\\s\\-_]*(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull()
            ?.let { return it }
        SEASON_WORDS.forEach { (word, number) -> if (t.contains(normalizeAr(word))) return number }
        Regex("(?i)season[\\s\\-_]?(\\d{1,2})").find(t)?.groupValues?.get(1)?.toIntOrNull()
            ?.let { return it }
        Regex("(?i)(?:^|[\\-_ ])s(\\d{1,2})(?:[\\-_ /]|$)").find(t)?.groupValues?.get(1)?.toIntOrNull()
            ?.let { return it }
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
            cfPost(data, mapOf("View" to "1"), referer = data)
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
        // EgyDead's own "MegaMax"-style player rotates domains and hides its real sources
        // behind Inertia payloads. Two endpoints exist:
        //   /iframe/<hash>   -> streaming mirrors (props.streams)
        //   /download/<hash> -> download mirrors  (props.attachments -> props.download)
        // Everything else is a plain file host handed to loadExtractor.
        val path = server.substringAfter("://").substringAfter("/", "")
        val mirrors = when {
            path.startsWith("iframe/") -> resolveStreamMirrors(server)
            path.startsWith("download/") -> resolveDownloadMirrors(server)
            !isExtractorHost(server) -> resolveStreamMirrors(server)
            else -> emptyList()
        }
        if (mirrors.isNotEmpty()) {
            var found = false
            for (mirror in mirrors) {
                found = resolveOne(mirror, server, subtitleCallback, emit) || found
            }
            if (found) return true
        }
        return resolveOne(server, referer, subtitleCallback, emit)
    }

    /**
     * Our own host extractors. [loadExtractor] only ever calls the *first* extractor whose
     * `mainUrl` matches, and for hosts we share with a built-in (`vidmoly.biz`, `uqload.vc`,
     * ...) that ordering is not guaranteed, so a stale built-in can win and emit nothing.
     * Dispatching to our own extractor first removes that dependency entirely.
     */
    private val ownExtractors: List<ExtractorApi> = listOf(
        EgyDeadStreamWish(), EgyDeadStreamWishSite(), EgyDeadHgCloud(), EgyDeadHgLink(),
        EgyDeadEarnVids(), EgyDeadVidHide(), EgyDeadVidHideAlt(),
        EgyDeadStreamRubyCom(), EgyDeadStreamRubyNet(), EgyDeadStreamRubyVip(),
        EgyDeadVidmoly(), EgyDeadUqload(), EgyDeadVidara(), EgyDeadByse()
    )

    /** Hands a single embed URL to our own extractors, then CloudStream's built-ins. */
    private suspend fun resolveOne(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        emit: (ExtractorLink) -> Unit
    ): Boolean {
        val lowered = url.lowercase()
        val own = ownExtractors.firstOrNull {
            lowered.contains(it.mainUrl.substringAfter("://").trimEnd('/').lowercase())
        }
        if (own != null) {
            var emitted = false
            try {
                own.getUrl(url, referer, subtitleCallback) { link ->
                    emitted = true
                    emit(link)
                }
            } catch (e: Throwable) {
                Log.i(TAG, "own extractor failed $url -> $e")
            }
            if (emitted) return true
        }
        return try {
            loadExtractor(url, referer, subtitleCallback, emit)
        } catch (e: Throwable) {
            Log.i(TAG, "extractor failed $url -> $e")
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
    private suspend fun resolveStreamMirrors(iframeUrl: String): List<String> {
        val page = try {
            app.get(iframeUrl, headers = buildHeaders(mainUrl), timeout = 30).text
        } catch (e: Throwable) {
            return emptyList()
        }
        if (!page.contains("data-page=\"app\"") || !page.contains(IFRAME_HOST)) return emptyList()
        val version = Regex("\"version\":\"([^\"]+)\"").find(page)?.groupValues?.get(1)
            ?: return emptyList()
        val body = try {
            app.get(
                iframeUrl,
                headers = buildHeaders(iframeUrl) + mapOf(
                    "X-Inertia" to "true",
                    "X-Inertia-Version" to version,
                    "X-Inertia-Partial-Component" to IFRAME_HOST,
                    "X-Inertia-Partial-Data" to "streams",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "text/html, application/xhtml+xml"
                ),
                referer = iframeUrl,
                timeout = 30
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

    /**
     * Resolves `https://<host>/download/<hash>` into the list of real download mirror
     * URLs. Each quality is an "attachment"; POSTing its id returns the mirror list.
     * The site is a Laravel + Inertia app, so the request carries the XSRF token and
     * Inertia version harvested from the first GET.
     */
    private suspend fun resolveDownloadMirrors(downloadUrl: String): List<String> {
        val first = try {
            app.get(downloadUrl, headers = buildHeaders(mainUrl), timeout = 30)
        } catch (e: Throwable) {
            return emptyList()
        }
        val root = try {
            JSONObject(first.document.selectFirst("script[data-page=app]")?.data() ?: return emptyList())
        } catch (e: Throwable) {
            return emptyList()
        }
        val attachments = root.optJSONObject("props")?.optJSONArray("attachments") ?: return emptyList()
        if (attachments.length() == 0) return emptyList()
        val version = root.optString("version")

        val xsrf = first.cookies["XSRF-TOKEN"]?.let { raw ->
            if (raw.contains('%')) runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw) else raw
        }
        val cookieHeader = first.cookies.entries.filter { it.value.isNotBlank() }
            .joinToString("; ") { "${it.key}=${it.value}" }

        val out = LinkedHashSet<String>()
        for (i in 0 until attachments.length()) {
            val attId = attachments.optJSONObject(i)?.optString("hashId")?.takeIf { it.isNotBlank() } ?: continue
            val headers = mutableMapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html, application/xhtml+xml",
                "Content-Type" to "application/json",
                "X-Requested-With" to "XMLHttpRequest",
                "X-Inertia" to "true",
                "X-Inertia-Partial-Component" to DOWNLOAD_COMPONENT,
                "X-Inertia-Partial-Data" to "download"
            )
            if (version.isNotBlank()) headers["X-Inertia-Version"] = version
            if (!xsrf.isNullOrBlank()) headers["X-XSRF-TOKEN"] = xsrf
            if (cookieHeader.isNotBlank()) headers["Cookie"] = cookieHeader

            val mirrors = try {
                val resp = app.post(
                    downloadUrl,
                    headers = headers,
                    json = mapOf("attach" to attId),
                    referer = downloadUrl,
                    timeout = 30
                )
                JSONObject(resp.text).optJSONObject("props")?.optJSONObject("download")
                    ?.optJSONObject("data")?.optJSONArray("mirrors")
            } catch (e: Throwable) {
                null
            } ?: continue

            for (j in 0 until mirrors.length()) {
                var link = mirrors.optJSONObject(j)?.optString("link")?.trim().orEmpty()
                if (link.startsWith("//")) link = "https:$link"
                if (link.startsWith("http")) out.add(link)
            }
        }
        return out.toList()
    }
}
