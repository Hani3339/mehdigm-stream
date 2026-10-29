package com.cartoony

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CloudStream provider for https://carateen.tv/ (with cartoony.net as an API fallback).
 *
 * Cartoony currently exposes two catalogue generations. The newer `/api/sp/…`
 * API returns AES-CBC wrapped JSON, while `/api/…` still serves older show and
 * episode IDs. Keeping the source marker in each CloudStream URL lets a show
 * use the matching episode and playback endpoints.
 */
class Cartoony : MainAPI() {
    override var mainUrl = "https://carateen.tv"
    override var name = "كرتوني (Cartoony)"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val usesWebView = false
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.TvSeries,
        TvType.Movie,
        TvType.Cartoon,
    )

    override val mainPage = mainPageOf(
        "latest" to "آخر الحلقات المضافة",
        "new" to "المرفوعات الجديدة",
        "movies" to "الأفلام",
        "series" to "المسلسلات",
        "popular" to "الأكثر مشاهدة",
        "top" to "الأعلى تقييماً",
        "anime" to "الأنمي",
        "cartoon" to "الكرتون",
        "all" to "جميع الأعمال",
    )

    private enum class CatalogSource(val key: String) {
        SP("sp"),
        LEGACY("legacy"),
    }

    private data class Show(
        val id: Int,
        val source: CatalogSource,
        val title: String,
        val plot: String? = null,
        val poster: String? = null,
        val isMovie: Boolean = false,
        val isNew: Boolean = false,
        val isPopular: Boolean = false,
        val isTop: Boolean = false,
        val isHot: Boolean = false,
        val tags: List<String> = emptyList(),
        val rating: Int? = null,
        val durationMinutes: Int? = null,
        val year: Int? = null,
        val minimumAge: Int? = null,
        val createdAt: String? = null,
    ) {
        val isAnime: Boolean
            get() = tags.any { tag ->
                tag.contains("أنمي", ignoreCase = true) || tag.contains("anime", ignoreCase = true)
            } || title.contains("أنمي", ignoreCase = true)

        val cloudStreamType: TvType
            get() = when {
                isMovie && isAnime -> TvType.AnimeMovie
                isMovie -> TvType.Movie
                isAnime -> TvType.Anime
                else -> TvType.Cartoon
            }
    }

    private data class EpisodeItem(
        val id: Int,
        val title: String,
        val number: Int?,
        val poster: String? = null,
        val videoId: String? = null,
    )

    private data class WatchRoute(
        val source: CatalogSource,
        val showId: Int,
        val episodeId: Int? = null,
    )

    private data class MediaRef(
        val source: CatalogSource,
        val showId: Int,
        val episodeId: Int,
        val videoId: String? = null,
    )

    private data class StreamCandidate(
        val url: String,
        val label: String,
        val qualityHint: String? = null,
        val baseOrigin: String? = null,
    )

    private data class ApiPayload(
        val text: String,
        val origin: String,
    )

    private data class PendingJson(
        val value: JSONObject,
        val defaultLabel: String,
        val depth: Int,
    )

    private val catalogueMutex = Mutex()
    @Volatile
    private var catalogueCache: List<Show>? = null
    @Volatile
    private var catalogueCachedAt = 0L
    private val catalogueTtlMs = 5 * 60 * 1000L

    private val baseUrl: String
        get() = mainUrl.trimEnd('/')

    private val apiOrigins: List<String>
        get() = listOf(baseUrl, LEGACY_MAIN_URL).distinct()

    private fun requestHeaders(origin: String): Map<String, String> = mapOf(
        "User-Agent" to MOBILE_USER_AGENT,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "Referer" to "$origin/",
        "Origin" to origin,
    )

    private fun mediaHeaders(origin: String): Map<String, String> = mapOf(
        "User-Agent" to MOBILE_USER_AGENT,
        "Referer" to "$origin/",
        "Origin" to origin,
    )

    // The site uses this client-side AES key to unwrap its public API responses;
    // it is not a user credential or an account/session token.
    private val apiEncryptionKey = "7annaba3l_loves_crypto_safe_key!"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val catalogue = getCatalogue()
        val visibleCatalogue = deduplicateForDisplay(catalogue)
        val allBySourceId = catalogue.associateBy { "${it.source.key}:${it.id}" }

        val entries: List<Show> = when (request.data) {
            "latest" -> recentShows(allBySourceId).ifEmpty {
                visibleCatalogue.sortedByDescending { it.createdAt.orEmpty() }
            }
            "new" -> visibleCatalogue.filter { it.isNew }.ifEmpty {
                visibleCatalogue.sortedByDescending { it.createdAt.orEmpty() }
            }
            "movies" -> visibleCatalogue.filter { it.isMovie }
            "series" -> visibleCatalogue.filter { !it.isMovie }
            "popular" -> visibleCatalogue.filter { it.isPopular || it.isHot }
                .ifEmpty { visibleCatalogue.sortedByDescending { it.rating ?: 0 } }
            "top" -> visibleCatalogue.filter { it.isTop }
                .ifEmpty { visibleCatalogue.sortedByDescending { it.rating ?: 0 } }
            "anime" -> visibleCatalogue.filter { !it.isMovie && it.isAnime }
            "cartoon" -> visibleCatalogue.filter { !it.isMovie && !it.isAnime }
            else -> visibleCatalogue
        }

        val start = ((page.coerceAtLeast(1) - 1) * PAGE_SIZE).coerceAtMost(entries.size)
        val pageEntries = entries.drop(start).take(PAGE_SIZE).map(::toSearchResponse)
        val hasNext = entries.size > start + PAGE_SIZE
        return newHomePageResponse(request.name, pageEntries, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val normalizedQuery = normalizeTitle(query)
        if (normalizedQuery.isBlank()) return emptyList()

        return deduplicateForDisplay(getCatalogue())
            .filter { show ->
                val searchableText = buildString {
                    append(show.title)
                    show.plot?.let { append(' ').append(it) }
                    if (show.tags.isNotEmpty()) append(' ').append(show.tags.joinToString(" "))
                }
                normalizeTitle(searchableText).contains(normalizedQuery)
            }
            .map(::toSearchResponse)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val route = parseWatchRoute(url) ?: return null
        val catalogue = getCatalogue()
        val show = catalogue.firstOrNull { it.source == route.source && it.id == route.showId }
        val isMarkedAsMovie = url.contains("type=movie", ignoreCase = true)
        val showInfo = show?.let { existing ->
            if (isMarkedAsMovie && !existing.isMovie) existing.copy(isMovie = true) else existing
        } ?: Show(
            id = route.showId,
            source = route.source,
            title = "كرتوني ${route.showId}",
            isMovie = isMarkedAsMovie,
        )

        // Support direct links copied from the website, e.g. /watch/453/4243.
        if (route.episodeId != null) {
            val episode = getEpisodes(route.source, route.showId)
                .firstOrNull { it.id == route.episodeId }
            val title = episode?.title?.let { "${showInfo.title} — $it" } ?: showInfo.title
            val data = encodeMediaRef(
                source = route.source,
                showId = route.showId,
                episodeId = route.episodeId,
                videoId = episode?.videoId,
            )
            return newMovieLoadResponse(
                name = title,
                url = url,
                type = if (showInfo.isAnime) TvType.Anime else TvType.Cartoon,
                dataUrl = data,
            ) {
                this.posterUrl = episode?.poster ?: showInfo.poster
                this.plot = showInfo.plot
                this.tags = showInfo.tags
                this.score = scoreFromHundred(showInfo.rating)
                this.year = showInfo.year
                this.contentRating = ageRating(showInfo.minimumAge)
            }
        }

        val isMovie = showInfo.isMovie
        val episodes = getEpisodes(route.source, route.showId)
        if (episodes.isEmpty()) return null

        if (isMovie) {
            val first = episodes.first()
            return newMovieLoadResponse(
                name = showInfo.title,
                url = url,
                type = showInfo.cloudStreamType,
                dataUrl = encodeMediaRef(route.source, route.showId, first.id, first.videoId),
            ) {
                this.posterUrl = showInfo.poster ?: first.poster
                this.plot = showInfo.plot
                this.tags = showInfo.tags
                this.score = scoreFromHundred(showInfo.rating)
                this.duration = showInfo.durationMinutes
                this.year = showInfo.year
                this.contentRating = ageRating(showInfo.minimumAge)
            }
        }

        val cloudStreamEpisodes = episodes.map { episode ->
            newEpisode(encodeMediaRef(route.source, route.showId, episode.id, episode.videoId)) {
                this.name = episode.title
                this.episode = episode.number
                this.posterUrl = episode.poster ?: showInfo.poster
            }
        }

        return newTvSeriesLoadResponse(
            name = showInfo.title,
            url = url,
            type = showInfo.cloudStreamType,
            episodes = cloudStreamEpisodes,
        ) {
            this.posterUrl = showInfo.poster
            this.plot = showInfo.plot
            this.tags = showInfo.tags
            this.score = scoreFromHundred(showInfo.rating)
            this.duration = showInfo.durationMinutes
            this.year = showInfo.year
            this.contentRating = ageRating(showInfo.minimumAge)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ref = decodeMediaRef(data) ?: return false
        val emittedUrls = mutableSetOf<String>()
        var found = false

        fun emitOnce(link: ExtractorLink) {
            val key = link.url.trim()
            if (key.isNotEmpty() && emittedUrls.add(key)) {
                callback(link)
                found = true
            }
        }

        suspend fun resolve(candidate: StreamCandidate) {
            val origin = candidate.baseOrigin?.trimEnd('/') ?: baseUrl
            val referer = "$origin/"
            val headers = mediaHeaders(origin)
            val streamUrl = normalizeStreamUrl(candidate.url, origin) ?: return
            when {
                isHlsUrl(streamUrl) -> {
                    val expanded = try {
                        M3u8Helper.generateM3u8(
                            source = name,
                            streamUrl = streamUrl,
                            referer = referer,
                            quality = qualityFromHint(candidate.qualityHint),
                            headers = headers,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        emptyList()
                    }

                    if (expanded.isNotEmpty()) {
                        expanded.forEach(::emitOnce)
                    } else {
                        emitOnce(
                            newExtractorLink(name, candidate.label, streamUrl, ExtractorLinkType.M3U8) {
                                this.referer = referer
                                this.headers = headers
                                this.quality = qualityFromHint(candidate.qualityHint)
                            },
                        )
                    }
                }

                isDirectVideoUrl(streamUrl) -> {
                    emitOnce(
                        newExtractorLink(name, candidate.label, streamUrl, ExtractorLinkType.VIDEO) {
                            this.referer = referer
                            this.headers = headers
                            this.quality = qualityFromHint(candidate.qualityHint)
                        },
                    )
                }

                else -> {
                    try {
                        if (loadExtractor(streamUrl, referer, subtitleCallback, ::emitOnce)) {
                            found = true
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w(TAG, "Could not resolve Cartoony server ${candidate.label}: ${error.message}")
                    }
                }
            }
        }

        if (ref.source == CatalogSource.SP) {
            val response = requestSpEpisodeLink(ref.episodeId, ref.showId)
            if (response != null) {
                val result = runCatching { JSONObject(response.text) }.getOrNull()
                if (result != null && result.optBoolean("success", true)) {
                    collectStreamCandidates(result, response.origin).forEach { resolve(it) }

                    // This unsigned CDN path is only a fallback; prefer the fresh,
                    // signed `link` returned by the API whenever one is present.
                    if (!found) {
                        val privateId = jsonString(result, "cdn_stream_private_id")
                        if (privateId != null && SAFE_PATH_ID.matches(privateId)) {
                            resolve(
                                StreamCandidate(
                                    url = "$VOD_ORIGIN/asset/$privateId/play_video/index.m3u8",
                                    label = "Cartoony CDN",
                                ),
                            )
                        }
                    }
                } else if (result == null) {
                    normalizeStreamUrl(response.text, response.origin)?.let {
                        resolve(StreamCandidate(it, "Cartoony SP", baseOrigin = response.origin))
                    }
                }
            }

            if (!found) {
                legacyHlsFallback(ref.videoId, minimumLength = 21)?.let { resolve(it) }
            }
        } else {
            val legacyResponse = requestLegacyEpisode(ref.episodeId, ref.showId)
            if (legacyResponse != null) {
                val result = runCatching { JSONObject(legacyResponse.text) }.getOrNull()
                if (result != null) {
                    collectStreamCandidates(result, legacyResponse.origin).forEach { resolve(it) }
                } else {
                    normalizeStreamUrl(legacyResponse.text, legacyResponse.origin)?.let {
                        resolve(StreamCandidate(it, "Cartoony Legacy", baseOrigin = legacyResponse.origin))
                    }
                }
            }

            // Some older Cartoony records only carry a video ID, with no stream URL
            // in the legacy episode endpoint. Keep that route as a secondary server.
            legacyHlsFallback(ref.videoId)?.let { resolve(it) }
        }

        Log.d(TAG, "loadLinks source=${ref.source.key} episode=${ref.episodeId} found=$found")
        return found
    }

    private suspend fun getCatalogue(): List<Show> = catalogueMutex.withLock {
        val now = System.currentTimeMillis()
        val cached = catalogueCache
        if (cached != null && now - catalogueCachedAt < catalogueTtlMs) return@withLock cached

        val (spShows, legacyShows) = coroutineScope {
            val sp = async { fetchShows(CatalogSource.SP) }
            val legacy = async { fetchShows(CatalogSource.LEGACY) }
            sp.await() to legacy.await()
        }

        val merged = (spShows + legacyShows).distinctBy { "${it.source.key}:${it.id}" }
        if (merged.isNotEmpty()) {
            catalogueCache = merged
            catalogueCachedAt = now
        }
        merged.ifEmpty { cached.orEmpty() }
    }

    private suspend fun fetchShows(source: CatalogSource): List<Show> {
        val path = if (source == CatalogSource.SP) "sp/tvshows" else "tvshows"
        val payload = apiGet(path) { response ->
            hasItemsWithId(parseArray(response.text, "shows", "tvshows", "results", "data"), "id")
        } ?: return emptyList()
        val array = parseArray(payload.text, "shows", "tvshows", "results", "data") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val objectAt = array.optJSONObject(index) ?: return@mapNotNull null
            when (source) {
                CatalogSource.SP -> mapSpShow(objectAt, payload.origin)
                CatalogSource.LEGACY -> mapLegacyShow(objectAt, payload.origin)
            }
        }
    }

    private fun mapSpShow(obj: JSONObject, origin: String): Show? {
        val id = jsonInt(obj, "id")?.takeIf { it > 0 } ?: return null
        val title = jsonString(obj, "name", "title", "pref") ?: "غير معنون"
        return Show(
            id = id,
            source = CatalogSource.SP,
            title = title,
            plot = jsonString(obj, "pref", "description"),
            poster = imageUrl(jsonString(obj, "cover_full_path", "cover", "poster"), "posters", origin),
            isMovie = jsonFlag(obj, "is_movie"),
            isNew = jsonFlag(obj, "is_new"),
            isPopular = jsonFlag(obj, "is_popular"),
            isTop = jsonFlag(obj, "is_top"),
            isHot = jsonFlag(obj, "is_hot"),
            tags = parseTags(jsonString(obj, "tags")),
            rating = rating100(jsonNumber(obj, "rating")),
            durationMinutes = durationMinutes(jsonString(obj, "ep_duration", "episodes_length")),
            year = parseYear(jsonString(obj, "release_year", "year")),
            minimumAge = jsonInt(obj, "min_age")?.takeIf { it > 0 },
            createdAt = jsonString(obj, "created_at", "updated_at"),
        )
    }

    private fun mapLegacyShow(obj: JSONObject, origin: String): Show? {
        val id = jsonInt(obj, "id")?.takeIf { it > 0 } ?: return null
        val category = jsonString(obj, "category").orEmpty()
        val normalizedCategory = normalizeTitle(category)
        val episodeCountText = jsonString(obj, "episodes_count", "episode_count").orEmpty()
        val parsedCount = Regex("\\d+").find(episodeCountText)?.value?.toIntOrNull()
        val isMovie = listOf("فيلم", "افلام", "فلم").any { normalizedCategory.contains(it) } ||
            (!episodeCountText.contains("حلقة") && parsedCount == 1)
        return Show(
            id = id,
            source = CatalogSource.LEGACY,
            title = jsonString(obj, "title", "name") ?: "غير معنون",
            plot = jsonString(obj, "description", "plot"),
            poster = imageUrl(jsonString(obj, "poster_cover", "poster"), "posters", origin),
            isMovie = isMovie,
            tags = parseTags(category),
            rating = rating100(jsonNumber(obj, "rating")),
            durationMinutes = durationMinutes(jsonString(obj, "episodes_length", "duration")),
            year = parseYear(jsonString(obj, "release_year", "year")),
            createdAt = jsonString(obj, "created_at", "updated_at"),
        )
    }

    private suspend fun getEpisodes(source: CatalogSource, showId: Int): List<EpisodeItem> {
        val prefix = if (source == CatalogSource.SP) "sp/" else ""
        val payload = apiGet("${prefix}episodes?id=$showId") { response ->
            hasItemsWithId(parseArray(response.text, "episodes", "results", "data"), "id", "episode_id")
        } ?: return emptyList()
        val array = parseArray(payload.text, "episodes", "results", "data") ?: return emptyList()

        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val id = jsonInt(obj, "id", "episode_id")?.takeIf { it > 0 } ?: return@mapNotNull null
            val fallbackNumber = index + 1
            val number = jsonInt(obj, "number", "order_id")?.takeIf { it >= 0 } ?: fallbackNumber
            val title = jsonString(obj, "pref", "title", "name") ?: "الحلقة $number"
            EpisodeItem(
                id = id,
                title = title,
                number = number,
                poster = imageUrl(jsonString(obj, "cover_full_path", "cover", "thumbnail"), "thumbnails", payload.origin),
                videoId = jsonString(obj, "video_id")?.takeIf { isSafeVideoId(it) },
            )
        }.sortedWith(compareBy<EpisodeItem> { it.number ?: Int.MAX_VALUE }.thenBy { it.id })
    }

    private suspend fun recentShows(catalogueBySourceId: Map<String, Show>): List<Show> {
        val payload = apiGet("sp/recentEpisodes") { response ->
            hasItemsWithId(parseArray(response.text, "episodes", "results", "data"), "tv_series_id")
        } ?: return emptyList()
        val array = parseArray(payload.text, "episodes", "results", "data") ?: return emptyList()
        val result = linkedMapOf<Int, Show>()

        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val showId = jsonInt(obj, "tv_series_id")?.takeIf { it > 0 } ?: continue
            if (result.containsKey(showId)) continue
            val catalogueShow = catalogueBySourceId["sp:$showId"]
            result[showId] = catalogueShow ?: Show(
                id = showId,
                source = CatalogSource.SP,
                title = jsonString(obj, "name") ?: "غير معنون",
                poster = imageUrl(jsonString(obj, "cover_full_path", "cover"), "thumbnails", payload.origin),
            )
        }
        return result.values.toList()
    }

    private suspend fun apiGet(
        path: String,
        isUsable: (ApiPayload) -> Boolean = { it.text.isNotBlank() },
    ): ApiPayload? {
        for (origin in apiOrigins) {
            val text = requestText("$origin/api/${path.trimStart('/')}", origin) ?: continue
            val payload = ApiPayload(text, origin)
            if (isUsable(payload)) return payload
        }
        return null
    }

    private suspend fun requestText(url: String, origin: String): String? = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
        try {
            val response = app.get(url, headers = requestHeaders(origin), timeout = REQUEST_TIMEOUT_SECONDS)
            if (response.code !in 200..299) {
                Log.w(TAG, "Cartoony request failed (HTTP ${response.code})")
                null
            } else {
                decryptEnvelope(response.text)?.takeIf { it.isNotBlank() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Cartoony request failed: ${error.message}")
            null
        }
    }

    private suspend fun requestSpEpisodeLink(episodeId: Int, showId: Int): ApiPayload? {
        for (origin in apiOrigins) {
            val text = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                try {
                    val response = app.post(
                        "$origin/api/sp/episode/link",
                        headers = requestHeaders(origin),
                        data = mapOf(
                            "episodeId" to episodeId.toString(),
                            "showId" to showId.toString(),
                        ),
                        timeout = REQUEST_TIMEOUT_SECONDS,
                    )
                    if (response.code !in 200..299) {
                        Log.w(TAG, "Cartoony playback request failed (HTTP ${response.code})")
                        null
                    } else {
                        decryptEnvelope(response.text)?.takeIf { it.isNotBlank() }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Cartoony playback request failed: ${error.message}")
                    null
                }
            } ?: continue

            val payload = ApiPayload(text, origin)
            val result = runCatching { JSONObject(text) }.getOrNull()
            if (result == null) {
                if (normalizeStreamUrl(text, origin) != null) return payload
                continue
            }
            val hasPlayback = collectStreamCandidates(result, origin).isNotEmpty() ||
                jsonString(result, "cdn_stream_private_id")?.let { SAFE_PATH_ID.matches(it) } == true
            if (result.optBoolean("success", true) && hasPlayback) return payload
        }
        return null
    }

    private suspend fun requestLegacyEpisode(episodeId: Int, showId: Int): ApiPayload? {
        val paths = listOf(
            "episode?episodeId=$episodeId&showId=$showId",
            "episode?episodeId=$episodeId",
        )
        for (path in paths) {
            val response = apiGet(path) { payload ->
                val objectResponse = runCatching { JSONObject(payload.text) }.getOrNull()
                objectResponse?.let { collectStreamCandidates(it, payload.origin).isNotEmpty() }
                    ?: (normalizeStreamUrl(payload.text, payload.origin) != null)
            } ?: continue
            return response
        }
        return null
    }

    private fun collectStreamCandidates(root: JSONObject, origin: String): List<StreamCandidate> {
        val candidates = linkedMapOf<String, StreamCandidate>()
        val pending = ArrayDeque<PendingJson>()
        pending.add(PendingJson(root, "Cartoony", 0))

        fun addCandidate(raw: String?, owner: JSONObject, fallbackLabel: String, sourceKey: String) {
            val url = normalizeStreamUrl(raw, origin) ?: return
            val serverLabel = jsonString(owner, "server", "server_name", "source_name", "label")
                ?.takeUnless { it.startsWith("http", ignoreCase = true) }
            val downloadLabel = if (
                sourceKey.contains("download", ignoreCase = true) && !fallbackLabel.contains("تنزيل")
            ) " — تنزيل" else ""
            val label = (serverLabel ?: fallbackLabel) + downloadLabel
            val quality = jsonString(owner, "quality", "resolution")
            if (!candidates.containsKey(url)) candidates[url] = StreamCandidate(url, label, quality, origin)
        }

        val urlKeys = listOf(
            "link", "url", "streamUrl", "stream_url", "video_url", "file",
            "direct_url", "directUrl", "download", "downloadUrl", "download_url",
            "download_link", "source_url",
        )
        val listKeys = listOf("servers", "links", "sources", "qualities", "videos", "downloads", "download_links")
        val nestedKeys = listOf("data", "result", "episode", "playback", "payload")

        while (pending.isNotEmpty()) {
            val item = pending.removeFirst()
            if (item.depth > 3) continue

            for (key in urlKeys) {
                when (val value = item.value.opt(key)) {
                    is String -> addCandidate(value, item.value, item.defaultLabel, key)
                    is JSONObject -> pending.add(PendingJson(value, item.defaultLabel, item.depth + 1))
                    is JSONArray -> {
                        for (index in 0 until value.length()) {
                            when (val nested = value.opt(index)) {
                                is String -> addCandidate(nested, item.value, item.defaultLabel, key)
                                is JSONObject -> pending.add(PendingJson(nested, item.defaultLabel, item.depth + 1))
                            }
                        }
                    }
                }
            }

            for (key in listKeys) {
                val values = item.value.optJSONArray(key) ?: continue
                val defaultLabel = if (key.contains("download", ignoreCase = true)) {
                    "Cartoony — تنزيل"
                } else {
                    item.defaultLabel
                }
                for (index in 0 until values.length()) {
                    when (val nested = values.opt(index)) {
                        is String -> addCandidate(nested, item.value, defaultLabel, key)
                        is JSONObject -> pending.add(PendingJson(nested, defaultLabel, item.depth + 1))
                    }
                }
            }

            for (key in nestedKeys) {
                val nested = item.value.optJSONObject(key) ?: continue
                pending.add(PendingJson(nested, item.defaultLabel, item.depth + 1))
            }
        }

        return candidates.values.toList()
    }

    private fun legacyHlsFallback(videoId: String?, minimumLength: Int = 11): StreamCandidate? {
        val safeId = videoId?.takeIf { isSafeVideoId(it) }?.takeIf { it.length >= minimumLength } ?: return null
        return StreamCandidate(
            url = "$PEGASUS_ORIGIN/api/hls/$safeId/playlist.m3u8",
            label = "Cartoony HLS احتياطي",
        )
    }

    private fun encodeMediaRef(
        source: CatalogSource,
        showId: Int,
        episodeId: Int,
        videoId: String?,
    ): String = JSONObject()
        .put("source", source.key)
        .put("showId", showId)
        .put("episodeId", episodeId)
        .put("videoId", videoId ?: JSONObject.NULL)
        .toString()

    private fun decodeMediaRef(data: String): MediaRef? {
        val trimmed = data.trim()
        if (trimmed.startsWith("{")) {
            val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
            val source = when (jsonString(obj, "source")) {
                "sp" -> CatalogSource.SP
                "legacy" -> CatalogSource.LEGACY
                else -> return null
            }
            val showId = jsonInt(obj, "showId")?.takeIf { it > 0 } ?: return null
            val episodeId = jsonInt(obj, "episodeId")?.takeIf { it > 0 } ?: return null
            val videoId = jsonString(obj, "videoId")?.takeIf { isSafeVideoId(it) }
            return MediaRef(source, showId, episodeId, videoId)
        }

        // Read the compact data format used by earlier Cartoony builds too.
        val source = when {
            trimmed.startsWith("sp:") -> CatalogSource.SP
            trimmed.startsWith("leg:") -> CatalogSource.LEGACY
            else -> return null
        }
        val fields = trimmed.substringAfter(':').split('&').mapNotNull { field ->
            val pair = field.split('=', limit = 2)
            if (pair.size == 2) pair[0] to pair[1] else null
        }.toMap()
        val showId = fields["s"]?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val episodeId = fields["e"]?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val videoId = fields["v"]?.takeIf { isSafeVideoId(it) }
        return MediaRef(source, showId, episodeId, videoId)
    }

    private fun parseWatchRoute(url: String): WatchRoute? {
        val match = WATCH_PATH.find(url) ?: return null
        val source = if (match.groupValues[1].isNotBlank()) CatalogSource.SP else CatalogSource.LEGACY
        val showId = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val episodeId = match.groupValues.getOrNull(3)?.toIntOrNull()?.takeIf { it > 0 }
        return WatchRoute(source, showId, episodeId)
    }

    private fun showUrl(show: Show): String {
        val id = if (show.source == CatalogSource.SP) "sp/${show.id}" else show.id.toString()
        val type = if (show.isMovie) "movie" else "series"
        return "$baseUrl/watch/$id?type=$type"
    }

    private fun toSearchResponse(show: Show): SearchResponse =
        newAnimeSearchResponse(show.title, showUrl(show), show.cloudStreamType) {
            this.posterUrl = show.poster
        }

    private fun deduplicateForDisplay(shows: List<Show>): List<Show> =
        shows.distinctBy { show ->
            val normalized = normalizeTitle(show.title)
            val titleKey = if (normalized.isBlank() || normalized == normalizeTitle("غير معنون")) {
                "${show.source.key}:${show.id}"
            } else {
                normalized
            }
            "${if (show.isMovie) "movie" else "series"}:$titleKey"
        }

    private fun parseArray(text: String?, vararg keys: String): JSONArray? {
        if (text.isNullOrBlank()) return null
        runCatching { JSONArray(text) }.getOrNull()?.let { return it }
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        for (key in keys) obj.optJSONArray(key)?.let { return it }
        return null
    }

    private fun hasItemsWithId(array: JSONArray?, vararg idKeys: String): Boolean {
        if (array == null) return false
        return (0 until array.length()).any { index ->
            array.optJSONObject(index)?.let { jsonInt(it, *idKeys)?.let { id -> id > 0 } } == true
        }
    }

    private fun decryptEnvelope(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val envelope = runCatching { JSONObject(trimmed) }.getOrNull() ?: return trimmed
        val encrypted = jsonString(envelope, "encryptedData") ?: return trimmed
        val ivHex = jsonString(envelope, "iv") ?: return trimmed

        return try {
            val key = apiEncryptionKey.toByteArray(Charsets.UTF_8)
            if (key.size !in setOf(16, 24, 32)) return null
            val iv = hexToBytes(ivHex) ?: return null
            val ciphertext = hexToBytes(encrypted) ?: return null
            if (iv.size != 16 || ciphertext.isEmpty()) return null

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (error: Exception) {
            Log.w(TAG, "Could not decode Cartoony API response: ${error.message}")
            null
        }
    }

    private fun hexToBytes(hex: String): ByteArray? {
        val value = hex.trim()
        if (value.isEmpty() || value.length % 2 != 0) return null
        val output = ByteArray(value.length / 2)
        for (index in value.indices step 2) {
            val high = value[index].digitToIntOrNull(16) ?: return null
            val low = value[index + 1].digitToIntOrNull(16) ?: return null
            output[index / 2] = ((high shl 4) or low).toByte()
        }
        return output
    }

    private fun imageUrl(raw: String?, folder: String, origin: String = baseUrl): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalizedOrigin = origin.trimEnd('/')
        return when {
            value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/assets/", ignoreCase = true) -> "$normalizedOrigin$value"
            value.startsWith("assets/", ignoreCase = true) -> "$normalizedOrigin/$value"
            value.startsWith("/") -> "$normalizedOrigin$value"
            else -> "$normalizedOrigin/assets/img/$folder/$value"
        }
    }

    private fun normalizeStreamUrl(raw: String?, origin: String = baseUrl): String? {
        val value = raw?.trim()?.replace("&amp;", "&")?.takeIf { it.isNotEmpty() } ?: return null
        val normalizedOrigin = origin.trimEnd('/')
        return when {
            value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$normalizedOrigin$value"
            else -> null
        }
    }

    private fun isHlsUrl(url: String): Boolean =
        url.substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true) ||
            url.contains("/play_video/index.m3u8", ignoreCase = true)

    private fun isDirectVideoUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return listOf(".mp4", ".mkv", ".webm", ".mov", ".avi").any { extension -> path.endsWith(extension) }
    }

    private fun isSafeVideoId(value: String): Boolean =
        value.length in 11..256 && SAFE_VIDEO_ID.matches(value) && !value.equals("null", ignoreCase = true)

    private fun qualityFromHint(hint: String?): Int {
        val normalized = hint?.lowercase().orEmpty()
        val height = QUALITY_PATTERN.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return when {
            height != null && height >= 2160 || normalized.contains("4k") || normalized.contains("uhd") -> Qualities.P2160.value
            height != null && height >= 1440 || normalized.contains("2k") || normalized.contains("qhd") -> Qualities.P1440.value
            height != null && height >= 1080 || normalized.contains("fhd") -> Qualities.P1080.value
            height != null && height >= 720 || Regex("""\bhd\b""").containsMatchIn(normalized) -> Qualities.P720.value
            height != null && height >= 540 -> 540
            height != null && height >= 480 || Regex("""\bsd\b""").containsMatchIn(normalized) -> Qualities.P480.value
            height != null && height >= 360 -> Qualities.P360.value
            height != null && height >= 240 -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    private fun rating100(value: Double?): Int? {
        val rating = value?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        return when {
            rating <= 5.0 -> (rating * 20.0).toInt().coerceIn(0, 100)
            rating <= 100.0 -> rating.toInt().coerceIn(0, 100)
            else -> null
        }
    }

    private fun scoreFromHundred(value: Int?): Score? =
        value?.let { Score.from10(it.coerceIn(0, 100) / 10.0) }

    private fun durationMinutes(raw: String?): Int? {
        val value = raw?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() } ?: return null
        if (value <= 0) return null
        return (if (value > 600) value / 60 else value).takeIf { it > 0 }
    }

    private fun parseYear(raw: String?): Int? {
        val year = raw?.let { Regex("(?:19|20)\\d{2}").find(it)?.value?.toIntOrNull() } ?: return null
        return year.takeIf { it in 1900..2100 }
    }

    private fun parseTags(raw: String?): List<String> = raw
        ?.split(',', '،', '\t', '\n')
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.distinct()
        .orEmpty()

    private fun normalizeTitle(value: String): String = value.lowercase()
        .replace(Regex("[أإآ]"), "ا")
        .replace('ة', 'ه')
        .replace('ى', 'ي')
        .replace(Regex("[^a-z0-9\\u0600-\\u06FF\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun ageRating(age: Int?): String? = age?.takeIf { it > 0 }?.let { "$it+" }

    private fun jsonString(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = obj.opt(key) ?: continue
            if (value == JSONObject.NULL) continue
            val text = value.toString().trim()
            if (text.isNotEmpty() && !text.equals("null", ignoreCase = true)) return text
        }
        return null
    }

    private fun jsonInt(obj: JSONObject, vararg keys: String): Int? =
        jsonString(obj, *keys)?.toIntOrNull()
            ?: jsonString(obj, *keys)?.toDoubleOrNull()?.toInt()

    private fun jsonNumber(obj: JSONObject, vararg keys: String): Double? =
        jsonString(obj, *keys)?.toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun jsonFlag(obj: JSONObject, key: String): Boolean = when (val value = obj.opt(key)) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        null, JSONObject.NULL -> false
        else -> value.toString().let { it == "1" || it.equals("true", ignoreCase = true) || it.equals("yes", ignoreCase = true) }
    }

    companion object {
        private const val TAG = "Cartoony"
        private const val REQUEST_TIMEOUT_MS = 15_000L
        private const val REQUEST_TIMEOUT_SECONDS = 15L
        private const val PAGE_SIZE = 30
        private const val LEGACY_MAIN_URL = "https://cartoony.net"
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
        private const val VOD_ORIGIN = "https://vod.spacetoongo.com"
        private const val PEGASUS_ORIGIN = "https://pegasus.5387692.xyz"
        private val WATCH_PATH = Regex("/watch/(sp/)?(\\d+)(?:/(\\d+))?", RegexOption.IGNORE_CASE)
        private val SAFE_VIDEO_ID = Regex("[A-Za-z0-9_-]+")
        private val SAFE_PATH_ID = Regex("[A-Za-z0-9_-]{6,256}")
        private val QUALITY_PATTERN = Regex("(?i)(2160|1440|1080|720|540|480|360|240)")
    }
}
