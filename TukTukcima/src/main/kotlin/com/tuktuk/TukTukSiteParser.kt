package com.tuktuk

import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import java.net.URI
import java.text.Normalizer
import java.util.Locale

internal data class TukTukCard(
    val title: String,
    val url: String,
    val poster: String?,
    val kind: TukTukContentKind,
)

internal data class TukTukMetadata(
    val title: String,
    val poster: String?,
    val plot: String?,
    val year: Int?,
    val rating: Double?,
    val kind: TukTukContentKind,
)

internal data class TukTukSeasonRef(
    val number: Int,
    val title: String,
    val url: String,
)

internal data class TukTukEpisode(
    val title: String,
    val url: String,
    val number: Int?,
    val season: Int,
    val poster: String?,
    val explicitSeason: Boolean = false,
)

internal enum class TukTukContentKind {
    MOVIE,
    SERIES,
    SEASON,
    EPISODE,
    ANIME,
    ANIME_MOVIE,
    CARTOON,
}

/** DOM and URL parsing isolated from requests so page parsing can be regression-tested. */
internal object TukTukSiteParser {
    const val BASE_URL = "https://tuktukcinemas.com"
    const val SITE_HOST = "tuktukcinemas.com"
    const val PAGE_SIZE = 20

    private val cardSelector = listOf(
        "li.Small--Box",
        "div.Block--Item",
        "li.AsidePost",
        "li.Slides--Item",
        ".MovieItem",
        ".Movie--Item",
        ".MovieList li",
        ".MoviesList li",
        ".SeriesPosterCard",
        "article.post",
        "article[class*=movie]",
        "article[class*=series]",
        "article[class*=episode]",
    ).joinToString(", ")

    private val episodeCardSelector = listOf(
        "a.SeriesEpisodeCard[href], a.SeriesEpisodeCard[data-url]",
        ".SeriesEpisodesGrid a[href], .SeriesEpisodesGrid a[data-url]",
        ".EpisodesList a[href], .EpisodesList a[data-url]",
        ".episodes-list a[href], .episodes-list a[data-url]",
        ".episode-card a[href], .episode-card a[data-url]",
        ".episode-item a[href], .episode-item a[data-url]",
        "li.episode a[href], li.episode a[data-url]",
    ).joinToString(", ")

