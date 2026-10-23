package com.anime4up

import android.util.Base64
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class Anime4Up : MainAPI() {
    override var mainUrl = "https://w1.anime4up.rest"
    override var name = "انمي فور اب (Anime4Up)"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        private val EPISODE_NUM_REGEX = Regex(
            """(?i)(?:الحلقة|حلقة|الفلم|الفيلم|فيلم|فلم|الأوفا|اوفا|الخاصة|episode|ep)[^\d٠-٩]{0,18}([\d٠-٩]+)"""
        )
        private val TRAILING_NUM_REGEX = Regex("""(?:^|[-\s_/])([\d٠-٩]{1,4})(?:[-\s_/]|$)""")
        private val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2})\b""")
        private val CLEAN_TITLE_REGEX = Regex(
            """(?i)\s*(?:جميع\s+حلقات|مشاهدة\s+و\s*تحميل|مشاهدة|تحميل|انمي|أنمي|مترجم\s+اون\s+لاين|مترجم|مدبلج|اون\s*لاين)\s*"""
        )

        private fun normalizeDigits(input: String): String {
            val sb = StringBuilder(input.length)
            for (ch in input) {
                if (ch in '٠'..'٩') {
                    sb.append(('0'.code + (ch.code - '٠'.code)).toChar())
                } else {
                    sb.append(ch)
                }
            }
            return sb.toString()
        }

        private fun decodeUrlSafe(url: String): String =
            runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
    }

    override val mainPage = mainPageOf(
        "$mainUrl/home8/" to "الرئيسية",
        "$mainUrl/episode" to "أحدث الحلقات المضافة",
        "$mainUrl/%d9%82%d8%a7%d8%a6%d9%85%d8%a9-%d8%a7%d9%84%d8%a7%d9%86%d9%85%d9%8a" to "قائمة الأنمي",
        "$mainUrl/anime-category/%d8%a7%d9%84%d8%a3%d9%86%d9%85%d9%8a-%d8%a7%d9%84%d9%85%d8%aa%d8%b1%d8%ac%d9%85" to "الأنمي المترجم",
        "$mainUrl/anime-category/%d8%a7%d9%84%d8%a7%d9%86%d9%85%d9%8a-%d8%a7%d9%84%d9%85%d8%af%d8%a8%d9%84%d8%ac" to "الأنمي المدبلج",
        "$mainUrl/anime-type/movie-3" to "أفلام الأنمي",
        "$mainUrl/anime-type/tv2" to "مسلسلات أنمي (TV)",
        "$mainUrl/anime-type/ona1" to "أنميات ONA",
        "$mainUrl/anime-type/ova1" to "حلقات خاصة وأوفا (OVA)"
    )

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun Element.extractPosterUrl(): String? {
        val img = selectFirst("img") ?: return null
        val candidates = listOf(
            img.attr("data-image"),
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("src")
        )
        val raw = candidates.firstOrNull { it.isNotBlank() && !it.startsWith("data:image") }
            ?: img.attr("srcset").substringBefore(" ").trim().takeIf { it.isNotBlank() && !it.startsWith("data:") }
        return fixUrlNull(raw)
    }

    private fun extractEpisodeNumber(label: String, epUrl: String = ""): Int? {
        val normLabel = normalizeDigits(label)
        EPISODE_NUM_REGEX.find(normLabel)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        val decodedSlug = normalizeDigits(decodeUrlSafe(epUrl).substringAfter("/episode/"))
        EPISODE_NUM_REGEX.find(decodedSlug)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        Regex("""^\s*([\d٠-٩]{1,4})\s*$""").find(normLabel)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return TRAILING_NUM_REGEX.findAll(decodedSlug).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun parseCardType(typeBadge: String, titleText: String): TvType {
        val t = "$typeBadge $titleText".lowercase()
        return when {
            t.contains("movie") || t.contains("فيلم") || t.contains("فلم") -> TvType.AnimeMovie
            t.contains("ova") || t.contains("ona") || t.contains("special") || t.contains("اوفا") || t.contains("أوفا") || t.contains("خاصة") -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        // Always prefer the explicit /anime/.../ link inside h3 so custom slugs (e.g. -sgtfr) never 404
        val animeLinkEl = selectFirst(
            "div.anime-card-title h3 a[href*='/anime/'], h3 a[href*='/anime/'], a[href*='/anime/']"
        )
        val epLinkEl = selectFirst(
            "div.ep_num a[href*='/episode/'], div.episodes-card-title h3 a[href*='/episode/'], a.overlay[href*='/episode/'], a[href*='/episode/']"
        )

        val targetHref = animeLinkEl?.attr("href")?.takeIf { it.isNotBlank() }
            ?: epLinkEl?.attr("href")?.takeIf { it.isNotBlank() }
            ?: selectFirst("a[href]")?.attr("href")?.takeIf { it.isNotBlank() }
            ?: return null

        val cleanHref = fixUrlNull(targetHref)
            ?.takeIf { it.startsWith("http") && !it.contains(" ") && !it.contains("'") }
            ?: return null

        val rawTitle = animeLinkEl?.attr("title")?.takeIf { it.isNotBlank() }
            ?: animeLinkEl?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("div.anime-card-title h3 a, h3 a")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        val title = rawTitle.replace(Regex("""\s+"""), " ").trim()
        if (title.isBlank()) return null

        val posterUrl = extractPosterUrl()
        val epBadgeText = selectFirst("div.ep_num a, div.episodes-card-title a, span.ep-num")?.text()?.trim().orEmpty()
        val epHref = epLinkEl?.attr("href").orEmpty()
        val epNum = if (epBadgeText.isNotEmpty() || epHref.isNotEmpty()) {
            extractEpisodeNumber(epBadgeText, epHref)
        } else {
            null
        }

        val typeText = selectFirst("div.anime-card-type a, span.anime-type")?.text()?.trim().orEmpty()
        val statusAndCat = select("div.anime-card-status a, span.badge").text()
        val isDub = title.contains("مدبلج") ||
            epBadgeText.contains("مدبلج") ||
            statusAndCat.contains("مدبلج") ||
            decodeUrlSafe(cleanHref).contains("مدبلج")

        val tvType = parseCardType(typeText, title)

        return newAnimeSearchResponse(title, cleanHref, tvType) {
            this.posterUrl = posterUrl
            addDubStatus(isDub, epNum)
        }
    }

    private fun canonicalEpisodeKey(url: String): String =
        decodeUrlSafe(url).trim().lowercase().removeSuffix("/")

    // =========================================================================
    // Main Page
    // =========================================================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')

        // Multi-widget dashboard (/home8/) — single non-paginated landing view
        if (base.endsWith("/home8")) {
            if (page > 1) return newHomePageResponse(emptyList(), hasNext = false)
            val doc = Anime4UpNetwork.fetchDocument("$base/", referer = "$mainUrl/")
                ?: Anime4UpNetwork.fetchDocument("$mainUrl/", referer = "$mainUrl/")
                ?: return newHomePageResponse(emptyList(), hasNext = false)

            val homeLists = ArrayList<HomePageList>()
            for (widget in doc.select("div.main-widget")) {
                val heading = widget.selectFirst("div.main-didget-head h3, h3.widget-title, h2")?.text()?.trim()
                    ?.replace(Regex("""\s+"""), " ")
                    ?: continue
                if (heading.contains("تابع المشاهدة")) continue

                val seen = LinkedHashSet<String>()
                val items = widget.select("div.anime-card-container, div.episodes-card-container").mapNotNull { card ->
                    val item = card.toSearchResponse() ?: return@mapNotNull null
                    if (seen.add(item.url)) item else null
                }
                if (items.isNotEmpty()) {
                    homeLists.add(HomePageList(heading, items))
                }
            }
            return newHomePageResponse(homeLists, hasNext = false)
        }

        // Standard paginated listing (/episode, /قائمة-الانمي, /anime-category/..., /anime-type/...)
        val pageUrl = if (page <= 1) "$base/" else "$base/page/$page/"
        val doc = Anime4UpNetwork.fetchDocument(pageUrl, referer = "$mainUrl/")
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val cards = doc.select(
            "div.anime-grid div.anime-card-container, " +
                "div.page-content-container div.anime-card-container, " +
                "div.episodes-card-container, " +
                "div.anime-card-container"
        )

        val seen = LinkedHashSet<String>()
        val items = cards.mapNotNull { card ->
            val res = card.toSearchResponse() ?: return@mapNotNull null
            // On /episode/ archive, keep distinct anime entries per page while preserving order
            if (seen.add(res.url)) res else null
        }

        val hasNextPage = items.isNotEmpty() && (
            doc.selectFirst("a.next.page-numbers, ul.page-numbers a.next, link[rel=next]") != null ||
                cards.size >= 18
            )

        return newHomePageResponse(request.name, items, hasNext = hasNextPage)
    }

    // =========================================================================
    // Search
    // =========================================================================

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(trimmed, "UTF-8")

        val searchUrls = listOf(
            "$mainUrl/?search_param=animes&s=$encoded",
            "$mainUrl/?s=$encoded"
        )

        val seen = LinkedHashSet<String>()
        val results = ArrayList<SearchResponse>()

        for (sUrl in searchUrls) {
            val doc = Anime4UpNetwork.fetchDocument(sUrl, referer = "$mainUrl/") ?: continue
            val cards = doc.select(
                "div.anime-grid div.anime-card-container, " +
                    "div.page-content-container div.anime-card-container, " +
                    "div.anime-card-container"
            )
            for (card in cards) {
                val parsed = card.toSearchResponse() ?: continue
                if (seen.add(parsed.url)) {
                    results.add(parsed)
                }
            }
            if (results.isNotEmpty()) break
        }

        return results
    }

    // =========================================================================
    // Load Anime / Movie Info & Complete Episode List
    // =========================================================================

    override suspend fun load(url: String): LoadResponse? {
        var targetUrl = url.trim()
        var initialEpisodeDoc: Document? = null
        var doc = Anime4UpNetwork.fetchDocument(targetUrl, referer = "$mainUrl/") ?: return null

        // If user opened an /episode/.../ URL directly, follow parent link to the full /anime/.../ page
        if (targetUrl.contains("/episode/")) {
            initialEpisodeDoc = doc
            val parentAnimeUrl = doc.selectFirst(
                "div.anime-page-link a[href*='/anime/'], div.episode-Watch-navigation a[href*='/anime/'], a[href*='/anime/']"
            )?.attr("href")?.let { fixUrlNull(it) }

            if (!parentAnimeUrl.isNullOrBlank()) {
                val animeDoc = Anime4UpNetwork.fetchDocument(parentAnimeUrl, referer = targetUrl)
                if (animeDoc != null) {
                    targetUrl = parentAnimeUrl
                    doc = animeDoc
                }
            }
        }

        val rawTitle = doc.selectFirst("h1.anime-details-title, div.anime-details h1, h1.entry-title, h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore("-")?.trim()
            ?: return null

        val title = rawTitle
            .replace(CLEAN_TITLE_REGEX, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { rawTitle }

        val poster = doc.selectFirst("div.anime-thumbnail, div.anime-poster")?.extractPosterUrl()
            ?: fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))

        val description = doc.selectFirst("p.anime-story, div.anime-story")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }

        val genres = doc.select("ul.anime-genres li a, div.anime-genres a")
            .mapNotNull { it.text().trim().takeIf { g -> g.isNotEmpty() } }
            .distinct()

        var year: Int? = null
        var status: ShowStatus? = null
        var isDub = title.contains("مدبلج") || rawTitle.contains("مدبلج")
        var tvType = TvType.Anime
        var duration: Int? = null
        var expectedEpisodeCount: Int? = null

        doc.select("div.anime-info, ul.anime-info li, div.anime-details-info div").forEach { infoEl ->
            val txt = normalizeDigits(infoEl.text().trim())
            when {
                txt.contains("بداية العرض") || txt.contains("سنة العرض") -> {
                    year = YEAR_REGEX.find(txt)?.groupValues?.get(1)?.toIntOrNull() ?: year
                }
                txt.contains("حالة الأنمي") -> {
                    status = when {
                        txt.contains("يعرض الان") || txt.contains("مستمر") -> ShowStatus.Ongoing
                        txt.contains("مكتمل") || txt.contains("منتهي") -> ShowStatus.Completed
                        else -> status
                    }
                }
                txt.contains("نوع الأنمي") -> {
                    tvType = parseCardType(txt, rawTitle)
                }
                txt.contains("قسم الأنمي") -> {
                    if (txt.contains("مدبلج")) isDub = true
                }
                txt.contains("مدة الحلقة") -> {
                    duration = Regex("""(\d+)""").find(txt)?.groupValues?.get(1)?.toIntOrNull() ?: duration
                }
                txt.contains("عدد الحلقات") -> {
                    expectedEpisodeCount = Regex("""(\d+)""").find(txt)?.groupValues?.get(1)?.toIntOrNull() ?: expectedEpisodeCount
                }
            }
        }

        val malId = doc.selectFirst("a.anime-mal[href*='myanimelist.net/anime/'], a[href*='myanimelist.net/anime/']")
            ?.attr("href")
            ?.let { Regex("""myanimelist\.net/anime/(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val trailerUrl = doc.selectFirst("a.anime-trailer[href], a[href*='youtube.com/watch'], a[href*='youtu.be/']")
            ?.attr("href")
            ?.takeIf { it.contains("youtu") }

        val recommendations = doc.select("div#related-anime div.anime-card-container")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }

        // =====================================================================
        // Multi-Layer Episode Extraction (fixes "Coming Soon" across all titles)
        // =====================================================================
        val episodeMap = LinkedHashMap<String, Episode>()

        fun addEpisodeCandidate(epHrefRaw: String?, rawLabel: String?, thumbUrl: String? = null) {
            val epUrl = fixUrlNull(epHrefRaw) ?: return
            if (!epUrl.contains("/episode/")) return
            val key = canonicalEpisodeKey(epUrl)
            if (episodeMap.containsKey(key)) return

            val label = rawLabel?.trim().orEmpty()
            val epNum = extractEpisodeNumber(label, epUrl)
            val cleanName = when {
                label.isNotBlank() && (label.contains("الحلقة") || label.contains("الفلم") || label.contains("الفيلم") || label.contains("الخاصة") || label.contains("اوفا")) -> {
                    if (epNum != null && !normalizeDigits(label).contains(epNum.toString())) "$label $epNum" else label
                }
                tvType == TvType.AnimeMovie && (epNum == null || epNum == 1) -> "الفيلم الكامل"
                epNum != null -> "الحلقة $epNum"
                label.isNotBlank() -> label
                else -> "الحلقة 1"
            }

            episodeMap[key] = newEpisode(epUrl) {
                this.name = cleanName
                this.episode = epNum
                if (!thumbUrl.isNullOrBlank()) this.posterUrl = thumbUrl
            }
        }

        fun collectCardsFromAnimeDoc(pageDoc: Document) {
            // Restrict strictly to #episodesList (never #related-anime)
            val epCards = pageDoc.select("div#episodesList div.anime-card-container").ifEmpty {
                pageDoc.select("div.episodes-list-content div.anime-card-container, div.episodes-card-container")
                    .filterNot { it.parents().any { p -> p.id() == "related-anime" } }
            }

            for (card in epCards) {
                val epAnchor = card.selectFirst(
                    "div.ep_num a[href*='/episode/'], a.overlay[href*='/episode/'], div.episodes-card-title h3 a[href*='/episode/'], a[href*='/episode/']"
                ) ?: continue
                val label = card.selectFirst("div.ep_num a, div.episodes-card-title h3 a")?.text()?.trim()
                    ?: epAnchor.attr("title").ifBlank { epAnchor.text() }
                val cardThumb = card.extractPosterUrl()
                addEpisodeCandidate(epAnchor.attr("href"), label, cardThumb)
            }

            // Legacy direct links inside #episodesList or ul#ULEpisodesList if rendered inline
            pageDoc.select("ul#ULEpisodesList li a[href*='/episode/'], div#episodesList a[href*='/episode/']").forEach { a ->
                val txt = a.text().trim().ifBlank { a.attr("title").trim() }
                addEpisodeCandidate(a.attr("href"), txt)
            }
        }

        // Layer 1: Collect page-1 episodes directly from the anime info document
        collectCardsFromAnimeDoc(doc)

        val hasLoadMore = doc.selectFirst("a.episodes-load-more, nav.episodes-pagination-holder a.page-numbers") != null
        val maxPage = doc.select("nav.episodes-pagination-holder a.page-numbers, ul.page-numbers a.page-numbers")
            .mapNotNull { normalizeDigits(it.text().trim()).toIntOrNull() }
            .maxOrNull() ?: if (hasLoadMore) 2 else 1

        val needsMoreEpisodes = episodeMap.isEmpty() ||
            hasLoadMore ||
            maxPage > 1 ||
            (expectedEpisodeCount != null && expectedEpisodeCount!! > episodeMap.size)

        val firstEpUrl = doc.selectFirst("a.anime-first-ep[href*='/episode/']")?.attr("href")?.let { fixUrlNull(it) }
            ?: episodeMap.values.firstOrNull()?.data
            ?: (if (url.contains("/episode/")) url else null)

        // Layer 2: Single-request full episode list via `ul#ULEpisodesList` on the episode page
        // (Fetches all 500 Naruto Shippuuden episodes or 1180+ One Piece episodes in 1 HTTP call!)
        if (needsMoreEpisodes && !firstEpUrl.isNullOrBlank()) {
            val epDoc = if (initialEpisodeDoc != null && canonicalEpisodeKey(url) == canonicalEpisodeKey(firstEpUrl)) {
                initialEpisodeDoc
            } else {
                Anime4UpNetwork.fetchDocument(firstEpUrl, referer = targetUrl)
            }

            if (epDoc != null) {
                val fullListLinks = epDoc.select(
                    "ul#ULEpisodesList li a[href*='/episode/'], " +
                        "ul.all-episodes-list li a[href*='/episode/'], " +
                        "div.episodes-list-wrapper ul li a[href*='/episode/']"
                )
                for (a in fullListLinks) {
                    val label = a.text().trim().ifBlank { a.attr("title").trim() }
                    addEpisodeCandidate(a.attr("href"), label)
                }
            }
        }

        // Layer 3: Paginated `/page/2..N/` fallback if `ul#ULEpisodesList` wasn't populated
        val stillIncomplete = maxPage > 1 && (
            episodeMap.size <= 48 ||
                (expectedEpisodeCount != null && episodeMap.size < expectedEpisodeCount!!)
            )
        if (stillIncomplete) {
            val baseAnimeUrl = targetUrl.substringBefore("/page/").trimEnd('/')
            val safeMaxPage = maxPage.coerceAtMost(40)
            val sem = Semaphore(4)
            val pageDocs = coroutineScope {
                (2..safeMaxPage).map { p ->
                    async {
                        sem.withPermit {
                            Anime4UpNetwork.fetchDocument("$baseAnimeUrl/page/$p/", referer = targetUrl)
                        }
                    }
                }.awaitAll()
            }
            pageDocs.filterNotNull().forEach { collectCardsFromAnimeDoc(it) }
        }

        // Layer 4: Final single-episode / movie fallback so valid titles never show "Coming Soon"
        if (episodeMap.isEmpty() && !firstEpUrl.isNullOrBlank()) {
            addEpisodeCandidate(
                firstEpUrl,
                if (tvType == TvType.AnimeMovie) "الفيلم الكامل" else "الحلقة 1",
                poster
            )
        }

        val sortedEpisodes = episodeMap.values
            .mapIndexed { idx, ep -> idx to ep }
            .sortedWith(
                compareBy<Pair<Int, Episode>> { it.second.episode ?: Int.MAX_VALUE }
                    .thenBy { it.first }
            )
            .mapIndexed { sortedIdx, (_, ep) ->
                if (ep.episode == null && episodeMap.size == 1) {
                    ep.apply { this.episode = 1 }
                } else if (ep.episode == null) {
                    ep.apply { this.episode = sortedIdx + 1 }
                } else {
                    ep
                }
            }

        return newAnimeLoadResponse(title, targetUrl, tvType) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.duration = duration
            this.tags = genres
            this.showStatus = status
            if (recommendations.isNotEmpty()) {
                this.recommendations = recommendations
            }
            addMalId(malId)
            if (!trailerUrl.isNullOrBlank()) {
                addTrailer(trailerUrl)
            }
            addEpisodes(if (isDub) DubStatus.Dubbed else DubStatus.Subbed, sortedEpisodes)
        }
    }

    // =========================================================================
    // Load Watch & Download Video Links
    // =========================================================================

    private data class CandidateServer(
        val url: String,
        val serverName: String?,
        val qualityHint: String?,
        val priority: Int
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = Anime4UpNetwork.fetchDocument(data, referer = "$mainUrl/") ?: return false

        val candidateMap = LinkedHashMap<String, CandidateServer>()

        fun registerCandidate(rawUrl: String?, serverName: String? = null, qualityHint: String? = null, priority: Int = 5) {
            val cleaned = rawUrl?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("javascript:") && !it.startsWith("#") } ?: return
            val fullUrl = when {
                cleaned.startsWith("//") -> "https:$cleaned"
                cleaned.startsWith("http") -> cleaned
                else -> fixUrlNull(cleaned) ?: return
            }
            val key = Anime4UpExtractorSuite.canonicalDedupKey(fullUrl)
            val existing = candidateMap[key]
            if (existing == null) {
                candidateMap[key] = CandidateServer(fullUrl, serverName?.trim(), qualityHint?.trim(), priority)
            } else {
                // Keep richer quality/server metadata if the download table provides explicit resolution (e.g. FHD - 1080p)
                val mergedQuality = when {
                    !existing.qualityHint.isNullOrBlank() && !existing.qualityHint.contains("متعدد") -> existing.qualityHint
                    !qualityHint.isNullOrBlank() -> qualityHint.trim()
                    else -> existing.qualityHint
                }
                val mergedServer = existing.serverName?.takeIf { it.isNotBlank() } ?: serverName?.trim()
                candidateMap[key] = existing.copy(
                    qualityHint = mergedQuality,
                    serverName = mergedServer,
                    priority = minOf(existing.priority, priority)
                )
            }
        }

        // 1. Watch Servers (`ul#episode-servers li[data-watch]`)
        doc.select("ul#episode-servers li[data-watch], ul#watch-servers li[data-watch], li[data-watch]").forEach { li ->
            val watchUrl = li.attr("data-watch")
            val sName = li.selectFirst(".watch-server-name, span.server")?.text() ?: li.text()
            val qHint = li.selectFirst("span.quality")?.text()
            val prio = if (
                watchUrl.contains("c1oiijc.shop") ||
                watchUrl.contains("/Anime4up-S", ignoreCase = true) ||
                watchUrl.contains("megamax") ||
                watchUrl.contains("share4max") ||
                watchUrl.contains("vkvideo")
            ) 1 else 2
            registerCandidate(watchUrl, sName, qHint, prio)
        }

        // 2. Active / noscript iframe embeds
        doc.select("div#episode-watch-content iframe[src], noscript iframe[src], div.player-iframe iframe[src]").forEach { iframe ->
            registerCandidate(iframe.attr("src"), priority = 3)
        }
        Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(doc.select("noscript").html())
            .forEach { m -> registerCandidate(m.groupValues[1], priority = 3) }

        // 3. Legacy Base64 `input[name=wl]` & `input[name=moshahda]`
        doc.selectFirst("input[name='wl']")?.attr("value")?.takeIf { it.isNotBlank() }?.let { wlVal ->
            runCatching {
                val decoded = String(Base64.decode(wlVal, Base64.DEFAULT), Charsets.UTF_8)
                val json = JSONObject(decoded)
                json.keys().forEach { k ->
                    json.optJSONObject(k)?.keys()?.forEach { serverKey ->
                        registerCandidate(json.optJSONObject(k)?.optString(serverKey), serverKey, k, priority = 3)
                    }
                }
            }
        }
        doc.selectFirst("input[name='moshahda']")?.attr("value")?.takeIf { it.isNotBlank() }?.let { mVal ->
            val code = runCatching { String(Base64.decode(mVal, Base64.DEFAULT), Charsets.UTF_8).trim() }.getOrDefault(mVal.trim())
            if (code.isNotBlank()) {
                val mUrl = if (code.startsWith("http")) code else "https://moshahda.net/embed-$code.html"
                registerCandidate(mUrl, "Moshahda", null, priority = 4)
            }
        }
        doc.select("a[data-ep-url]").forEach { a ->
            registerCandidate(a.attr("data-ep-url"), a.text(), null, priority = 3)
        }

        // 4. Download Table (`div#download` / `div.episode-download-container`) — all qualities (FHD 1080p / HD 720p / SD 480p)
        doc.select(
            "div#download table tbody tr, " +
                "div.episode-download-container table tbody tr, " +
                "div.download-list table tbody tr"
        ).forEach { row ->
            val href = row.selectFirst("td.td-link a[href], a[href]")?.attr("href") ?: return@forEach
            val sName = row.selectFirst("span.server-name, td.td-server")?.text()
            val qHint = row.selectFirst("td.td-quality, span.quality")?.text()
            val prio = if (href.contains("mediafire.com") || href.contains("megamax.me") || href.contains("share4max.com")) 1 else 3
            registerCandidate(href, sName, qHint, prio)
        }

        // Legacy download anchors outside table
        doc.select("div.download-links a[href], a.btn-download[href]").forEach { a ->
            registerCandidate(a.attr("href"), a.text(), null, priority = 4)
        }

        if (candidateMap.isEmpty()) return false

        val orderedCandidates = candidateMap.values.sortedBy { it.priority }
        val emittedUrls = ConcurrentHashMap.newKeySet<String>()
        val linkLock = Any()
        val dedupCallback: (ExtractorLink) -> Unit = { link ->
            if (emittedUrls.add(link.url)) {
                synchronized(linkLock) {
                    callback(link)
                }
            }
        }
        val emittedSubUrls = ConcurrentHashMap.newKeySet<String>()
        val subLock = Any()
        val dedupSubCallback: (SubtitleFile) -> Unit = { sub ->
            if (emittedSubUrls.add(sub.url)) {
                synchronized(subLock) {
                    subtitleCallback(sub)
                }
            }
        }

        val sem = Semaphore(6)
        coroutineScope {
            orderedCandidates.map { item ->
                async {
                    sem.withPermit {
                        Anime4UpExtractorSuite.resolve(
                            rawUrl = item.url,
                            referer = data,
                            qualityHint = item.qualityHint,
                            serverHint = item.serverName,
                            subtitleCallback = dedupSubCallback,
                            callback = dedupCallback
                        )
                    }
                }
            }.awaitAll()
        }

        return emittedUrls.isNotEmpty()
    }
}
