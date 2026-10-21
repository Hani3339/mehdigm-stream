package com.anime3rb

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Anime3rb(private val context: android.content.Context) : MainAPI() {
    override var mainUrl = "https://anime3rb.com"
    override var name = "أنمي عرب (Anime3rb)"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Anime, TvType.AnimeMovie, TvType.OVA,
        TvType.TvSeries, TvType.Movie, TvType.Cartoon
    )

    override val mainPage = mainPageOf(
        "latest" to "أحدث الحلقات",
        "tv" to "مسلسلات",
        "movie" to "أفلام",
        "ova" to "أوفا",
        "ona" to "أونا",
        "special" to "خاصة",
    )

    companion object {
        const val TAG = "Anime3rb"
        // Mobile UA; the site serves the full HTML only to a mobile browser UA.
        private const val UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    private val headers = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
    )

    // =========================================================================
    // Networking (plain, mirrors the working reference implementation)
    // =========================================================================

    private suspend fun getDocument(url: String): Document? = runCatching {
        app.get(url, headers = headers).document
    }.getOrNull()

    private suspend fun getText(url: String, referer: String? = null): String? {
        val h = if (referer != null) headers + ("Referer" to referer) else headers
        return runCatching { app.get(url, headers = h).text }.getOrNull()
    }

    // =========================================================================
    // Main page
    // =========================================================================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = when (request.data) {
            "latest" -> parseHomeCards(getDocument(mainUrl))
            else -> parseTitleList(getDocument("$mainUrl/titles/list/${request.data}?page=$page"))
        }
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = request.data == "latest"),
            hasNext = request.data != "latest" && items.isNotEmpty()
        )
    }

    /** Homepage cards: `a.video-card` pointing at `/episode/<slug>/<num>`. */
    private fun parseHomeCards(doc: Document?): List<SearchResponse> {
        if (doc == null) return emptyList()
        val out = LinkedHashMap<String, SearchResponse>()
        for (a in doc.select("a.video-card, a[href^=/episode/]")) {
            val href = absolute(a.attr("href"))
            val slug = episodeSlug(href) ?: continue
            val img = a.selectFirst("img")
            val title = a.selectFirst("h3.title-name, .title-name, .video-title, h2, h3, span")?.text()?.trim()
                ?: img?.attr("alt")?.trim()?.removePrefix("بوستر ")?.trim()
                ?: continue
            if (title.isBlank()) continue
            val poster = img?.let { poster(it) }
            out[slug] = newAnimeSearchResponse(title, "$mainUrl/titles/$slug", TvType.Anime) {
                this.posterUrl = poster
            }
        }
        return out.values.toList()
    }

    // =========================================================================
    // Search
    // =========================================================================

    /**
     * The site's `/search` route is heavily Cloudflare-protected and its results
     * are rendered client-side by Livewire, so a plain GET returns a challenge
     * (and the app appears to "hang"). Instead we drive the Livewire component
     * directly: grab the `wire:snapshot` + CSRF token from the homepage form and
     * POST the query to `/livewire/update`, then parse the returned HTML.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val livewire = searchViaLivewire(query)
        if (livewire.isNotEmpty()) return livewire

        // Fallback: some deployments still server-render `/titles/list?search=`.
        val url = "$mainUrl/titles/list?search=${URLEncoder.encode(query, "UTF-8")}"
        return parseTitleList(getDocument(url))
    }

    private suspend fun searchViaLivewire(query: String): List<SearchResponse> {
        val home = getDocument(mainUrl) ?: return emptyList()

        val csrf = home.selectFirst("script[src*=livewire.min.js]")?.attr("data-csrf")
            ?: home.selectFirst("meta[name=csrf-token]")?.attr("content")
            ?: return emptyList()

        val snapshotRaw = home.selectFirst("form[wire\\:id]")?.attr("wire:snapshot")
            ?: return emptyList()
        val snapshot = org.jsoup.parser.Parser.unescapeEntities(snapshotRaw, true)

        val body = mapOf(
            "_token" to csrf,
            "components" to listOf(
                mapOf(
                    "snapshot" to snapshot,
                    "updates" to mapOf("query" to query),
                    "calls" to emptyList<Any>()
                )
            )
        )

        val text = runCatching {
            app.post(
                "$mainUrl/livewire/update",
                headers = headers + mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "*/*",
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/",
                    "X-Livewire" to "true",
                ),
                json = body
            ).text
        }.getOrNull() ?: return emptyList()

        if (!text.contains("\"html\"")) return emptyList()

        val json = runCatching {
            com.lagradost.cloudstream3.utils.AppUtils.parseJson<Map<String, Any>>(text)
        }.getOrNull() ?: return emptyList()

        @Suppress("UNCHECKED_CAST")
        val components = json["components"] as? List<Map<String, Any>> ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val effects = components.firstOrNull()?.get("effects") as? Map<String, Any> ?: return emptyList()
        val html = effects["html"] as? String ?: return emptyList()

        return parseLivewireResults(html)
    }

    /** Livewire search results: `a.simple-title-card` with `h4` + `img`. */
    private fun parseLivewireResults(html: String): List<SearchResponse> =
        parseTitleList(org.jsoup.Jsoup.parse(html, mainUrl))

    /** Parse a grid of anime cards: `a.simple-title-card` or `a[href*=/titles/]`. */
    private fun parseTitleList(doc: Document?): List<SearchResponse> {
        if (doc == null) return emptyList()
        val out = LinkedHashMap<String, SearchResponse>()
        // Prefer the dedicated search-result cards when present (they are more
        // precise than the generic title anchors that may include navigation).
        var anchors = doc.select("a.simple-title-card")
        if (anchors.isEmpty()) anchors = doc.select("a[href*=/titles/]")
        for (a in anchors) {
            val href = absolute(a.attr("href"))
            val slug = titleSlug(href) ?: continue
            if (slug == "list" || slug.contains("/")) continue
            val img = a.selectFirst("img")
            val title = a.selectFirst("h2, h3, h4, .title-name, .title, span")?.text()?.trim()
                ?: img?.attr("alt")?.trim()?.removePrefix("بوستر ")?.trim()
                ?: continue
            if (title.isBlank()) continue
            out[slug] = newAnimeSearchResponse(title, "$mainUrl/titles/$slug", TvType.Anime) {
                this.posterUrl = img?.let { poster(it) }
            }
        }
        return out.values.toList()
    }

    // =========================================================================
    // Load (details + episodes)
    // =========================================================================

    override suspend fun load(url: String): LoadResponse {
        val doc = getDocument(url) ?: return newAnimeLoadResponse(url, url, TvType.Anime) {}
        val slug = titleSlug(url) ?: url.substringAfterLast("/")

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.let { cleanTitle(it) }
            ?: cleanTitle(doc.title())
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img[alt^=بوستر], div.poster img, img.poster, .anime-poster img")?.let { poster(it) }
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
            ?.substringBefore(" Anime3rb")?.trim()
        val tags = doc.select("a[href*=/genre/]").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()
        val year = Regex("""\b(19|20)\d{2}\b""").find(doc.select(".anime-info, .details").text())
            ?.value?.toIntOrNull()

        val episodes = parseEpisodes(doc, slug)

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    /** Strip the site's "أنمي "/" - Anime3rb" decorations from a title. */
    private fun cleanTitle(raw: String): String = raw
        .substringBefore(" - Anime3rb")
        .removePrefix("أنمي ")
        .replace(Regex("""\s*\(\s*(مسلسل|فيلم|أوفا|حلقة)\s*\)\s*"""), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun parseEpisodes(doc: Document, slug: String): List<Episode> {
        val out = ArrayList<Episode>()
        for (a in doc.select("div.video-list a[href*=/episode/], a[href*=/episode/]")) {
            val href = absolute(a.attr("href"))
            val epSlug = episodeSlug(href) ?: continue
            if (epSlug != slug) continue
            val num = href.trimEnd('/').substringAfterLast('/').toIntOrNull() ?: continue
            val data = a.selectFirst(".video-data")
            val epName = data?.select("span")?.getOrNull(0)?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "الحلقة $num"
            val thumb = a.selectFirst("img")?.let { img(it) }
            out.add(newEpisode(href) {
                this.name = epName
                this.episode = num
                this.posterUrl = thumb
            })
        }
        return out.distinctBy { it.data }.sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    // =========================================================================
    // loadLinks (streaming + downloads)
    // =========================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = data.trim()
        if (episodeUrl.isBlank()) return false

        val html = getText(episodeUrl) ?: return false
        var found = false

        // 1) Streaming: the Livewire snapshot holds `video_url`, which points at
        //    the vid3rb player; the player page exposes direct MP4 sources.
        val playerUrl = extractVideoUrl(html)
        android.util.Log.i(TAG, "loadLinks playerUrl=$playerUrl")
        if (playerUrl != null) {
            if (emitFromPlayer(playerUrl, episodeUrl, callback)) found = true
        }
        android.util.Log.i(TAG, "loadLinks streaming found=$found")

        // 2) Downloads: `/download/<uuid>?expires=&signature=` per quality.
        //    Only offered when streaming is unavailable — these live on the
        //    Cloudflare-protected main domain and are not playable in-app.
        if (!found) {
            for (link in extractDownloads(html)) {
                emitDownload(link.first, link.second, episodeUrl, callback)
            }
        }

        return found
    }

    private fun extractVideoUrl(html: String): String? {
        // The episode page embeds the vid3rb player URL inside the Livewire
        // snapshot (JSON-escaped `https:\/\/...`). The URL contains a query with
        // `&amp;expires=`; both quotes and the `&` must be kept, otherwise the
        // token is truncated and the player rejects it (HTTP 403 -> 2004).
        val direct = Regex("""https:(?:\\/|/){2}video\.vid3rb\.com(?:\\/|/)player(?:\\/|/)(?:(?!&quot;|\\"|\s).)+""")
            .find(html)?.value
        if (direct != null) return normalizeUrl(direct)

        val patterns = listOf(
            Regex("""&quot;video_url&quot;:&quot;(https?:.+?)&quot;"""),
            Regex(""""video_url"\s*:\s*"(https?:.+?)""""),
        )
        for (re in patterns) {
            val m = re.find(html) ?: continue
            return normalizeUrl(m.groupValues[1])
        }
        return null
    }

    /**
     * JSON escaping turns `https://` into `https:\/\/`, and naive removal of the
     * backslashes yields `https:////`. Unescape `\/` first, decode HTML entities,
     * then collapse any duplicated slashes that follow the scheme.
     */
    private fun normalizeUrl(raw: String): String {
        var u = raw
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("\\", "")
            .trim()
        u = u.replace(Regex("""^https?:/+"""), "https://")
        u = u.replace(Regex("""([^:])/{2,}"""), "$1/")
        return u
    }

    private fun extractDownloads(html: String): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        // <a href="https://anime3rb.com/download/<uuid>?...&signature=..."> .. <label>..جودة..</label>
        val re = Regex("""href="(https://anime3rb\.com/download/[^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        for (m in re.findAll(html)) {
            val href = m.groupValues[1].replace("&amp;", "&")
            val quality = Regex("""\[([^\]]+)\]""").find(m.groupValues[2])?.groupValues?.get(1)
                ?: Regex("""(\d{3,4}p)""").find(m.groupValues[2])?.groupValues?.get(1)
                ?: "تحميل"
            out[href] = quality
        }
        return out.entries.map { it.key to it.value }
    }

    private suspend fun emitFromPlayer(
        playerUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Exactly mirrors the working reference implementation: fetch the vid3rb
        // player page as an embedded iframe (site + cross-site) and read the
        // `var video_sources = [...]` array. No extra cookies/headers.
        val videoHeaders = mapOf(
            "Host" to "video.vid3rb.com",
            "User-Agent" to UA,
            "Referer" to "$mainUrl/",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Site" to "cross-site",
        )

        val playerHtml = runCatching {
            app.get(playerUrl, headers = videoHeaders).text
        }.getOrNull() ?: return false
        android.util.Log.i(TAG, "emitFromPlayer got ${playerHtml.length} chars, hasSources=${playerHtml.contains("video_sources")}")

        var any = false

        // Primary format: `var video_sources = [ {"src":"...","label":"1080p","premium":false}, ... ];
        val arrayRe = Regex(
            """var\s+video_sources\s*=\s*(\[.*?\]);""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        for (arr in arrayRe.findAll(playerHtml)) {
            val json = arr.groupValues[1]
                .replace("\\/", "/")
                .replace("&amp;", "&")
                .replace("\\u0026", "&")
                .replace("\\", "")
            val items = runCatching {
                com.lagradost.cloudstream3.utils.AppUtils
                    .parseJson<List<Map<String, Any>>>(json)
            }.getOrNull() ?: continue
            for (item in items) {
                val src = item["src"]?.toString()
                    ?.replace("\\/", "/")?.replace("&amp;", "&")?.replace("\\u0026", "&")
                    ?.takeIf { it.startsWith("http") } ?: continue
                if (item["premium"]?.toString() == "true") continue
                val label = item["label"]?.toString()?.takeIf { it.isNotBlank() } ?: qualityLabel(src)
                emitStream(src, label, callback)
                any = true
            }
            if (any) break
        }

        // Fallback: bare `"src":"https://..."` entries.
        if (!any) {
            val re = Regex(
                """"src"\s*:\s*"(https://[^"]+\.(?:mp4|m3u8)[^"]*)"""",
                RegexOption.IGNORE_CASE
            )
            for (m in re.findAll(playerHtml)) {
                val src = m.groupValues[1].replace("\\/", "/").replace("&amp;", "&")
                if (src.isBlank()) continue
                emitStream(src, qualityLabel(src), callback)
                any = true
            }
        }
        return any
    }

    private suspend fun emitStream(
        url: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ) {
        // The working reference emits the source with no custom referer/headers;
        // adding them (Host/Referer) makes ExoPlayer's request fail with 2004.
        callback(
            newExtractorLink(
                source = name,
                name = "$name - $label",
                url = url
            ) {
                this.quality = qualityValue(label, url)
            }
        )
    }

    private suspend fun emitDownload(
        downloadUrl: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback(
            newExtractorLink(
                source = name,
                name = "$name تحميل - $label",
                url = downloadUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = qualityValue(label, downloadUrl)
                this.headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to referer,
                    "Accept" to "*/*",
                )
            }
        )
        return true
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun titleSlug(url: String): String? =
        Regex("""/titles/([^/?#]+)""").find(url)?.groupValues?.get(1)

    private fun episodeSlug(url: String): String? =
        Regex("""/episode/([^/?#]+)""").find(url)?.groupValues?.get(1)

    private fun img(el: Element): String? {
        val src = el.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: el.attr("data-src").takeIf { it.isNotBlank() }
            ?: el.attr("data-original").takeIf { it.isNotBlank() }
            ?: el.attr("data-lazy-src").takeIf { it.isNotBlank() }
        return src?.let { absolute(it) }
    }

    private fun poster(el: Element): String? = img(el)

    private fun absolute(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> mainUrl + url
        else -> "$mainUrl/$url"
    }

    private fun qualityValue(label: String, url: String): Int {
        val s = (label + " " + url).lowercase()
        return when {
            s.contains("2160") || s.contains("4k") -> Qualities.P2160.value
            s.contains("1080") || s.contains("hevc") || s.contains("premium") -> Qualities.P1080.value
            s.contains("720") -> Qualities.P720.value
            s.contains("480") -> Qualities.P480.value
            s.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun qualityLabel(url: String): String {
        val s = url.lowercase()
        return when {
            s.contains("1080") -> "1080p"
            s.contains("720") -> "720p"
            s.contains("480") -> "480p"
            s.contains("360") -> "360p"
            else -> "auto"
        }
    }
}
