package com.witanime

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * WitAnime - https://witanime.site
 *
 * Laravel + Livewire + Alpine.js anime site. Pages are fully server-rendered
 * (jsoup-parseable) and the playable sources are exposed through a small JSON
 * API:
 *
 *   POST /watch/<slug>/<ep>/sources          (X-CSRF-TOKEN) -> { players, downloads }
 *   POST /watch/stream-source/<token>        -> { sandbox, referrerPolicy }
 *   GET  /watch/stream-gate/<token>          -> player iframe (real embed)
 *   POST /watch/download-source/<token>
 *   GET  /watch/download-gate/<token>        -> download file
 *
 * The CSRF token is read from `<meta name="csrf-token">` on the watch page; the
 * session cookies from the same response are forwarded with every API call.
 */
class WitAnime(private val context: android.content.Context) : MainAPI() {
    override var mainUrl = "https://witanime.site"
    override var name = "ويت أنمي (WitAnime)"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.TvSeries,
        TvType.Movie,
        TvType.Cartoon,
    )

    // Solves the Cloudflare challenge in a hidden WebView and reuses the cleared
    // cookies. Must be used sequentially (shared WebView cookie store).
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val cfInterceptor: Interceptor get() = cloudflareKiller

    private val browserUa =
        "Mozilla/5.0 (Linux; Android 14; M2006C3MG Build/UQ1A.240205.004) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.7871.63 Mobile Safari/537.36"

    private val pageHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,ar-MA;q=0.8,ar;q=0.7",
        "User-Agent" to browserUa,
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Dest" to "document",
        "Upgrade-Insecure-Requests" to "1",
    )

    private val xRequestedWith = "mark.via.gp"

    override val mainPage = mainPageOf(
        "home" to "الرئيسية",
        "browse" to "قائمة الأنمي",
        "movies" to "الأفلام",
        "seasonal" to "أنميات الموسم",
        "schedule" to "مواعيد الحلقات",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = when (request.data) {
            "home" -> if (page <= 1) mainUrl else "$mainUrl/page/$page"
            "browse" -> if (page <= 1) "$mainUrl/browse" else "$mainUrl/browse/page/$page"
            "movies" -> if (page <= 1) "$mainUrl/movies" else "$mainUrl/movies/page/$page"
            "seasonal" -> if (page <= 1) "$mainUrl/seasonal" else "$mainUrl/seasonal/page/$page"
            "schedule" -> "$mainUrl/schedule"
            else -> mainUrl
        }
        val document = app.get(url, headers = pageHeaders, interceptor = cfInterceptor).document
        val items = document.parseCards()
        return newHomePageResponse(request, items, hasNext = items.isNotEmpty() && request.data != "schedule")
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/search",
            headers = pageHeaders,
            params = mapOf("q" to query),
            interceptor = cfInterceptor
        ).document
        return document.parseCards()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = pageHeaders, interceptor = cfInterceptor).document
        val isMovie = url.contains("/movie/")

        val title = document.selectFirst("h1")?.text()?.trim()
            ?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.title().substringBefore("—").trim()

        val poster = document.selectFirst("section img[src*=posters]")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = document.selectFirst("p.mb-6.text-sm")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val tags = document.select("div.mb-6.flex.flex-wrap.gap-2.max-md\\:order-2 span")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val year = document.infoValue("السنة")?.toIntOrNull()
        val duration = document.infoValue("المدة")?.filter { it.isDigit() }?.toIntOrNull()
        val statusText = document.selectFirst("div.mb-4 span.bg-neutral-800")?.text()?.trim()

        val recommendations = document.select("a[href*=witanime.site/anime/], a[href*=witanime.site/movie/]")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
            .take(20)

        return if (isMovie) {
            // Movies expose a single "watch" entry point (episode 1).
            val watchUrl = document.selectFirst("a[href*=witanime.site/watch/]")?.attr("href")?.trim()
                ?.ifBlank { null }
                ?: "$mainUrl/watch/${url.trimEnd('/').substringAfterLast('/')}/1"
            newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                this.duration = duration
                this.recommendations = recommendations
            }
        } else {
            val episodes = document.parseEpisodes(url)
            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                this.duration = duration
                this.showStatus = if (statusText?.contains("مستمر") == true) {
                    ShowStatus.Ongoing
                } else {
                    ShowStatus.Completed
                }
                this.recommendations = recommendations
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data = watch page url: /watch/<slug>/<ep>
        val watchUrl = if (data.startsWith("http")) data else "$mainUrl$data"
        println("[WitAnime] loadLinks data=$data watchUrl=$watchUrl")

        // Entire flow runs inside a real (hidden) WebView: Cloudflare rejects the
        // OkHttp client for the /sources endpoint (404) and the site rate-limits
        // aggressively (429), so a browser context is both necessary and does the
        // 429 backoff for us.
        val activity = context as? android.app.Activity
        if (activity == null || activity.isFinishing) {
            println("[WitAnime] no activity for WebView")
            return false
        }
        val wv = try {
            WitAnimeWebView.fetch(
                activity = activity,
                watchUrl = watchUrl,
                cookies = emptyMap(),
                userAgent = browserUa
            )
        } catch (e: Exception) {
            println("[WitAnime] WEBVIEW failed: ${e.message}")
            return false
        }
        println("[WitAnime] WEBVIEW json=${wv.json?.take(400)} err=${wv.error}")
        val raw = wv.json
        if (raw.isNullOrBlank() || !raw.trimStart().startsWith("{")) return false

        var gateMap: Map<String, String> = emptyMap()
        runCatching {
            val gs = JSONObject(raw).optJSONObject("gates") ?: return@runCatching
            gateMap = buildMap {
                for (k in gs.keys()) {
                    val v = gs.optString(k)
                    if (v.isNotBlank()) put(k, v)
                }
            }
        }

        val cookies = try {
            android.webkit.CookieManager.getInstance().getCookie(watchUrl)
                ?.split(";")
                ?.mapNotNull { p ->
                    val kv = p.trim().split("=", limit = 2)
                    if (kv.size == 2) kv[0] to kv[1] else null
                }?.toMap() ?: emptyMap()
        } catch (_: Exception) {
            emptyMap<String, String>()
        }
        println("[WitAnime] webview cookies=${cookies.keys}")
        val finalBody: String = raw

        val root = try {
            JSONObject(finalBody)
        } catch (_: Exception) {
            return false
        }

        var found = false

        val players = root.optJSONObject("players")
        if (players != null) {
            // Warm the gate map from the WebView (browser context) so the exact
            // embed URL is available even if the OkHttp gate is bot-blocked.
            val allTokens = linkedSetOf<String>()
            for (quality in players.keys()) {
                val list = players.optJSONArray(quality) ?: continue
                for (i in 0 until list.length()) {
                    val t = list.optJSONObject(i)?.optString("token")?.ifBlank { null }
                    if (t != null) allTokens.add(t)
                }
            }
            if (gateMap.isEmpty()) {
                val mutable = LinkedHashMap<String, String>()
                for (t in allTokens) {
                    val gateUrl = "$mainUrl/watch/stream-gate/$t"
                    val real = try {
                        WitAnimeWebView.resolveGate(activity, gateUrl, cookies, browserUa)
                    } catch (_: Exception) {
                        null
                    }
                    if (!real.isNullOrBlank()) mutable[t] = real
                }
                gateMap = mutable
                println("[WitAnime] webview gates=${gateMap.values}")
            }
            for (quality in players.keys()) {
                val list = players.optJSONArray(quality) ?: continue
                for (i in 0 until list.length()) {
                    val entry = list.optJSONObject(i) ?: continue
                    val token = entry.optString("token").ifBlank { continue }
                    val label = entry.optString("label")
                    val qualityValue = parseQuality(quality)
                    if (emitStream(token, quality, qualityValue, label, watchUrl, cookies, gateMap[token], subtitleCallback, callback)) {
                        found = true
                    }
                }
            }
        }

        // Downloads are surfaced as extra links. The download gate 302s to the
        // real file host, which we then feed through the extractor pipeline —
        // never emit the gate URL itself (it is not playable, which is what
        // made every download "link" fail with ERROR_CODE_IO_BAD_HTTP_STATUS).
        val downloads = root.optJSONObject("downloads")
        if (downloads != null && !found) {
            for (quality in downloads.keys()) {
                val list = downloads.optJSONArray(quality) ?: continue
                for (i in 0 until list.length()) {
                    val entry = list.optJSONObject(i) ?: continue
                    val token = entry.optString("token").ifBlank { continue }
                    val label = entry.optString("label").ifBlank { null }
                    val qualityValue = parseQuality(quality)
                    val host = resolveDownload(token, watchUrl, cookies) ?: continue
                    val display = if (label == null) "$name تحميل $quality" else "$name تحميل $quality - $label"
                    val resolved = mutableListOf<ExtractorLink>()
                    try {
                        loadExtractor(host, watchUrl, subtitleCallback) { resolved.add(it) }
                    } catch (e: Exception) {
                        println("[WitAnime] download loadExtractor($host) failed: ${e.message}")
                    }
                    if (resolved.isEmpty()) continue
                    resolved.forEach { link ->
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = display,
                                url = link.url,
                                type = link.type
                            ) {
                                this.referer = link.referer
                                this.headers = link.headers
                                this.quality = qualityValue
                            }
                        )
                        found = true
                    }
                }
            }
        }

        return found
    }

    private suspend fun emitStream(
        token: String,
        quality: String,
        qualityValue: Int,
        label: String?,
        watchUrl: String,
        cookies: Map<String, String>,
        webGate: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val gate = "$mainUrl/watch/stream-gate/$token"
        val display = if (label.isNullOrBlank()) "$name $quality" else "$name $quality - $label"
        val resolved = mutableListOf<ExtractorLink>()

        // The WebView already resolved the gate (302) to the real embed host
        // (mega.nz/embed, mp4upload.com/embed, videa.hu/player, 4shared, ...).
        // Feed that straight into the extractor pipeline.
        val candidates = linkedSetOf<String>()
        if (!webGate.isNullOrBlank()) {
            println("[WitAnime] webGate $token -> $webGate")
            candidates.add(webGate)
        }
        // Only hit the site over OkHttp when the browser could not resolve the
        // gate — extra requests to /watch/stream-gate worsen the site's 429 rate
        // limiting, which is exactly what breaks this provider.
        if (webGate.isNullOrBlank()) {
            try {
                val r = app.get(
                    gate,
                    headers = mapOf(
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                        "X-Requested-With" to xRequestedWith,
                        "User-Agent" to browserUa,
                        "Referer" to watchUrl,
                        "Accept-Language" to "en-US,en;q=0.9,ar-MA;q=0.8,ar;q=0.7",
                        "Sec-Fetch-Site" to "same-origin",
                        "Sec-Fetch-Mode" to "navigate",
                        "Sec-Fetch-User" to "?1",
                        "Sec-Fetch-Dest" to "iframe",
                        "Upgrade-Insecure-Requests" to "1",
                    ),
                    cookies = cookies,
                    allowRedirects = false,
                    interceptor = cfInterceptor
                )
                val loc = r.headers["Location"] ?: r.headers["location"]
                println("[WitAnime] gateHTTP $token code=${r.code} location=$loc")
                if (!loc.isNullOrBlank()) candidates.add(loc)
                if (r.url.isNotBlank() && r.url != gate) candidates.add(r.url)
                Regex("""https?://[^"'\\\s]+\.(?:m3u8|mp4)[^"'\\\s]*""")
                    .findAll(r.text).forEach { candidates.add(it.value) }
            } catch (e: Exception) {
                println("[WitAnime] gateHTTP $token failed: ${e.message}")
            }
        }
        if (candidates.isEmpty()) candidates.add(gate)

        candidates.forEach { candidate ->
            // MEGA serves an AES-encrypted blob / HTML embed that ExoPlayer cannot
            // play from a plain URL, and no extractor matches it, so skip it rather
            // than emit a link that always fails.
            if (candidate.contains("mega.nz") || candidate.contains("mega.co.nz")) {
                println("[WitAnime] skipping MEGA candidate $candidate")
                return@forEach
            }
            try {
                loadExtractor(candidate, candidate, subtitleCallback) { resolved.add(it) }
            } catch (e: Exception) {
                println("[WitAnime] loadExtractor($candidate) failed: ${e.message}")
            }
        }
        println("[WitAnime] gate=$gate candidates=${candidates.size} resolved=${resolved.size}")
        resolved.forEach { link ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = display,
                    url = link.url,
                    type = link.type
                ) {
                    // Prefer the embed's referer (required by mp4upload/videa/4shared),
                    // fall back to the WitAnime watch page.
                    this.referer = link.referer ?: watchUrl
                    this.headers = link.headers
                    this.quality = qualityValue
                }
            )
        }
        return resolved.isNotEmpty()
    }

    /**
     * Resolves `GET /watch/download-gate/<token>` to the real file-host URL.
     * The gate is a same-origin 302; read its `Location` without following so the
     * host can be fed to `loadExtractor`. Returns `null` (never the gate URL)
     * when it cannot be resolved.
     */
    private suspend fun resolveDownload(
        token: String,
        watchUrl: String,
        cookies: Map<String, String>
    ): String? {
        val gate = "$mainUrl/watch/download-gate/$token"
        return try {
            val r = app.get(
                gate,
                headers = mapOf(
                    "User-Agent" to browserUa,
                    "Referer" to watchUrl,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                ),
                cookies = cookies,
                allowRedirects = false,
                interceptor = cfInterceptor
            )
            val loc = r.headers["Location"] ?: r.headers["location"]
            if (!loc.isNullOrBlank()) loc else null
        } catch (e: Exception) {
            println("[WitAnime] download-gate $token failed: ${e.message}")
            null
        }
    }

    private fun parseQuality(raw: String?): Int = when (raw?.trim()?.uppercase()) {
        "4K", "2160P", "2160" -> Qualities.P2160.value
        "FHD", "1080P", "1080" -> Qualities.P1080.value
        "HD", "720P", "720" -> Qualities.P720.value
        "SD", "480P", "480" -> Qualities.P480.value
        "360P", "360" -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    private fun Document.infoValue(label: String): String? {
        return select("div.mb-6.grid > div").firstOrNull {
            it.selectFirst("span.text-neutral-400")?.text()?.trim() == label
        }?.selectFirst("p.text-white")?.text()?.trim()
    }

    private fun Document.parseEpisodes(seriesUrl: String): List<Episode> {
        val slug = seriesUrl.trimEnd('/').substringAfterLast('/')
        val episodes = linkedMapOf<Int, Episode>()
        select("a[href*=witanime.site/watch/$slug/]").forEach { a ->
            val href = a.attr("href").trimEnd('/')
            val num = href.substringAfterLast('/').toIntOrNull() ?: return@forEach
            if (episodes.containsKey(num)) return@forEach
            episodes[num] = newEpisode("$mainUrl/watch/$slug/$num") {
                this.name = "الحلقة $num"
                this.episode = num
            }
        }
        return episodes.entries.sortedBy { it.key }.map { it.value }
    }

    private fun Document.parseCards(): List<SearchResponse> {
        return select("a[href*=witanime.site/anime/], a[href*=witanime.site/movie/]")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = attr("href").trim()
        if (href.isBlank()) return null
        // Only accept real content links (avoid nav/other anchors).
        if (!href.matches(Regex(".*/witanime\\.site/(anime|movie)/.+"))) return null

        val title = selectFirst("h3")?.text()?.trim()
            ?: selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        if (title.isBlank()) return null

        val poster = selectFirst("img")?.attr("src")?.trim()
            ?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        val isMovie = href.contains("/movie/")
        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

        return if (isMovie) {
            newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = poster
            }
        } else {
            newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = poster
            }
        }
    }
}
