package com.anime4up

import android.content.Context
import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okio.ByteString.Companion.decodeBase64
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Anime4Up(private val context: Context) : MainAPI() {
    override var mainUrl = "https://w1.anime4up.rest"
    override var name = "انمي فور اب (Anime4Up)"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val usesWebView = false

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Cartoon
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        private val HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "ar,en-US;q=0.7,en;q=0.3"
        )

        private val EPISODE_REGEX = Regex("""(?i)(?:الحلقة|episode|ep)[^\d]*(\d+)""")
        private val NON_DIGITS = Regex("""\D+""")
    }

    override val mainPage = mainPageOf(
        "$mainUrl/home8/" to "أحدث الحلقات المضافة",
        "$mainUrl/قائمة-الانمي/page/" to "قائمة الأنمي",
        "$mainUrl/anime-type/movie-3/page/" to "أفلام الأنمي",
        "$mainUrl/anime-status/مستمر/page/" to "أنميات مستمرة",
        "$mainUrl/anime-status/مكتمل/page/" to "أنميات مكتملة"
    )

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private fun decodeBase64Safe(str: String?): String {
        if (str.isNullOrBlank()) return ""
        return try {
            str.decodeBase64()?.utf8() ?: ""
        } catch (_: Exception) {
            try {
                String(Base64.decode(str, Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
        }
    }

    private suspend fun fetchDocument(url: String, referer: String? = null): Document? {
        val reqHeaders = HEADERS.toMutableMap()
        if (referer != null) reqHeaders["Referer"] = referer

        return try {
            val response = app.get(url, headers = reqHeaders)
            if (response.code in 200..399) {
                response.document
            } else {
                null
            }
        } catch (_: Exception) {
            try {
                app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val linkElem = selectFirst("div.hover > a, h3 a, a.overlay") ?: selectFirst("a") ?: return null
        val rawHref = linkElem.attr("href").trim()
        if (rawHref.isBlank()) return null

        val cleanUrl = rawHref
            .replace("-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-.*".toRegex(), "")
            .replace("/episode/", "/anime/")

        val imgElem = selectFirst("div.hover > img, img.img-responsive, img")
        val title = imgElem?.attr("alt")?.ifBlank { null }
            ?: selectFirst("h3 a, .anime-card-title a, .entry-title")?.text()?.ifBlank { null }
            ?: linkElem.attr("title").ifBlank { null }
            ?: return null

        val posterUrl = imgElem?.attr("data-src")?.ifBlank { null }
            ?: imgElem?.attr("data-lazy-src")?.ifBlank { null }
            ?: imgElem?.attr("src")?.ifBlank { null }

        val typeText = select("div.anime-card-type > a, span.anime-card-type, .type").text()
        val type = when {
            typeText.contains("Movie|فيلم".toRegex(RegexOption.IGNORE_CASE)) -> TvType.AnimeMovie
            typeText.contains("OVA|ONA|خاصة".toRegex(RegexOption.IGNORE_CASE)) -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(title.trim(), fixUrl(cleanUrl), type) {
            this.posterUrl = posterUrl?.let { fixUrl(it) }
            val epNum = EPISODE_REGEX.find(rawHref)?.groupValues?.get(1)?.toIntOrNull()
            if (epNum != null) {
                this.addDubStatus(isDub = title.contains("مدبلج"), epNum)
            } else {
                this.addDubStatus(isDub = title.contains("مدبلج"))
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (request.data.contains("/page/")) {
            "${request.data}$page/"
        } else if (page > 1) {
            "${request.data.trimEnd('/')}/page/$page/"
        } else {
            request.data
        }

        val doc = fetchDocument(targetUrl) ?: return newHomePageResponse(emptyList(), hasNext = false)

        val homeSections = mutableListOf<HomePageList>()

        val contentContainers = doc.select(".page-content-container")
        if (contentContainers.isNotEmpty()) {
            for (container in contentContainers) {
                val headerTitle = container.selectFirst("div.main-widget-head h3, div.main-didget-head h3, h3")?.text()?.trim()
                    ?: request.name

                val items = container.select("div.anime-card-container, div.episodes-card-container, div.anime-card-post")
                    .mapNotNull { it.toSearchResponse() }
                    .distinctBy { it.url }

                if (items.isNotEmpty()) {
                    homeSections.add(
                        HomePageList(
                            name = headerTitle,
                            list = items,
                            isHorizontalImages = headerTitle.contains("حلقات") || headerTitle.contains("أحدث")
                        )
                    )
                }
            }
        }

        if (homeSections.isEmpty()) {
            val items = doc.select("div.anime-card-container, div.episodes-card-container, div.anime-card-post, div.col-md-3, div.row.display-flex > div")
                .mapNotNull { it.toSearchResponse() }
                .distinctBy { it.url }

            if (items.isNotEmpty()) {
                homeSections.add(
                    HomePageList(
                        name = request.name,
                        list = items,
                        isHorizontalImages = request.name.contains("حلقات")
                    )
                )
            }
        }

        val hasNext = doc.selectFirst("ul.pagination li.active + li, a.next, .pagination-next") != null || page < 100
        return newHomePageResponse(homeSections, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?search_param=animes&s=$query"
        val doc = fetchDocument(searchUrl)
            ?: fetchDocument("$mainUrl/?s=$query")
            ?: return emptyList()

        return doc.select("div.anime-card-container, div.episodes-card-container, div.anime-card-post, div.col-md-3, div.row.display-flex > div")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = fixUrl(url)
        var doc = fetchDocument(cleanUrl) ?: return null

        if (cleanUrl.contains("/episode/") || cleanUrl.contains("-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-")) {
            val parentAnimeLink = doc.selectFirst("div.breadcrumb a[href*='/anime/'], a.anime-link, h1.entry-title a")?.attr("href")
                ?: cleanUrl.replace("-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-.*".toRegex(), "").replace("/episode/", "/anime/")

            val parentDoc = fetchDocument(parentAnimeLink)
            if (parentDoc != null) {
                doc = parentDoc
            }
        }

        val title = doc.selectFirst("h1.anime-details-title, h1.entry-title, h1.title, div.anime-details h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: "Anime4Up"

        val poster = doc.selectFirst("div.anime-thumbnail img, div.poster-image img, div.poster img, div.anime-details img")?.let {
            it.attr("data-src").ifBlank { null } ?: it.attr("data-lazy-src").ifBlank { null } ?: it.attr("src")
        }?.let { fixUrl(it) }

        val description = doc.selectFirst("p.anime-story, div.anime-story, div.story")?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")?.trim()

        val year = doc.selectFirst("div.anime-info:contains(بداية العرض), div.anime-info:contains(تاريخ), span:contains(سنة)")?.text()
            ?.let { Regex("""\b(19\d{2}|20\d{2})\b""").find(it)?.value?.toIntOrNull() }

        val typeText = doc.select(".anime-info:contains(النوع) a, div.anime-info:contains(النوع)").text()
        val type = when {
            typeText.contains("Movie|فيلم".toRegex(RegexOption.IGNORE_CASE)) -> TvType.AnimeMovie
            typeText.contains("OVA|ONA|خاصة".toRegex(RegexOption.IGNORE_CASE)) -> TvType.OVA
            else -> TvType.Anime
        }

        val genres = doc.select("ul.anime-genres li a, div.anime-info:contains(التصنيف) a, div.anime-info:contains(النوع) a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val statusText = doc.select(".anime-info:contains(حالة) a, div.anime-info:contains(حالة)").text()
        val status = when {
            statusText.contains("مستمر") -> ShowStatus.Ongoing
            statusText.contains("مكتمل") -> ShowStatus.Completed
            else -> null
        }

        val malId = doc.selectFirst("a.anime-mal, a[href*='myanimelist.net/anime/']")?.attr("href")
            ?.replace(Regex(""".*anime/|/.*"""), "")
            ?.toIntOrNull()

        val episodeElements = doc.select("div#DivEpisodesList > div, div.episodes-list a, div.episodes-card-container, div.episodes-list-content a")
        val episodes = mutableListOf<Episode>()

        for (elem in episodeElements) {
            val aTag = if (elem.tagName() == "a") elem else elem.selectFirst("h3 a, a") ?: continue
            val epHref = fixUrl(aTag.attr("href").trim())
            if (epHref.isBlank()) continue

            val epTitle = aTag.text().trim().ifBlank {
                elem.selectFirst("h3, .episode-title")?.text()?.trim() ?: "حلقة"
            }

            val epNum = EPISODE_REGEX.find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: EPISODE_REGEX.find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                ?: NON_DIGITS.replace(epTitle, "").toIntOrNull()

            val thumb = elem.selectFirst("img")?.let {
                it.attr("data-src").ifBlank { null } ?: it.attr("data-lazy-src").ifBlank { null } ?: it.attr("src")
            }?.let { fixUrl(it) }

            episodes.add(
                newEpisode(epHref) {
                    this.name = epTitle
                    this.episode = epNum
                    this.posterUrl = thumb ?: poster
                }
            )
        }

        val sortedEpisodes = episodes.distinctBy { it.data }.sortedBy { it.episode ?: 9999 }

        return newAnimeLoadResponse(title, cleanUrl, type) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = genres
            this.showStatus = status
            addMalId(malId)
            addEpisodes(if (title.contains("مدبلج")) DubStatus.Dubbed else DubStatus.Subbed, sortedEpisodes)
        }
    }

    data class WatchSources(
        @JsonProperty("fhd") var fhd: Map<String, String>? = null,
        @JsonProperty("hd") var hd: Map<String, String>? = null,
        @JsonProperty("sd") var sd: Map<String, String>? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = fixUrl(data.trim())
        val doc = fetchDocument(episodeUrl) ?: return false

        var foundLinks = false

        // ==========================================
        // 1. WATCH SERVERS: Base64 JSON in input[name="wl"]
        // ==========================================
        val wlVal = doc.selectFirst("input[name='wl']")?.attr("value")
        val decodedWl = decodeBase64Safe(wlVal)
        if (decodedWl.isNotBlank()) {
            try {
                val watchObj = parseJson<WatchSources>(decodedWl)
                val allServers = listOfNotNull(
                    watchObj.fhd?.values,
                    watchObj.hd?.values,
                    watchObj.sd?.values
                ).flatten().distinct()

                for (rawServerUrl in allServers) {
                    val resolvedUrl = fixUrl(rawServerUrl.trim())
                    if (loadExtractor(resolvedUrl, episodeUrl, subtitleCallback, callback)) {
                        foundLinks = true
                    }
                }
            } catch (_: Exception) {
                // Ignore json parsing issues
            }
        }

        // ==========================================
        // 2. MOSHAHDA INTERNAL SERVER (input[name="moshahda"])
        // ==========================================
        val moshahdaId = decodeBase64Safe(doc.selectFirst("input[name='moshahda']")?.attr("value"))
        if (moshahdaId.isNotBlank()) {
            val qualitiesMap = listOf(
                Pair(Qualities.P1080.value, "download_o"),
                Pair(Qualities.P720.value, "download_x"),
                Pair(Qualities.P480.value, "download_h"),
                Pair(Qualities.P360.value, "download_n"),
                Pair(Qualities.P240.value, "download_l")
            )

            for ((quality, code) in qualitiesMap) {
                val streamDirectUrl = "https://moshahda.net/$moshahdaId.html?$code"
                callback.invoke(
                    newExtractorLink(
                        source = "Moshahda",
                        name = "Moshahda (سيرفر فور اب السريع)",
                        url = streamDirectUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://moshahda.net/"
                        this.quality = quality
                        this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://moshahda.net/")
                    }
                )
                foundLinks = true
            }

            if (loadExtractor("https://moshahda.net/embed-$moshahdaId.html", episodeUrl, subtitleCallback, callback)) {
                foundLinks = true
            }
        }

        // ==========================================
        // 3. WATCH SERVERS: Server list tabs & iframes
        // ==========================================
        val watchServerElements = doc.select("ul#episode-servers li a, div.server-item a, div.watch-servers a, iframe[src]")
        for (elem in watchServerElements) {
            val rawUrl = elem.attr("data-ep-url").ifBlank { null }
                ?: elem.attr("data-src").ifBlank { null }
                ?: elem.attr("src").ifBlank { null }
                ?: elem.attr("href").ifBlank { null }
                ?: continue

            val decodedUrl = if (!rawUrl.startsWith("http") && !rawUrl.startsWith("//")) {
                decodeBase64Safe(rawUrl)
            } else {
                rawUrl
            }

            if (decodedUrl.isNotBlank()) {
                val targetUrl = fixUrl(decodedUrl)
                if (loadExtractor(targetUrl, episodeUrl, subtitleCallback, callback)) {
                    foundLinks = true
                }
            }
        }

        // ==========================================
        // 4. DOWNLOAD LINKS (سيرفرات التحميل بجميع الجودات)
        // ==========================================
        val downloadContainers = doc.select("div.download-links, ul.download-links, div.episodes-download-container, div.downloads, table.download-table")
        for (container in downloadContainers) {
            val downloadAnchors = container.select("a[href]")
            for (anchor in downloadAnchors) {
                val dlUrl = anchor.attr("href").trim()
                if (dlUrl.isBlank() || dlUrl.startsWith("#") || dlUrl.startsWith("javascript")) continue

                val dlText = anchor.text().trim()
                val parentText = anchor.parents().select(".quality, .badge, h4, h5, th").firstOrNull()?.text() ?: ""
                val fullLabel = "$parentText $dlText".trim()

                val quality = when {
                    fullLabel.contains("1080") || fullLabel.contains("FHD", ignoreCase = true) -> Qualities.P1080.value
                    fullLabel.contains("720") || fullLabel.contains("HD", ignoreCase = true) -> Qualities.P720.value
                    fullLabel.contains("480") || fullLabel.contains("SD", ignoreCase = true) -> Qualities.P480.value
                    fullLabel.contains("360") -> Qualities.P360.value
                    fullLabel.contains("240") -> Qualities.P240.value
                    else -> Qualities.Unknown.value
                }

                val serverName = when {
                    dlUrl.contains("mega.nz") -> "Mega"
                    dlUrl.contains("mediafire.com") -> "MediaFire"
                    dlUrl.contains("drive.google.com") -> "Google Drive"
                    dlUrl.contains("4shared.com") -> "4Shared"
                    dlUrl.contains("gofile.io") -> "GoFile"
                    dlUrl.contains("send.cm") -> "Sendcm"
                    dlUrl.contains("uqload") -> "Uqload"
                    dlUrl.contains("mp4upload") -> "Mp4Upload"
                    dlUrl.contains("moshahda") -> "Moshahda Download"
                    else -> anchor.text().ifBlank { "سيرفر تحميل" }
                }

                // Try to load via official extractor
                if (loadExtractor(dlUrl, episodeUrl, subtitleCallback, callback)) {
                    foundLinks = true
                } else if (dlUrl.endsWith(".mp4", ignoreCase = true) || dlUrl.contains("mediafire.com") || dlUrl.contains("drive.google.com") || dlUrl.contains("moshahda")) {
                    callback.invoke(
                        newExtractorLink(
                            source = serverName,
                            name = "[تحميل] $serverName ($fullLabel)",
                            url = dlUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = episodeUrl
                            this.quality = quality
                            this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to episodeUrl)
                        }
                    )
                    foundLinks = true
                }
            }
        }

        return foundLinks
    }
}
