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
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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
        private const val MAX_SEASON_PAGES = 20
        private const val MAX_SEARCH_PAGES = 100
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
                ?: li.selectFirst("a.postInner[href]")
                ?: li.selectFirst("a[href*=watch.php]")
                ?: return@mapNotNull null
            val href = anchor.attr("href").trim()
            if (href.isBlank()) return@mapNotNull null
            val title = anchor.attr("title").trim()
                .ifBlank { anchor.selectFirst("div.title")?.text()?.trim().orEmpty() }
                .ifBlank { anchor.text().trim() }
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
        var episodes = parseAllEpisodes(doc, url, title)

        // The detail page of a series only carries the season that was opened on the real site
        // (a `SeasonsBox` with one season button), while other seasons live on their own pages
        // with no link between them. Look the show up in the site search and merge the episodes of
        // every sibling season page that is still missing, so the whole series is exposed.
        if (looksLikeSeries(title, url)) {
            episodes = aggregateSeasons(title, url, episodes)
        }

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

    /**
     * Reads the episodes of a detail page: season-box templates first, then the plain episode
     * grid used by season/category pages (`category.php?cat=<Show>-S<NN>`, `mosalsalat.php`).
     */
    private fun parseAllEpisodes(doc: Document, url: String, title: String): List<Episode> {
        val hasSeasonBox = doc.selectFirst(
            "div.SeasonsBox, div.SeasonsEpisodesMain, div.SeasonsEpisodesMains, " +
                ".seasons-sec, .SeasonsEpisodes, .AiredEPS"
        ) != null
        var episodes = if (hasSeasonBox) parseEpisodes(doc, title) else emptyList()
        if (episodes.isEmpty() && looksLikeSeries(title, url)) {
            episodes = parseEpisodeGrid(doc, url)
        }
        return episodes
    }

    private fun parseEpisodes(doc: Document, title: String): List<Episode> {
        val out = mutableListOf<Episode>()
        val pageSeason = seasonFromText(title)

        // The panel id is only an index (`Season1`, `Season2`, …), not the real season number: the
        // tab whose episodes belong to "الموسم السادس" can still be `id="Season1"`. Map each tab id
        // to the season spelled out in its button ("الموسم 6") so the numbering is correct.
        val tabSeasons = doc.select("div.SeasonsBox button.tablinks[onclick]").mapNotNull { button ->
            val id = Regex("""openCity\([^,]+,\s*['"]([^'"]+)['"]\)""")
                .find(button.attr("onclick"))?.groupValues?.get(1)
            val season = seasonFromText(button.text())
            if (id != null && season != null) id to season else null
        }.toMap()

        // Variant A: season panels. Newer templates use div.SeasonsEpisodesMains (and render the
        // episodes as video cards, `a.postInner`, instead of a plain list of links); older ones use
        // div.SeasonsEpisodesMain with a bare `<ul>` of anchors.
        doc.select(
            "div.SeasonsEpisodesMain .tabcontent, div.SeasonsEpisodesMains .tabcontent, " +
                "div.tabcontent[id^=Season]"
        ).forEach { panel ->
            val season = tabSeasons[panel.id()]
                ?: panel.id().removePrefix("Season").toIntOrNull()
                ?: pageSeason ?: 1
            collectAnchors(panel, season, out)
        }

        // Variant B: .SeasonsEpisodes[data-season]
        doc.select(".SeasonsEpisodes").forEach { grp ->
            collectAnchors(grp, grp.attr("data-season").toIntOrNull() ?: pageSeason ?: 1, out)
        }

        // Variant C: div.AiredEPS (episodes of the page's own season)
        doc.select(".AiredEPS").forEach { grp ->
            collectAnchors(grp, pageSeason ?: 1, out)
        }

        return out.distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
    }

    /** Reads episode links from a season container (plain links or `.postInner` video cards). */
    private fun collectAnchors(container: Element, defaultSeason: Int, out: MutableList<Episode>) {
        container.select("a[href]").forEach { a ->
            val abs = fixUrl(a.attr("href"))
            if (!abs.contains("watch.php") && !abs.contains("play.php")) return@forEach
            val label = episodeLabel(a)
            // The episode title ("… الموسم السادس الحلقة 30 …") is the authoritative season; the
            // card badge and the container are only fallbacks.
            val cardSeason = a.selectFirst("div.topSpan2")?.text()?.let { seasonFromText(it) }
            val season = seasonFromText(label) ?: cardSeason ?: defaultSeason
            addEpisode(out, a.attr("href"), label, season)
        }
    }

    private fun episodeLabel(anchor: Element): String =
        anchor.attr("title").trim()
            .ifBlank { anchor.selectFirst("div.title")?.text()?.trim().orEmpty() }
            .ifBlank { anchor.text().trim() }

    /** Season/category pages expose the episodes in the same grid used by search/listing pages. */
    private fun parseEpisodeGrid(doc: Document, url: String): List<Episode> {
        val out = mutableListOf<Episode>()
        val urlSeason = seasonFromText(url)
        doc.select("ul.pm-ul-browse-videos > li, ul#pm-grid > li").forEach { li ->
            val anchor = li.selectFirst("div.caption h3 a[href]")
                ?: li.selectFirst("a.postInner[href]")
                ?: li.selectFirst("a[href*=watch.php]") ?: return@forEach
            val label = episodeLabel(anchor)
            if (!isEpisodeLabel(label)) return@forEach
            val cardSeason = li.selectFirst("div.topSpan2")?.text()?.let { seasonFromText(it) }
            addEpisode(out, anchor.attr("href"), label, seasonFromText(label) ?: cardSeason ?: urlSeason ?: 1)
        }
        return out.distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
    }

    /**
     * Merges the episodes of every season page that belongs to the same show as [title], so that
     * opening any season card yields the whole series. The site does not link seasons together, so
     * we page through the show's search results (the seasons are spread over several pages) and
     * collect:
     *  - season/series cards (a title without an episode number), and
     *  - one representative episode per season (a title with an episode number),
     * because on some series only episode results are returned while the season pages themselves
     * carry just a single season.
     */
    private suspend fun aggregateSeasons(
        title: String,
        url: String,
        current: List<Episode>
    ): List<Episode> {
        val base = baseSeriesName(title)
        if (base.length < 2) return current

        val q = URLEncoder.encode(base, "UTF-8").replace("+", "%20")
        val self = fixUrl(url)
        val knownSeasons = current.mapNotNull { it.season }.toSet()
        val seasonCards = LinkedHashMap<String, Triple<String, Int?, String>>()
        val episodeReps = LinkedHashMap<Int, Triple<String, Int?, String>>()
        val seenHrefs = HashSet<String>()
        var page = 1
        while (page <= MAX_SEARCH_PAGES) {
            val searchDoc = runCatching {
                app.get("$mainUrl/search.php?keywords=$q&page=$page", headers = HEADERS).document
            }.getOrNull() ?: break
            val anchors = searchDoc.select("a[href*=watch.php]")
            if (anchors.isEmpty()) break
            var newHrefs = 0
            anchors.forEach { a ->
                val hrefRaw = a.attr("href").trim()
                if (hrefRaw.isBlank() || !seenHrefs.add(hrefRaw)) return@forEach
                newHrefs++
                val label = episodeLabel(a)
                if (label.isBlank()) return@forEach
                if (!baseSeriesName(label).equals(base, ignoreCase = true)) return@forEach
                val href = fixUrl(hrefRaw)
                if (href == self) return@forEach
                val season = seasonFromText(label)
                if (isEpisodeLabel(label)) {
                    if (season != null) {
                        episodeReps[season] = Triple(href, season, label) // prefer newer/any episode card as rep
                    }
                } else {
                    seasonCards.putIfAbsent(href, Triple(href, season, label))
                }
            }
            if (newHrefs == 0) break
            page++
        }

        val targets = LinkedHashMap<String, Triple<String, Int?, String>>()
        val targetedSeasons = HashSet<Int>()
        seasonCards.values.forEach { ref ->
            val season = ref.second
            // A series page (no season in its title) usually holds every season, so always take it;
            // also take any season card found - the clicked card's page might be incomplete for other seasons.
            if (season == null || true) {
                if (targets.putIfAbsent(ref.first, ref) == null && season != null) {
                    targetedSeasons.add(season)
                }
            }
        }
        episodeReps.values.forEach { ref ->
            val season = ref.second
            if (season != null && season !in targetedSeasons) {
                targets.putIfAbsent(ref.first, ref)
            }
        }

        val siblings = targets.values.take(MAX_SEASON_PAGES * 2)
        if (siblings.isEmpty()) return current

        val extra = coroutineScope {
            siblings.map { (href, _, label) ->
                async(Dispatchers.IO) {
                    runCatching { app.get(href, headers = HEADERS).document }.getOrNull()
                        ?.let { parseAllEpisodes(it, href, label) } ?: emptyList()
                }
            }.awaitAll().flatten()
        }

        return (current + extra)
            .distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
    }

    /** Reduces a title to the show's own name (drops season/episode parts and site decorations). */
    private fun baseSeriesName(raw: String): String {
        var t = raw
        for (marker in listOf("الحلقة", "حلقة")) {
            val i = t.indexOf(marker)
            if (i > 0) {
                t = t.substring(0, i)
                break
            }
        }
        val seasonIndex = t.indexOf("الموسم")
        if (seasonIndex > 0) t = t.substring(0, seasonIndex)
        for (noise in listOf(
            "مشاهدة", "مسلسل", "انمي", "أنمي", "مترجم", "مترجمة", "كامل",
            "ايجي بست", "ماي سيما", "وي سيما", "الملفات", "MYCIMA", "WECIMA"
        )) {
            t = t.replace(noise, " ")
        }
        t = t.replace(Regex("""[.\-–—:،,()\[\]«»"'’]+"""), " ")
        return t.replace(Regex("""\s+"""), " ").trim()
    }

    private fun looksLikeSeries(title: String, url: String): Boolean =
        url.contains("category.php") || url.contains("categories-cimawbas.php") ||
            url.contains("mosalsalat.php") || url.contains("/series") ||
            title.contains("مسلسل") || title.contains("انمي") ||
            title.contains("الموسم") || title.contains("حلقة")

    private fun isEpisodeLabel(label: String): Boolean =
        label.contains("الحلقة") || label.contains("حلقة") ||
            Regex("""(?i)s\d{1,3}\s*e\d{1,3}""").containsMatchIn(label)

    private fun seasonFromText(text: String): Int? {
        Regex("""الموسم\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("""(?i)(?:^|[^a-z])s0*(\d{1,3})(?:[^0-9]|$)""").find(text)
            ?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        val ordinals = listOf(
            "الاول" to 1, "الأول" to 1, "الثانى" to 2, "الثاني" to 2, "الثالث" to 3, "الرابع" to 4,
            "الخامس" to 5, "السادس" to 6, "السابع" to 7, "الثامن" to 8, "التاسع" to 9, "العاشر" to 10,
            "الحادي عشر" to 11, "الثاني عشر" to 12, "الثالث عشر" to 13, "الرابع عشر" to 14,
            "الخامس عشر" to 15, "السادس عشر" to 16, "السابع عشر" to 17, "الثامن عشر" to 18,
            "التاسع عشر" to 19, "العشرون" to 20
        )
        val idx = text.indexOf("الموسم")
        if (idx >= 0) {
            val tail = text.substring(idx + 6).trim()
            ordinals.firstOrNull { tail.startsWith(it.first) }?.let { return it.second }
        }
        return null
    }

    private fun addEpisode(out: MutableList<Episode>, href: String, label: String, season: Int) {
        if (href.isBlank()) return
        val abs = fixUrl(href)
        if (!abs.contains("watch.php") && !abs.contains("play.php")) return
        val episode = Regex("""(?:الحلقة|حلقة|episode|ep)\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(label)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(?i)s\d+\s*e\s*(\d+)""").find(label)?.groupValues?.get(1)?.toIntOrNull()
            ?: run {
                val matches = Regex("""\d+""").findAll(label).map { it.value.toInt() }.toList()
                if (matches.size == 1) matches[0] else matches.lastOrNull()
            }
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

    private fun parseEmbeds(doc: Document): List<Pair<String, String>> =
        doc.select("[data-embed]").mapNotNull { el ->
            val embed = el.attr("data-embed").trim()
            if (embed.isBlank()) return@mapNotNull null
            val src = Regex("""src\s*=\s*['"]([^'"]+)['"]""").find(embed)?.groupValues?.get(1)?.trim()
            if (src.isNullOrBlank()) return@mapNotNull null
            val label = el.selectFirst("strong")?.text()?.trim().orEmpty()
                .ifBlank { el.attr("id").removePrefix("server_").ifBlank { "Server" } }
            src to label
        }.distinctBy { it.first }

    private suspend fun resolve(
        embed: String,
        server: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val host = runCatching { URI(embed).host?.lowercase() }.getOrNull() ?: ""
        val scraperHost = host.contains("liiivideo") || host.contains("minochinos") ||
            host.contains("vipserver")

        if (scraperHost) {
            val done = runCatching {
                CimawbasHostExtractor.extract(embed, server, referer, null, null, callback)
            }.getOrDefault(false)
            if (done) return true
        }

        val doneLoad = runCatching {
            loadExtractor(embed, referer, subtitleCallback, callback)
        }.getOrDefault(false)
        if (doneLoad) return true

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
