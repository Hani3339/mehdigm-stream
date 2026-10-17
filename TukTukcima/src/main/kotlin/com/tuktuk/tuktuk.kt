package com.tuktuk

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import kotlinx.coroutines.delay
import java.net.URLDecoder
import java.net.URLEncoder

class TukTukHd : MainAPI() {
    override var mainUrl = "https://tuktukhd.com"
    override var name = "توك توك سينما (TukTuk)"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val hasDownloadSupport = true

    override val mainPage = mainPageOf(
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
        val url = if (request.data.endsWith("/")) request.data + page else request.data + page
        val document = app.get(url, referer = mainUrl).document
        val home = document.select("li.Small--Box, div.Block--Item, li.AsidePost, li.Slides--Item").mapNotNull {
            toSearchResult(it)
        }
        return newHomePageResponse(request.name, home)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val linkTag = element.selectFirst("a[href]") ?: return null
        val href = fixUrl(linkTag.attr("href"))
        if (href.isBlank()) return null
        if (!href.contains(mainUrl.removePrefix("https://")) && !href.contains(mainUrl.removePrefix("http://"))) {
            // keep only internal-ish links
        }
        val title = linkTag.attr("title").ifBlank {
            element.selectFirst(".title, h2, h3")?.text()
                ?: linkTag.text()
        }
        if (title.isBlank()) return null
        val imgTag = element.selectFirst(".Poster--Block img, .Poster img, .SeriesEpisodeThumb img, .SeriesPosterCard img, img")
        val posterUrl = bestImageUrl(imgTag)?.let { fixUrl(it) }

        val lowerTitle = title.lowercase()
        val isMovie = !lowerTitle.contains("مسلسل") && !lowerTitle.contains("حلقة") && !href.contains("/series/")
        val tvType = if (isMovie) TvType.Movie else TvType.TvSeries

        return if (tvType == TvType.Movie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/?s=$encoded&page=$page"
        val doc = app.get(url, referer = mainUrl).document
        val results = doc.select("div.Block--Item, li.Small--Box").mapNotNull { toSearchResult(it) }
        return newSearchResponseList(results, results.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1)?.items ?: emptyList()
    }
    private fun extractYear(title: String): Int? {
        val match = Regex("(19|20)\\d{2}").find(title)
        return match?.value?.toIntOrNull()
    }

    private fun extractRating(doc: org.jsoup.nodes.Document): Int? {
        val text = doc.selectFirst(".imdbS strong")?.text() ?: ""
        return text.toDoubleOrNull()?.times(10)?.toInt()
    }

    private fun extractPlot(doc: org.jsoup.nodes.Document): String? {
        return doc.selectFirst(".story p")?.text() ?: doc.selectFirst(".story")?.text()
    }

    private fun bestImageUrl(img: Element?): String? {
        if (img == null) return null
        val srcset = img.attr("srcset")
        if (srcset.isNotBlank()) {
            val best = srcset.split(',').mapNotNull { part ->
                val p = part.trim().split(Regex("\\s+"))
                val u = p.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val w = p.getOrNull(1)?.removeSuffix("w")?.toIntOrNull()
                    ?: p.getOrNull(1)?.removeSuffix("x")?.toFloatOrNull()?.toInt() ?: 0
                Pair(u, w)
            }.maxByOrNull { it.second }?.first
            if (!best.isNullOrBlank()) return best
        }
        for (attr in listOf("src", "data-src", "data-lazy-src", "data-original", "data-srcset")) {
            val v = img.attr(attr)
            if (v.isNotBlank() && !v.startsWith("data:")) {
                if (attr == "data-srcset") {
                    val b = v.split(',').mapNotNull { part ->
                        val p = part.trim().split(Regex("\\s+"))
                        p.getOrNull(0)?.takeIf { it.isNotBlank() }
                    }.lastOrNull()
                    if (!b.isNullOrBlank()) return b
                } else {
                    return v
                }
            }
        }
        return null
    }

    private fun extractPoster(doc: org.jsoup.nodes.Document): String? {
        val mainImg = doc.selectFirst(".MainSingle .left .image img")
            ?: doc.selectFirst(".MainSingle .image img")
            ?: doc.selectFirst(".image img")
        bestImageUrl(mainImg)?.let { return it }
        return doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("meta[name=twitter:image]")?.attr("content")
    }
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, referer = mainUrl).document
        val ogType = doc.selectFirst("meta[property=og:type]")?.attr("content") ?: ""
        val h1 = doc.selectFirst("h1.post-title, h1")?.text() ?: ""
        val title = h1.replace(Regex("""\s*(الحلقة\s*\d+|مترجم|مدبلج).*"""), "").trim()
        val poster = extractPoster(doc)
        val plot = extractPlot(doc)
        val year = extractYear(h1)
        val rating = extractRating(doc)
        val isEpisode = ogType.contains("episode") || h1.contains("الحلقة")

