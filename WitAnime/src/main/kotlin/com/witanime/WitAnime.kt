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
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
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
class WitAnime : MainAPI() {
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

    private val apiHeaders = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "X-Requested-With" to "XMLHttpRequest",
    )

    private val pageHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    )

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
        val document = app.get(url, headers = pageHeaders).document
        val items = document.parseCards()
        return newHomePageResponse(request, items, hasNext = items.isNotEmpty() && request.data != "schedule")
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/search",
            headers = pageHeaders,
            params = mapOf("q" to query)
        ).document
        return document.parseCards()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = pageHeaders).document
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
        val page = try {
            app.get(watchUrl, headers = pageHeaders)
        } catch (_: Exception) {
            return false
        }
        val document = page.document
        val csrf = document.selectFirst("meta[name=csrf-token]")?.attr("content")?.trim()
            ?.ifBlank { null } ?: return false
        val cookieHeader = page.cookies.entries
            .filter { it.value.isNotBlank() }
            .joinToString("; ") { "${it.key}=${it.value}" }

        val sourcesUrl = "$watchUrl/sources"
        val sourceHeaders = buildMap {
            putAll(apiHeaders)
            put("X-CSRF-TOKEN", csrf)
            put("Referer", watchUrl)
            put("Origin", mainUrl)
            if (cookieHeader.isNotBlank()) put("Cookie", cookieHeader)
        }

        val body = try {
            app.post(sourcesUrl, headers = sourceHeaders, data = emptyMap()).text
        } catch (_: Exception) {
            return false
        }

        val root = try {
            JSONObject(body)
        } catch (_: Exception) {
            return false
        }

        var found = false

        val players = root.optJSONObject("players")
        if (players != null) {
            for (quality in players.keys()) {
                val list = players.optJSONArray(quality) ?: continue
                for (i in 0 until list.length()) {
                    val entry = list.optJSONObject(i) ?: continue
                    val token = entry.optString("token").ifBlank { continue }
                    val label = entry.optString("label")
                    val qualityValue = parseQuality(quality)
                    if (emitStream(token, quality, qualityValue, label, watchUrl, csrf, cookieHeader, subtitleCallback, callback)) {
                        found = true
                    }
                }
            }
        }

        // Downloads are surfaced as extra links as a best-effort fallback.
        val downloads = root.optJSONObject("downloads")
        if (downloads != null && !found) {
            for (quality in downloads.keys()) {
                val list = downloads.optJSONArray(quality) ?: continue
                for (i in 0 until list.length()) {
                    val entry = list.optJSONObject(i) ?: continue
                    val token = entry.optString("token").ifBlank { continue }
                    val qualityValue = parseQuality(quality)
                    val resolved = resolveDownload(token, watchUrl, csrf, cookieHeader)
                    if (resolved != null) {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} تحميل $quality",
                                url = resolved,
                                type = INFER_TYPE
                            ) {
                                this.referer = watchUrl
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
        csrf: String,
        cookieHeader: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Prime the gate, then hand the gate URL to loadExtractor.
        try {
            app.post(
                "$mainUrl/watch/stream-source/$token",
                headers = buildMap {
                    putAll(apiHeaders)
                    put("X-CSRF-TOKEN", csrf)
                    put("Referer", watchUrl)
                    put("Origin", mainUrl)
                    if (cookieHeader.isNotBlank()) put("Cookie", cookieHeader)
                },
                data = emptyMap()
            )
        } catch (_: Exception) {
            // Non-fatal: proceed to try the gate anyway.
        }

        val gate = "$mainUrl/watch/stream-gate/$token"
        val display = if (label.isNullOrBlank()) "$name $quality" else "$name $quality - $label"
        val resolved = mutableListOf<ExtractorLink>()
        try {
            loadExtractor(gate, watchUrl, subtitleCallback) { resolved.add(it) }
        } catch (_: Exception) {
        }
        resolved.forEach { link ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = display,
                    url = link.url,
                    type = link.type
                ) {
                    this.referer = link.referer ?: watchUrl
                    this.headers = link.headers
                    this.quality = qualityValue
                }
            )
        }
        return resolved.isNotEmpty()
    }

    private suspend fun resolveDownload(
        token: String,
        watchUrl: String,
        csrf: String,
        cookieHeader: String
    ): String? {
        try {
            app.post(
                "$mainUrl/watch/download-source/$token",
                headers = buildMap {
                    putAll(apiHeaders)
                    put("X-CSRF-TOKEN", csrf)
                    put("Referer", watchUrl)
                    put("Origin", mainUrl)
                    if (cookieHeader.isNotBlank()) put("Cookie", cookieHeader)
                },
                data = emptyMap()
            )
        } catch (_: Exception) {
        }
        return "$mainUrl/watch/download-gate/$token"
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
