package com.cartoonanime

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * CartoonAnime provider for CloudStream.
 *
 * مبني على فحص تطبيق "مسلسلات كرتون وانمي" (com.anime.rashon.speed.loyert) V25.
 *
 * ما تغيّر في نسخة 25:
 *   - الـ API انتقل إلى https://base-v4.apps-anime.com/
 *   - كل طلب يحتاج توقيع HMAC (X-AUTH-TS / X-AUTH-NON / X-AUTH-SIG)
 *     + X-App-Version: 25
 *   - كل طلب يحتاج جلسة: Authorization: Basic base64("s1:" + session)
 *   - الجلسة تُؤخذ من session/challenge ثم session/attest
 *   - التطبيق الأصلي يستخدم Android Key Attestation، لكن السيرفر يقبل
 *     أيضاً مسار "unsupported" (chain فارغة + unsupported) ويصدر جلسة.
 *     هذا هو الـ bypass المستخدم هنا حتى تعمل الإضافة بدون جهاز حقيقي.
 *   - البحث الموحّد: cartoon_with_info/searchCartoonUnified.php
 *   - معلومات العمل/المواسم/الحلقات ترسل form-encoded وليس JSON.
 */
class CartoonAnime : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://apps-anime.com"
    override var name = "مسلسلات كرتون وأنمي (CartoonAnime)"

    override val usesWebView = false
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon, TvType.AnimeMovie
    )

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val apiBase = "https://base-v4.apps-anime.com/"

    // القيم المستخرجة من APK (defpackage/r92.java و defpackage/ab.java)
    private val hmacSecret = "Eb6bi_DNiNd70i1qiJJ1bZSQHtFBYtqZxEijjJd2FzikYrpUplEu9n9vQyfhB9VN"
    private val appVersion = "25"

    private val rng = SecureRandom()

    @Volatile private var sessionToken: String? = null
    @Volatile private var sessionExpiresAt: Long = 0L
    @Volatile private var sessionIid: String? = null

    // ---------------------------------------------------------------
    // HMAC / session
    // ---------------------------------------------------------------

    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(digits[v ushr 4]).append(digits[v and 0x0f])
        }
        return sb.toString()
    }

    private fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        rng.nextBytes(b)
        return hex(b)
    }

    private fun hmacSha256(input: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return hex(mac.doFinal(input.toByteArray(Charsets.UTF_8)))
    }

    private fun b64(input: String): String =
        Base64.encodeToString(input.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    /**
     * نفس ترويسات التطبيق: ختم زمني بالثواني + nonce 16 hex + توقيع HMAC.
     * ملاحظة مهمة: السيرفر يرفض الميلي ثانية، لذلك نستخدم الثواني.
     */
    private fun authHeaders(token: String?): Map<String, String> {
        val ts = (System.currentTimeMillis() / 1000L).toString()
        val nonce = randomHex(8)
        val headers = mutableMapOf(
            "X-AUTH-TS" to ts,
            "X-AUTH-NON" to nonce,
            "X-AUTH-SIG" to hmacSha256("$ts:$nonce"),
            "X-App-Version" to appVersion,
            "User-Agent" to ua,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "ar,en;q=0.9"
        )
        if (!token.isNullOrBlank()) {
            headers["Authorization"] = "Basic " + b64("s1:$token")
        }
        return headers
    }

    private suspend fun postJsonRaw(path: String, body: Any, token: String?): String {
        var last: Exception? = null
        repeat(3) {
            try {
                val resp = app.post(
                    apiBase + path,
                    json = body,
                    headers = authHeaders(token),
                    timeout = 45
                )
                if (resp.code in 200..299) return resp.text
                last = IllegalStateException("HTTP ${resp.code}: ${resp.text.take(240)}")
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("request failed: $path")
    }

    private suspend fun ensureSession(force: Boolean = false): String {
        val now = System.currentTimeMillis() / 1000L
        val cached = sessionToken
        if (!force && !cached.isNullOrBlank() && sessionExpiresAt > now + 120L) {
            return cached
        }

        val iid = sessionIid ?: randomHex(16).also { sessionIid = it }

        val challengeJson = postJsonRaw(
            "session/challenge",
            mapOf("iid" to iid),
            null
        )
        val challenge = JSONObject(challengeJson).optString("challenge")
        if (challenge.isBlank()) {
            throw IllegalStateException("session/challenge returned no challenge")
        }

        // هذا هو الـ bypass: السيرفر يقبل chain فارغة إذا أرسلنا unsupported
        // فيعيد trust=unsupported وجلسة صالحة.
        val attestBody = mapOf<String, Any>(
            "iid" to iid,
            "challenge" to challenge,
            "chain" to emptyList<String>(),
            "unsupported" to "IllegalStateException"
        )
        val attestJson = postJsonRaw("session/attest", attestBody, null)
        val obj = JSONObject(attestJson)
        val token = obj.optString("session")
        if (token.isBlank()) {
            throw IllegalStateException("session/attest failed: ${attestJson.take(240)}")
        }
        sessionToken = token
        sessionExpiresAt = obj.optLong("exp", now + 3600L)
        return token
    }

    // ---------------------------------------------------------------
    // Network helpers
    // ---------------------------------------------------------------

    private suspend fun getOnce(url: String, token: String): Pair<Int, String> {
        var last: Exception? = null
        repeat(3) {
            try {
                val resp = app.get(url, headers = authHeaders(token), timeout = 45)
                return resp.code to resp.text
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("GET failed: $url")
    }

    private suspend fun postFormOnce(
        path: String,
        data: Map<String, String>,
        token: String
    ): Pair<Int, String> {
        var last: Exception? = null
        repeat(3) {
            try {
                val resp = app.post(
                    apiBase + path,
                    data = data,
                    headers = authHeaders(token),
                    timeout = 45
                )
                return resp.code to resp.text
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("POST failed: $path")
    }

    private suspend fun getText(path: String): String {
        var token = ensureSession()
        var (code, text) = getOnce(apiBase + path, token)
        if (code == 401) {
            sessionToken = null
            token = ensureSession(force = true)
            val retry = getOnce(apiBase + path, token)
            code = retry.first
            text = retry.second
        }
        if (code !in 200..299) throw IllegalStateException("HTTP $code: ${text.take(240)}")
        return text
    }

    private suspend fun postForm(path: String, data: Map<String, String>): String {
        var token = ensureSession()
        var (code, text) = postFormOnce(path, data, token)
        if (code == 401) {
            sessionToken = null
            token = ensureSession(force = true)
            val retry = postFormOnce(path, data, token)
            code = retry.first
            text = retry.second
        }
        if (code !in 200..299) throw IllegalStateException("HTTP $code: ${text.take(240)}")
        return text
    }

    private suspend fun getArray(path: String): JSONArray =
        try {
            parseArray(getText(path))
        } catch (_: Exception) {
            JSONArray()
        }

    private suspend fun postArray(path: String, data: Map<String, String>): JSONArray =
        try {
            parseArray(postForm(path, data))
        } catch (_: Exception) {
            JSONArray()
        }

    private suspend fun postObject(path: String, data: Map<String, String>): JSONObject? =
        try {
            JSONObject(postForm(path, data))
        } catch (_: Exception) {
            null
        }

    private fun parseArray(text: String): JSONArray {
        val t = text.trim()
        if (t.isEmpty()) return JSONArray()
        if (t.startsWith("[")) {
            return try {
                JSONArray(t)
            } catch (_: Exception) {
                JSONArray()
            }
        }
        if (t.startsWith("{")) {
            return try {
                val obj = JSONObject(t)
                obj.optJSONArray("data")
                    ?: obj.optJSONArray("result")
                    ?: obj.optJSONArray("items")
                    ?: JSONArray()
            } catch (_: Exception) {
                JSONArray()
            }
        }
        return JSONArray()
    }

    private fun JSONObject.str(key: String): String =
        optString(key, "").trim().let { if (it == "null") "" else it }

    private fun JSONObject.intOf(key: String, def: Int = 0): Int {
        val v = opt(key)
        return when (v) {
            is Number -> v.toInt()
            is String -> v.toDoubleOrNull()?.toInt() ?: def
            else -> def
        }
    }

    // ---------------------------------------------------------------
    // Main page
    // ---------------------------------------------------------------

    private data class Row(val title: String, val path: String, val paged: Boolean)

    private val rows = listOf(
        Row("الأكثر مشاهدة", "cartoon_with_info/getMostViewedCartoons.php", false),
        Row("أحدث الحلقات", "episodeWithInfo/latest.php", false),
        Row("تابع المشاهدة", "cartoon_with_info/readPagingContinueAnime.php", true),
        Row("أنمي مترجم", "cartoon_with_info/readPagingTranslatedSeriesAnime.php", true),
        Row("أنمي مدبلج", "cartoon_with_info/readPagingDUBBEDSeriesAnime.php", true),
        Row("أفلام مترجمة", "cartoon_with_info/readPagingTranslatedFilms.php", true),
        Row("أفلام مدبلجة", "cartoon_with_info/readPagingDUBBEDFilms.php", true),
        Row("مواعيد الحلقات", "episode_dates_with_info/read.php", false)
    )

    override val mainPage: List<MainPageData> =
        rows.map { MainPageData(it.title, it.path, false) }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val row = rows.firstOrNull { it.title == request.name }
        val paged = row?.paged ?: false
        val path = row?.path ?: request.data
        val url = if (paged) "$path?page=$page" else path
        val arr = getArray(url)
        val list = (0 until arr.length()).mapNotNull { toSearch(arr.optJSONObject(it)) }
        return newHomePageResponse(request.name, list, hasNext = paged && list.isNotEmpty())
    }

    // ---------------------------------------------------------------
    // Search / item parsing
    // ---------------------------------------------------------------

    private fun toSearch(obj: JSONObject?): SearchResponse? {
        if (obj == null) return null
        val cartoon = obj.optJSONObject("cartoon") ?: obj

        // EpisodeDate يستخدم cartoon_id (ورقم الصف في id) لذلك نقدّم cartoon_id
        // على id عندما لا يكون هناك كائن cartoon متداخل.
        val nestedId = obj.optJSONObject("cartoon")?.str("id").orEmpty()
        val id = nestedId
            .ifBlank { obj.str("cartoon_id") }
            .ifBlank { obj.str("id") }
        if (id.isBlank() || id == "0") return null

        // EpisodeDate لديه title و name، نفضّل title (اسم العمل)
        val title = cartoon.str("title")
            .ifBlank { obj.str("title") }
            .ifBlank { obj.str("name") }
        if (title.isBlank()) return null

        val thumb = cartoon.str("thumb").ifBlank { obj.str("thumb") }
        val type = cartoon.intOf("type", obj.intOf("type", 1))
        val cls = cartoon.intOf("classification", obj.intOf("classification", 0))
        val tvType = if (type == 2) TvType.Movie else TvType.Anime
        val url = buildUrl(id, title, thumb, type, cls)

        return if (tvType == TvType.Movie) {
            newMovieSearchResponse(title, url, tvType) {
                this.posterUrl = thumb.ifBlank { null }
            }
        } else {
            newTvSeriesSearchResponse(title, url, tvType) {
                this.posterUrl = thumb.ifBlank { null }
            }
        }
    }

    private fun buildUrl(id: String, title: String, thumb: String, type: Int, cls: Int): String =
        "$mainUrl/anime/$id?t=${enc(title)}&p=${enc(thumb)}&ty=$type&cls=$cls"

    override suspend fun search(query: String): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        val q = enc(query)

        val unified = try {
            JSONObject(getText("cartoon_with_info/searchCartoonUnified.php?search=$q"))
        } catch (_: Exception) {
            null
        }

        if (unified != null) {
            for (key in listOf("dubbed_series", "dubbed_films", "translated_series", "translated_films")) {
                val arr = unified.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    val r = toSearch(arr.optJSONObject(i)) ?: continue
                    out[r.url] = r
                }
            }
        }

        // احتياطي في حال تغيّر الـ endpoint الموحّد مستقبلاً
        if (out.isEmpty()) {
            for (t in listOf(1, 2)) {
                for (c in listOf(1, 2)) {
                    val arr = getArray("cartoon_with_info/searchCartoon.php?search=$q&type=$t&classification=$c")
                    for (i in 0 until arr.length()) {
                        val r = toSearch(arr.optJSONObject(i)) ?: continue
                        out[r.url] = r
                    }
                }
            }
        }
        return out.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ---------------------------------------------------------------
    // Load (details + episodes)
    // ---------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val id = url.substringAfter("/anime/").substringBefore("?").substringBefore("/").trim()
        val query = url.substringAfter("?", "")
        val title = param(query, "t")?.let { dec(it) }?.takeIf { it.isNotBlank() } ?: "بدون عنوان"
        val poster = param(query, "p")?.let { dec(it) }?.takeIf { it.isNotBlank() }
        val type = param(query, "ty")?.toIntOrNull() ?: 1
        val cls = param(query, "cls")?.toIntOrNull() ?: 0

        val info = postObject("information/readOne.php", mapOf("cartoon_id" to id))
        val plot = info?.str("story")?.ifBlank { null }
        val year = info?.str("view_date")?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
        val tags = (info?.str("category") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .toMutableList()
        when (cls) {
            1 -> tags.add("مدبلج")
            2 -> tags.add("مترجم")
        }

        val playlists = postArray("playlist/read.php", mapOf("cartoon_id" to id))

        val episodes = ArrayList<Episode>()
        val seenEpisodes = HashSet<String>()
        for (p in 0 until playlists.length()) {
            val pl = playlists.optJSONObject(p) ?: continue
            val plId = pl.str("id")
            if (plId.isBlank()) continue
            val season = p + 1
            var page = 1
            while (page <= 200) {
                val arr = postArray(
                    "episode/readPaging.php?page=$page",
                    mapOf("playlist_id" to plId)
                )
                if (arr.length() == 0) break

                var added = 0
                for (e in 0 until arr.length()) {
                    val ep = arr.optJSONObject(e) ?: continue
                    val built = makeEpisode(ep, season, poster)
                    if (built.data.isNotBlank() && seenEpisodes.add(built.data)) {
                        episodes.add(built)
                        added++
                    }
                }
                // بعض الـ endpoints تعيد آخر حلقة فقط في كل صفحة بعد النهاية
                if (added == 0) break
                page++
            }
        }

        val uniq = episodes.distinctBy { it.data }
        val tvType = if (type == 2) TvType.Movie else TvType.Anime

        return if (type == 2) {
            val first = uniq.firstOrNull()
            newMovieLoadResponse(title, url, TvType.Movie, first?.data ?: "") {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            newTvSeriesLoadResponse(title, url, tvType, uniq) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    private fun makeEpisode(ep: JSONObject, season: Int, poster: String?): Episode {
        val epTitle = ep.str("title").ifBlank { "الحلقة" }
        val num = Regex("\\d+").find(epTitle)?.value?.toIntOrNull()
        val links = ArrayList<String>()
        for (k in listOf("video", "video1", "video2", "video3", "video4", "video5", "video_url")) {
            val v = ep.str(k)
            if (v.isNotEmpty()) links.add(v)
        }
        return newEpisode(links.joinToString("\n")) {
            this.name = epTitle
            this.season = season
            this.episode = num
            this.posterUrl = poster
        }
    }

    // ---------------------------------------------------------------
    // Link resolution
    // ---------------------------------------------------------------

    private fun workerHeaders(): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Accept" to "*/*",
        "Accept-Language" to "ar,en;q=0.9"
    )

    private fun isVideoUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains(".mp4") || u.contains(".m3u8") || u.contains(".mkv") ||
            u.contains("okcdn.ru") || u.contains("vkuser.net") || u.contains("vk.com/video")
    }

    /**
     * يستخرج الروابط من ردود الـ workers:
     *  - {"availableQualities":[{"quality":"1080p","url":"..."}]}
     *  - [{"url":"...","quality":"..."}]
     *  - نص يحتوي رابطاً مباشراً
     */
    private fun parseWorkerPayload(text: String): List<Pair<String, String?>> {
        val result = ArrayList<Pair<String, String?>>()
        val t = text.trim()
        if (t.isEmpty()) return result

        if (t.startsWith("{")) {
            try {
                val obj = JSONObject(t)
                val arr = obj.optJSONArray("availableQualities")
                    ?: obj.optJSONArray("qualities")
                    ?: obj.optJSONArray("data")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val v = o.str("url").ifBlank { o.str("file") }.ifBlank { o.str("link") }
                        if (v.isBlank() || !v.startsWith("http")) continue
                        val q = o.str("quality").ifBlank { o.str("name") }.ifBlank { null }
                        result.add(v to q)
                    }
                } else {
                    val v = obj.str("url").ifBlank { obj.str("file") }.ifBlank { obj.str("link") }
                    if (v.startsWith("http")) {
                        val q = obj.str("quality").ifBlank { obj.str("name") }.ifBlank { null }
                        result.add(v to q)
                    }
                }
            } catch (_: Exception) {
                // تجاهل
            }
            return result
        }

        if (t.startsWith("[")) {
            try {
                val arr = JSONArray(t)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val v = o.str("url").ifBlank { o.str("file") }.ifBlank { o.str("link") }
                    if (v.isBlank() || !v.startsWith("http")) continue
                    val q = o.str("quality").ifBlank { o.str("name") }.ifBlank { null }
                    result.add(v to q)
                }
            } catch (_: Exception) {
                // تجاهل
            }
            return result
        }

        if (t.startsWith("http")) result.add(t to null)
        return result
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val sources = data.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        for (link in sources) {
            try {
                when {
                    // روابط Google Photos المباشرة غير قابلة للتشغيل،
                    // لكن worker?string=https://photos.google.com/... مسموح.
                    link.contains("photos.google.com") && !link.contains(".workers.dev") -> continue

                    // روابط مباشرة (mp4/m3u8) لا نطلبها حتى لا نحمل الفيديو
                    isVideoUrl(link) && !link.contains(".workers.dev") -> {
                        emit(link, link, null, callback)
                        found = true
                    }

                    // test-stream / multiplecdnqualities / أي worker يعيد JSON
                    link.contains(".workers.dev/video?url=") ||
                        link.contains("multiplecdnqualities") ||
                        link.contains(".workers.dev/?string=") -> {
                        val resp = app.get(link, headers = workerHeaders(), timeout = 45)
                        val parsed = parseWorkerPayload(resp.text)
                        if (parsed.isNotEmpty()) {
                            for ((vurl, q) in parsed) {
                                emit(link, vurl, q, callback)
                                found = true
                            }
                        } else if (resp.url != link && isVideoUrl(resp.url)) {
                            emit(link, resp.url, null, callback)
                            found = true
                        }
                    }

                    // link / cdnlink / linkcdn: غالباً 302 إلى mp4 مباشر
                    link.contains(".workers.dev/") -> {
                        val resp = app.get(link, headers = workerHeaders(), timeout = 45)
                        val finalUrl = resp.url
                        if (finalUrl.isNotBlank() && finalUrl != link) {
                            try {
                                emit(link, finalUrl, null, callback)
                                found = true
                            } finally {
                                // لا نقرأ جسم الفيديو، فقط نغلق الاتصال
                                try {
                                    resp.okhttpResponse.close()
                                } catch (_: Exception) {
                                }
                            }
                        } else {
                            val parsed = parseWorkerPayload(resp.text)
                            for ((vurl, q) in parsed) {
                                emit(link, vurl, q, callback)
                                found = true
                            }
                        }
                    }

                    // أي رابط فيديو آخر
                    isVideoUrl(link) -> {
                        emit(link, link, null, callback)
                        found = true
                    }
                }
            } catch (_: Exception) {
                // تابع بقية السيرفرات
            }
        }
        return found
    }

    private suspend fun emit(
        referer: String,
        url: String,
        quality: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val q = quality?.trim()?.takeIf { it.isNotBlank() }
        val ref = when {
            url.contains("okcdn.ru") || url.contains("vkuser.net") || referer.contains("ok.ru") ->
                "https://ok.ru/"
            referer.contains(".workers.dev/") -> referer
            else -> ""
        }
        callback(
            newExtractorLink(
                source = this.name,
                name = this.name + if (q != null) " - $q" else "",
                url = url,
                type = ExtractorLinkType.VIDEO
            ) {
                this.quality = parseQuality(q)
                this.referer = ref
                this.headers = mapOf(
                    "User-Agent" to ua,
                    "Accept" to "*/*"
                )
            }
        )
    }

    private fun parseQuality(q: String?): Int {
        val n = Regex("\\d{3,4}").find(q ?: "")?.value?.toIntOrNull()
            ?: return Qualities.Unknown.value
        return when {
            n >= 2160 -> Qualities.P2160.value
            n >= 1080 -> Qualities.P1080.value
            n >= 720 -> Qualities.P720.value
            n >= 480 -> Qualities.P480.value
            n >= 360 -> Qualities.P360.value
            n >= 240 -> Qualities.P240.value
            else -> Qualities.P144.value
        }
    }

    // ---------------------------------------------------------------
    // Misc
    // ---------------------------------------------------------------

    private fun enc(s: String): String =
        try {
            URLEncoder.encode(s, "UTF-8")
        } catch (_: Exception) {
            s
        }

    private fun dec(s: String): String =
        try {
            URLDecoder.decode(s, "UTF-8")
        } catch (_: Exception) {
            s
        }

    private fun param(query: String, key: String): String? {
        for (pair in query.split("&")) {
            val i = pair.indexOf('=')
            if (i > 0 && pair.substring(0, i) == key) return pair.substring(i + 1)
        }
        return null
    }
}
