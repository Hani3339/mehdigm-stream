package com.tuktuk

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

class TukTukHd : MainAPI() {
    override var mainUrl = TukTukSiteParser.BASE_URL
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
        "$mainUrl/channel/full-series-1/page/" to "تحميل مسلسلات برابط واحد",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = TukTukSiteParser.pageUrl(request.data, page)
        val document = getSiteDocument(url, "$mainUrl/main/")
            ?: throw IllegalStateException("تعذر تحميل صفحة توك توك: $url")
        val items = TukTukSiteParser.parseCards(document, baseUrl = url).mapNotNull(::toSearchResponse)
        return newHomePageResponse(request.name, items, TukTukSiteParser.hasNextPage(document, url, items.size))
    }

    private fun siteHeaders(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "Referer" to referer,
    )

    private fun playerHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to TukTukMediaUtils.BROWSER_USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "Referer" to referer,
    )

    private suspend fun getSiteDocument(url: String, referer: String = "$mainUrl/main/"): Document? {
        val canonical = TukTukSiteParser.normalizeUrl(url) ?: return null
        return try {
            app.get(canonical, headers = siteHeaders(referer), referer = referer).document
        } catch (e: Exception) {
            Log.w("TukTuk", "Site request failed: $canonical", e)
            null
        }
    }

    private fun toSearchResponse(card: TukTukCard): SearchResponse {
        val type = tvType(card.kind)
        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(card.title, card.url, type) {
                posterUrl = card.poster
            }
            TvType.Movie, TvType.AnimeMovie -> newMovieSearchResponse(card.title, card.url, type) {
                posterUrl = card.poster
            }
            else -> newAnimeSearchResponse(card.title, card.url, type) {
                posterUrl = card.poster
            }
        }
    }

    private fun tvType(kind: TukTukContentKind): TvType = when (kind) {
        TukTukContentKind.MOVIE -> TvType.Movie
        TukTukContentKind.SERIES, TukTukContentKind.SEASON, TukTukContentKind.EPISODE -> TvType.TvSeries
        TukTukContentKind.ANIME -> TvType.Anime
        TukTukContentKind.ANIME_MOVIE -> TvType.AnimeMovie
        TukTukContentKind.CARTOON -> TvType.Cartoon
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (TukTukSearchUtils.normalize(query).isBlank()) {
            return newSearchResponseList(emptyList(), false)
        }

        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val safePage = page.coerceAtLeast(1)
        // WordPress search is rooted at / even though the site's front page is /main/.
        // The final route is retained as a fallback for installations with custom search.
        val urls = buildList {
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
            val document = getSiteDocument(url, "$mainUrl/main/") ?: continue
            val results = TukTukSiteParser.parseCards(document, query, url).mapNotNull(::toSearchResponse)
                .distinctBy { it.url }
            if (results.isNotEmpty()) {
                return newSearchResponseList(
                    results,
                    TukTukSiteParser.hasNextPage(document, url, results.size),
                )
            }
        }
        return newSearchResponseList(emptyList(), false)
    }

    override suspend fun search(query: String): List<SearchResponse> = search(query, 1)?.items.orEmpty()

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val canonicalUrl = TukTukSiteParser.normalizeUrl(url)
            ?: throw IllegalArgumentException("رابط توك توك غير صالح: $url")
        val document = getSiteDocument(canonicalUrl, "$mainUrl/main/")
            ?: throw IllegalStateException("تعذر تحميل صفحة توك توك: $canonicalUrl")
        val metadata = TukTukSiteParser.parseMetadata(document, canonicalUrl)

        if (TukTukSiteParser.isEpisodeUrl(canonicalUrl, metadata.title) || metadata.kind == TukTukContentKind.EPISODE) {
            return loadEpisodePage(document, canonicalUrl, metadata)
        }

        // Season URLs are often separate pages. Prefer the parent series when its
        // breadcrumb is available so CloudStream receives every season together.
        if (TukTukSiteParser.isSeasonPage(canonicalUrl, metadata.title)) {
            val parentUrl = TukTukSiteParser.findParentSeriesUrl(document, canonicalUrl)
            if (parentUrl != null && parentUrl != canonicalUrl) {
                val parentDocument = getSiteDocument(parentUrl, canonicalUrl)
                if (parentDocument != null) {
                    return loadSeriesPage(
                        parentDocument,
                        parentUrl,
                        TukTukSiteParser.parseMetadata(parentDocument, parentUrl),
                    )
                }
            }
        }

        if (TukTukSiteParser.isSeriesPage(document, canonicalUrl, metadata)) {
            return loadSeriesPage(document, canonicalUrl, metadata)
        }
        return loadMoviePage(canonicalUrl, metadata)
    }

    private fun loadMoviePage(url: String, metadata: TukTukMetadata): LoadResponse {
        val type = when (metadata.kind) {
            TukTukContentKind.ANIME -> TvType.AnimeMovie
            else -> tvType(metadata.kind)
        }
        return newMovieLoadResponse(metadata.title, url, type, url) {
            posterUrl = metadata.poster
            plot = metadata.plot
            year = metadata.year
            score = metadata.rating?.let { Score.from10(it) }
        }
    }

    private suspend fun loadEpisodePage(
        document: Document,
        episodeUrl: String,
        metadata: TukTukMetadata,
    ): LoadResponse {
        val requestedEpisode = episodeFromPage(document, episodeUrl, metadata)
        var parentUrl = TukTukSiteParser.findParentSeriesUrl(document, episodeUrl)
        val visited = linkedSetOf<String>()

        // Breadcrumbs sometimes link first to a season rather than to the show.
        // Follow up to three parent levels, but stop safely on cycles or missing pages.
        for (depth in 0 until MAX_PARENT_LOOKUP_DEPTH) {
            val candidate = parentUrl ?: break
            val key = urlKey(candidate)
            if (!visited.add(key)) break
            val parentDocument = getSiteDocument(candidate, episodeUrl) ?: break
            val parentMetadata = TukTukSiteParser.parseMetadata(parentDocument, candidate)
            if (TukTukSiteParser.isSeasonPage(candidate, parentMetadata.title)) {
                parentUrl = TukTukSiteParser.findParentSeriesUrl(parentDocument, candidate)
                continue
            }
            return loadSeriesPage(parentDocument, candidate, parentMetadata, requestedEpisode)
        }

        val fallbackTitle = seriesTitleFromEpisode(metadata.title)
        return newTvSeriesLoadResponse(
            fallbackTitle,
            episodeUrl,
            seriesTvType(metadata.title, episodeUrl, metadata.kind),
            listOf(toCloudstreamEpisode(requestedEpisode)),
        ) {
            posterUrl = metadata.poster
            plot = metadata.plot
            year = metadata.year
            score = metadata.rating?.let { Score.from10(it) }
        }
    }

    private fun episodeFromPage(document: Document, url: String, metadata: TukTukMetadata): TukTukEpisode {
        val breadcrumbs = document.select(
            "#mpbreadcrumbs a[href], .breadcrumb a[href], .breadcrumbs a[href], nav[aria-label*=breadcrumb] a[href]"
        ).joinToString(" ") { "${it.text()} ${it.attr("href")}" }
        val parsedSeason = TukTukSiteParser.seasonNumber(metadata.title, url, breadcrumbs)
        return TukTukEpisode(
            title = metadata.title.ifBlank { "الحلقة" },
            url = url,
            number = TukTukSiteParser.episodeNumber(metadata.title, url),
            season = parsedSeason ?: 1,
            poster = metadata.poster,
            explicitSeason = parsedSeason != null,
        )
    }

    private fun seriesTitleFromEpisode(title: String): String {
        val withoutEpisode = title.replace(
            Regex(
                "\\s*(?:[-:|–—]\\s*)?(?:الحلقة|حلقه|حلقة|episode|ep)\\s*[-:#_]*\\s*[0-9٠-٩۰-۹]+.*$|\\s+s[0-9]{1,2}e[0-9]{1,4}.*$",
                RegexOption.IGNORE_CASE,
            ),
            "",
        ).trim()
        return withoutEpisode.ifBlank { "مسلسل" }
    }

    private suspend fun loadSeriesPage(
        document: Document,
        url: String,
        metadata: TukTukMetadata,
        requestedEpisode: TukTukEpisode? = null,
    ): LoadResponse {
        val canonicalUrl = TukTukSiteParser.normalizeUrl(url) ?: url
        val episodes = linkedMapOf<String, TukTukEpisode>()
        val visitedPages = linkedSetOf<String>()
        val pageBudget = EpisodePageBudget(MAX_TOTAL_EPISODE_PAGES)
        val isSeasonPage = TukTukSiteParser.isSeasonPage(canonicalUrl, metadata.title)
        val defaultSeason = TukTukSiteParser.seasonNumber(metadata.title, canonicalUrl) ?: 1

        collectEpisodePages(
            document,
            canonicalUrl,
            defaultSeason,
            isSeasonPage,
            episodes,
            visitedPages,
            pageBudget,
        )

        val seasons = TukTukSiteParser.parseSeasonRefs(document, canonicalUrl)
            .distinctBy { urlKey(it.url) }
            .take(MAX_SEASONS_TO_SCAN)
        for (season in seasons) {
            if (pageBudget.remaining <= 0) break
            if (urlKey(season.url) == urlKey(canonicalUrl)) continue
            val seasonDocument = getSiteDocument(season.url, canonicalUrl) ?: continue
            collectEpisodePages(
                seasonDocument,
                season.url,
                season.number,
                true,
                episodes,
                visitedPages,
                pageBudget,
            )
        }

        requestedEpisode?.let { addEpisode(episodes, it) }
        val sortedEpisodes = episodes.values.sortedWith(
            compareBy<TukTukEpisode>({ it.season }, { it.number ?: Int.MAX_VALUE }, { it.title.lowercase(Locale.ROOT) }),
        ).map(::toCloudstreamEpisode)

        val displayTitle = seriesDisplayTitle(metadata.title)
        return newTvSeriesLoadResponse(
            displayTitle,
            canonicalUrl,
            seriesTvType(displayTitle, canonicalUrl, metadata.kind),
            sortedEpisodes,
        ) {
            posterUrl = metadata.poster
            plot = metadata.plot
            year = metadata.year
            score = metadata.rating?.let { Score.from10(it) }
        }
    }

    private suspend fun collectEpisodePages(
        firstDocument: Document,
        firstUrl: String,
        defaultSeason: Int,
        defaultSeasonIsExplicit: Boolean,
        episodes: LinkedHashMap<String, TukTukEpisode>,
        visitedPages: MutableSet<String>,
        pageBudget: EpisodePageBudget,
    ) {
        var document = firstDocument
        var pageUrl = firstUrl
        while (pageBudget.remaining > 0) {
            if (!visitedPages.add(urlKey(pageUrl))) return
            pageBudget.remaining -= 1
            TukTukSiteParser.parseEpisodes(document, pageUrl, defaultSeason, defaultSeasonIsExplicit)
                .forEach { addEpisode(episodes, it) }

            val nextUrl = TukTukSiteParser.nextPageUrl(document, pageUrl) ?: break
            val nextKey = urlKey(nextUrl)
            if (nextKey in visitedPages) break
            val nextDocument = getSiteDocument(nextUrl, firstUrl) ?: break
            document = nextDocument
            pageUrl = nextUrl
        }
    }

    private data class EpisodePageBudget(var remaining: Int)

    private fun addEpisode(episodes: LinkedHashMap<String, TukTukEpisode>, candidate: TukTukEpisode) {
        val key = urlKey(candidate.url)
        val previous = episodes[key]
        when {
            previous == null -> episodes[key] = candidate
            !previous.explicitSeason && candidate.explicitSeason -> episodes[key] = candidate
            previous.number == null && candidate.number != null -> episodes[key] = previous.copy(number = candidate.number)
            previous.poster == null && candidate.poster != null -> episodes[key] = previous.copy(poster = candidate.poster)
        }
    }

    private fun seriesDisplayTitle(title: String): String {
        val stripped = title.replace(
            Regex(
                "\\s*(?:[-:|–—]\\s*)?(?:الموسم|موسم|season)\\s*(?:[0-9٠-٩۰-۹]+|ال?أ?ول|ال?ثاني|ال?ثالث|ال?رابع|ال?خامس|ال?سادس|ال?سابع|ال?ثامن|ال?تاسع|ال?عاشر).*$",
                RegexOption.IGNORE_CASE,
            ),
            "",
        ).trim()
        return stripped.ifBlank { title.ifBlank { "مسلسل" } }
    }

    private fun seriesTvType(title: String, url: String, kind: TukTukContentKind): TvType {
        val searchable = "$title $url".lowercase(Locale.ROOT)
        return when {
            kind == TukTukContentKind.ANIME || listOf("أنمي", "انمي", "anime").any { searchable.contains(it) } -> TvType.Anime
            kind == TukTukContentKind.CARTOON || listOf("كرتون", "cartoon", "animation").any { searchable.contains(it) } -> TvType.Cartoon
            else -> TvType.TvSeries
        }
    }

    private fun toCloudstreamEpisode(episode: TukTukEpisode): Episode = newEpisode(episode.url) {
        name = episode.title
        this.episode = episode.number
        season = episode.season
        posterUrl = episode.poster
    }

    private fun urlKey(rawUrl: String): String {
        val normalized = TukTukSiteParser.normalizeUrl(rawUrl) ?: rawUrl
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return normalized
        val path = uri.path.orEmpty().trimEnd('/').ifBlank { "/" }
        return buildString {
            append(uri.host?.lowercase(Locale.ROOT).orEmpty())
            append(path)
            uri.rawQuery?.let { append('?').append(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val episodeUrl = TukTukSiteParser.normalizeUrl(data) ?: return false
        val document = getSiteDocument(episodeUrl, "$mainUrl/main/") ?: return false
        val emittedUrls = linkedSetOf<String>()
        val visitedPlayers = linkedSetOf<String>()
        var emittedCount = 0

        fun emitUnique(link: ExtractorLink, qualityHint: Int = Qualities.Unknown.value) {
            if (link.quality == Qualities.Unknown.value && qualityHint > 0) link.quality = qualityHint
            if (emittedUrls.add(link.url)) {
                emittedCount += 1
                callback(link)
            }
        }

        val directMedia = linkedSetOf<String>()
        document.select("video[src], video source[src], source[type*=mpegurl][src], [data-hls], [data-file]")
            .forEach { element ->
                listOf("src", "data-hls", "data-file").forEach { attribute ->
                    element.attr(attribute).takeIf(String::isNotBlank)?.let(directMedia::add)
                }
            }
        TukTukMediaUtils.mediaUrls(episodeUrl, document.html()).forEach(directMedia::add)
        for (rawUrl in directMedia) {
            runCatching {
                emitDirectMedia(rawUrl, "TukTuk", episodeUrl, Qualities.Unknown.value) { emitUnique(it) }
            }.onFailure { Log.w("TukTuk", "Could not emit direct media $rawUrl", it) }
        }

        for (playerUrl in getPlayerUrls(document, episodeUrl)) {
            expandPlayerPage(
                playerUrl,
                episodeUrl,
                0,
                visitedPlayers,
                subtitleCallback,
            ) { link, quality -> emitUnique(link, quality) }
        }

        Log.i("TukTuk", "Resolved $emittedCount playable link(s) from $episodeUrl")
        return emittedCount > 0
    }

    private fun getPlayerUrls(document: Document, pageUrl: String): List<String> {
        val result = linkedSetOf<String>()
        for (element in document.select("[data-crypt]")) {
            decodePlayerUrl(element.attr("data-crypt"), pageUrl)?.let(result::add)
        }

        val playerElements = (document.select(
            "iframe[src], iframe[data-src], [data-player], [data-player-url], [data-video-url], [data-url], [data-link], .server a[href], .servers a[href], .server-list a[href], .ServerList a[href], [class*=server] a[href]",
        ) + document.select("a[href]").filter { anchor ->
            val href = anchor.attr("href").lowercase(Locale.ROOT)
            listOf("megamax", "share4max", "megatuktuk", "byse", "lulustream", "luluvdo", "upnshare", "videoland", "tuktukvip")
                .any { provider -> href.contains(provider) }
        }).distinct()
        for (element in playerElements) {
            val raw = listOf("src", "data-src", "data-player-url", "data-player", "data-video-url", "data-url", "data-link", "href")
                .firstNotNullOfOrNull { element.attr(it).trim().takeIf(String::isNotBlank) } ?: continue
            decodePlayerUrl(raw, pageUrl)?.takeIf { urlKey(it) != urlKey(pageUrl) }?.let(result::add)
        }
        return result.toList()
    }

    private fun decodePlayerUrl(rawValue: String, baseUrl: String): String? {
        val value = rawValue.trim().replace("&amp;", "&").replace("\\/", "/")
        if (value.isBlank()) return null
        val isDirectReference = value.startsWith("http://", true) || value.startsWith("https://", true) ||
            value.startsWith("//") || value.startsWith("/") || value.startsWith("./") || value.startsWith("../")
        if (isDirectReference) return TukTukMediaUtils.resolveMediaUrl(baseUrl, value)

        val decoded = listOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_WRAP).firstNotNullOfOrNull { flags ->
            runCatching { String(Base64.decode(value, flags), Charsets.UTF_8).trim() }.getOrNull()
                ?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) || it.startsWith("//") || it.startsWith("/") }
        } ?: return null
        return TukTukMediaUtils.resolveMediaUrl(baseUrl, decoded)
    }

    private suspend fun expandPlayerPage(
        rawPlayerUrl: String,
        referer: String,
        depth: Int,
        visitedPlayers: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        emit: (ExtractorLink, Int) -> Unit,
    ) {
        val playerUrl = TukTukMediaUtils.resolveMediaUrl(referer, rawPlayerUrl) ?: return
        val key = playerUrl.lowercase(Locale.ROOT).trimEnd('/')
        if (!visitedPlayers.add(key)) return

        if (isDirectMediaUrl(playerUrl)) {
            resolveMirror(playerUrl, null, null, referer, Qualities.Unknown.value, subtitleCallback) { emit(it, it.quality) }
            return
        }

        // Give CloudStream's registered/custom providers first chance; the HTML walk
        // below remains as a fallback for server lists and nested player frames.
        resolveMirror(playerUrl, null, null, referer, Qualities.Unknown.value, subtitleCallback) {
            emit(it, it.quality)
        }

        var body = try {
            app.get(playerUrl, headers = playerHeaders(referer), referer = referer).text
        } catch (e: Exception) {
            Log.w("TukTuk", "Could not load player page $playerUrl", e)
            return
        }

        val version = inertiaVersion(body)
        if (!version.isNullOrBlank()) {
            try {
                val component = inertiaComponent(body) ?: "files/mirror/video"
                val headers = playerHeaders(playerUrl) + mapOf(
                    "X-Inertia" to "true",
                    "X-Inertia-Version" to version,
                    "X-Inertia-Partial-Component" to component,
                    "X-Inertia-Partial-Data" to "streams",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*;q=0.01",
                )
                body = app.get(playerUrl, headers = headers, referer = playerUrl).text
            } catch (e: Exception) {
                Log.w("TukTuk", "Inertia stream request failed for $playerUrl", e)
            }
        }

        for (stream in parseInertiaStreams(body)) {
            val quality = TukTukMediaUtils.qualityHint(stream.qualityLabel)
            for (mirror in stream.mirrors) {
                resolveMirror(
                    mirror.url,
                    mirror.driver,
                    mirror.symbol ?: mirror.name,
                    playerUrl,
                    quality,
                    subtitleCallback,
                ) { link -> emit(link, quality) }
            }
        }

        for (mediaUrl in TukTukMediaUtils.mediaUrls(playerUrl, body)) {
            runCatching {
                emitDirectMedia(mediaUrl, "TukTuk", playerUrl, Qualities.Unknown.value) { emit(it, it.quality) }
            }.onFailure { Log.w("TukTuk", "Could not emit fallback media URL $mediaUrl", it) }
        }

        if (depth >= MAX_PLAYER_DEPTH) return
        val playerDocument = Jsoup.parse(body, playerUrl)
        for (nestedUrl in getPlayerUrls(playerDocument, playerUrl)) {
            if (urlKey(nestedUrl) == urlKey(playerUrl)) continue
            expandPlayerPage(
                nestedUrl,
                playerUrl,
                depth + 1,
                visitedPlayers,
                subtitleCallback,
                emit,
            )
        }
    }

    private fun inertiaVersion(html: String): String? {
        val payload = Jsoup.parse(html).selectFirst("[data-page]")?.attr("data-page").orEmpty()
        val fromPayload = runCatching { JSONObject(payload).optString("version") }.getOrNull()
        if (!fromPayload.isNullOrBlank() && fromPayload != "null") return fromPayload
        return Regex("[\"']version[\"']\\s*:\\s*[\"']([^\"']+)[\"']")
            .find(html)?.groupValues?.getOrNull(1)
    }

    private fun inertiaComponent(html: String): String? {
        val payload = Jsoup.parse(html).selectFirst("[data-page]")?.attr("data-page").orEmpty()
        return runCatching { JSONObject(payload).optString("component") }
            .getOrNull()?.takeIf { it.isNotBlank() && it != "null" }
    }

    private data class MirrorItem(
        val url: String,
        val driver: String? = null,
        val symbol: String? = null,
        val name: String? = null,
    )

    private data class StreamItem(
        val qualityLabel: String?,
        val mirrors: List<MirrorItem>,
    )

    private fun parseInertiaStreams(rawBody: String): List<StreamItem> {
        val root = parseJsonObject(rawBody) ?: return emptyList()
        val props = root.optJSONObject("props")
            ?: root.optJSONObject("page")?.optJSONObject("props")
            ?: return emptyList()
        val streams = props.opt("streams") ?: return emptyList()
        val streamItems = asJsonValues(streams).flatMap(::asJsonValues)
        return streamItems.mapNotNull { value ->
            val stream = value as? JSONObject ?: return@mapNotNull null
            val quality = firstJsonString(stream, "resolution", "label", "quality", "name")
            val mirrors = mutableListOf<MirrorItem>()
            for (key in listOf("mirrors", "servers", "sources", "links", "data")) {
                stream.opt(key)?.let { nested -> mirrors += parseMirrorItems(nested) }
            }
            firstJsonString(stream, "url", "link", "href", "source")?.let { url ->
                mirrors.add(
                    0,
                    MirrorItem(
                        url = url,
                        driver = firstJsonString(stream, "driver", "provider", "server"),
                        symbol = firstJsonString(stream, "symbol"),
                        name = firstJsonString(stream, "name"),
                    ),
                )
            }
            if (mirrors.isEmpty()) return@mapNotNull null
            StreamItem(quality, mirrors.distinctBy { it.url })
        }
    }

    private fun parseJsonObject(raw: String): JSONObject? = runCatching {
        val body = raw.trim().removePrefix("for(;;);").trim()
        JSONObject(body)
    }.getOrNull()

    private fun asJsonValues(value: Any?): List<Any> = when (value) {
        null, JSONObject.NULL -> emptyList()
        is JSONArray -> (0 until value.length()).mapNotNull { index -> value.opt(index)?.takeUnless { it == JSONObject.NULL } }
        is JSONObject -> {
            val nested = value.opt("data") ?: value.opt("items") ?: value.opt("streams")
            if (nested != null && nested !== value) asJsonValues(nested) else listOf(value)
        }
        is String -> {
            val text = value.trim()
            when {
                text.startsWith("[") -> runCatching { asJsonValues(JSONArray(text)) }.getOrDefault(emptyList())
                text.startsWith("{") -> runCatching { asJsonValues(JSONObject(text)) }.getOrDefault(emptyList())
                else -> emptyList()
            }
        }
        else -> emptyList()
    }

    private fun parseMirrorItems(value: Any?, inheritedName: String? = null): List<MirrorItem> {
        return when (value) {
            null, JSONObject.NULL -> emptyList()
            is JSONArray -> (0 until value.length()).flatMap { index ->
                parseMirrorItems(value.opt(index), inheritedName)
            }
            is JSONObject -> {
                val directUrl = firstJsonString(value, "link", "url", "href", "source")
                val direct = directUrl?.let {
                    listOf(
                        MirrorItem(
                            url = it,
                            driver = firstJsonString(value, "driver", "provider", "server") ?: inheritedName,
                            symbol = firstJsonString(value, "symbol"),
                            name = firstJsonString(value, "name") ?: inheritedName,
                        ),
                    )
                }.orEmpty()
                val nested = mutableListOf<MirrorItem>()
                val ignoredKeys = setOf(
                    "link", "url", "href", "source", "driver", "provider", "server", "symbol", "name",
                    "resolution", "quality", "label", "id", "created_at", "updated_at",
                )
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key in ignoredKeys) continue
                    val child = value.opt(key) ?: continue
                    if (child is JSONObject || child is JSONArray || child is String) {
                        nested += parseMirrorItems(child, key.takeIf { it !in setOf("data", "items", "mirrors", "servers", "sources", "links") } ?: inheritedName)
                    }
                }
                direct + nested
            }
            is String -> {
                val text = value.trim()
                if (isLinkCandidate(text)) listOf(MirrorItem(text, driver = inheritedName, name = inheritedName))
                else if (text.startsWith("[") || text.startsWith("{")) asJsonValues(text).flatMap { parseMirrorItems(it, inheritedName) }
                else emptyList()
            }
            else -> emptyList()
        }
    }

    private fun firstJsonString(json: JSONObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            json.opt(key)?.takeUnless { it == JSONObject.NULL }?.toString()
                ?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", true) }
        }

    private fun isLinkCandidate(value: String): Boolean =
        value.startsWith("http://", true) || value.startsWith("https://", true) || value.startsWith("//") ||
            value.startsWith("/") || Regex("\\.(?:m3u8|m3u|mp4|m4v|mpd)(?:$|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(value)

    private fun isDirectMediaUrl(url: String): Boolean =
        Regex("\\.(?:m3u8|m3u|mp4|m4v|mpd)(?:$|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(url) ||
            url.contains("/master.txt", ignoreCase = true)

    private fun streamHeaders(mediaUrl: String, referer: String): Map<String, String> = mapOf(
        "User-Agent" to TukTukMediaUtils.BROWSER_USER_AGENT,
        "Referer" to referer,
        "Origin" to TukTukMediaUtils.originOf(mediaUrl, mainUrl),
    )

    private suspend fun emitDirectMedia(
        rawUrl: String,
        sourceName: String,
        referer: String,
        qualityHint: Int,
        callback: (ExtractorLink) -> Unit,
    ) {
        val url = TukTukMediaUtils.resolveMediaUrl(referer, rawUrl) ?: return
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
            newExtractorLink(source = sourceName, name = sourceName, url = url) {
                this.referer = referer
                this.quality = qualityHint.takeIf { it > 0 } ?: Qualities.Unknown.value
                this.type = when {
                    isHls -> ExtractorLinkType.M3U8
                    isDash -> ExtractorLinkType.DASH
                    else -> ExtractorLinkType.VIDEO
                }
                this.headers = headers
            },
        )
    }

    private suspend fun resolveMirror(
        rawUrl: String,
        driver: String?,
        symbol: String?,
        playerUrl: String,
        quality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val link = TukTukMediaUtils.resolveMediaUrl(playerUrl, rawUrl) ?: return
        val hint = "${driver.orEmpty()} ${symbol.orEmpty()} $link".lowercase(Locale.ROOT)
        val sourceName = driver?.takeIf(String::isNotBlank)
            ?: symbol?.takeIf(String::isNotBlank)
            ?: runCatching { URI(link).host?.removePrefix("www.") }.getOrNull()
            ?: "TukTuk"
        try {
            when {
                isDirectMediaUrl(link) -> emitDirectMedia(link, sourceName, playerUrl, quality, callback)
                hint.contains("megamax") || hint.contains("share4max") ||
                    TukTukMediaUtils.isMegaMaxUrl(link, driver.orEmpty()) ->
                    TukTukMegaMaxExtractor().getUrl(link, playerUrl, subtitleCallback, callback)
                hint.contains("byse") || hint.contains("byseraguci") ->
                    TukTukByseExtractor().getUrl(link, playerUrl, subtitleCallback, callback)
                hint.contains("lulustream") || hint.contains("luluvdo") || hint.contains("lulu") ->
                    TukTukLuluExtractor().getUrl(link, playerUrl, subtitleCallback, callback)
                hint.contains("upnshare") || hint.contains("upns.one") || hint.contains("upn") ->
                    TukTukUpnExtractor().getUrl(link, playerUrl, subtitleCallback, callback)
                hint.contains("tuktukvip") || hint.contains("lolololu") || hint.contains("vip") ->
                    TukTukVipExtractor().getUrl(link, playerUrl, subtitleCallback, callback)
                hint.contains("videoland") ->
                    VideolandExtractor().getUrl(link, playerUrl, subtitleCallback, callback)
                else -> loadExtractor(link, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.w("TukTuk", "Mirror extraction failed ($driver / $symbol): $link", e)
        }
    }

    private companion object {
        const val MAX_PARENT_LOOKUP_DEPTH = 3
        const val MAX_SEASONS_TO_SCAN = 40
        const val MAX_TOTAL_EPISODE_PAGES = 80
        const val MAX_PLAYER_DEPTH = 3
    }
}
