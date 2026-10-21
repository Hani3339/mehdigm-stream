package com.anime3rb

import android.util.Log
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
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    private val headers = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
    )

    // =========================================================================
    // Cloudflare handling
    // =========================================================================

    /**
     * Cookies persisted by [Anime3rbWebView] after a challenge was solved.
     * Cloudflare's `cf_clearance` is bound to the solving user-agent, so the
     * same [UA] must be reused everywhere.
     */
    private fun persistedCookies(url: String): Map<String, String> = try {
        android.webkit.CookieManager.getInstance().getCookie(url)
            ?.split(";")
            ?.mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null
                else part.substring(0, idx).trim() to part.substring(idx + 1).trim()
            }
            ?.toMap()
            ?: emptyMap()
    } catch (_: Throwable) {
        emptyMap()
    }

    private fun isCloudflareSolved(): Boolean =
        persistedCookies(mainUrl).keys.any { it == "cf_clearance" }

    /**
     * GET a page, transparently falling back to a real WebView when OkHttp is
     * stopped by Cloudflare's managed challenge (HTTP 403 + `cf-mitigated`).
     */
    private suspend fun getDocument(url: String): Document? {
        val direct = runCatching {
            app.get(url, headers = headers, cookies = persistedCookies(url)).document
        }.getOrNull()
        if (direct != null && !looksLikeChallenge(direct)) return direct

        Log.i(TAG, "challenge detected on $url, falling back to WebView")
        val html = Anime3rbWebView.fetch(context, url, headers) ?: return direct
        return runCatching { org.jsoup.Jsoup.parse(html, url) }.getOrNull() ?: direct
    }

    private suspend fun getText(url: String, referer: String? = null): String? {
        val h = if (referer != null) headers + ("Referer" to referer) else headers
        val direct = runCatching {
            app.get(url, headers = h, cookies = persistedCookies(url)).text
        }.getOrNull()
        if (direct != null && !direct.contains("Just a moment") && !direct.contains("cf-mitigated"))
            return direct

        Log.i(TAG, "challenge detected on $url, falling back to WebView")
        return Anime3rbWebView.fetch(context, url, h) ?: direct
    }

    private fun looksLikeChallenge(doc: Document): Boolean {
        val title = doc.title()
        return title.contains("Just a moment", true) ||
            title.contains("لحظة") ||
            doc.selectFirst("div#challenge-form, form#challenge-form, script[src*=challenges.cloudflare.com]") != null ||
            doc.selectFirst("meta[http-equiv=refresh]") != null && doc.body()?.text()?.contains("Cloudflare") == true
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

    /** Homepage slides are `a.video-card` pointing at `/episode/<slug>/<num>`. */
    private fun parseHomeCards(doc: Document?): List<SearchResponse> {
        if (doc == null) return emptyList()
        val out = LinkedHashMap<String, SearchResponse>()
        for (a in doc.select("a.video-card, a[href^=/episode/]")) {
            val href = absolute(a.attr("href"))
            val slug = episodeSlug(href) ?: continue
            val title = a.selectFirst("div > span, .video-title, span")?.text()?.trim()
                ?: a.selectFirst("img")?.attr("alt")?.trim()
                ?: continue
            if (title.isBlank()) continue
            val poster = a.selectFirst("img")?.let { img(it) }
            out[slug] = newAnimeSearchResponse(title, "$mainUrl/titles/$slug", TvType.Anime) {
                this.posterUrl = poster
            }
        }
        return out.values.toList()
    }

    // =========================================================================
    // Search
    // =========================================================================

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${URLEncoder.encode(query, "UTF-8")}"
        val doc = getDocument(url) ?: return emptyList()
        return parseTitleList(doc)
    }

    /** Parse a grid of anime cards: `a[href^=/titles/]` with a poster + title. */
    private fun parseTitleList(doc: Document?): List<SearchResponse> {
        if (doc == null) return emptyList()
        val out = LinkedHashMap<String, SearchResponse>()
        for (a in doc.select("a[href*=/titles/]")) {
            val href = absolute(a.attr("href"))
            val slug = titleSlug(href) ?: continue
            if (slug == "list" || slug.contains("/")) continue
            val img = a.selectFirst("img")
            val title = a.selectFirst("h2, h3, .title, span")?.text()?.trim()
                ?: img?.attr("alt")?.trim()
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
            ?.substringBefore(" - Anime3rb")?.trim()
            ?: doc.title().substringBefore(" - Anime3rb").trim()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("div.poster img, img.poster, .anime-poster img")?.let { poster(it) }
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

    private fun parseEpisodes(doc: Document, slug: String): List<Episode> {
        val out = ArrayList<Episode>()
        for (a in doc.select("a[href*=/episode/]")) {
            val href = absolute(a.attr("href"))
            val epSlug = episodeSlug(href) ?: continue
            if (epSlug != slug) continue
            val num = href.trimEnd('/').substringAfterLast('/').toIntOrNull() ?: continue
            val epName = a.selectFirst("div > span, span")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "الحلقة $num"
            out.add(newEpisode(href) {
                this.name = epName
                this.episode = num
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
        if (playerUrl != null) {
            if (emitFromPlayer(playerUrl, episodeUrl, callback)) found = true
        }

        // 2) Downloads: `/download/<uuid>?expires=&signature=` per quality.
        for (link in extractDownloads(html)) {
            if (emitDownload(link.first, link.second, episodeUrl, callback)) found = true
        }

        return found
    }

    private fun extractVideoUrl(html: String): String? {
        // JSON-encoded inside the wire:snapshot attribute, and also present in the
        // raw Alpine block as `video_url`.
        val patterns = listOf(
            Regex("""&quot;video_url&quot;:&quot;(https?:\\?/\\?/[^&]+?)&quot;"""),
            Regex(""""video_url"\s*:\s*"(https?:\\?/\\?/[^"]+?)""""),
        )
        for (re in patterns) {
            val m = re.find(html) ?: continue
            return m.groupValues[1]
                .replace("\\/", "/")
                .replace("&amp;", "&")
                .trim()
        }
        return null
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
        val playerHtml = getText(playerUrl, referer) ?: return false
        // video_sources = [{ "src":"https://video.vid3rb.com/video/<uuid>?...","label":"1080p",...}]
        val re = Regex(""""src"\s*:\s*"(https://[^"]+\.(?:mp4|m3u8)[^"]*)"""", RegexOption.IGNORE_CASE)
        val altRe = Regex("""\{\s*"src"\s*:\s*"([^"]+)"\s*,\s*"type"\s*:\s*"video/([a-z0-9]+)"\s*,\s*"label"\s*:\s*"([^"]+)"""")
        var any = false

        for (m in altRe.findAll(playerHtml)) {
            val src = m.groupValues[1].replace("\\/", "/").replace("&amp;", "&")
            val label = m.groupValues[3]
            if (src.isBlank()) continue
            emitStream(src, label, playerUrl, referer, callback)
            any = true
        }
        if (!any) {
            for (m in re.findAll(playerHtml)) {
                val src = m.groupValues[1].replace("\\/", "/").replace("&amp;", "&")
                emitStream(src, qualityLabel(src), playerUrl, referer, callback)
                any = true
            }
        }
        return any
    }

    private suspend fun emitStream(
        url: String,
        label: String,
        playerUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val isHls = url.contains(".m3u8")
        callback(
            newExtractorLink(
                source = name,
                name = "$name - $label",
                url = url,
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = playerUrl
                this.quality = qualityValue(label, url)
                this.headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to playerUrl,
                )
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
