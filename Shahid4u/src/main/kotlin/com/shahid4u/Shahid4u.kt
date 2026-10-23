package com.shahid4u

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.getQualityFromString
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class Shahid4u : MainAPI() {

    override var mainUrl: String
        get() = Shahid4uNetwork.mainUrl
        set(_) {}

    override var name = "شاهد فور يو (Shahid4u)"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "ar"

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.Anime,
        TvType.AnimeMovie
    )

    override val mainPage = mainPageOf(
        "" to "الأحدث (الرئيسية)",
        "?order=pin_index" to "المثبت",
        "?order=last_eps" to "جديد الحلقات",
        "?order=last_films" to "جديد الأفلام",
        "?order=views" to "الأكثر مشاهدة",
        "?order=rating" to "الأعلى تقييماً",
        "category/افلام-اجنبي" to "أفلام أجنبي",
        "category/افلام-انمي" to "أفلام أنمي",
        "category/افلام-اسيوية" to "أفلام آسيوية",
        "category/افلام-تركية" to "أفلام تركية",
        "category/افلام-هندي" to "أفلام هندي",
        "category/افلام-عربي" to "أفلام عربي",
        "category/مسلسلات-اجنبي" to "مسلسلات أجنبي",
        "category/مسلسلات-انمي" to "مسلسلات أنمي",
        "category/مسلسلات-تركية" to "مسلسلات تركية",
        "category/مسلسلات-اسيوية" to "مسلسلات آسيوية",
        "category/مسلسلات-مدبلجة" to "مسلسلات مدبلجة",
        "category/مسلسلات-عربي" to "مسلسلات عربي",
        "category/مسلسلات-هندية" to "مسلسلات هندية",
        "category/مسلسلات-رمضان-2026" to "مسلسلات رمضان 2026",
        "category/برامج-تلفزيونية" to "برامج تلفزيونية",
        "category/عروض-مصارعة" to "عروض مصارعة"
    )

    companion object {
        // Compound Arabic ordinals ordered longest-first so "الثاني عشر" (12) matches before "الثاني" (2)
        private val ARABIC_SEASON_ORDINALS: List<Pair<String, Int>> = listOf(
            "الحادي والعشرون" to 21, "العشرون" to 20,
            "التاسع عشر" to 19, "الثامن عشر" to 18, "السابع عشر" to 17, "السادس عشر" to 16,
            "الخامس عشر" to 15, "الرابع عشر" to 14, "الثالث عشر" to 13,
            "الثاني عشر" to 12, "الثانى عشر" to 12, "الحادي عشر" to 11, "الحادى عشر" to 11,
            "العاشر" to 10, "التاسع" to 9, "الثامن" to 8, "السابع" to 7, "السادس" to 6,
            "الخامس" to 5, "الرابع" to 4, "الثالث" to 3,
            "الثاني" to 2, "الثانى" to 2,
            "الأول" to 1, "الاول" to 1
        )

        private val BG_URL_REGEX = Regex("""url\(\s*['"]?([^'")\s]+)['"]?\s*\)""", RegexOption.IGNORE_CASE)
        private val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2})\b""")
        private val EP_NUMBER_REGEX = Regex("""(?:الحلقة|حلقة|episode|ep)[\s\-_:]*(\d{1,4})""", RegexOption.IGNORE_CASE)
        private val SEASON_NUMBER_REGEX = Regex("""(?:الموسم|موسم|season)[\s\-_:]*(\d{1,3})""", RegexOption.IGNORE_CASE)

        private data class RawEpisodeEntry(
            val url: String,
            val seasonNum: Int,
            val epNum: Int?,
            val label: String
        )

        private val seasonEpisodesCache = ConcurrentHashMap<String, List<RawEpisodeEntry>>()

        private fun normalizeArabicDigits(text: String): String {
            val sb = StringBuilder(text.length)
            for (c in text) {
                when (c) {
                    in '٠'..'٩' -> sb.append(('0'.code + (c.code - '٠'.code)).toChar())
                    in '۰'..'۹' -> sb.append(('0'.code + (c.code - '۰'.code)).toChar())
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun decodeSlug(url: String): String =
            runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
    }

    // ── Homepage & Categories ────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val spec = request.data.trim().trimStart('/')
        val pageUrl = buildListingUrl(spec, page)
        val fetched = Shahid4uNetwork.getPage(pageUrl)
            ?: return newHomePageResponse(HomePageList(request.name, emptyList()), false)

        val doc = fetched.document
        val seenUrls = LinkedHashSet<String>()
        val items = ArrayList<SearchResponse>()

        // Primary `.shows-container` grid
        doc.select(".shows-container a.show-card, .container .row a.show-card").forEach { card ->
            val parsed = parseShowCard(card) ?: return@forEach
            if (seenUrls.add(parsed.url)) items.add(parsed)
        }

        // On page 1 of main homepage, include pinned/featured `.glide` slider cards if not already present
        if (spec.isEmpty() && page == 1) {
            doc.select(".glide a.show-card").forEach { card ->
                val parsed = parseShowCard(card) ?: return@forEach
                if (seenUrls.add(parsed.url)) items.add(parsed)
            }
        }

        val hasNext = hasNextPage(doc, page, items.size)
        return newHomePageResponse(HomePageList(request.name, items), hasNext)
    }

    private fun buildListingUrl(spec: String, page: Int): String {
        val base = mainUrl.trimEnd('/')
        return when {
            spec.isEmpty() -> if (page <= 1) "$base/" else "$base/?page=$page"
            spec.startsWith("?") -> if (page <= 1) "$base/$spec" else "$base/$spec&page=$page"
            else -> if (page <= 1) "$base/$spec" else "$base/$spec?page=$page"
        }
    }

    private fun hasNextPage(doc: Document, currentPage: Int, itemCount: Int): Boolean {
        if (itemCount == 0) return false
        val nextTarget = "page=${currentPage + 1}"
        if (doc.select("ul.pagination a.page-link[href]").any { it.attr("href").contains(nextTarget) }) {
            return true
        }
        return doc.selectFirst("ul.pagination li.page-item:last-child:not(.disabled) a.page-link") != null
    }

    // ── Search (Single-Page + Paginated + QuickSearch) ───────────────────────

    override suspend fun quickSearch(query: String): List<SearchResponse> =
        search(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return newSearchResponseList(emptyList(), false)

        val encodedQ = URLEncoder.encode(trimmed, "UTF-8")
        val searchUrl = if (page <= 1) {
            "$mainUrl/search?s=$encodedQ"
        } else {
            "$mainUrl/search?s=$encodedQ&page=$page"
        }

        val fetched = Shahid4uNetwork.getPage(searchUrl) ?: return newSearchResponseList(emptyList(), false)
        val doc = fetched.document
        val seen = LinkedHashSet<String>()
        val results = ArrayList<SearchResponse>()

        doc.select(".shows-container a.show-card, a.show-card").forEach { card ->
            val parsed = parseShowCard(card) ?: return@forEach
            if (seen.add(parsed.url)) results.add(parsed)
        }

        val hasNext = hasNextPage(doc, page, results.size)
        return newSearchResponseList(results, hasNext)
    }

    // ── Card & Poster Extraction ─────────────────────────────────────────────

    private fun parseShowCard(card: Element): SearchResponse? {
        if (Shahid4uNetwork.isHiddenOrTrapElement(card)) return null
        val rawHref = card.attr("href").trim()
        val url = Shahid4uNetwork.resolveUrl(rawHref, mainUrl) ?: return null
        if (Shahid4uNetwork.isCanaryUrl(url) || url.contains("/m/")) return null

        val rawTitle = card.selectFirst("p.title, h5.title, .title")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: card.selectFirst("img[alt]")?.attr("alt")?.trim()
            ?: return null

        val cleanName = cleanDisplayTitle(rawTitle, keepEpisodeLabel = false)
            .ifBlank { rawTitle }

        val poster = extractSafePoster(card)
        val categoryBadge = card.selectFirst("span.categ, .categ")?.text()?.trim().orEmpty()
        val epText = card.selectFirst("span.ep, .ep")?.text()?.trim().orEmpty()
        val rateVal = card.selectFirst("span.rate, .rate")?.text()?.trim()
            ?.let { normalizeArabicDigits(it).toDoubleOrNull() }
        val qualText = card.selectFirst("span.qual, .quality")?.text()?.trim()

        val epNumber = extractEpisodeNumber(epText.ifBlank { rawTitle }, url)
        val isDubbed = rawTitle.contains("مدبلج") || categoryBadge.contains("مدبلج")
        val tvType = classifyTvType(url, rawTitle, categoryBadge)

        return when (tvType) {
            TvType.Movie, TvType.AnimeMovie -> {
                newMovieSearchResponse(cleanName, url, tvType) {
                    this.posterUrl = poster
                    if (rateVal != null && rateVal > 0.0) this.score = Score.from10(rateVal)
                    qualText?.let { this.quality = getQualityFromString(it) }
                }
            }
            TvType.Anime -> {
                newAnimeSearchResponse(cleanName, url, TvType.Anime) {
                    this.posterUrl = poster
                    if (rateVal != null && rateVal > 0.0) this.score = Score.from10(rateVal)
                    qualText?.let { this.quality = getQualityFromString(it) }
                    if (epNumber != null) {
                        addDubStatus(isDubbed, epNumber)
                    }
                }
            }
            else -> {
                if (epNumber != null) {
                    newAnimeSearchResponse(cleanName, url, TvType.TvSeries) {
                        this.posterUrl = poster
                        if (rateVal != null && rateVal > 0.0) this.score = Score.from10(rateVal)
                        qualText?.let { this.quality = getQualityFromString(it) }
                        addDubStatus(isDubbed, epNumber)
                    }
                } else {
                    newTvSeriesSearchResponse(cleanName, url, TvType.TvSeries) {
                        this.posterUrl = poster
                        if (rateVal != null && rateVal > 0.0) this.score = Score.from10(rateVal)
                        qualText?.let { this.quality = getQualityFromString(it) }
                    }
                }
            }
        }
    }

    private fun extractSafePoster(root: Element): String? {
        // 1. Safe `<img>` attributes
        root.select("img").forEach { img ->
            for (attr in listOf("data-src", "data-lazy-src", "data-original", "src")) {
                val cand = Shahid4uNetwork.resolveUrl(img.attr(attr), mainUrl)
                if (Shahid4uNetwork.isSafePosterUrl(cand)) return cand
            }
        }
        // 2. CSS `--background-image-url: url(...)` or `background-image: url(...)`
        val styledTargets = buildList {
            add(root)
            addAll(root.select(".poster, [style*=background]"))
        }
        for (el in styledTargets) {
            val style = el.attr("style")
            if (style.isNotBlank()) {
                BG_URL_REGEX.findAll(style).forEach { m ->
                    val cand = Shahid4uNetwork.resolveUrl(m.groupValues[1], mainUrl)
                    if (Shahid4uNetwork.isSafePosterUrl(cand)) return cand
                }
            }
        }
        return null
    }

    private fun extractPagePoster(doc: Document): String? {
        doc.selectFirst(".poster-side .poster, .show-main .poster, .poster")?.let { p ->
            extractSafePoster(p)?.let { return it }
        }
        doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { og ->
            val resolved = Shahid4uNetwork.resolveUrl(og, mainUrl)
            if (Shahid4uNetwork.isSafePosterUrl(resolved)) return resolved
        }
        return null
    }

    // ── Load Details (Movies, Series, Seasons, Episodes) ─────────────────────

    override suspend fun load(url: String): LoadResponse? = coroutineScope {
        val normalizedUrl = normalizeContentUrl(url)
        val page = Shahid4uNetwork.getPage(normalizedUrl) ?: return@coroutineScope null
        val doc = page.document
        val finalUrl = page.finalUrl

        val rawTitle = doc.selectFirst(".info-side .title, h1.title, h1")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: doc.title().substringBefore(" - شاهد فور يو").trim()
        if (rawTitle.isEmpty()) return@coroutineScope null

        var poster = extractPagePoster(doc)
        val description = doc.selectFirst(".info-side .description, .description")?.text()?.trim()
        val year = doc.selectFirst(".info-side a[href*=/release-year/]")?.text()?.trim()
            ?.let { normalizeArabicDigits(it).toIntOrNull() }
            ?: YEAR_REGEX.find(normalizeArabicDigits(rawTitle))?.groupValues?.get(1)?.toIntOrNull()

        val categoryText = doc.select(".info-side a[href*=/category/]").joinToString(" ") { it.text().trim() }
        val genres = LinkedHashSet<String>().apply {
            doc.select(".info-side a[href*=/genre/]").forEach { a ->
                a.text().trim().takeIf { it.isNotEmpty() }?.let { add(it) }
            }
            doc.select(".info-side a[href*=/category/]").forEach { a ->
                a.text().trim().takeIf { it.isNotEmpty() }?.let { add(it) }
            }
            doc.select(".info-side .qualities a").forEach { a ->
                a.text().trim().takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }.toList()

        val ratingVal = doc.select(".info-side span, .rate")
            .mapNotNull { el ->
                val t = normalizeArabicDigits(el.ownText().trim())
                if (t.matches(Regex("""^\d(\.\d{1,2})?$"""))) t.toDoubleOrNull() else null
            }
            .firstOrNull { it in 1.0..10.0 }

        val actors = parseCastAndCrew(doc)
        val recommendations = doc.select(".glide a.show-card")
            .mapNotNull { parseShowCard(it) }
            .distinctBy { it.url }

        val tvType = classifyTvType(finalUrl, rawTitle, categoryText)
        val pathLow = URI(finalUrl).path.lowercase()
        val hasSeasonsOrEpisodes = doc.selectFirst("a.epss[href*=/episode/], a.epss[href*=/season/]") != null
        val isSeriesLike = pathLow.contains("/series/") ||
            pathLow.contains("/season/") ||
            pathLow.contains("/episode/") ||
            hasSeasonsOrEpisodes

        if (!isSeriesLike || pathLow.contains("/film/") || pathLow.contains("/movie/")) {
            val movieTitle = cleanDisplayTitle(rawTitle, keepEpisodeLabel = false).ifBlank { rawTitle }
            val movieType = if (tvType == TvType.Anime || tvType == TvType.AnimeMovie) TvType.AnimeMovie else TvType.Movie
            return@coroutineScope newMovieLoadResponse(movieTitle, finalUrl, movieType, finalUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = genres
                if (ratingVal != null) this.score = Score.from10(ratingVal)
                if (actors.isNotEmpty()) this.actors = actors
                if (recommendations.isNotEmpty()) this.recommendations = recommendations
            }
        }

        // ── Series / Season / Episode Resolution ─────────────────────────────
        val seasonsMap = LinkedHashMap<String, Pair<Int, String>>() // seasonUrl -> (seasonNum, label)

        fun collectSeasonsFrom(d: Document) {
            d.select("a.epss[href*=/season/]").forEach { a ->
                if (Shahid4uNetwork.isHiddenOrTrapElement(a)) return@forEach
                val sUrl = Shahid4uNetwork.resolveUrl(a.attr("href"), mainUrl) ?: return@forEach
                if (Shahid4uNetwork.isCanaryUrl(sUrl)) return@forEach
                val label = a.text().trim()
                val sNum = extractSeasonNumber(label, sUrl) ?: (seasonsMap.size + 1)
                seasonsMap.putIfAbsent(sUrl, sNum to label)
            }
        }

        collectSeasonsFrom(doc)

        // Check parent `/series/<slug>` link if present to pick up series-level title/poster & all seasons
        var seriesDisplayTitle = extractSeriesTitleFromBreadcrumbs(doc)
            ?: cleanSeriesRootTitle(rawTitle)
        val parentSeriesUrl = doc.selectFirst("a[href*=/series/]")
            ?.let { Shahid4uNetwork.resolveUrl(it.attr("href"), mainUrl) }
            ?.takeIf { !Shahid4uNetwork.isCanaryUrl(it) }

        if (parentSeriesUrl != null && !finalUrl.equals(parentSeriesUrl, ignoreCase = true) && seasonsMap.isEmpty()) {
            Shahid4uNetwork.getPage(parentSeriesUrl, referer = finalUrl)?.let { seriesPage ->
                collectSeasonsFrom(seriesPage.document)
                if (poster == null) poster = extractPagePoster(seriesPage.document)
                seriesPage.document.selectFirst(".info-side .title, h1")?.text()?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { seriesDisplayTitle = cleanSeriesRootTitle(it) }
            }
        }

        val currentSeasonUrl = doc.selectFirst("a[href*=/season/]:not(.epss)")
            ?.let { Shahid4uNetwork.resolveUrl(it.attr("href"), mainUrl) }
            ?: if (pathLow.contains("/season/")) finalUrl else null

        val currentSeasonNum = extractSeasonNumber(rawTitle, currentSeasonUrl ?: finalUrl) ?: 1
        val currentInlineEpisodes = parseInlineEpisodes(doc, currentSeasonNum)
        if (currentSeasonUrl != null && currentInlineEpisodes.isNotEmpty()) {
            seasonEpisodesCache[currentSeasonUrl] = currentInlineEpisodes
        }

        val allRawEpisodes = ArrayList<RawEpisodeEntry>()

        if (seasonsMap.isEmpty()) {
            if (currentInlineEpisodes.isNotEmpty()) {
                allRawEpisodes.addAll(currentInlineEpisodes)
            } else if (pathLow.contains("/episode/")) {
                allRawEpisodes.add(
                    RawEpisodeEntry(
                        url = finalUrl,
                        seasonNum = currentSeasonNum,
                        epNum = extractEpisodeNumber(rawTitle, finalUrl) ?: 1,
                        label = rawTitle
                    )
                )
            }
        } else {
            val sem = Semaphore(3)
            val perSeasonLists = seasonsMap.entries.map { (sUrl, meta) ->
                val (sNum, _) = meta
                async {
                    if (currentSeasonUrl != null &&
                        sUrl.equals(currentSeasonUrl, ignoreCase = true) &&
                        currentInlineEpisodes.isNotEmpty()
                    ) {
                        return@async currentInlineEpisodes
                    }
                    seasonEpisodesCache[sUrl]?.takeIf { it.isNotEmpty() }?.let { return@async it }
                    sem.withPermit {
                        fetchSeasonEpisodes(sUrl, sNum, referer = finalUrl)
                    }
                }
            }.awaitAll()

            perSeasonLists.flatten().forEach { allRawEpisodes.add(it) }
            if (allRawEpisodes.isEmpty() && currentInlineEpisodes.isNotEmpty()) {
                allRawEpisodes.addAll(currentInlineEpisodes)
            }
        }

        // Deduplicate & sort `(seasonNum, epNum)` ascending
        val uniqueEpisodes = allRawEpisodes
            .distinctBy { it.url }
            .sortedWith(compareBy<RawEpisodeEntry> { it.seasonNum }.thenBy { it.epNum ?: Int.MAX_VALUE })
            .mapIndexed { idx, entry ->
                val resolvedEpNum = entry.epNum ?: (idx + 1)
                newEpisode(entry.url) {
                    this.name = "الحلقة $resolvedEpNum"
                    this.season = entry.seasonNum
                    this.episode = resolvedEpNum
                    this.posterUrl = poster
                }
            }

        val seriesType = if (tvType == TvType.Anime || tvType == TvType.AnimeMovie) TvType.Anime else TvType.TvSeries
        newTvSeriesLoadResponse(
            seriesDisplayTitle.ifBlank { cleanDisplayTitle(rawTitle, false) },
            finalUrl,
            seriesType,
            uniqueEpisodes
        ) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = genres
            if (ratingVal != null) this.score = Score.from10(ratingVal)
            if (actors.isNotEmpty()) this.actors = actors
            if (recommendations.isNotEmpty()) this.recommendations = recommendations
        }
    }

    private suspend fun fetchSeasonEpisodes(
        seasonUrl: String,
        seasonNum: Int,
        referer: String
    ): List<RawEpisodeEntry> {
        val sPage = Shahid4uNetwork.getPage(seasonUrl, referer = referer) ?: return emptyList()
        var eps = parseInlineEpisodes(sPage.document, seasonNum)

        // Fallback if inline episode grid is empty and `/season/<slug>/episodes` link exists
        if (eps.isEmpty()) {
            val allEpsHref = sPage.document.selectFirst("a[href*=/episodes]")?.attr("href")
            val allEpsUrl = Shahid4uNetwork.resolveUrl(allEpsHref, mainUrl)
            if (allEpsUrl != null && !Shahid4uNetwork.isCanaryUrl(allEpsUrl)) {
                Shahid4uNetwork.getPage(allEpsUrl, referer = seasonUrl)?.let { epPage ->
                    eps = parseInlineEpisodes(epPage.document, seasonNum)
                }
            }
        }

        if (eps.isNotEmpty()) {
            seasonEpisodesCache[seasonUrl] = eps
        }
        return eps
    }

    private fun parseInlineEpisodes(doc: Document, seasonNum: Int): List<RawEpisodeEntry> {
        val list = ArrayList<RawEpisodeEntry>()
        val seen = HashSet<String>()
        doc.select("a.epss[href*=/episode/], a[href*=/episode/].epss").forEach { a ->
            if (Shahid4uNetwork.isHiddenOrTrapElement(a)) return@forEach
            val epUrl = Shahid4uNetwork.resolveUrl(a.attr("href"), mainUrl) ?: return@forEach
            if (Shahid4uNetwork.isCanaryUrl(epUrl) || !seen.add(epUrl)) return@forEach
            val spanNum = a.selectFirst("span")?.text()?.trim()
                ?.let { normalizeArabicDigits(it).toIntOrNull() }
            val epNum = spanNum ?: extractEpisodeNumber(a.text(), epUrl)
            list.add(RawEpisodeEntry(epUrl, seasonNum, epNum, a.text().trim()))
        }
        return list
    }

    private fun parseCastAndCrew(doc: Document): List<ActorData> {
        val out = ArrayList<ActorData>()
        val seen = HashSet<String>()

        doc.select(".stuff a[href*=/actor/], a[href*=/actor/]").forEach { a ->
            val name = a.text().trim()
            if (name.isNotEmpty() && seen.add("actor:$name")) {
                out.add(ActorData(Actor(name, null)))
            }
        }
        doc.select(".stuff a[href*=/director/], a[href*=/director/]").forEach { a ->
            val name = a.text().trim()
            if (name.isNotEmpty() && seen.add("dir:$name")) {
                out.add(ActorData(Actor(name, null), roleString = "إخراج"))
            }
        }
        doc.select(".stuff a[href*=/writer/], a[href*=/writer/]").forEach { a ->
            val name = a.text().trim()
            if (name.isNotEmpty() && seen.add("wri:$name")) {
                out.add(ActorData(Actor(name, null), roleString = "تأليف"))
            }
        }
        return out
    }

    // ── Load Links (ALL Watch + Multi-Quality Download Providers!) ───────────

    private data class ResolvedProviderJob(
        val canonicalKey: String,
        val externalUrl: String,
        val referer: String,
        val qualityHint: Int?
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val (watchUrl, downloadUrl) = buildWatchAndDownloadUrls(data) ?: return@coroutineScope false
        val jobsByKey = LinkedHashMap<String, ResolvedProviderJob>()

        fun registerExternalUrl(extUrl: String, referer: String, qualityHint: Int?) {
            val clean = extUrl.trim()
            if (!clean.startsWith("http") || Shahid4uNetwork.isCanaryUrl(clean)) return
            val key = Shahid4uExtractors.canonicalVideoKey(clean)
            val existing = jobsByKey[key]
            if (existing == null) {
                jobsByKey[key] = ResolvedProviderJob(key, clean, referer, qualityHint)
            } else if (existing.qualityHint == null && qualityHint != null) {
                // Upgrade Watch job with explicit Download quality tier (e.g. 1080p) without duplicating HTTP calls!
                jobsByKey[key] = existing.copy(qualityHint = qualityHint)
            }
        }

        // ── Phase 1: Watch Page (`/watch/<slug>`) -> `POST /watch/resolve` ───
        val watchPage = Shahid4uNetwork.getPage(watchUrl, referer = data)
        if (watchPage != null) {
            val watchDoc = watchPage.document
            val csrfToken = Regex("""csrfToken\s*=\s*["']([^"']+)["']""")
                .find(watchPage.rawHtml)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: watchDoc.selectFirst("meta[name=csrf-token]")?.attr("content")?.trim().orEmpty()
            val watchKey = Regex("""watchResolveKey\s*=\s*["']([^"']+)["']""")
                .find(watchPage.rawHtml)?.groupValues?.get(1)?.trim().orEmpty()
            val customResolveUrl = Regex("""resolveUrl\s*=\s*["']([^"']+)["']""")
                .find(watchPage.rawHtml)?.groupValues?.get(1)?.replace("\\/", "/")?.trim()

            // Collect any high-rank honeytrap tokens from inline `const servers = [...]` JSON
            val allTrapTokens = HashSet(watchPage.trapTokens)
            Regex("""const\s+servers\s*=\s*(\[[\s\S]*?\]);""").find(watchPage.rawHtml)?.groupValues?.get(1)?.let { arrStr ->
                runCatching {
                    val arr = JSONArray(arrStr)
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        if (o.optInt("rank", 0) >= 900_000) {
                            o.optString("token").trim().takeIf { it.isNotEmpty() }?.let { allTrapTokens.add(it) }
                        }
                    }
                }
            }

            if (csrfToken.isNotEmpty() && watchKey.isNotEmpty()) {
                val visibleButtons = watchDoc.select("#servers button.btn-server[data-token], button.btn-server[data-token]")
                    .filter { btn ->
                        val tok = btn.attr("data-token").trim()
                        tok.isNotEmpty() &&
                            !allTrapTokens.contains(tok) &&
                            !Shahid4uNetwork.isHiddenOrTrapElement(btn)
                    }
                    .distinctBy { it.attr("data-token").trim() }

                val resolveEndpoint = Shahid4uNetwork.resolveUrl(customResolveUrl, mainUrl)
                    ?: "${mainUrl.trimEnd('/')}/watch/resolve"

                for ((idx, btn) in visibleButtons.withIndex()) {
                    val serverTok = btn.attr("data-token").trim()
                    val payload = JSONObject()
                        .put("key", watchKey)
                        .put("token", serverTok)
                        .put("human_signal", Shahid4uNetwork.buildHumanSignal(idx))
                        .toString()

                    val postRes = Shahid4uNetwork.postJsonSticky(
                        url = resolveEndpoint,
                        jsonBody = payload,
                        referer = watchPage.finalUrl,
                        csrfToken = csrfToken,
                        transport = watchPage.transport
                    ) ?: continue

                    // Immediately stop watch loop if rate-limited or forbidden to protect session
                    if (postRes.code == 403 || postRes.code == 429) break
                    if (postRes.code !in 200..299) continue

                    val json = runCatching { JSONObject(postRes.body) }.getOrNull() ?: continue
                    if (!json.optBoolean("ok", false)) continue

                    val mPath = json.optString("url").trim()
                    if (mPath.isEmpty() || Shahid4uNetwork.isCanaryUrl(mPath)) continue

                    val external = Shahid4uNetwork.resolveRedirectSticky(
                        url = mPath,
                        referer = watchPage.finalUrl,
                        transport = watchPage.transport
                    )
                    if (!external.isNullOrBlank()) {
                        registerExternalUrl(external, watchPage.finalUrl, qualityHint = null)
                    }
                }
            }
        }

        // ── Phase 2: Multi-Quality Download Page (`/download/<slug>`) ────────
        val dlPage = Shahid4uNetwork.getPage(downloadUrl, referer = data)
        if (dlPage != null) {
            data class PendingDlLink(val mHref: String, val qualityHint: Int?)

            val pendingDl = ArrayList<PendingDlLink>()
            val seenMHrefs = HashSet<String>()
            val serversRoot = dlPage.document.selectFirst(".down-container .servers, .servers")

            if (serversRoot != null) {
                var sectionQuality: Int? = null
                for (child in serversRoot.children()) {
                    if (Shahid4uNetwork.isHiddenOrTrapElement(child)) continue
                    if (child.hasClass("qual")) {
                        sectionQuality = parseQualityNumber(child.text())
                        continue
                    }
                    child.select("a.btn-down[href]").forEach { a ->
                        if (Shahid4uNetwork.isHiddenOrTrapElement(a)) return@forEach
                        val href = a.attr("href").trim()
                        if (href.isEmpty() || Shahid4uNetwork.isCanaryUrl(href)) return@forEach

                        val hostName = a.selectFirst(".info span:first-child, span")?.text()?.trim().orEmpty()
                        if (hostName.equals("Server 1", ignoreCase = true)) return@forEach
                        if (!Shahid4uExtractors.isSupportedHostName(hostName)) return@forEach

                        val rowQualityText = a.select(".info span").lastOrNull()?.text()?.trim()
                        val qual = parseQualityNumber(rowQualityText) ?: sectionQuality

                        val absM = Shahid4uNetwork.resolveUrl(href, mainUrl) ?: return@forEach
                        if (seenMHrefs.add(absM)) {
                            pendingDl.add(PendingDlLink(absM, qual))
                        }
                    }
                }
            }

            // Resolve `/m/<hash>` links politely on `dlPage.transport` (bounded concurrency = 2)
            val mSem = Semaphore(2)
            val resolvedDl = pendingDl.map { item ->
                async {
                    mSem.withPermit {
                        val ext = Shahid4uNetwork.resolveRedirectSticky(
                            url = item.mHref,
                            referer = dlPage.finalUrl,
                            transport = dlPage.transport
                        )
                        if (!ext.isNullOrBlank()) ext to item.qualityHint else null
                    }
                }
            }.awaitAll().filterNotNull()

            for ((extUrl, qHint) in resolvedDl) {
                registerExternalUrl(extUrl, dlPage.finalUrl, qHint)
            }
        }

        if (jobsByKey.isEmpty()) return@coroutineScope false

        // ── Phase 3: Parallel Sub-Extractor Dispatch Across All Providers ────
        val emittedUrls = ConcurrentHashMap.newKeySet<String>()
        val extractSem = Semaphore(6)

        jobsByKey.values.map { job ->
            async {
                extractSem.withPermit {
                    runCatching {
                        Shahid4uExtractors.extractAny(
                            rawUrl = job.externalUrl,
                            referer = job.referer,
                            qualityHint = job.qualityHint,
                            subtitleCallback = subtitleCallback,
                            callback = { link ->
                                if (emittedUrls.add("${link.url}|${link.quality}")) {
                                    callback(link)
                                }
                            }
                        )
                    }.onFailure { e ->
                        if (e is CancellationException) throw e
                    }
                }
            }
        }.awaitAll()

        emittedUrls.isNotEmpty()
    }

    // ── Parsing & URL Normalization Utilities ────────────────────────────────

    private fun normalizeContentUrl(rawUrl: String): String {
        val resolved = Shahid4uNetwork.resolveUrl(rawUrl, mainUrl) ?: rawUrl
        val path = runCatching { URI(resolved).path.orEmpty() }.getOrDefault("")
        val decodedPath = decodeSlug(path)
        return when {
            path.startsWith("/watch/") || path.startsWith("/download/") -> {
                val slug = path.substringAfter("/watch/").substringAfter("/download/").trim('/')
                val isEp = decodedPath.contains("الحلقة") || decodedPath.contains("مسلسل") ||
                    decodedPath.contains("انمي") || decodedPath.contains("الموسم")
                val targetPrefix = if (isEp) "episode" else "film"
                Shahid4uNetwork.encodeUrl("${mainUrl.trimEnd('/')}/$targetPrefix/$slug")
            }
            else -> resolved
        }
    }

    private fun buildWatchAndDownloadUrls(dataUrl: String): Pair<String, String>? {
        val resolved = Shahid4uNetwork.resolveUrl(dataUrl, mainUrl) ?: return null
        val path = runCatching { URI(resolved).path.orEmpty() }.getOrDefault("")
        val segments = path.trim('/').split('/')
        if (segments.size < 2) return null
        val kind = segments[0].lowercase()
        if (kind !in setOf("episode", "film", "movie", "post", "watch", "download")) return null
        val slug = segments.drop(1).joinToString("/")
        val base = mainUrl.trimEnd('/')
        return Shahid4uNetwork.encodeUrl("$base/watch/$slug") to
            Shahid4uNetwork.encodeUrl("$base/download/$slug")
    }

    private fun parseQualityNumber(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val norm = normalizeArabicDigits(text)
        return Regex("""\b(2160|1440|1080|720|480|360|240)\b""").find(norm)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun classifyTvType(url: String, title: String, categoryText: String): TvType {
        val decUrl = decodeSlug(url).lowercase()
        val combined = "$title $categoryText $decUrl"
        val isAnime = combined.contains("انمي") || combined.contains("أنمي") || combined.contains("anime")
        val isMovie = decUrl.contains("/film/") || decUrl.contains("/movie/") ||
            categoryText.contains("افلام") || categoryText.contains("أفلام") ||
            title.trim().startsWith("فيلم") || title.trim().startsWith("فلم")

        return when {
            isMovie && isAnime -> TvType.AnimeMovie
            isMovie -> TvType.Movie
            isAnime -> TvType.Anime
            else -> TvType.TvSeries
        }
    }

    private fun extractEpisodeNumber(text: String, url: String = ""): Int? {
        val normText = normalizeArabicDigits(text)
        EP_NUMBER_REGEX.find(normText)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        val normSlug = normalizeArabicDigits(decodeSlug(url))
        EP_NUMBER_REGEX.find(normSlug)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    private fun extractSeasonNumber(text: String, url: String? = null): Int? {
        val combined = normalizeArabicDigits("$text ${url?.let { decodeSlug(it) }.orEmpty()}")
        SEASON_NUMBER_REGEX.find(combined)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        for ((word, num) in ARABIC_SEASON_ORDINALS) {
            if (combined.contains("الموسم $word") || combined.contains("موسم $word") || combined.contains("الموسم-$word")) {
                return num
            }
        }
        for ((word, num) in ARABIC_SEASON_ORDINALS) {
            if (combined.contains(word)) return num
        }
        return null
    }

    private fun extractSeriesTitleFromBreadcrumbs(doc: Document): String? {
        val seriesAnchor = doc.selectFirst(".info-side a[href*=/series/], nav a[href*=/series/], a[href*=/series/]")
            ?: return null
        val t = seriesAnchor.text().trim()
        return t.takeIf { it.isNotEmpty() && !it.contains("المسلسلات") && !it.contains("قسم") }
            ?.let { cleanSeriesRootTitle(it) }
    }

    private fun cleanDisplayTitle(raw: String, keepEpisodeLabel: Boolean): String {
        var s = raw.trim()
        s = s.replace(Regex("""^(?:مشاهدة\s+و?\s*تحميل|مشاهدة|تحميل)\s+"""), "")
        s = s.replace(Regex("""\s+(?:مترجمة?|مدبلجة?)\s+اون\s*لاين.*$"""), "")
        s = s.replace(Regex("""\s+اون\s*لاين.*$"""), "")
        if (!keepEpisodeLabel) {
            s = s.replace(Regex("""\s+(?:مترجم|مترجمة)\s*$"""), "")
        }
        return s.replace(Regex("""\s{2,}"""), " ").trim()
    }

    private fun cleanSeriesRootTitle(raw: String): String {
        var s = cleanDisplayTitle(raw, keepEpisodeLabel = false)
        s = s.replace(Regex("""^(?:مسلسل|انمي|أنمي|برنامج|عرض)\s+"""), "")
        s = s.replace(Regex("""\s+الموسم\s+\S+(?:\s+عشر)?.*$"""), "")
        s = s.replace(Regex("""\s+الحلقة\s+\d+.*$"""), "")
        return s.replace(Regex("""\s{2,}"""), " ").trim().ifBlank { raw.trim() }
    }
}