    private val yearRegex = Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)")
    private val episodeLabelRegex = Regex(
        "(?:الحلقة|حلقه|حلقة|episode|ep)\\s*[-:#_]*\\s*([0-9٠-٩۰-۹]+)",
        RegexOption.IGNORE_CASE,
    )
    private val seasonLabelRegex = Regex(
        "(?:الموسم|موسم|season|seasons)[/\\s=:#_-]*([0-9٠-٩۰-۹]+)",
        RegexOption.IGNORE_CASE,
    )
    private val seasonSlugRegex = Regex("(?:^|[/_-])season[-_ ]*([0-9٠-٩۰-۹]+)(?:$|[/_-])", RegexOption.IGNORE_CASE)
    private val shortSeasonRegex = Regex("(?:^|[^a-z0-9])s([0-9٠-٩۰-۹]{1,2})(?:$|[^a-z0-9])", RegexOption.IGNORE_CASE)
    private val sxeRegex = Regex("(?:^|[^a-z0-9])s([0-9٠-٩۰-۹]{1,2})e([0-9٠-٩۰-۹]{1,4})(?:$|[^a-z0-9])", RegexOption.IGNORE_CASE)

    fun normalizeUrl(raw: String, baseUrl: String = BASE_URL): String? {
        val value = raw.trim()
            .replace("&amp;", "&")
            .replace("\\/", "/")
            .takeIf { it.isNotEmpty() } ?: return null
        val absolute = when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            else -> runCatching { URI(baseUrl).resolve(value).toString() }.getOrNull() ?: return null
        }
        val uri = runCatching { URI(absolute) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return null
        if (scheme !in setOf("http", "https") || !isAcceptedHost(host)) return null

        val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "https://$SITE_HOST$path$query"
    }

    fun isSiteUrl(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT)?.removePrefix("www.") }.getOrNull()
            ?: return false
        return isAcceptedHost(host)
    }

    private fun isAcceptedHost(host: String): Boolean =
        host == SITE_HOST || host.endsWith(".$SITE_HOST") || host == "tuktukhd.com" || host.endsWith(".tuktukhd.com")

    fun pageUrl(route: String, page: Int): String {
        val normalized = normalizeUrl(route) ?: BASE_URL
        val safePage = page.coerceAtLeast(1)
        if (safePage == 1) return normalized

        val uri = runCatching { URI(normalized) }.getOrNull() ?: return normalized
        val path = uri.path.orEmpty().trimEnd('/')
        val paginated = when {
            Regex("/page/\\d+$", RegexOption.IGNORE_CASE).containsMatchIn(path) ->
                path.replace(Regex("/page/\\d+$", RegexOption.IGNORE_CASE), "/page/$safePage")
            path.endsWith("/page", ignoreCase = true) -> "$path/$safePage"
            path == "/main" -> "/main/page/$safePage"
            path.isBlank() -> "/page/$safePage"
            else -> "$path/page/$safePage"
        }
        return "https://$SITE_HOST$paginated/" + uri.rawQuery?.let { "?$it" }.orEmpty()
    }

    fun parseCards(document: Document, query: String? = null, baseUrl: String = BASE_URL): List<TukTukCard> {
        val selected = document.select(cardSelector)
        val elements = if (selected.isNotEmpty()) selected.toList() else document.select("a[href]").toList()
        val results = linkedMapOf<String, TukTukCard>()
        for (element in elements) {
            val card = parseCard(element, baseUrl) ?: continue
            if (query != null && !TukTukSearchUtils.matchesTitle(card.title, query)) continue
            results.putIfAbsent(card.url, card)
        }

        // Search templates may put the hit list outside the known card containers.
        if (results.isEmpty() && query != null) {
            for (link in document.select("a[href]")) {
                val card = parseCard(link, baseUrl) ?: continue
                if (!TukTukSearchUtils.matchesTitle(card.title, query)) continue
                results.putIfAbsent(card.url, card)
            }
        }
        return results.values.toList()
    }

    private fun parseCard(element: Element, baseUrl: String): TukTukCard? {
        val anchor = if (element.tagName().equals("a", true)) element else element.selectFirst("a[href]") ?: return null
        val url = normalizeUrl(anchor.attr("href"), baseUrl) ?: return null
        val image = element.selectFirst("img") ?: anchor.selectFirst("img")
        val title = titleFrom(element, anchor, image)
        if (title.isBlank()) return null
        val poster = image?.let(::imageUrl)?.let { normalizeUrl(it, url) }
        return TukTukCard(title, url, poster, classify(title, url))
    }

    fun parseMetadata(document: Document, url: String): TukTukMetadata {
        val structuredTitle = document.selectFirst("meta[property=og:title], meta[name=twitter:title]")?.attr("content").orEmpty()
        val titleElement = document.selectFirst(
            "h1.post-title, h1.entry-title, .single-title, .MainSingle h1, .movie-title, .series-title, h1"
        )
        val rawTitle = titleElement?.text()?.trim().orEmpty().ifBlank { structuredTitle }
            .ifBlank { document.title() }
        val title = cleanTitle(rawTitle).ifBlank { titleFromSlug(url) }

        val description = document.selectFirst(
            "meta[property=og:description], meta[name=description], meta[name=twitter:description]"
        )?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".story, .Story, .description, .Description, .single-description, [itemprop=description]")
                ?.text()?.trim()?.takeIf { it.isNotBlank() }

        val poster = document.selectFirst(
            "meta[property=og:image], meta[name=twitter:image]"
        )?.attr("content")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(
                ".MainSingle .left .image img, .MainSingle .image img, .movie-poster img, .poster img, img.wp-post-image, .image img"
            )?.let(::imageUrl)
        val normalizedPoster = poster?.let { normalizeUrl(it, url) ?: resolveExternalImage(it, url) }

        val jsonLd = document.select("script[type=application/ld+json]").joinToString(" ") { it.data() }
        val dateText = document.selectFirst("time[datetime], [itemprop=datePublished]")?.let {
            it.attr("datetime").ifBlank { it.attr("content").ifBlank { it.text() } }
        }.orEmpty() + " " + jsonLd
        val year = extractYear(rawTitle, structuredTitle, dateText, url)
        val rating = extractRating(document, jsonLd)
        val ogType = document.selectFirst("meta[property=og:type]")?.attr("content").orEmpty()
        val kind = classifyPage(document, url, title, ogType)
        return TukTukMetadata(title, normalizedPoster, description, year, rating, kind)
    }

    fun classifyPage(document: Document, url: String, title: String, ogType: String = ""): TukTukContentKind {
        if (isEpisodeUrl(url, title)) return TukTukContentKind.EPISODE
        val seasons = parseSeasonRefs(document, url)
        val hasEpisodeSection = document.select(episodeCardSelector).isNotEmpty() ||
            document.select(".SeriesEpisodesGrid, .EpisodesList, .episodes-list, .allseasonss, [data-season]").isNotEmpty()
        val seriesClue = seasons.isNotEmpty() || hasEpisodeSection || pathLooksLikeSeries(url) ||
            ogType.contains("series", true) || ogType.contains("tv_show", true) || containsSeriesMarker(title)
        if (seriesClue) {
            return if (isSeasonUrl(url, title) && seasons.isEmpty()) TukTukContentKind.SEASON
            else kindForSeries(title, url)
        }
        return classify(title, url)
    }

    fun isSeriesPage(document: Document, url: String, metadata: TukTukMetadata): Boolean = when (metadata.kind) {
        TukTukContentKind.SERIES, TukTukContentKind.SEASON, TukTukContentKind.ANIME -> true
        TukTukContentKind.CARTOON -> pathLooksLikeSeries(url) || containsSeriesMarker(metadata.title) ||
            parseSeasonRefs(document, url).isNotEmpty() || document.select(episodeCardSelector).isNotEmpty()
        else -> false
    }

    fun isSeasonPage(url: String, title: String): Boolean = isSeasonUrl(url, title)

    fun parseSeasonRefs(document: Document, currentUrl: String): List<TukTukSeasonRef> {
        val refs = linkedMapOf<String, TukTukSeasonRef>()
        val seasonContainers = document.select(
            ".SeriesSeasons, .allseasonss, .SeasonList, .seasons, .season-list, .seasonList, [data-seasons]"
        )
        val scopedAnchors = linkedSetOf<Element>()
        for (container in seasonContainers) {
            if (container.tagName().equals("a", true) && container.hasAttr("href")) scopedAnchors.add(container)
            container.select("a[href], [data-season][data-url], [data-season][data-href]").forEach(scopedAnchors::add)
        }
        val hasSeriesContext = pathLooksLikeSeries(currentUrl) ||
            containsSeriesMarker(titleFromDocument(document)) || document.select(episodeCardSelector).isNotEmpty()
        val anchors = if (scopedAnchors.isNotEmpty()) scopedAnchors
            else if (hasSeriesContext) document.select("a[href], [data-season][data-url], [data-season][data-href]").toList()
            else emptyList()
        for (element in anchors) {
            val rawUrl = element.attr("href").ifBlank { element.attr("data-url") }.ifBlank { element.attr("data-href") }
            val url = normalizeUrl(rawUrl, currentUrl) ?: continue
            if (url == currentUrl || isEpisodeUrl(url, titleFrom(element, element, element.selectFirst("img")))) continue
            val label = titleFrom(element, element, element.selectFirst("img"))
                .ifBlank { element.attr("data-season") }
            val season = seasonNumber(label, url, element.attr("data-season")) ?: continue
            val explicitSeasonCue = containsSeasonMarker(label) || containsSeasonMarker(url) || element.hasAttr("data-season")
            if (!explicitSeasonCue) continue
            refs.putIfAbsent(url, TukTukSeasonRef(season, label.ifBlank { "الموسم $season" }, url))
        }
        return refs.values.sortedWith(compareBy<TukTukSeasonRef>({ it.number }, { it.url }))
    }

    fun parseEpisodes(
        document: Document,
        baseUrl: String,
        defaultSeason: Int,
        defaultSeasonIsExplicit: Boolean = false,
    ): List<TukTukEpisode> {
        val candidates = linkedSetOf<Element>()
        document.select(episodeCardSelector).forEach(candidates::add)
        document.select("a[href], a[data-url], a[data-href]").forEach { anchor ->
            if (isEpisodeAnchor(anchor)) candidates.add(anchor)
        }
        val episodes = linkedMapOf<String, TukTukEpisode>()
        for (anchor in candidates) {
            val rawUrl = anchor.attr("href").ifBlank { anchor.attr("data-url") }.ifBlank { anchor.attr("data-href") }
            val url = normalizeUrl(rawUrl, baseUrl) ?: continue
            if (!isEpisodeAnchor(anchor) && !isEpisodeUrl(url, titleFrom(anchor, anchor, anchor.selectFirst("img")))) continue
            val image = anchor.selectFirst("img") ?: anchor.parent()?.selectFirst("img")
            val title = titleFrom(anchor, anchor, image).ifBlank { "الحلقة" }
            val number = episodeNumber(title, url)
            val parsedSeason = seasonNumber(title, url)
            val season = parsedSeason ?: defaultSeason.coerceAtLeast(1)
            val poster = image?.let(::imageUrl)?.let { normalizeUrl(it, url) ?: resolveExternalImage(it, url) }
            episodes.putIfAbsent(
                url,
                TukTukEpisode(title, url, number, season, poster, parsedSeason != null || defaultSeasonIsExplicit),
            )
        }
        return episodes.values.sortedWith(
            compareBy<TukTukEpisode>({ it.season }, { it.number ?: Int.MAX_VALUE }, { it.title })
        )
    }

    fun findParentSeriesUrl(document: Document, currentUrl: String): String? {
        val currentPath = runCatching { URI(currentUrl).path.orEmpty().trimEnd('/') }.getOrDefault("")
        val breadcrumbLinks = document.select(
            "#mpbreadcrumbs a[href], .breadcrumb a[href], .breadcrumbs a[href], nav[aria-label*=breadcrumb] a[href], [itemtype*=BreadcrumbList] a[href]"
        ).toList().asReversed()
        val breadcrumbSet = breadcrumbLinks.toSet()
        val allCandidates = breadcrumbLinks + document.select("a[href]").toList()
        val seriesCandidates = linkedMapOf<String, Int>()
        val seasonFallbacks = linkedMapOf<String, Int>()
        val documentTitle = normalizedLooseTitle(titleFromDocument(document))

        for (anchor in allCandidates) {
            val candidate = normalizeUrl(anchor.attr("href"), currentUrl) ?: continue
            val label = titleFrom(anchor, anchor, anchor.selectFirst("img"))
            if (candidate == currentUrl || isEpisodeUrl(candidate, label)) continue
            val candidatePath = runCatching { URI(candidate).path.orEmpty().trimEnd('/') }.getOrDefault("")
            val path = runCatching { URI(candidate).path.orEmpty() }.getOrDefault("")
            val routeCue = pathLooksLikeSeries(candidate) || containsSeriesMarker(label)
            val ancestorCue = currentPath.startsWith("$candidatePath/") && candidatePath.isNotBlank()
            val titleCue = label.isNotBlank() && normalizedLooseTitle(label).split(' ').any { word ->
                word.length >= 4 && documentTitle.contains(word)
            }
            val score = (if (anchor in breadcrumbSet) 5000 else 0) +
                (if (titleCue) 500 else 0) +
                (if (pathLooksLikeSeries(candidate)) 1000 else 0) +
                (if (ancestorCue) 500 else 0) + (if (routeCue) 100 else 0) + path.length

            if (isSeasonUrl(candidate, label)) {
                seasonFallbacks[candidate] = maxOf(seasonFallbacks[candidate] ?: Int.MIN_VALUE, score)
            } else if (routeCue || ancestorCue || titleCue) {
                seriesCandidates[candidate] = maxOf(seriesCandidates[candidate] ?: Int.MIN_VALUE, score)
            }
        }
        return seriesCandidates.maxByOrNull { it.value }?.key
            ?: seasonFallbacks.maxByOrNull { it.value }?.key
    }

    fun nextPageUrl(document: Document, currentUrl: String): String? {
        val next = document.select(
            "a[rel=next][href], a.next[href], .pagination a[href], .pagination-next a[href], a[aria-label][href]"
        ).firstOrNull { anchor ->
            val text = anchor.text().trim()
            val rel = anchor.attr("rel")
            text.contains("التالي") || text.equals("next", true) || rel.contains("next", true) ||
                anchor.attr("class").contains("next", true) || anchor.attr("aria-label").contains("next", true)
        }
        val selected = next ?: run {
            val path = runCatching { URI(currentUrl).path.orEmpty() }.getOrDefault("")
            val currentPage = Regex("/page/(\\d+)", RegexOption.IGNORE_CASE)
                .find(path)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            document.select(".pagination a[href], a.page-numbers[href]").firstOrNull { anchor ->
                val href = anchor.attr("href")
                val linkedPage = Regex("(?:/page/|[?&](?:paged|page)=)(\\d+)", RegexOption.IGNORE_CASE)
                    .find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                linkedPage == currentPage + 1
            }
        } ?: return null
        return normalizeUrl(selected.attr("href"), currentUrl)?.takeIf { it != currentUrl }
    }

    fun hasNextPage(document: Document, currentUrl: String, itemCount: Int): Boolean =
        nextPageUrl(document, currentUrl) != null || itemCount >= PAGE_SIZE

    fun seasonNumber(vararg values: String): Int? {
        for (value in values) {
            val text = asciiDigits(value.lowercase(Locale.ROOT))
            val sxe = sxeRegex.find(text)
            if (sxe != null) sxe.groupValues.getOrNull(1)?.toIntOrNull()?.let { if (it in 1..99) return it }
            seasonLabelRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { if (it in 1..99) return it }
            seasonSlugRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { if (it in 1..99) return it }
            shortSeasonRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { if (it in 1..99) return it }
            val ordinal = arabicSeasonOrdinal(text)
            if (ordinal != null) return ordinal
        }
        return null
    }

    fun episodeNumber(vararg values: String): Int? {
        for (value in values) {
            val text = asciiDigits(value.lowercase(Locale.ROOT))
            val sxe = sxeRegex.find(text)
            if (sxe != null) sxe.groupValues.getOrNull(2)?.toIntOrNull()?.let { if (it in 0..9999) return it }
            episodeLabelRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { if (it in 0..9999) return it }
            Regex("(?:^|[/_-])(?:ep|episode)[-_ ]*([0-9]{1,4})(?:$|[/_-])", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { if (it in 0..9999) return it }
        }
        return null
    }

    fun isEpisodeUrl(url: String, title: String = ""): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        return path.contains("/episode/") || path.contains("/episodes/") ||
            sxeRegex.containsMatchIn(asciiDigits("$title $path".lowercase(Locale.ROOT))) ||
            episodeLabelRegex.containsMatchIn("$title $path")
    }

    private fun isEpisodeAnchor(anchor: Element): Boolean {
        val title = titleFrom(anchor, anchor, anchor.selectFirst("img"))
        val context = listOf(title, anchor.attr("href"), anchor.className(), anchor.parent()?.className().orEmpty())
            .joinToString(" ")
        return isEpisodeUrl(anchor.attr("href"), context) ||
            anchor.className().contains("episode", true) ||
            anchor.parents().any { parent ->
                val classes = parent.className().lowercase(Locale.ROOT)
                classes.contains("episode") || classes.contains("episodes-grid") || classes.contains("episodeslist")
            }
    }

    private fun isSeasonUrl(url: String, title: String): Boolean =
        !isEpisodeUrl(url, title) && seasonNumber(title, url) != null &&
            (containsSeasonMarker(title) || containsSeasonMarker(url))

    private fun containsSeasonMarker(value: String): Boolean =
        Regex("(?:الموسم|موسم|season|seasons|s[0-9٠-٩۰-۹]{1,2})", RegexOption.IGNORE_CASE).containsMatchIn(value)

    private fun containsSeriesMarker(value: String): Boolean =
        listOf("مسلسل", "مسلسلات", "series", "tv show", "tv-show").any { value.contains(it, true) }

    private fun pathLooksLikeSeries(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        return path.contains("/series/") || path.contains("/show/") || path.contains("/tv-show/") || path.contains("/serial/")
    }

    private fun classify(title: String, url: String): TukTukContentKind {
        val searchable = "$title $url".lowercase(Locale.ROOT)
        val anime = listOf("أنمي", "انمي", "anime").any { searchable.contains(it) }
        val cartoon = listOf("كرتون", "cartoon", "animation").any { searchable.contains(it) }
        val series = containsSeriesMarker(searchable) || pathLooksLikeSeries(url) || isSeasonUrl(url, title) ||
            isEpisodeUrl(url, title)
        return when {
            anime && series -> TukTukContentKind.ANIME
            anime -> TukTukContentKind.ANIME_MOVIE
            cartoon -> TukTukContentKind.CARTOON
            series -> TukTukContentKind.SERIES
            else -> TukTukContentKind.MOVIE
        }
    }

    private fun kindForSeries(title: String, url: String): TukTukContentKind = when (classify(title, url)) {
        TukTukContentKind.ANIME, TukTukContentKind.ANIME_MOVIE -> TukTukContentKind.ANIME
        TukTukContentKind.CARTOON -> TukTukContentKind.CARTOON
        else -> TukTukContentKind.SERIES
    }

    private fun titleFrom(element: Element, anchor: Element, image: Element?): String = cleanTitle(
        anchor.attr("title").ifBlank { element.attr("title") }
            .ifBlank { element.selectFirst(".title, .Title, h1, h2, h3, .MovieTitle, .BottomTitle")?.text().orEmpty() }
            .ifBlank { image?.attr("alt").orEmpty() }
            .ifBlank { anchor.text() }
    )

    private fun cleanTitle(value: String): String {
        val decoded = value.replace(Regex("\\s+"), " ").trim()
        return decoded.replace(
            Regex("\\s*(?:[|–—]\\s*)?(?:توك توك سينما|TukTuk(?:Cinema)?|tuktukcinemas\\.com).*$", RegexOption.IGNORE_CASE),
            "",
        ).trim()
    }

    private fun imageUrl(image: Element): String? {
        val srcset = image.attr("data-srcset").ifBlank { image.attr("srcset") }
        if (srcset.isNotBlank()) {
            val best = srcset.split(',').mapNotNull { part ->
                val tokens = part.trim().split(Regex("\\s+"))
                val candidate = tokens.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val width = tokens.getOrNull(1)?.removeSuffix("w")?.toIntOrNull() ?: 0
                candidate to width
            }.maxByOrNull { it.second }?.first
            if (!best.isNullOrBlank()) return best
        }
        return listOf("data-src", "data-lazy-src", "data-original", "data-original-src", "src")
            .firstNotNullOfOrNull { attr -> image.attr(attr).trim().takeIf { it.isNotBlank() && !it.startsWith("data:", true) } }
    }

    private fun resolveExternalImage(raw: String, base: String): String? =
        runCatching { URI(base).resolve(raw).toString() }.getOrNull()

    private fun titleFromSlug(url: String): String =
        runCatching { URI(url).path.orEmpty().substringAfterLast('/').replace('-', ' ').replace('_', ' ') }
            .getOrDefault("TukTuk")
            .replaceFirstChar { it.uppercase(Locale.ROOT) }

    private fun normalizedLooseTitle(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKD)
            .lowercase(Locale.ROOT)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    private fun titleFromDocument(document: Document): String =
        document.selectFirst("h1")?.text().orEmpty()

    private fun extractYear(vararg values: String): Int? {
        for (value in values) {
            yearRegex.find(value)?.value?.toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun extractRating(document: Document, jsonLd: String): Double? {
        val values = listOfNotNull(
            document.selectFirst("[itemprop=ratingValue]")?.let { it.attr("content").ifBlank { it.text() } },
            document.selectFirst(".imdbS strong, .imdb strong, .rating strong, .imdb-rating")?.text(),
            Regex(""""ratingValue"\s*:\s*"?([0-9]+(?:[.,][0-9]+)?)"?""")
                .find(jsonLd)?.groupValues?.getOrNull(1),
        )
        for (raw in values) {
            val value = Regex("[0-9]+(?:[.,][0-9]+)?").find(raw.replace(',', '.'))?.value?.toDoubleOrNull() ?: continue
            when {
                value in 0.0..10.0 -> return value
                value in 10.1..100.0 -> return value / 10.0
            }
        }
        return null
    }

    private fun arabicSeasonOrdinal(value: String): Int? {
        val normalized = value
            .replace("ال", "")
            .replace("أ", "ا")
            .replace("إ", "ا")
            .replace("ى", "ي")
        val entries = listOf(
            listOf("اول", "اولي", "اولى") to 1,
            listOf("ثاني", "ثانيه") to 2,
            listOf("ثالث", "ثالثه") to 3,
            listOf("رابع", "رابعه") to 4,
            listOf("خامس", "خامسه") to 5,
            listOf("سادس", "سادسه") to 6,
            listOf("سابع", "سابعه") to 7,
            listOf("ثامن", "ثامنه") to 8,
            listOf("تاسع", "تاسعه") to 9,
            listOf("عاشر", "عاشره") to 10,
        )
        return entries.firstOrNull { (forms, _) ->
            forms.any { form ->
                Regex("(?:موسم|season)[\\s_-]*$form(?:$|[^\\p{L}])", RegexOption.IGNORE_CASE)
                    .containsMatchIn(normalized)
            }
        }?.second
    }

    private fun asciiDigits(value: String): String = buildString(value.length) {
        for (char in value) {
            val digit = when (char) {
                in '٠'..'٩' -> char.code - '٠'.code
                in '۰'..'۹' -> char.code - '۰'.code
                else -> -1
            }
            append(if (digit >= 0) ('0'.code + digit).toChar() else char)
        }
    }
}