        if (isEpisode) {
            // find series show page from breadcrumbs
            var showUrl: String? = null
            val crumbs = doc.select("#mpbreadcrumbs a[href]")
            for (c in crumbs) {
                val chref = fixUrl(c.attr("href"))
                if (chref.contains("/series/")) {
                    val ctext = c.text()
                    if (!ctext.contains("الموسم")) {
                        showUrl = chref
                        break
                    }
                }
            }
            // if not found, try to extract from JSON-LD? fallback to showUrl from h1 logic not possible; use current as data
            if (showUrl != null) {
                return loadTvSeries(showUrl, doc, url, title, poster, plot, year, rating)
            }
        }

        val isSeriesPage = doc.select("section.SeriesEpisodesGrid, section.SeriesSeasons, a.SeriesEpisodeCard").isNotEmpty() ||
            doc.selectFirst(".allseasonss") != null || (ogType.contains("series") || url.contains("/series/"))

        if (isSeriesPage && !isEpisode) {
            return loadTvSeriesFromShow(doc, url, title, poster, plot, year, rating)
        }

        // movie
        return newMovieLoadResponse(title.ifBlank { h1 }, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = Score.from10(rating)
        }
    }
    private suspend fun loadTvSeriesFromShow(doc: org.jsoup.nodes.Document, url: String, title: String, poster: String?, plot: String?, year: Int?, rating: Int?): LoadResponse {
        val episodesMap = linkedMapOf<String, Episode>()
        val seasonLinks = mutableListOf<Pair<Int, String>>()
        // from SeriesSeasons
        doc.select("section.SeriesSeasons a[href*='/series/']").forEach { a ->
            val shref = fixUrl(a.attr("href"))
            val stitle = a.attr("title") ?: a.text()
            val sNum = Regex("الموسم\\s*(\\d+)").find(stitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            if (shref != url) {
                seasonLinks.add(Pair(sNum, shref))
            }
        }
        // if no season links, maybe only this page; check episode cards
        if (seasonLinks.isEmpty()) {
            doc.select("a.SeriesEpisodeCard").forEach { ep ->
                val epHref = fixUrl(ep.attr("href"))
                val epTitle = ep.attr("title") ?: ep.text()
                val epNum = Regex("الحلقة\\s*(\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                val epThumb = bestImageUrl(ep.selectFirst("img"))?.let { fixUrl(it) }
                episodesMap[epHref] = newEpisode(epHref) {
                    this.name = epTitle
                    this.episode = epNum
                    this.season = 1
                    this.posterUrl = epThumb
                }
            }
            val sorted = episodesMap.values.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sorted) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = Score.from10(rating)
            }
        }
        // fetch each season
        for ((sNum, surl) in seasonLinks) {
            try {
                val sdoc = app.get(surl, referer = mainUrl).document
                sdoc.select("a.SeriesEpisodeCard").forEach { ep ->
                    val epHref = fixUrl(ep.attr("href"))
                    val epTitle = ep.attr("title") ?: ep.text()
                    val epNum = Regex("الحلقة\\s*(\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                    val epThumb = bestImageUrl(ep.selectFirst("img"))?.let { fixUrl(it) }
                    if (!episodesMap.containsKey(epHref)) {
                        episodesMap[epHref] = newEpisode(epHref) {
                            this.name = epTitle
                            this.episode = epNum
                            this.season = sNum
                            this.posterUrl = epThumb
                        }
                    }
                }
                delay(300)
            } catch (_: Exception) {}
        }
        val sorted = episodesMap.values.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sorted) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = Score.from10(rating)
        }
    }

    private suspend fun loadTvSeries(showUrl: String, episodeDoc: org.jsoup.nodes.Document, episodeUrl: String, title: String, poster: String?, plot: String?, year: Int?, rating: Int?): LoadResponse {
        // fetch show page
        val showDoc = app.get(showUrl, referer = mainUrl).document
        val showTitle = showDoc.selectFirst("h1")?.text() ?: title
        return loadTvSeriesFromShow(showDoc, showUrl, showTitle, extractPoster(showDoc) ?: poster, extractPlot(showDoc) ?: plot, extractYear(showTitle) ?: year, extractRating(showDoc) ?: rating)
    }
    private fun extractQuality(resolution: String?): Int {
        val cleanRes = resolution?.lowercase()?.trim() ?: return Qualities.Unknown.value
        if (cleanRes.contains("x")) {
            val height = cleanRes.substringAfter("x").filter { it.isDigit() }.toIntOrNull()
            if (height != null && height > 0) return height
        }
        val digits = cleanRes.filter { it.isDigit() }.toIntOrNull()
        if (digits != null && digits > 0) {
            return digits
        }
        return when {
            cleanRes.contains("4k") || cleanRes.contains("2160") -> 2160
            cleanRes.contains("2k") || cleanRes.contains("1440") -> 1440
            cleanRes.contains("fhd") || cleanRes.contains("1080") -> 1080
            cleanRes.contains("hd") || cleanRes.contains("720") -> 720
            cleanRes.contains("sd") || cleanRes.contains("480") -> 480
            else -> Qualities.Unknown.value
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, referer = mainUrl).document
        val iframeCrypt = doc.select("iframe#main-video-frame").attr("data-crypt")
        if (iframeCrypt.isEmpty()) return false
        try {
            val decodedBytes = Base64.decode(iframeCrypt, Base64.DEFAULT)
            val playerUrl = String(decodedBytes)
            val initialResponse = app.get(
                playerUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to mainUrl
                )
            )
            val version = Regex(""""version":"([^"]+)"""").find(initialResponse.text)?.groupValues?.get(1)
            if (version != null) {
                val inertiaHeaders = mapOf(
                    "X-Inertia" to "true",
                    "X-Inertia-Version" to version,
                    "X-Inertia-Partial-Component" to "files/mirror/video",
                    "X-Inertia-Partial-Data" to "streams",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to playerUrl,
                    "Accept" to "text/html, application/xhtml+xml"
                )
                val inertiaResponse = app.get(
                    playerUrl,
                    headers = inertiaHeaders
                ).parsed<InertiaResponse>()
                val streams = inertiaResponse.props.streams?.data ?: emptyList()
                val allMirrors = streams.flatMap { streamItem ->
                    val qualityInt = extractQuality(streamItem.resolution ?: streamItem.label)
                    streamItem.mirrors?.map { mirror ->
                        Pair(mirror, qualityInt)
                    } ?: emptyList()
                }
                allMirrors.amap { (mirror, qualityInt) ->
                    val rawLink = mirror.link ?: return@amap
                    val link = if (rawLink.startsWith("//")) "https:$rawLink" else rawLink
                    val displayName = mirror.driver ?: mirror.symbol ?: "Server"
                    when {
                        mirror.driver?.contains("streamruby", ignoreCase = true) == true || mirror.symbol?.contains("streamruby", ignoreCase = true) == true -> {
                            loadExtractor(link, subtitleCallback) { extractorLink ->
                                if (extractorLink.quality == Qualities.Unknown.value) extractorLink.quality = qualityInt
                                callback(extractorLink)
                            }
                        }
                        mirror.driver?.contains("rpmshare", ignoreCase = true) == true -> {
                            // reuse pattern if exists via loadExtractor? or skip
                            loadExtractor(link, subtitleCallback) { extractorLink ->
                                if (extractorLink.quality == Qualities.Unknown.value) extractorLink.quality = qualityInt
                                callback(extractorLink)
                            }
                        }
                        mirror.driver?.contains("earnvids", ignoreCase = true) == true ||
                            mirror.symbol?.contains("TukTukVIP", ignoreCase = true) == true -> {
                            TukTukVipExtractor().getUrl(link, playerUrl, subtitleCallback) { extractorLink ->
                                if (extractorLink.quality == Qualities.Unknown.value) extractorLink.quality = qualityInt
                                callback(extractorLink)
                            }
                        }
                        mirror.driver?.contains("byse", ignoreCase = true) == true -> {
                            TukTukByseExtractor().getUrl(link, playerUrl, subtitleCallback) { extractorLink ->
                                if (extractorLink.quality == Qualities.Unknown.value) extractorLink.quality = qualityInt
                                callback(extractorLink)
                            }
                        }
                        mirror.driver?.contains("lulustream", ignoreCase = true) == true -> {
                            TukTukLuluExtractor().getUrl(link, playerUrl, subtitleCallback) { extractorLink ->
                                if (extractorLink.quality == Qualities.Unknown.value) extractorLink.quality = qualityInt
                                callback(extractorLink)
                            }
                        }
                        else -> {
                            loadExtractor(link, subtitleCallback) { extractorLink ->
                                if (extractorLink.quality == Qualities.Unknown.value) extractorLink.quality = qualityInt
                                callback(extractorLink)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return true
    }

    data class InertiaResponse(
        @JsonProperty("props") val props: InertiaProps
    )
    data class InertiaProps(
        @JsonProperty("streams") val streams: StreamsData?
    )
    data class StreamsData(
        @JsonProperty("data") val data: List<StreamItem>?
    )
    data class StreamItem(
        @JsonProperty("resolution") val resolution: String?,
        @JsonProperty("label") val label: String?,
        @JsonProperty("mirrors") val mirrors: List<MirrorItem>?
    )
    data class MirrorItem(
        @JsonProperty("link") val link: String?,
        @JsonProperty("symbol") val symbol: String?,
        @JsonProperty("driver") val driver: String?
    )
}
