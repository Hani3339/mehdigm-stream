package com.anime3rb

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Anime3rb(private val context: android.content.Context) : MainAPI() {
    override var mainUrl = "https://anime3rb.com"
    override var name = "أنمي عرب (Anime3rb)"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        const val TAG = "Anime3rb"
        private var savedCookies: String = ""
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        val NON_DIGITS = Regex("[^0-9]")
        val TITLE_EP_REGEX = Regex("الحلقة \\d+")
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية"
    )

    private fun toAbsoluteUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private fun cleanTitleText(text: String): String {
        return text.replace("\\n", " ")
            .replace("\n", " ")
            .replace(Regex("بترجمة.*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
    private suspend fun getDocumentSmart(url: String): Document? {
        return try {
            val response = app.get(url, headers = mapOf("User-Agent" to USER_AGENT))
            if (response.code in 200..399) {
                val setCookie = response.headers["set-cookie"]
                if (!setCookie.isNullOrBlank()) savedCookies = setCookie
                response.document
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val doc = getDocumentSmart(request.data) ?: return null
        val homeSets = mutableListOf<HomePageList>()

        val pinnedHeader = doc.selectFirst("h2:contains(الأنميات المثبتة)")
        val pinnedList = pinnedHeader?.parent()?.parent()?.parent()?.select(".glide__slide:not(.glide__slide--clone) a.video-card")?.mapNotNull {
            toSearchResult(it)
        }
        if (!pinnedList.isNullOrEmpty()) {
            homeSets.add(HomePageList("الأنميات المثبتة", pinnedList))
        }

        val latestList = doc.select("#videos a.video-card").mapNotNull {
            toSearchResult(it)
        }
        if (latestList.isNotEmpty()) {
            homeSets.add(HomePageList("أحدث الحلقات", latestList))
        }

        val addedHeader = doc.selectFirst("h3:contains(آخر الأنميات المضافة)")
        val addedList = addedHeader?.parent()?.parent()?.parent()?.select(".glide__slide:not(.glide__slide--clone) a.video-card")?.mapNotNull {
            toSearchResult(it)
        }
        if (!addedList.isNullOrEmpty()) {
            homeSets.add(HomePageList("آخر الأنميات المضافة", addedList))
        }

        return newHomePageResponse(homeSets, false)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        try {
            val rawTitle = element.select("h3.title-name").text()
            val title = cleanTitleText(rawTitle)
            val href = toAbsoluteUrl(element.attr("href"))
            val posterUrl = element.select("img").attr("src")
            val episodeText = cleanTitleText(element.select("p.number").text())
            val episodeNum = episodeText.filter { it.isDigit() }.toIntOrNull()

            return newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
                addDubStatus(false, episodeNum)
            }
        } catch (e: Exception) {
            return null
        }
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val mainDoc = getDocumentSmart(mainUrl) ?: return emptyList()

        val scriptTag = mainDoc.selectFirst("script[src*=livewire.min.js]")
        val csrfToken = scriptTag?.attr("data-csrf") ?: return emptyList()

        val form = mainDoc.selectFirst("form[wire:id]")
        val snapshotRaw = form?.attr("wire:snapshot") ?: return emptyList()
        val snapshotStr = org.jsoup.parser.Parser.unescapeEntities(snapshotRaw, true)

        val headers = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Content-Type" to "application/json",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/"
        )
        if (savedCookies.isNotBlank()) {
            headers["Cookie"] = savedCookies
        }

        val updateUrl = "$mainUrl/livewire/update"
        val payload = mapOf(
            "_token" to csrfToken,
            "components" to listOf(
                mapOf(
                    "snapshot" to snapshotStr,
                    "updates" to mapOf("query" to query),
                    "calls" to emptyList<Any>()
                )
            )
        )

        val postRes = app.post(updateUrl, headers = headers, json = payload)
        if (postRes.code != 200) return emptyList()

        val responseJson = AppUtils.parseJson<Map<String, Any>>(postRes.text)
        val components = responseJson["components"] as? List<Map<String, Any>> ?: return emptyList()
        val effects = components.firstOrNull()?.get("effects") as? Map<String, Any> ?: return emptyList()
        val htmlContent = effects["html"] as? String ?: return emptyList()

        val soupResults = Jsoup.parse(htmlContent)

        return soupResults.select("a.simple-title-card").mapNotNull { item ->
            val rawTitle = item.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
            val title = cleanTitleText(rawTitle)
            val link = item.attr("href")
            val absoluteLink = toAbsoluteUrl(link)
            val img = item.selectFirst("img")
            val image = img?.attr("src")
            val ratingTag = item.selectFirst(".badge")
            val rating = ratingTag?.text()?.trim() ?: "N/A"

            val type = if (rating.contains("Movie") || rating.contains("Film") || title.contains("فيلم")) {
                TvType.AnimeMovie
            } else {
                TvType.Anime
            }

            newAnimeSearchResponse(title, absoluteLink, type) {
                this.posterUrl = image
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = getDocumentSmart(url) ?: return null

        val title = TITLE_EP_REGEX.replace(doc.selectFirst("h1")?.text() ?: "", "").trim()
        val poster = doc.selectFirst("img[alt*='بوستر']")?.attr("src") ?: ""
        val desc = doc.selectFirst("p.synopsis")?.text() ?: doc.selectFirst("meta[name='description']")?.attr("content") ?: ""

        val episodes = doc.select(".video-list a").reversed().mapNotNull { element ->
            val href = toAbsoluteUrl(element.attr("href"))
            val videoData = element.selectFirst(".video-data")
            val epText = videoData?.child(0)?.text() ?: ""
            val epName = videoData?.child(1)?.text() ?: ""
            val epNum = epText.replace(NON_DIGITS, "").toIntOrNull()
            val imgAttr = element.selectFirst("img")?.attr("src") ?: ""

            newEpisode(href) {
                this.name = epName.ifBlank { epText }
                this.episode = epNum
                this.posterUrl = imgAttr
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = desc
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = data.trim()
        if (episodeUrl.isBlank()) return false

        val html = runCatching {
            app.get(episodeUrl, headers = mapOf("User-Agent" to USER_AGENT)).text
        }.getOrNull() ?: return false
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
            "User-Agent" to USER_AGENT,
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
                AppUtils.parseJson<List<Map<String, Any>>>(json)
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
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Accept" to "*/*",
                )
            }
        )
        return true
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
