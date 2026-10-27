package com.tuktuk

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import kotlinx.coroutines.delay
import java.net.URI
import java.net.URLEncoder
import android.util.Log

class TukTukHd : MainAPI() {
    override var mainUrl = "https://tuktukcinemas.com"
    override var name = "توك توك سينما (TukTuk)"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
    )
    override val hasDownloadSupport = true

    override val mainPage = mainPageOf(
        "$mainUrl/main/" to "الرئيسية",
        "$mainUrl/recent/page/" to "المضاف حديثاً",
        "$mainUrl/category/movies-2/page/" to "أحدث الأفلام",
        "$mainUrl/category/series-1/page/" to "أحدث الحلقات",
        "$mainUrl/category/movies-2/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d9%85%d8%af%d8%a8%d9%84%d8%ac%d8%a9/page/" to "أفلام مدبلجة",
        "$mainUrl/category/anime-6/page/" to "أنمي",
        "$mainUrl/channel/film-netflix-1/page/" to "افلام نتفليكس",
        "$mainUrl/channel/series-netflix-2/page/" to "مسلسلات نتفليكس",
        "$mainUrl/channel/full-series-1/page/" to "تحميل مسلسلات برابط واحد"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val document = app.get(url, headers = siteHeaders(url), referer = "$mainUrl/main/").document
        val items = collectSearchResults(document)
        return newHomePageResponse(request.name, items, hasNextPage(document, page, items.size))
    }

    private fun pageUrl(data: String, page: Int): String {
        val normalized = normalizeSiteUrl(data)
        return when {
            normalized.trimEnd('/').endsWith("/main", ignoreCase = true) -> {
                if (page <= 1) normalized else "$mainUrl/main/page/$page"
            }
            normalized.endsWith("/page/", ignoreCase = true) -> "$normalized$page"
            else -> "${normalized.trimEnd('/')}/page/$page"
        }
    }

    private fun siteHeaders(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "Referer" to referer,
    )

    private fun normalizeSiteUrl(raw: String): String {
        val fixed = fixUrl(raw.trim())
        return fixed
            .replace(LEGACY_HOST, mainUrl)
            .replace(WWW_SITE_HOST, mainUrl)
    }

    private fun isSiteUrl(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host == SITE_HOST || host.endsWith(".$SITE_HOST")
    }

    private fun collectSearchResults(
        document: org.jsoup.nodes.Document,
        searchQuery: String? = null,
    ): List<SearchResponse> {
        val selectors = listOf(
            "li.Small--Box, div.Block--Item, li.AsidePost, li.Slides--Item",
            ".MovieItem, .Movie--Item, .MovieList li, .MoviesList li",
            ".SeriesPosterCard, article.post, article[class*=movie], article[class*=series]",
        )
        val results = linkedMapOf<String, SearchResponse>()
        for (selector in selectors) {
            for (card in document.select(selector)) {
                val item = toSearchResult(card) ?: continue
                if (searchQuery != null && !TukTukSearchUtils.matchesTitle(item.name, searchQuery)) continue
                results.putIfAbsent(item.url, item)
            }
        }
        if (results.isEmpty()) {
            val links = if (searchQuery == null) {
                document.select(
                    "a[href*='/movie/'], a[href*='/movies/'], a[href*='/series/'], a[href*='/episode/']"
                )
            } else {
                // Search templates can use different card markup; inspect same-site
                // anchors but keep only titles matching every query term.
                document.select("a[href]")
            }
            for (link in links) {
                val item = toSearchResult(link) ?: continue
                if (searchQuery != null && !TukTukSearchUtils.matchesTitle(item.name, searchQuery)) continue
                results.putIfAbsent(item.url, item)
            }
        }
        return results.values.toList()
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val linkTag = if (element.tagName().equals("a", ignoreCase = true)) {
            element
        } else {
            element.selectFirst("a[href]") ?: return null
        }
        val href = normalizeSiteUrl(linkTag.attr("href"))
        if (href.isBlank() || !isSiteUrl(href)) return null

        val title = linkTag.attr("title").ifBlank { element.attr("title") }.ifBlank {
            element.selectFirst(".title, .Title, h2, h3, .MovieTitle")?.text().orEmpty()
        }.ifBlank { linkTag.selectFirst("img[alt]")?.attr("alt").orEmpty() }
            .ifBlank { linkTag.text() }
            .trim()
        if (title.isBlank()) return null

        val image = element.selectFirst(
            ".Poster--Block img, .Poster img, .SeriesEpisodeThumb img, .SeriesPosterCard img, img"
        )
        val posterUrl = bestImageUrl(image)?.let(::normalizeSiteUrl)
        val searchable = "$title ${element.text()} $href".lowercase()
        val path = runCatching { URI(href).path.orEmpty().lowercase() }.getOrDefault("")
        val isEpisodeOrSeries = path.contains("/series/") || path.contains("/episode/") ||
            listOf("مسلسل", "حلقة", "الموسم", "season", "episode").any { searchable.contains(it) }
        val isAnime = listOf("أنمي", "انمي", "anime").any { searchable.contains(it) }
        val isCartoon = listOf("كرتون", "cartoon").any { searchable.contains(it) }
        val isMovie = !isEpisodeOrSeries || path.contains("/movie/") || path.contains("/movies/")
        val type = when {
            isAnime && isEpisodeOrSeries -> TvType.Anime
            isAnime && isMovie -> TvType.AnimeMovie
            isAnime -> TvType.Anime
            isCartoon -> TvType.Cartoon
            isEpisodeOrSeries -> TvType.TvSeries
            else -> TvType.Movie
        }

        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
            TvType.Movie, TvType.AnimeMovie -> newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
            else -> newAnimeSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        }
    }

    private fun hasNextPage(document: org.jsoup.nodes.Document, page: Int, itemCount: Int): Boolean {
        val hasNextLink = document.select("a[rel=next][href], a.next[href], .pagination a[href]").any { link ->
            val text = link.text().trim()
            val href = normalizeSiteUrl(link.attr("href"))
            text.contains("التالي") || text.equals("next", ignoreCase = true) ||
                href.contains("/page/${page + 1}") || link.attr("rel").equals("next", ignoreCase = true)
        }
        return hasNextLink || itemCount >= PAGE_SIZE
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (TukTukSearchUtils.normalize(query).isBlank()) {
            return newSearchResponseList(emptyList(), false)
        }

        val safePage = page.coerceAtLeast(1)
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val urls = buildList {
            // WordPress's native search route is at the domain root, even when the
            // site's configured front page is /main/. Try it first to avoid scraping
            // the homepage carousel as if it were a search-results page.
            add(if (safePage == 1) {
                "$mainUrl/?s=$encoded"
            } else {
                "$mainUrl/page/$safePage/?s=$encoded"
            })
            if (safePage > 1) add("$mainUrl/?s=$encoded&paged=$safePage")
            add(if (safePage == 1) {
                "$mainUrl/main/?s=$encoded"
            } else {
                "$mainUrl/main/page/$safePage/?s=$encoded"
            })
        }.distinct()

        for (url in urls) {
            val document = try {
                app.get(url, headers = siteHeaders(url), referer = "$mainUrl/main/").document
            } catch (e: Exception) {
                Log.w("TukTuk", "Search request failed for $url", e)
                continue
            }
            val results = collectSearchResults(document, query)
                .distinctBy { it.url }
            if (results.isNotEmpty()) {
                return newSearchResponseList(results, hasNextPage(document, safePage, results.size))
            }
        }
        return newSearchResponseList(emptyList(), false)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items.orEmpty()

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun extractYear(title: String): Int? {
        val match = Regex("(19|20)\\d{2}").find(title)
        return match?.value?.toIntOrNull()
    }

    private fun extractRating(doc: org.jsoup.nodes.Document): Double? {
        val raw = doc.selectFirst(
            ".imdbS strong, .imdb strong, .rating strong, [itemprop=ratingValue]"
        )?.let { element ->
            element.attr("content").ifBlank { element.attr("data-rating").ifBlank { element.text() } }
        } ?: doc.selectFirst("meta[itemprop=ratingValue], meta[property=og:rating]")?.attr("content")
            .orEmpty()
        val number = Regex("(?:10(?:[.,]0+)?|[0-9](?:[.,][0-9]+)?)")
            .find(raw.replace(',', '.'))?.value?.toDoubleOrNull() ?: return null
        return when {
            number in 0.0..10.0 -> number
            number in 10.1..100.0 -> number / 10.0
            else -> null
        }
    }

    private fun extractPlot(doc: org.jsoup.nodes.Document): String? = doc.selectFirst(
        ".story p, .story, .Story, .description, .Description, .single-description, [itemprop=description]"
    )?.text()?.trim()?.takeIf { it.isNotBlank() }

    private fun bestSrcsetUrl(srcset: String): String? = srcset.split(',')
        .mapNotNull { part ->
            val tokens = part.trim().split(Regex("\\s+"))
            val url = tokens.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val size = tokens.getOrNull(1)?.removeSuffix("w")?.toIntOrNull()
                ?: tokens.getOrNull(1)?.removeSuffix("x")?.toFloatOrNull()?.toInt()
                ?: 0
            url to size
        }
        .maxByOrNull { it.second }
        ?.first

    private fun bestImageUrl(img: Element?): String? {
        if (img == null) return null
        for (attr in listOf("srcset", "data-srcset")) {
            bestSrcsetUrl(img.attr(attr))?.let { return it }
        }
        for (attr in listOf("data-src", "data-lazy-src", "data-original", "data-original-src", "src")) {
            val value = img.attr(attr).trim()
            if (value.isNotBlank() && !value.startsWith("data:", ignoreCase = true)) return value
        }
        return null
    }

    private fun extractPoster(doc: org.jsoup.nodes.Document): String? {
        val image = doc.selectFirst(
            ".MainSingle .left .image img, .MainSingle .image img, .movie-poster img, .poster img, img.wp-post-image, .image img"
        )
        bestImageUrl(image)?.let { return it }
        return doc.selectFirst("meta[property=og:image], meta[name=twitter:image]")
            ?.attr("content")?.takeIf { it.isNotBlank() }
    }

    private fun extractEpisodeNumber(title: String, url: String = ""): Int? =
        TukTukSeriesParser.episodeNumber(title, url)

    private fun extractSeasonNumber(vararg values: String): Int? =
        TukTukSeriesParser.seasonNumber(*values)

    private fun classifyType(title: String, url: String, isMovie: Boolean): TvType {
        val searchable = "$title $url".lowercase()
        val anime = listOf("أنمي", "انمي", "anime").any { searchable.contains(it) }
        val cartoon = listOf("كرتون", "cartoon", "animation").any { searchable.contains(it) }
        return when {
            anime && isMovie -> TvType.AnimeMovie
            anime -> TvType.Anime
            cartoon -> TvType.Cartoon
            isMovie -> TvType.Movie
            else -> TvType.TvSeries
        }
    }

    private fun extractPath(url: String): String =
        runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")

    private fun isEpisodeUrl(url: String, title: String = ""): Boolean = TukTukSeriesParser.isEpisode(url, title)

    override suspend fun load(url: String): LoadResponse {
        val canonicalUrl = normalizeSiteUrl(url)
        val doc = app.get(canonicalUrl, headers = siteHeaders(canonicalUrl), referer = "$mainUrl/main/").document
        val ogType = doc.selectFirst("meta[property=og:type]")?.attr("content").orEmpty()
        val rawTitle = doc.selectFirst("h1.post-title, h1.entry-title, .single-title, h1")?.text()
            .orEmpty()
            .ifBlank { doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty() }
        val title = rawTitle.replace(
            Regex("""\s*(?:[-|–:]?\s*)?(الحلقة\s*\d+|حلقة\s*\d+|episode\s*\d+|مترجم|مدبلج).*""", RegexOption.IGNORE_CASE),
            ""
        ).trim().ifBlank { doc.title().substringBefore("|").trim() }
        val poster = extractPoster(doc)
        val plot = extractPlot(doc)
        val year = extractYear(rawTitle)
            ?: extractYear(doc.selectFirst("time, [itemprop=datePublished]")?.attr("datetime").orEmpty())
            ?: extractYear(canonicalUrl)
        val rating = extractRating(doc)
        val path = extractPath(canonicalUrl)
        val isEpisode = ogType.contains("episode", ignoreCase = true) || isEpisodeUrl(canonicalUrl, rawTitle)

        if (isEpisode) {
            val showUrl = findParentSeriesUrl(doc, canonicalUrl)
            if (showUrl != null) {
                return loadTvSeries(showUrl, canonicalUrl, title, poster, plot, year, rating, rawTitle)
            }
            val episodeNumber = extractEpisodeNumber(rawTitle, canonicalUrl)
            val episode = newEpisode(canonicalUrl) {
                this.name = rawTitle.ifBlank { title }
                this.episode = episodeNumber
                this.season = extractSeasonNumber(rawTitle, canonicalUrl) ?: 1
                this.posterUrl = poster
            }
            return newTvSeriesLoadResponse(title.ifBlank { "مسلسل" }, canonicalUrl, classifyType(title, canonicalUrl, false), listOf(episode)) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = rating?.let { Score.from10(it) }
            }
        }

        if (TukTukSeriesParser.isSeasonPage(canonicalUrl, title)) {
            val parentUrl = findParentSeriesUrl(doc, canonicalUrl)
            if (parentUrl != null && !TukTukSeriesParser.isSeasonPage(parentUrl)) {
                try {
                    val parentDoc = app.get(parentUrl, headers = siteHeaders(parentUrl), referer = canonicalUrl).document
                    val parentTitle = parentDoc.selectFirst("h1.post-title, h1.entry-title, h1")?.text()
                        ?.trim()?.ifBlank { title } ?: title
                    return loadTvSeriesFromShow(
                        parentDoc,
                        parentUrl,
                        parentTitle,
                        extractPoster(parentDoc) ?: poster,
                        extractPlot(parentDoc) ?: plot,
                        extractYear(parentTitle) ?: year,
                        extractRating(parentDoc) ?: rating,
                    )
                } catch (e: Exception) {
                    Log.w("TukTuk", "Could not load parent series $parentUrl from season page", e)
                }
            }
        }

        val hasEpisodeCards = episodeCards(doc).isNotEmpty()
        val hasSeasonPages = TukTukSeriesParser.findSeasonPages(doc, canonicalUrl).isNotEmpty()
        val isSeriesPage = doc.select(
            "section.SeriesEpisodesGrid, section.SeriesSeasons, a.SeriesEpisodeCard, .SeriesEpisodeCard, .EpisodesList, .episodes-list, .allseasonss"
        ).isNotEmpty() || ogType.contains("series", ignoreCase = true) || path.contains("/series/") ||
            hasEpisodeCards || hasSeasonPages || TukTukSeriesParser.isSeasonPage(canonicalUrl, title)
        if (isSeriesPage) {
            return loadTvSeriesFromShow(doc, canonicalUrl, title, poster, plot, year, rating)
        }

        val movieType = classifyType(title, canonicalUrl, true)
        return newMovieLoadResponse(title.ifBlank { rawTitle }, canonicalUrl, movieType, canonicalUrl) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = rating?.let { Score.from10(it) }
        }
    }

    private fun findParentSeriesUrl(doc: org.jsoup.nodes.Document, currentUrl: String): String? {
        val breadcrumbs = doc.select(
            "#mpbreadcrumbs a[href], .breadcrumb a[href], .breadcrumbs a[href], nav[aria-label*=breadcrumb] a[href], [itemtype*=BreadcrumbList] a[href]"
        ).toList()
        val breadcrumbSet = breadcrumbs.toSet()
        val candidates = breadcrumbs + doc.select("a[href], a[data-url], a[data-href]").toList()
        val currentPath = extractPath(currentUrl).trimEnd('/')
        val seriesPages = linkedMapOf<String, Int>()
        val seasonPages = linkedMapOf<String, Int>()
        val pageTitle = TukTukSearchUtils.normalize(doc.selectFirst("h1")?.text().orEmpty())

        for (link in candidates) {
            val rawUrl = link.attr("href").ifBlank { link.attr("data-url") }.ifBlank { link.attr("data-href") }
            if (rawUrl.isBlank()) continue
            val href = normalizeSiteUrl(rawUrl)
            if (!isSiteUrl(href) || TukTukSeriesParser.pageKey(href) == TukTukSeriesParser.pageKey(currentUrl)) continue
            val label = link.attr("title").ifBlank { link.text() }.trim()
            if (TukTukSeriesParser.isEpisode(href, label)) continue

            val path = extractPath(href)
            val normalizedPath = path.trimEnd('/').ifBlank { "/" }
            val isGenericRoute = normalizedPath == "/" || normalizedPath == "/main" ||
                normalizedPath.startsWith("/category/") || normalizedPath.startsWith("/channel/") ||
                normalizedPath.startsWith("/recent/")
            val labelNormalized = TukTukSearchUtils.normalize(label)
            val routeCue = !isGenericRoute && (
                path.contains("/series/") || path.contains("/show/") || path.contains("/serial/") ||
                    label.contains("مسلسل") || label.contains("series", true)
                )
            val ancestorCue = !isGenericRoute && currentPath.isNotBlank() &&
                currentPath.startsWith(normalizedPath.trimEnd('/') + "/")
            val titleCue = !isGenericRoute &&
                labelNormalized.split(' ').any { word -> word.length >= 3 && pageTitle.contains(word) }
            val score = (if (link in breadcrumbSet) 10000 else 0) +
                (if (ancestorCue) 1000 else 0) + (if (titleCue) 500 else 0) +
                (if (routeCue) 100 else 0) + path.length

            if (TukTukSeriesParser.isSeasonPage(href, label)) {
                seasonPages[href] = maxOf(seasonPages[href] ?: Int.MIN_VALUE, score)
            } else if (routeCue || ancestorCue || titleCue) {
                seriesPages[href] = maxOf(seriesPages[href] ?: Int.MIN_VALUE, score)
            }
        }
        return seriesPages.maxByOrNull { it.value }?.key ?: seasonPages.maxByOrNull { it.value }?.key
    }

    private fun episodeCards(doc: org.jsoup.nodes.Document): List<Element> {
        val selected = doc.select(
            "a.SeriesEpisodeCard[href], a.SeriesEpisodeCard[data-url], .SeriesEpisodesGrid a[href], .SeriesEpisodesGrid a[data-url], .EpisodesList a[href], .EpisodesList a[data-url], .episodes-list a[href], .episodes-list a[data-url], .episode-card a[href], .episode-card a[data-url], .episode-item a[href], .episode-item a[data-url], li.episode a[href], li.episode a[data-url]"
        )
        val result = linkedMapOf<String, Element>()
        fun addIfSiteEpisode(link: Element) {
            val raw = link.attr("href").ifBlank { link.attr("data-url") }.ifBlank { link.attr("data-href") }
            if (raw.isBlank()) return
            val href = normalizeSiteUrl(raw)
            if (href.isNotBlank() && isSiteUrl(href)) result.putIfAbsent(TukTukSeriesParser.pageKey(href), link)
        }
        selected.forEach(::addIfSiteEpisode)
        for (link in doc.select("a[href], a[data-url], a[data-href]")) {
            val raw = link.attr("href").ifBlank { link.attr("data-url") }.ifBlank { link.attr("data-href") }
            if (raw.isBlank()) continue
            val href = normalizeSiteUrl(raw)
            if (!isSiteUrl(href)) continue
            val context = listOf(
                link.attr("title"), link.text(), raw, link.className(), link.parent()?.className().orEmpty()
            ).joinToString(" ")
            if (TukTukSeriesParser.isEpisode(href, context) || context.contains("episode", true)) {
                addIfSiteEpisode(link)
            }
        }
        return result.values.toList()
    }

    private data class ParsedEpisode(val episode: Episode, val explicitSeason: Boolean)
    private data class EpisodePageBudget(var remaining: Int)

    private fun episodeFromLink(link: Element, defaultSeason: Int, defaultSeasonIsExplicit: Boolean): ParsedEpisode? {
        val raw = link.attr("href").ifBlank { link.attr("data-url") }.ifBlank { link.attr("data-href") }
        if (raw.isBlank()) return null
        val href = normalizeSiteUrl(raw)
        if (href.isBlank() || !isSiteUrl(href)) return null
        val image = link.selectFirst("img") ?: link.parent()?.selectFirst("img")
        val name = link.attr("title").ifBlank { link.selectFirst(".title, .Title, h2, h3")?.text().orEmpty() }
            .ifBlank { image?.attr("alt").orEmpty() }
            .ifBlank { link.text() }.trim().ifBlank { "حلقة" }
        val number = extractEpisodeNumber(name, href)
        val parsedSeason = extractSeasonNumber(name, href)
        val season = parsedSeason ?: defaultSeason.coerceAtLeast(1)
        val poster = bestImageUrl(image)?.let(::normalizeSiteUrl)
        val episode = newEpisode(href) {
            this.name = name
            this.episode = number
            this.season = season
            this.posterUrl = poster
        }
        return ParsedEpisode(episode, parsedSeason != null || defaultSeasonIsExplicit)
    }

    private fun addEpisode(episodes: LinkedHashMap<String, ParsedEpisode>, candidate: ParsedEpisode) {
        val key = TukTukSeriesParser.pageKey(candidate.episode.data)
        val previous = episodes[key]
        val shouldReplace = previous == null ||
            (!previous.explicitSeason && candidate.explicitSeason) ||
            (previous.episode.episode == null && candidate.episode.episode != null) ||
            (previous.episode.posterUrl == null && candidate.episode.posterUrl != null)
        if (shouldReplace) episodes[key] = candidate
    }


    private fun tvSeriesType(title: String, url: String): TvType = when (classifyType(title, url, false)) {
        TvType.Anime -> TvType.Anime
        TvType.Cartoon -> TvType.Cartoon
        else -> TvType.TvSeries
    }

    private suspend fun loadTvSeriesFromShow(
        doc: org.jsoup.nodes.Document,
        url: String,
        title: String,
        poster: String?,
        plot: String?,
        year: Int?,
        rating: Double?,
        requestedEpisode: ParsedEpisode? = null,
    ): LoadResponse {
        val canonicalUrl = normalizeSiteUrl(url)
        val episodesMap = linkedMapOf<String, ParsedEpisode>()
        val visitedPages = linkedSetOf<String>()
        val budget = EpisodePageBudget(MAX_EPISODE_PAGES)
        val seasonLinks = TukTukSeriesParser.findSeasonPages(doc, canonicalUrl)
            .distinctBy { TukTukSeriesParser.pageKey(it.url) }
            .take(MAX_SEASONS_TO_SCAN)

        // Visit individual season pages first. Their explicit season number should
        // win over an unnumbered episode card copied onto the series landing page.
        for (season in seasonLinks) {
            if (budget.remaining <= 0) break
            if (TukTukSeriesParser.pageKey(season.url) == TukTukSeriesParser.pageKey(canonicalUrl)) continue
            try {
                val seasonDoc = app.get(season.url, headers = siteHeaders(season.url), referer = canonicalUrl).document
                collectEpisodePages(
                    seasonDoc,
                    season.url,
                    season.number,
                    true,
                    episodesMap,
                    visitedPages,
                    budget,
                )
                delay(100)
            } catch (e: Exception) {
                Log.w("TukTuk", "Could not load season ${season.number} from ${season.url}", e)
            }
        }

        val currentSeason = extractSeasonNumber(title, canonicalUrl) ?: 1
        collectEpisodePages(
            doc,
            canonicalUrl,
            currentSeason,
            TukTukSeriesParser.isSeasonPage(canonicalUrl, title),
            episodesMap,
            visitedPages,
            budget,
        )
        requestedEpisode?.let { addEpisode(episodesMap, it) }

        val sorted = episodesMap.values.map { it.episode }.sortedWith(
            compareBy<Episode>({ it.season ?: 1 }, { it.episode ?: Int.MAX_VALUE }, { it.name.orEmpty() })
        )
        return newTvSeriesLoadResponse(title, canonicalUrl, tvSeriesType(title, canonicalUrl), sorted) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = rating?.let { Score.from10(it) }
        }
    }

    private suspend fun collectEpisodePages(
        firstDocument: org.jsoup.nodes.Document,
        firstUrl: String,
        defaultSeason: Int,
        defaultSeasonIsExplicit: Boolean,
        episodes: LinkedHashMap<String, ParsedEpisode>,
        visitedPages: MutableSet<String>,
        budget: EpisodePageBudget,
    ) {
        var document = firstDocument
        var pageUrl = firstUrl
        while (budget.remaining > 0) {
            val pageKey = TukTukSeriesParser.pageKey(pageUrl)
            if (!visitedPages.add(pageKey)) return
            budget.remaining -= 1

            for (link in episodeCards(document)) {
                val episode = episodeFromLink(link, defaultSeason, defaultSeasonIsExplicit) ?: continue
                addEpisode(episodes, episode)
            }

            val nextUrl = TukTukSeriesParser.nextPageUrl(document, pageUrl) ?: break
            if (TukTukSeriesParser.pageKey(nextUrl) in visitedPages) break
            try {
                document = app.get(nextUrl, headers = siteHeaders(nextUrl), referer = firstUrl).document
                pageUrl = nextUrl
                delay(100)
            } catch (e: Exception) {
                Log.w("TukTuk", "Could not load episode page $nextUrl", e)
                break
            }
        }
    }

    private suspend fun loadTvSeries(
        showUrl: String,
        episodeUrl: String,
        title: String,
        poster: String?,
        plot: String?,
        year: Int?,
        rating: Double?,
        episodeTitle: String? = null,
    ): LoadResponse {
        val requestedSeason = extractSeasonNumber(episodeTitle.orEmpty(), episodeUrl, showUrl)
        val requestedEpisode = ParsedEpisode(
            newEpisode(episodeUrl) {
                this.name = episodeTitle?.ifBlank { "حلقة" } ?: title.ifBlank { "حلقة" }
                this.episode = extractEpisodeNumber(episodeTitle.orEmpty(), episodeUrl)
                this.season = requestedSeason ?: 1
                this.posterUrl = poster
            },
            explicitSeason = requestedSeason != null,
        )
        return try {
            val canonicalShowUrl = normalizeSiteUrl(showUrl)
            val showDoc = app.get(
                canonicalShowUrl,
                headers = siteHeaders(canonicalShowUrl),
                referer = "$mainUrl/main/"
            ).document
            val showTitle = showDoc.selectFirst("h1.post-title, h1.entry-title, h1")?.text()
                ?.trim()?.ifBlank { title } ?: title
            loadTvSeriesFromShow(
                showDoc,
                canonicalShowUrl,
                showTitle,
                extractPoster(showDoc) ?: poster,
                extractPlot(showDoc) ?: plot,
                extractYear(showTitle) ?: year,
                extractRating(showDoc) ?: rating,
                requestedEpisode,
            )
        } catch (e: Exception) {
            Log.w("TukTuk", "Could not load parent series $showUrl; keeping the requested episode", e)
            val episode = requestedEpisode.episode
            newTvSeriesLoadResponse(title.ifBlank { "مسلسل" }, episodeUrl, tvSeriesType(title, episodeUrl), listOf(episode)) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = rating?.let { Score.from10(it) }
            }
        }
    }

    private fun extractQuality(resolution: String?): Int {
        val value = resolution?.lowercase()?.trim().orEmpty()
        if (value.isBlank()) return Qualities.Unknown.value
        when {
            Regex("\\b4k\\b|2160").containsMatchIn(value) -> return 2160
            Regex("\\b2k\\b|1440").containsMatchIn(value) -> return 1440
            Regex("fhd|1080").containsMatchIn(value) -> return 1080
            Regex("hd|720").containsMatchIn(value) -> return 720
            Regex("sd|480").containsMatchIn(value) -> return 480
        }
        Regex("\\d{3,4}\\s*[x×]\\s*(\\d{3,4})").find(value)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        Regex("\\b(2160|1440|1080|720|540|480|360|240)p?\\b").find(value)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return Qualities.Unknown.value
    }

    private fun resolveUrl(raw: String, baseUrl: String): String {
        val clean = raw.trim().replace("&amp;", "&").replace("\\/", "/")
        if (clean.startsWith("//")) return "https:$clean"
        if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) {
            return normalizeSiteUrl(clean)
        }
        return runCatching { URI(baseUrl).resolve(clean).toString() }
            .getOrDefault("$mainUrl/${clean.trimStart('/')}")
    }

    private fun decodePlayerUrl(encoded: String, pageUrl: String): String? {
        val value = encoded.trim()
        if (value.isBlank()) return null
        if (value.startsWith("http", true) || value.startsWith("//") || value.startsWith("/")) {
            return resolveUrl(value, pageUrl)
        }
        val decoded = listOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_WRAP).firstNotNullOfOrNull { flags ->
            runCatching { String(Base64.decode(value, flags), Charsets.UTF_8).trim() }.getOrNull()
                ?.takeIf { it.startsWith("http", true) || it.startsWith("//") || it.startsWith("/") }
        } ?: return null
        return resolveUrl(decoded, pageUrl)
    }

    private fun getPlayerUrls(doc: org.jsoup.nodes.Document, pageUrl: String): List<String> {
        val urls = linkedSetOf<String>()
        for (element in doc.select("[data-crypt]")) {
            decodePlayerUrl(element.attr("data-crypt"), pageUrl)?.let(urls::add)
        }
        val frameSelector = "#main-video-frame[src], iframe[data-src], .VideoPlayer iframe[src], .player iframe[src], .play-box iframe[src], iframe[src*='/embed/'], iframe[src*='/player/'], iframe[src*='/iframe/'], iframe[src*='megamax'], iframe[src*='share4max'], iframe[src*='megatuktuk']"
        for (frame in doc.select(frameSelector)) {
            val raw = frame.attr("src").ifBlank { frame.attr("data-src") }
            if (raw.isBlank()) continue
            val url = resolveUrl(raw, pageUrl)
            if (url != pageUrl && url != "$mainUrl/main/") urls.add(url)
        }
        return urls.toList()
    }

    private fun inertiaVersion(html: String): String? {
        val pagePayload = org.jsoup.Jsoup.parse(html).selectFirst("[data-page]")?.attr("data-page").orEmpty()
        val fromPayload = runCatching { org.json.JSONObject(pagePayload).optString("version") }.getOrNull()
        if (!fromPayload.isNullOrBlank() && fromPayload != "null") return fromPayload
        return Regex("[\"']version[\"']\\s*:\\s*[\"']([^\"']+)[\"']")
            .find(html)?.groupValues?.getOrNull(1)
    }

    private fun streamHeaders(mediaUrl: String, referer: String): Map<String, String> {
        val origin = TukTukMediaUtils.originOf(mediaUrl, mainUrl)
        return mapOf("User-Agent" to USER_AGENT, "Referer" to referer, "Origin" to origin)
    }

    private suspend fun emitDirectMedia(
        rawUrl: String,
        sourceName: String,
        referer: String,
        qualityHint: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = resolveUrl(rawUrl, referer)
        val headers = streamHeaders(url, referer)
        val isHls = TukTukMediaUtils.isHls(url)
        val isDash = url.substringBefore('?').endsWith(".mpd", ignoreCase = true)
        if (isHls) {
            val generated = try {
                M3u8Helper.generateM3u8(
                    sourceName,
                    url,
                    referer,
                    quality = qualityHint.takeIf { it > 0 },
                    headers = headers,
                )
            } catch (e: Exception) {
                Log.w("TukTuk", "Could not expand HLS qualities for $url", e)
                emptyList()
            }
            if (generated.isNotEmpty()) {
                generated.forEach { link ->
                    if (link.quality == Qualities.Unknown.value && qualityHint > 0) link.quality = qualityHint
                    callback(link)
                }
                return
            }
        }
        callback(
            newExtractorLink(
                source = sourceName,
                name = sourceName,
                url = url,
            ) {
                this.referer = referer
                this.quality = qualityHint.takeIf { it > 0 } ?: Qualities.Unknown.value
                this.type = when {
                    isHls -> ExtractorLinkType.M3U8
                    isDash -> ExtractorLinkType.DASH
                    else -> ExtractorLinkType.VIDEO
                }
                this.headers = headers
            }
        )
    }

    private suspend fun resolveMirror(
        rawUrl: String,
        driver: String?,
        symbol: String?,
        playerUrl: String,
        quality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val link = resolveUrl(rawUrl, playerUrl)
        val hint = "${driver.orEmpty()} ${symbol.orEmpty()} $link".lowercase()
        val sourceName = driver?.takeIf { it.isNotBlank() } ?: symbol?.takeIf { it.isNotBlank() } ?: "TukTuk"
        var streamRubyEmitted = false
        try {
            when {
                Regex("\\.(?:m3u8|m3u|mp4|m4v|mpd)(?:$|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(link) ||
                    link.contains("/master.txt", ignoreCase = true) ->
                    emitDirectMedia(link, sourceName, playerUrl, quality, callback)
                TukTukMediaUtils.isStreamRubyProvider(link, hint) -> {
                    TukTukStreamRubyExtractor().getUrl(link, playerUrl, subtitleCallback) {
                        streamRubyEmitted = true
                        emitUnique(it, quality)
                    }
                    if (!streamRubyEmitted) {
                        loadExtractor(link, subtitleCallback) { emitUnique(it, quality) }
                    }
                }
                hint.contains("megamax") || hint.contains("share4max") ||
                    TukTukMediaUtils.isMegaMaxUrl(link, driver.orEmpty()) ->
                    TukTukMegaMaxExtractor().getUrl(link, playerUrl, subtitleCallback, callback)
                hint.contains("byse") || hint.contains("byseraguci") ->
                    TukTukByseExtractor().getUrl(link, playerUrl, subtitleCallback, callback)
                hint.contains("lulustream") || hint.contains("luluvdo") ->
                    TukTukLuluExtractor().getUrl(link, playerUrl, subtitleCallback, callback)
                hint.contains("upnshare") || hint.contains("upns.one") ->
                    TukTukUpnExtractor().getUrl(link, playerUrl, subtitleCallback, callback)
                hint.contains("tuktukvip") || hint.contains("lolololu") ->
                    TukTukVipExtractor().getUrl(link, playerUrl, subtitleCallback, callback)
                hint.contains("videoland") ->
                    VideolandExtractor().getUrl(link, playerUrl, subtitleCallback, callback)
                else -> loadExtractor(link, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.w("TukTuk", "Mirror extraction failed ($driver / $symbol): $link", e)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = normalizeSiteUrl(data)
        val doc = try {
            app.get(episodeUrl, headers = siteHeaders(episodeUrl), referer = "$mainUrl/main/").document
        } catch (e: Exception) {
            Log.e("TukTuk", "Could not load watch page $episodeUrl", e)
            return false
        }

        val emittedUrls = linkedSetOf<String>()
        var emitted = 0
        fun emitUnique(link: ExtractorLink, qualityHint: Int = Qualities.Unknown.value) {
            if (link.quality == Qualities.Unknown.value && qualityHint > 0) link.quality = qualityHint
            if (emittedUrls.add(link.url)) {
                emitted += 1
                callback(link)
            }
        }

        // Some episode pages expose a direct video source instead of an iframe.
        val directMedia = doc.select("video[src], video source[src], source[type*=mpegurl][src]")
            .map { it.attr("src") }.filter { it.isNotBlank() }
        for (raw in directMedia) {
            try {
                emitDirectMedia(raw, "TukTuk", episodeUrl, Qualities.Unknown.value) { emitUnique(it) }
            } catch (e: Exception) {
                Log.w("TukTuk", "Direct media extraction failed: $raw", e)
            }
        }

        val playerUrls = getPlayerUrls(doc, episodeUrl)
        for (playerUrl in playerUrls) {
            var mirrorBody = ""
            val initial = try {
                app.get(playerUrl, headers = siteHeaders(episodeUrl), referer = episodeUrl)
            } catch (e: Exception) {
                Log.w("TukTuk", "Could not load player page $playerUrl", e)
                continue
            }
            mirrorBody = initial.text
            val version = inertiaVersion(mirrorBody)
            if (!version.isNullOrBlank()) {
                try {
                    val inertiaHeaders = siteHeaders(playerUrl) + mapOf(
                        "X-Inertia" to "true",
                        "X-Inertia-Version" to version,
                        "X-Inertia-Partial-Component" to "files/mirror/video",
                        "X-Inertia-Partial-Data" to "streams",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "application/json, text/javascript, */*;q=0.01",
                    )
                    val response = app.get(playerUrl, headers = inertiaHeaders, referer = playerUrl)
                    mirrorBody = response.text
                    val inertia = response.parsed<InertiaResponse>()
                    val streams = inertia.props.streams?.data.orEmpty()
                    for (stream in streams) {
                        val quality = extractQuality(stream.resolution ?: stream.label ?: stream.quality)
                        val mirrors = stream.mirrors.orEmpty() + stream.servers.orEmpty() + stream.sources.orEmpty() +
                            listOfNotNull((stream.url ?: stream.link)?.let { MirrorItem(link = it) })
                        for (mirror in mirrors) {
                            val link = (mirror.link ?: mirror.url ?: mirror.href ?: mirror.source)?.trim().orEmpty()
                            if (link.isBlank()) continue
                            resolveMirror(
                                link,
                                mirror.driver,
                                mirror.symbol ?: mirror.name,
                                playerUrl,
                                quality,
                                subtitleCallback,
                            ) { emitUnique(it, quality) }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("TukTuk", "Inertia stream request/parse failed for $playerUrl", e)
                }
            }

            // If the mirror API changes, still try direct HLS/MP4/DASH links in its HTML or JSON.
            for (mediaUrl in TukTukMediaUtils.mediaUrls(playerUrl, mirrorBody)) {
                try {
                    emitDirectMedia(mediaUrl, "TukTuk", playerUrl, Qualities.Unknown.value) { emitUnique(it) }
                } catch (e: Exception) {
                    Log.w("TukTuk", "Could not emit fallback media URL $mediaUrl", e)
                }
            }

            val playerDoc = org.jsoup.Jsoup.parse(mirrorBody, playerUrl)
            for (frame in getPlayerUrls(playerDoc, playerUrl)) {
                try {
                    resolveMirror(frame, null, null, playerUrl, Qualities.Unknown.value, subtitleCallback) {
                        emitUnique(it)
                    }
                } catch (e: Exception) {
                    Log.w("TukTuk", "Nested player extraction failed: $frame", e)
                }
            }
        }

        Log.i("TukTuk", "Resolved $emitted playable link(s) from $episodeUrl")
        return emitted > 0
    }

    data class InertiaResponse(
        @JsonProperty("props") val props: InertiaProps = InertiaProps()
    )
    data class InertiaProps(
        @JsonProperty("streams") val streams: StreamsData? = null
    )
    data class StreamsData(
        @JsonProperty("data") val data: List<StreamItem>? = null
    )
    data class StreamItem(
        @JsonProperty("resolution") val resolution: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("mirrors") val mirrors: List<MirrorItem>? = null,
        @JsonProperty("servers") val servers: List<MirrorItem>? = null,
        @JsonProperty("sources") val sources: List<MirrorItem>? = null
    )
    data class MirrorItem(
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("href") val href: String? = null,
        @JsonProperty("source") val source: String? = null,
        @JsonProperty("symbol") val symbol: String? = null,
        @JsonProperty("driver") val driver: String? = null,
        @JsonProperty("name") val name: String? = null
    )

    private companion object {
        const val SITE_HOST = "tuktukcinemas.com"
        const val PAGE_SIZE = 20
        const val MAX_SEASONS_TO_SCAN = 40
        const val MAX_EPISODE_PAGES = 80
        private val LEGACY_HOST = Regex("^https?://(?:www\\.)?tuktukhd\\.com", RegexOption.IGNORE_CASE)
        private val WWW_SITE_HOST = Regex("^https?://www\\.tuktukcinemas\\.com", RegexOption.IGNORE_CASE)
    }
}
