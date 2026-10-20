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
import com.lagradost.cloudstream3.network.WebViewResolver
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
import org.jsoup.Jsoup
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
        private const val CN_RC4_KEY = "588330764i"
        private const val CN_TW = 71
        // The ad-gate's `fp` field is base64("Mozilla/5.37"); the XOR key for its payload is
        // constant on the fixed layout. Both were verified against a real capture.
        private const val CN_GATE_FP = "TW96aWxsYS81LjM3"
        private val GATE_XOR_KEYS = listOf("21686", "5006")
    }

    override var name = "سيما ناو (CimaNow)"
    override var lang = "ar"
    override var mainUrl = "https://cimanow.cc"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val usesWebView = true
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
        val shine = postDoc?.let { shineHref(it) }
        println("[CimaNow] shine=$shine")

        val players = LinkedHashSet<String>()
        val downloads = LinkedHashMap<String, String>()

        // /watching/ needs the ad-gate `?token=`; reproduce the gate's HMAC in code to obtain the
        // tokenized URL (the gate host's Cloudflare blocks the system WebView).
        var watchUrl = postDoc?.let { watchUrlFromDoc(it) } ?: watchingUrl(data)
        var raw: String? = null
        if (!shine.isNullOrBlank()) {
            // Reproduce the gate's HMAC in code (cookie-aware); fall back to running the gate in
            // the WebView if that fails.
            val tokenUrl = resolveWatchToken(shine) ?: shine.let { resolveWatchTokenWebView(it) }
            println("[CimaNow] tokenUrl=$tokenUrl")
            if (!tokenUrl.isNullOrBlank()) {
                watchUrl = tokenUrl
                raw = fetchTokenized(tokenUrl)
            }
        }
        if (raw.isNullOrBlank()) {
            watchUrl = postDoc?.let { watchUrlFromDoc(it) } ?: watchingUrl(data)
            raw = fetchWatch(watchUrl, data)
        }
        println("[CimaNow] watchUrl=$watchUrl")

        val title = Regex("""<title[^>]*>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
            .find(raw ?: "")?.groupValues?.get(1)?.replace(Regex("""\s+"""), " ")?.trim()
        println(
            "[CimaNow] watch raw len=${raw?.length} enc=${raw?.contains("data-Ab8VaR3")} " +
                "dl=${raw?.contains("aria-label=\"quality\"")} vault=${raw?.contains("_getCoreToken")} title=$title"
        )
        val watchHtml = raw?.let { decryptWatch(it) }
        println("[CimaNow] watch html len=${watchHtml?.length}")

        if (!watchHtml.isNullOrBlank()) {
            val watchDoc = Jsoup.parse(watchHtml, watchUrl)
            collectSources(watchDoc, watchUrl, players, downloads)
            collectServerEmbeds(watchHtml, watchDoc, watchUrl, players)
        }

        // The watch page is sometimes gated and only serves the trailer; the post page may still
        // expose the same mirrors for a few hosts.
        if (players.isEmpty() && downloads.isEmpty()) {
            postDoc?.let { collectSources(it, data, players, downloads) }
        }

        println("[CimaNow] players=${players.size} downloads=${downloads.size}")
        if (players.isEmpty() && downloads.isEmpty()) {
            val snippet = (watchHtml ?: "").replace(Regex("""\s+"""), " ").take(500)
            println("[CimaNow] empty! snippet=$snippet")
            return false
        }

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

    private fun shineHref(doc: Document): String? =
        doc.selectFirst("a.shine[href*=loadon], a.shine[href*=freex2line]")
            ?.attr("href")?.trim()?.takeIf { it.startsWith("http") }

    /**
     * The episode's "watch" button points at an ad-gate (`rm.freex2line.online/loadon/`) that
     * eventually yields a tokenized `cimanow.cc/<slug>/watching/?token=...` URL. The gate's JS
     * computes an HMAC that we can reproduce without a WebView (the WebView is blocked by the
     * gate's Cloudflare). Flow: loadon -> href.li -> redirectingfree -> blog-post.html, whose
     * payload is XOR-obfuscated; after decoding it exposes a config whose `ke`/`se` yield the
     * HMAC key, then a POST to `get-link.php` returns the tokenized URL.
     */
    private suspend fun resolveWatchToken(shineUrl: String): String? {
        var cookie: String? = null
        suspend fun gateHeaders(): Map<String, String> = buildMap {
            put("User-Agent", CN_MOBILE_UA)
            put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            put("Accept-Language", "en-US,en;q=0.9,ar-MA;q=0.8,ar;q=0.7")
            put("X-Requested-With", "mark.via.gp")
            put("Upgrade-Insecure-Requests", "1")
            put("sec-ch-ua", "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"150\", \"Android WebView\";v=\"150\"")
            put("sec-ch-ua-mobile", "?1")
            put("sec-ch-ua-platform", "\"Android\"")
            put("Sec-Fetch-Site", "same-origin")
            put("Sec-Fetch-Mode", "navigate")
            put("Sec-Fetch-Dest", "document")
            cookie?.let { put("Cookie", it) }
        }
        suspend fun fetch(url: String, referer: String?): String? = runCatching {
            val res = app.get(url, headers = gateHeaders(), referer = referer, timeout = 60L)
            // Preserve the session cookie set by `loadon`; it is required for the gate page.
            res.headers.values("Set-Cookie").forEach { sc ->
                val pair = sc.substringBefore(';').trim()
                if (pair.contains("PHPSESSID=")) cookie = pair
            }
            println("[CimaNow] gate GET $url -> ${res.code} len=${res.text.length} cookie=${cookie?.take(40)}")
            res.text
        }.onFailure { println("[CimaNow] gate GET $url FAILED: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()

        return try {
            // `loadon` -> `https://href.li/?https://rm.freex2line.online/redirectingfree/`.
            // `href.li` is a plain redirector that 403s non-browser clients, so unwrap it and go
            // straight to the real target.
            val loadon = fetch(shineUrl, "$mainUrl/") ?: return null
            val next1 = extractRedirect(loadon) ?: run {
                println("[CimaNow] gate: no redirect in loadon (len=${loadon.length})")
                return null
            }
            val redirectFree = unwrapHrefLi(next1)
            val redirectPage = fetch(redirectFree, "$mainUrl/") ?: return null
            val next3 = extractRedirect(redirectPage)
            // Keep the trailing slash: `.../blog-post.html/` serves the gate page (the one with
            // `TKFetpf`); without it the server returns a plain Blogger decoy.
            val blogUrl = unwrapHrefLi(next3 ?: redirectFree).trimEnd('/') + "/"
            // POSTs go to `<blog-url>get-link.php`, i.e. `.../blog-post.html/get-link.php`.
            val blogBase = blogUrl.trimEnd('/')
            val blog = fetch(blogUrl, redirectFree) ?: return null
            val gate = solveGateToken(blog) ?: run {
                println("[CimaNow] gate: solveGateToken failed (blog len=${blog.length})")
                return null
            }
            println("[CimaNow] gate: requestId=${gate.requestId} ch=${gate.ch} hmac=${gate.hmacToken}")
            val postRes = app.post(
                "$blogBase/get-link.php",
                data = mapOf(
                    "request_id" to gate.requestId,
                    "hmac_token" to gate.hmacToken,
                    "ch" to gate.ch,
                    "fp" to CN_GATE_FP
                ),
                headers = gateHeaders() + mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Origin" to origin(blogUrl),
                    "Referer" to blogUrl
                ),
                referer = blogUrl,
                timeout = 60L
            )
            val locHeader = postRes.headers.values("Location").firstOrNull()
            val post = postRes.text.trim().trimStart('\uFEFF').trim()
            println("[CimaNow] gate post code=${postRes.code} location=$locHeader len=${post.length}")
            println("[CimaNow] gate post resp=${post.take(600).replace("\n", " ")}")
            // The POST may answer with the tokenized URL directly, a `Location` header, or a small
            // HTML redirect page. Extract the tokenized `/watching/?token=...` URL from any of them.
            val candidates = listOfNotNull(locHeader, extractRedirect(post), post.takeIf { it.startsWith("http") })
            candidates.firstOrNull { it.contains("/watching", true) && it.contains("token=") }
        } catch (e: Exception) {
            println("[CimaNow] gate EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * Resolves the gate's `blog-post.html/` URL by following the redirect chain (OkHttp reaches
     * it fine, but the server serves a decoy unless the page's JS runs). Returns null on failure.
     */
    private suspend fun resolveGateUrl(shineUrl: String): String? {
        val gateHeaders = mapOf(
            "User-Agent" to CN_MOBILE_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9,ar-MA;q=0.8,ar;q=0.7",
            "X-Requested-With" to "mark.via.gp",
            "Upgrade-Insecure-Requests" to "1",
            "sec-ch-ua" to "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"150\", \"Android WebView\";v=\"150\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Dest" to "document"
        )
        suspend fun fetch(url: String, referer: String?): String? = runCatching {
            val res = app.get(url, headers = gateHeaders, referer = referer, timeout = 60L)
            val ct = res.headers.values("Content-Type").joinToString(";").take(60)
            val sc = res.headers.values("Set-Cookie").joinToString(";").take(120)
            val server = res.headers.values("Server").joinToString(";").take(40)
            println("[CimaNow] gate GET $url -> ${res.code} len=${res.text.length} ct=$ct server=$server setcookie=$sc")
            res.text
        }.onFailure { println("[CimaNow] gate GET $url FAILED: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()

        return try {
            val loadon = fetch(shineUrl, "$mainUrl/") ?: return null
            val next1 = extractRedirect(loadon) ?: return null
            val redirectFree = unwrapHrefLi(next1)
            val redirectPage = fetch(redirectFree, "$mainUrl/") ?: return null
            val next3 = extractRedirect(redirectPage)
            unwrapHrefLi(next3 ?: redirectFree).trimEnd('/') + "/"
        } catch (e: Exception) {
            println("[CimaNow] gate url EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * Runs the ad-gate inside CloudStream's WebView. The gate (`blog-post.html/`) shows a 10s
     * countdown then auto-submits `get-link.php`, navigating to `cimanow.cc/<slug>/watching/?token=`.
     * The resolver stops as soon as the WebView requests that tokenized URL and returns it.
     *
     * [startUrl] is a URL whose page is the gate itself (e.g. the `blog-post.html/` redirect target).
     */
    private suspend fun resolveWatchTokenWebView(startUrl: String): String? {
        return runCatching {
            val resolver = WebViewResolver(
                // Stop once the WebView navigates to the tokenized watch URL.
                interceptUrl = Regex(""".*/watching/\?token=[a-fA-F0-9]+"""),
                userAgent = CN_MOBILE_UA,
                useOkhttp = false,
                timeout = 60_000L
            )
            val (request, _) = resolver.resolveUsingWebView(startUrl, "$mainUrl/", "GET") { false }
            val result = request?.url?.toString()?.trim()
            println("[CimaNow] webview token=$result")
            result?.takeIf { it.contains("/watching") && it.contains("token=") }
        }.onFailure { println("[CimaNow] webview FAILED: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()
    }

    private data class GateToken(val requestId: String, val ch: String, val hmacToken: String)

    /** Reproduces the gate's HMAC token from the decoded `blog-post.html` payload. */
    private fun solveGateToken(blog: String): GateToken? {
        println("[CimaNow] gate blog: ctx=${blog.contains("ctx_")} map=${blog.contains("map_")} len=${blog.length}")
        // The gate page may either expose the config directly (randomized `ctx_XXXX`/`map_XXXX`
        // names + a random base64-chunk array) or hide it behind a `TKFetpf` XOR layer. Try the
        // direct parse first, then fall back to decoding the chunk array.
        parseGateConfig(blog)?.let { return it }
        val payload = extractPayload(blog) ?: run {
            println("[CimaNow] gate blog: no payload array; head=${blog.take(400).replace("\n", " ")}")
            return null
        }
        println("[CimaNow] gate payload len=${payload.size}")
        val page = gateDecodePayload(payload) ?: run {
            println("[CimaNow] gate: no key decoded payload head=${String(payload.copyOfRange(0, minOf(120, payload.size)), Charsets.ISO_8859_1)}")
            return null
        }
        println("[CimaNow] gate decoded page head=${page.take(400).replace("\n", " ")}")
        return parseGateConfig(page)
    }

    /**
     * Parses `window['ctx_XXXX'] = { v_a: '..', ... }` using the `window.map_YYYY =
     * { ch:'v_a', ri:'v_b', ke:'v_c', se:'v_d' }` indirection (names are randomized per request).
     */
    private fun parseGateConfig(page: String): GateToken? {
        val mapMatch = Regex("""\{[^{}]*\b(ch|ri|ke|se)\s*:[^{}]*\}""").findAll(page)
            .firstOrNull { it.value.contains("ch") && it.value.contains("ri") && it.value.contains("ke") }
            ?: return null
        val map = mapMatch.value
        fun ref(key: String): String? =
            Regex("""['"]?""" + key + """['"]?\s*:\s*['"](v_[0-9a-f]+)['"]""").find(map)?.groupValues?.get(1)
        val chRef = ref("ch") ?: return null
        val riRef = ref("ri") ?: return null
        val keRef = ref("ke") ?: return null
        val seRef = ref("se") ?: return null
        val ch = findJsonish(page, chRef) ?: return null
        val requestId = findJsonish(page, riRef) ?: return null
        val ke = findJsonish(page, keRef) ?: return null
        val se = findJsonish(page, seRef) ?: return null
        val keyBytes = xorRepeating(decodeBase64(ke) ?: return null, se.toByteArray(Charsets.ISO_8859_1))
        val key = String(keyBytes, Charsets.ISO_8859_1)
        val msg = (requestId + ch + CN_GATE_FP).toByteArray(Charsets.ISO_8859_1)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key.toByteArray(Charsets.ISO_8859_1), "HmacSHA256"))
        val hmacToken = encodeBase64(mac.doFinal(msg)) ?: return null
        println("[CimaNow] gate config: requestId=$requestId ch=$ch")
        return GateToken(requestId, ch, hmacToken)
    }

    /** Finds the random `window["NAME"] = [ "base64", ... ]` chunk array and joins it. */
    private fun extractPayload(blog: String): ByteArray? {
        val arrMatch = Regex("""window\[["'][A-Za-z0-9_]+["']\]\s*=\s*\[(.*?)\];""", RegexOption.DOT_MATCHES_ALL)
            .find(blog) ?: return null
        val chunks = Regex(""""([^"]*)"""").findAll(arrMatch.groupValues[1]).map { it.groupValues[1] }.toList()
        if (chunks.isEmpty()) return null
        return decodeBase64(chunks.joinToString(""))
    }

    private fun gateDecodePayload(payload: ByteArray): String? {
        for (candidate in GATE_XOR_KEYS) {
            val kb = candidate.toByteArray(Charsets.ISO_8859_1)
            val decoded = xorRepeating(payload, kb).toString(Charsets.UTF_8)
            if (decoded.contains("v_41e5") || decoded.contains("request_id")) return decoded
        }
        return null
    }

    private fun findJsonish(page: String, key: String): String? =
        Regex("""['"]?""" + Regex.escape(key) + """['"]?\s*[:=]\s*['"]([^'"]+)['"]""")
            .find(page)?.groupValues?.get(1)

    private fun extractRedirect(html: String): String? =
        Regex("""location\.(?:href|replace)\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            ?: Regex("""url=([^"' >]+)""").find(html)?.groupValues?.get(1)
            ?: Regex("""content="0;\s*url=([^"]+)""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)

    /** `https://href.li/?<target>` -> `<target>`; anything else is returned as-is. */
    private fun unwrapHrefLi(url: String): String {
        val idx = url.indexOf("href.li/?")
        if (idx < 0) return url
        val target = url.substring(idx + "href.li/?".length)
        return if (target.startsWith("http")) target else url
    }

    private val watchProfiles = listOf(
        mapOf(
            "User-Agent" to CN_MOBILE_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Dest" to "document"
        ),
        CN_HEADERS,
        mapOf("User-Agent" to "Mozilla/5.0")
    )

    private suspend fun fetchTokenized(url: String): String? {
        var fallback: String? = null
        for (profile in watchProfiles) {
            val body = runCatching { app.get(url, headers = profile, referer = "$mainUrl/").text }.getOrNull()
            if (body.isNullOrBlank()) continue
            if (fallback == null) fallback = body
            if (body.contains("data-Ab8VaR3") || body.contains("aria-label=\"quality\"") ||
                body.contains("_getCoreToken")
            ) {
                return body
            }
        }
        return fallback
    }

    private suspend fun fetchWatch(url: String, referer: String): String? {
        var fallback: String? = null
        for (profile in watchProfiles) {
            val body = runCatching { app.get(url, headers = profile, referer = referer).text }.getOrNull()
            if (body.isNullOrBlank()) continue
            if (fallback == null) fallback = body
            if (body.contains("data-Ab8VaR3") || body.contains("aria-label=\"quality\"") ||
                body.contains("\"quality\"") || body.contains("_getCoreToken")
            ) {
                return body
            }
        }
        return fallback
    }

    /**
     * The `/watching/` page ships as an RC4-obfuscated blob: the payload lives in
     * `data-Ab8VaR3`, is reversed, base64 decoded and then XOR-decoded with a static
     * RC4 keystream (key `588330764i`, index offset 71). The plaintext is UTF-8 with a
     * fixed 34-character prefix that is stripped.
     */
    private fun decryptWatch(html: String): String {
        if (!html.contains("data-Ab8VaR3")) return html
        val attr = Regex("""data-Ab8VaR3=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: return html
        val data = runCatching { Base64.decode(attr.reversed(), Base64.DEFAULT) }.getOrNull()
            ?: return html

        val key = CN_RC4_KEY.toByteArray(Charsets.US_ASCII)
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        var i = 0
        j = 0
        val out = ByteArray(data.size)
        for (y in data.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
            val k = s[(s[i] + s[j]) and 0xFF]
            out[y] = ((data[y].toInt() and 0xFF) xor (k xor ((i + CN_TW) and 0xFF))).toByte()
        }
        val text = String(out, Charsets.UTF_8)
        return if (text.length > 34) text.substring(34) else text
    }

    private fun coreToken(html: String): String? {
        val b64 = Regex("""window\.atob\(["']([^"']+)["']\)""").find(html)?.groupValues?.get(1)
            ?: return null
        return runCatching { String(Base64.decode(b64, Base64.DEFAULT)) }.getOrNull()
    }

    /** Calls `core.php?action=switch` for every server tab to expose all player embeds. */
    private suspend fun collectServerEmbeds(
        html: String,
        doc: Document,
        referer: String,
        players: MutableSet<String>
    ) {
        val token = coreToken(html) ?: return
        val tabs = doc.select("#watch li[data-id][data-index], ul#watch li[data-id][data-index]")
            .mapNotNull { li ->
                val id = li.attr("data-id").trim()
                val index = li.attr("data-index").trim()
                if (id.isBlank() || index.isBlank()) null else id to index
            }.distinctBy { it.second }
        println("[CimaNow] coreToken=${token.take(8)}.. tabs=${tabs.size}")
        if (tabs.isEmpty()) return

        val responses = coroutineScope {
            tabs.map { (id, index) ->
                async(Dispatchers.IO) {
                    val url = "$mainUrl/wp-content/themes/Cima%20Now%20New/core.php" +
                        "?token=$token&action=switch&index=$index&id=$id"
                    runCatching { app.get(url, referer = referer, headers = CN_HEADERS).text }.getOrNull()
                }
            }.awaitAll()
        }
        responses.forEach { resp ->
            Regex("""src=["']([^"']+)["']""").find(resp ?: "")?.groupValues?.get(1)?.let { src ->
                if (src.startsWith("http") && !isTrailer(src)) players.add(src)
            }
        }
        println("[CimaNow] after core.php players=${players.size}")
    }

    private fun decodeJetload(href: String): String? {
        if (!href.contains("jetload", true)) return null
        val m = Regex("""[?&]link=([^&]+)""").find(href) ?: return null
        val raw = runCatching { URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrDefault(m.groupValues[1])
        for (flags in listOf(Base64.URL_SAFE, Base64.DEFAULT)) {
            val v = runCatching { String(Base64.decode(raw, flags)) }.getOrNull()
            if (v != null && v.startsWith("http")) return v.trim()
        }
        return null
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
            val direct = decodeJetload(href)
            when {
                direct != null -> downloads[direct] = label
                isDownload(href) -> downloads[href] = label
                !players.contains(href) -> players.add(href)
            }
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
                embed.contains("cimanowtv.com") -> CimaNowCimaNow()
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

    private fun decodeBase64(value: String): ByteArray? = runCatching {
        Base64.decode(value.trim(), Base64.DEFAULT)
    }.getOrNull()

    private fun encodeBase64(value: ByteArray): String? = runCatching {
        Base64.encodeToString(value, Base64.NO_WRAP)
    }.getOrNull()

    private fun xorRepeating(data: ByteArray, key: ByteArray): ByteArray {
        if (key.isEmpty()) return data
        val out = ByteArray(data.size)
        for (i in data.indices) out[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
        return out
    }

    private fun origin(url: String): String =
        runCatching { java.net.URI(url) }
            .map { "${it.scheme}://${it.host}" }
            .getOrElse { mainUrl }
}
