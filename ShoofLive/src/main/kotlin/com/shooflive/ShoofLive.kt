package com.shooflive

import android.util.Log
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONArray
import org.json.JSONObject

/**
 * ============================================================================
 *  شوف لايف — ShoofLive
 * ============================================================================
 *  Site: https://shooflive.watch (WordPress + "AlbaPlayer" embeds)
 *
 *  Data sources
 *  ------------
 *   * `/page/N/`                       — home listing (cards carry posters)
 *   * `/?s=<query>`                    — "smart search v2" result cards
 *   * `/wp-json/wp/v2/search`          — episode search (REST)
 *   * `/series/<slug>/`                — series page with its episode grid
 *   * `/movies/<slug>/`, `/episode/<slug>/` — single item pages
 *
 *  Playback
 *  --------
 *  Every item page embeds `https://shooof.online/albaplayer/<slug>`, which
 *  lists the real servers (AnaFast, Voe, MP4Plus, VidSpeed, Vk, …). Each
 *  server page is handed to [ShoofLiveExtractors].
 */
class ShoofLive : MainAPI() {

    companion object {
        private const val TAG = "ShoofLive"
        private const val BASE = ShoofLiveParser.BASE
        private const val REST = "$BASE/wp-json/wp/v2"
        private const val ALBA_HOST = ShoofLiveParser.ALBA_HOST
    }

    override var lang = "ar"
    override var name = "شوف لايف (ShoofLive)"
    override val usesWebView = false
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // ------------------------------------------------------------------ http

    private suspend fun fetch(url: String, referer: String? = null): String? {
        return try {
            val res = app.get(url, headers = ShoofLiveExtractors.pageHeaders(referer), allowRedirects = true)
            if (res.code !in 200..299) {
                Log.i(TAG, "fetch $url -> HTTP ${res.code}")
                null
            } else res.text
        } catch (e: Throwable) {
            Log.i(TAG, "fetch $url failed: $e")
            null
        }
    }

    // ------------------------------------------------------------------ main page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) BASE else "$BASE/page/$page/"
        val html = fetch(url) ?: return newHomePageResponse(emptyList())
        val cards = ShoofLiveParser.parseCards(html)

        return newHomePageResponse(
            listOf(
                HomePageList(
                    "أحدث الحلقات",
                    cards.filter { it.type == "episode" }.map { it.toSearchItem() }
                ),
                HomePageList(
                    "المسلسلات",
                    cards.filter { it.type == "series" }.map { it.toSearchItem() }
                ),
                HomePageList(
                    "الأفلام",
                    cards.filter { it.type == "movies" }.map { it.toSearchItem() }
                )
            )
        )
    }

    private fun ShoofLiveParser.Card.toSearchItem() = newMovieSearchResponse(
        title.ifBlank { ShoofLiveParser.slugToTitle(url) },
        url,
        if (type == "movies") TvType.Movie else TvType.TvSeries
    ) {
        this.posterUrl = poster
    }

    // ------------------------------------------------------------------ search

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        // 1) smart search page (series / movies with posters)
        val html = fetch("$BASE/?s=${java.net.URLEncoder.encode(q, "UTF-8")}")
        if (html != null) {
            for (card in ShoofLiveParser.parseSearchCards(html)) {
                if (!seen.add(card.url)) continue
                results += newMovieSearchResponse(
                    card.title.ifBlank { ShoofLiveParser.slugToTitle(card.url) },
                    card.url,
                    if (card.type == "movies") TvType.Movie else TvType.TvSeries
                ) {
                    this.posterUrl = card.poster
                }
            }
        }

        // 2) REST search for episodes (the HTML search only returns works)
        try {
            val res = app.get(
                "$REST/search?search=${java.net.URLEncoder.encode(q, "UTF-8")}&subtype=episodes&per_page=20",
                headers = ShoofLiveExtractors.pageHeaders()
            )
            if (res.code in 200..299) {
                val arr = JSONArray(res.text)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val url = o.optString("url")
                    if (url.isBlank() || !seen.add(url)) continue
                    val title = ShoofLiveParser.clean(o.optString("title"))
                    results += newMovieSearchResponse(
                        title.ifBlank { ShoofLiveParser.slugToTitle(url) },
                        url,
                        TvType.TvSeries
                    )
                }
            }
        } catch (e: Throwable) {
            Log.i(TAG, "rest search failed: $e")
        }

        return results
    }

    // ------------------------------------------------------------------ details

    override suspend fun load(url: String): LoadResponse {
        val target = url.trim()
        return when {
            target.contains("/episode/") -> loadEpisode(target)
            target.contains("/series/") -> loadSeries(target)
            else -> loadMovie(target)
        }
    }

    private suspend fun loadSeries(url: String): LoadResponse {
        val html = fetch(url) ?: throw Exception("تعذّر تحميل صفحة المسلسل")
        val header = ShoofLiveParser.seriesHeader(html)
        val title = ShoofLiveParser.firstH1(header).ifBlank {
            ShoofLiveParser.firstH1(html).ifBlank { ShoofLiveParser.slugToTitle(url) }
        }
        val poster = ShoofLiveParser.extractPoster(header) ?: ShoofLiveParser.ldThumbnail(html)

        val episodes = ShoofLiveParser.parseCards(html)
            .filter { it.type == "episode" }
            .map { card ->
                newEpisode(card.url) {
                    this.name = card.title.ifBlank { "الحلقة ${card.episode ?: 1}" }
                    this.posterUrl = card.poster
                    this.season = ShoofLiveParser.seasonOf(card.title) ?: 1
                    this.episode = card.episode ?: ShoofLiveParser.episodeOf(card.title)
                }
            }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
        }
    }

    private suspend fun loadEpisode(url: String): LoadResponse {
        val html = fetch(url) ?: throw Exception("تعذّر تحميل صفحة الحلقة")
        val title = ShoofLiveParser.firstH1(html).ifBlank { ShoofLiveParser.slugToTitle(url) }
        val poster = ShoofLiveParser.ldThumbnail(html) ?: ShoofLiveParser.extractPoster(html)

        val episode = newEpisode(url) {
            this.name = title
            this.posterUrl = poster
            this.season = ShoofLiveParser.seasonOf(title) ?: 1
            this.episode = ShoofLiveParser.episodeOf(title) ?: 1
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(episode)) {
            this.posterUrl = poster
        }
    }

    private suspend fun loadMovie(url: String): LoadResponse {
        val html = fetch(url) ?: throw Exception("تعذّر تحميل صفحة الفيلم")
        val title = ShoofLiveParser.firstH1(html).ifBlank { ShoofLiveParser.slugToTitle(url) }
        val poster = ShoofLiveParser.ldThumbnail(html) ?: ShoofLiveParser.extractPoster(html)

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
        }
    }

    // ------------------------------------------------------------------ playback

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val target = data.trim()
        if (target.isBlank()) return false

        val embeds = resolveEmbeds(target) ?: return false
        if (embeds.isEmpty()) return false

        var found = false
        for (embed in embeds) {
            Log.i(TAG, "resolve server ${embed.name} -> ${embed.url}")
            val ok = ShoofLiveExtractors.emit(embed.url, target, embed.name, subtitleCallback, callback)
            found = found || ok
        }
        return found
    }

    /**
     * Turns an item page (or a direct AlbaPlayer url) into the list of servers
     * that still have to be resolved by the extractor.
     */
    private suspend fun resolveEmbeds(target: String): List<ShoofLiveExtractors.Embed>? {
        // Direct AlbaPlayer link.
        if (target.contains(ALBA_HOST) && target.contains("/albaplayer/")) {
            return albaServers(target, null)
        }

        val html = fetch(target) ?: return null

        // Legacy server list of the theme (kept for compatibility).
        val legacy = ShoofLiveParser.legacyServers(html)
        if (legacy.isNotEmpty()) {
            return legacy.map { ShoofLiveExtractors.Embed(it.name, it.href) }
        }

        val base = resolveAlbaBase(html) ?: return null
        return albaServers(base, null)
    }

    /**
     * Finds the AlbaPlayer url of an item page. Most pages embed it directly,
     * but some only embed the thin `/embed/<id>/` wrapper of the site, which in
     * turn contains the AlbaPlayer iframe.
     */
    private suspend fun resolveAlbaBase(html: String): String? {
        ShoofLiveParser.albaBase(html)?.let { return it }

        for (src in ShoofLiveParser.allIframes(html).filter { it.contains("/embed/") }) {
            val wrapper = fetch(src) ?: continue
            ShoofLiveParser.albaBase(wrapper)?.let { return it }
        }

        val embedUrl = ShoofLiveParser.ldEmbedUrl(html)
        if (embedUrl != null) {
            val wrapper = fetch(embedUrl) ?: return null
            return ShoofLiveParser.albaBase(wrapper)
        }
        return null
    }

    /** Reads the AlbaPlayer menu and returns one embed url per server. */
    private suspend fun albaServers(base: String, knownHtml: String?): List<ShoofLiveExtractors.Embed>? {
        val html = knownHtml
            ?: fetch(base)
            ?: fetch(base.trimEnd('/'))
            ?: return null

        val menu = ShoofLiveParser.parseAlbaMenu(html)
        if (menu.isEmpty()) {
            // Single server layout: use the iframe that is already embedded.
            val iframe = ShoofLiveParser.firstIframe(html) ?: return null
            return listOf(ShoofLiveExtractors.Embed("سيرفر", iframe))
        }

        val defaultIframe = ShoofLiveParser.firstIframe(html)
        val out = mutableListOf<ShoofLiveExtractors.Embed>()

        for (item in menu) {
            val isDefault = item.href.substringAfter("serv=", "1") == "1"
            if (isDefault && defaultIframe != null) {
                out += ShoofLiveExtractors.Embed(item.name, defaultIframe)
                continue
            }
            val serverHtml = fetch(item.href) ?: continue
            val iframe = ShoofLiveParser.firstIframe(serverHtml) ?: continue
            out += ShoofLiveExtractors.Embed(item.name, iframe)
        }
        return out
    }
}
