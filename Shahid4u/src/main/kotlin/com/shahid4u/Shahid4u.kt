package com.shahid4u

import android.util.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class Shahid4u : MainAPI() {
    override var mainUrl = "https://shaahidd4u.net/"
    override var name = "شاهد فور يو (Shahid4u)"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val logTag = "Shahid4u"

    override val mainPage = mainPageOf(
        "" to "الرئيسية",
        "${mainUrl}category/افلام-اجنبي" to "أفلام أجنبي",
        "${mainUrl}category/افلام-هندي" to "أفلام هندي",
        "${mainUrl}category/افلام-انمي" to "أفلام أنمي",
        "${mainUrl}category/مسلسلات-اجنبي" to "مسلسلات أجنبي",
        "${mainUrl}category/مسلسلات-تركية" to "مسلسلات تركية",
        "${mainUrl}category/مسلسلات-عربي" to "مسلسلات عربي",
        "${mainUrl}category/مسلسلات-مدبلجة" to "مسلسلات مدبلجة",
        "${mainUrl}category/مسلسلات-انمي" to "مسلسلات أنمي",
        "${mainUrl}category/مسلسلات-هندية" to "مسلسلات هندية",
        "${mainUrl}category/مسلسلات-رمضان-2026" to "مسلسلات رمضان 2026",
        "${mainUrl}category/برامج-تلفزيونية" to "برامج تلفزيونية",
        "${mainUrl}category/عروض-مصارعة" to "عروض مصارعة"
    )

    private val cloudflareKiller by lazy { CloudflareKiller() }

    /**
     * Cloudflare أحياناً يعيد صفحة تحقّق عربية بحالة HTTP 200، وCloudflareKiller
     * لا يتدخّل إلا مع أكواد الخطأ المعروفة. نغلّف السلسلة ونحوّل صفحة التحقّق
     * إلى 403 مع Server: cloudflare كي يتولاها الحل عبر WebView تلقائياً.
     */
    private val cfInterceptor: Interceptor = Interceptor { chain ->
        val rewritingChain = object : Interceptor.Chain by chain {
            override fun proceed(request: Request): Response {
                val response = chain.proceed(request)
                return if (isBrowserVerification(response)) {
                    response.newBuilder()
                        .code(403)
                        .header("Server", "cloudflare")
                        .build()
                } else {
                    response
                }
            }
        }
        cloudflareKiller.intercept(rewritingChain)
    }

    private fun isBrowserVerification(response: Response): Boolean {
        if (response.code != 200) return false
        if (response.header("cf-mitigated")?.contains("challenge", true) == true) return true
        val contentType = response.header("Content-Type").orEmpty()
        if (contentType.isNotBlank() && !contentType.contains("text/html", true)) return false
        val body = runCatching { response.peekBody(64L * 1024L).string() }.getOrDefault("")
        return body.contains("<title>Just a moment", true) ||
                body.contains("جارٍ التحقق من المتصفح") ||
                body.contains("سيتم فتح الصفحة تلقائيًا") ||
                body.contains("cf_chl_opt", true)
    }

    private val earnVids by lazy { ExternalEarnVidsExtractor() }

    private val docCache = ConcurrentHashMap<String, Pair<Long, Document>>()
    private val loadCache = ConcurrentHashMap<String, Pair<Long, LoadResponse>>()
    private val seriesCache =
        ConcurrentHashMap<String, Pair<Long, List<com.lagradost.cloudstream3.Episode>>>()
    private val watchCache = ConcurrentHashMap<String, Pair<Long, WatchData>>()
    private val resolveCache = ConcurrentHashMap<String, Pair<Long, String>>()
    private val linkCache =
        ConcurrentHashMap<String, Pair<Long, List<Triple<String, String, String>>>>()
    private val cookieStore =
        ConcurrentHashMap<String, MutableMap<String, String>>()

    /**
     * القالب يعرض البطاقات بأكثر من شكل حسب القسم/نسخة الموقع:
     * a.show-card، a.show.card، a.glide_post، أو حاويات div.shows-container.
     * الاعتماد على محدّد واحد كان يجعل الصفحة تبدو فارغة.
     */
    private val cardSelectors = listOf(
        "div.shows-container.row div[class*=col-]",
        "div.shows-container div[class*=col-]",
        "div.shows-container.row a.show-card[href]",
        "a.show-card[href]",
        "a.show.card[href]",
        "a.glide_post[href]",
        "a.glide-item[href]",
        "div.glide li.glide__slide:not(.glide__slide--clone)",
        "div.row div[class*='col'] a[href]",
        "a[href*='/watch/']",
        "a[href*='/film/']",
        "a[href*='/episode/']",
        "a[href*='/series/']",
        "a[href*='/season/']"
    )

    private val jsonMediaType = "application/json".toMediaType()
    private val fpEventId by lazy { java.util.UUID.randomUUID().toString() }

    init {
        // استرجاع جلسة Cloudflare المحفوظة حتى لا نبدأ من الصفر في كل تشغيل.
        val domain = cookieDomain(mainUrl)
        val saved = Shahid4uStore.loadCookies(domain)
        if (saved.isNotEmpty()) {
            cookieStore.getOrPut(domain) { ConcurrentHashMap() }.putAll(saved)
        }
    }

    private data class ServerEntry(
        val name: String,
        val token: String,
        val rank: Int
    )

    private data class WatchData(
        val watchUrl: String,
        val servers: List<ServerEntry>,
        val key: String,
        val csrf: String,
        val resolveUrl: String,
        val cookies: Map<String, String>
    )

    // =====================================================================
    // Helpers
    // =====================================================================

    private fun now() = System.currentTimeMillis()

    private fun isFresh(at: Long, ttl: Long) = now() - at in 0 until ttl

    private fun ua(): String =
        Shahid4uStore.deviceUserAgent()
            ?: "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private fun chromeMajor(): String =
        Regex("""Chrome/(\d+)""").find(ua())?.groupValues?.get(1) ?: "131"

    private fun secChUa(): String =
        "\"Chromium\";v=\"${chromeMajor()}\", \"Google Chrome\";v=\"${chromeMajor()}\", " +
                "\"Not_A Brand\";v=\"24\""

    private fun secChMobile(): String =
        if (ua().contains("Mobile", true)) "?1" else "?0"

    /** ترميز آمن لقيم الترويسات (Referer قد يحتوي عربياً). */
    private fun headerSafe(url: String): String =
        runCatching { URI(url).toASCIIString() }.getOrDefault(url)

    private fun browserHeaders(referer: String? = null): Map<String, String> = buildMap {
        put("User-Agent", ua())
        put(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9," +
                    "image/avif,image/webp,image/apng,*/*;q=0.8," +
                    "application/signed-exchange;v=b3;q=0.7"
        )
        put("Accept-Language", "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7")
        put("Cache-Control", "max-age=0")
        put("Upgrade-Insecure-Requests", "1")
        put("Sec-Fetch-Mode", "navigate")
        put("Sec-Fetch-User", "?1")
        put("Sec-Fetch-Dest", "document")
        put(
            "sec-ch-ua",
            secChUa()
        )
        put("sec-ch-ua-mobile", secChMobile())
        put("sec-ch-ua-platform", "\"Android\"")
        if (referer != null) {
            put("Referer", headerSafe(referer))
            put("Sec-Fetch-Site", "same-origin")
        } else {
            put("Sec-Fetch-Site", "none")
        }
    }

    private fun posterHeaders(): Map<String, String> = mapOf(
        "User-Agent" to ua(),
        "Referer" to headerSafe(mainUrl),
        "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
        "Accept-Language" to "ar-EG,ar;q=0.9,en;q=0.8"
    )

    private fun makeAbsoluteUrl(url: String?): String? {
        val p = url?.trim().orEmpty()
        if (p.isBlank()) return null
        return when {
            p.startsWith("http://", true) || p.startsWith("https://", true) -> p
            p.startsWith("//") -> "https:$p"
            p.startsWith("/") -> mainUrl.trimEnd('/') + p
            else -> mainUrl + p
        }
    }

    private fun decodeUrl(url: String): String =
        runCatching { java.net.URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host?.lowercase() }.getOrNull()

    private fun isSameSite(url: String): Boolean {
        val host = hostOf(url) ?: return false
        val mainHost = hostOf(mainUrl) ?: return false
        return host == mainHost || host.endsWith(".$mainHost")
    }

    /**
     * الموقع يضيف في كل صفحة رابط canary مخفياً:
     * <a href="/m/<64-hex>" hidden aria-hidden="true" tabindex="-1"
     *    rel="nofollow" style="display:none!important">Server</a>
     * أي بوت يتبعه يُسجَّل ويُحظر. نرفض أي عنصر مخفي أو داخل <template>.
     */
    private fun isHiddenElement(element: Element): Boolean {
        // tabindex=-1 على الرابط نفسه فقط؛ بعض الحاويات الشرعية تستخدمه أيضاً.
        if (element.attr("tabindex") == "-1") return true
        var current: Element? = element
        while (current != null) {
            if (current.hasAttr("hidden")) return true
            if (current.attr("aria-hidden").equals("true", true)) return true
            val style = current.attr("style").replace(" ", "").lowercase()
            if (style.contains("display:none") || style.contains("visibility:hidden")) return true
            if (current.tagName().equals("template", true)) return true
            current = current.parent()
        }
        return false
    }

    private fun isCanaryHref(href: String): Boolean {
        val lower = href.lowercase()
        return lower.contains("/media/watch/") ||
                lower.contains("/media/api/") ||
                lower.contains("/media/page/")
    }

    private fun isCanaryServer(name: String, rank: Int, url: String): Boolean {
        if (rank >= 900_000 || rank < 0) return true
        val lowerName = name.lowercase()
        if (listOf("backup", "mirror", "cdn player", "canary", "trap", "test", "bot")
                .any { lowerName.contains(it) }
        ) return true
        return isCanaryHref(url)
    }

    private fun withPage(url: String, page: Int): String =
        if (page <= 1) url else "$url${if (url.contains('?')) '&' else '?'}page=$page"

    private fun cookieDomain(url: String): String = hostOf(url) ?: "shaahidd4u.net"

    private fun cookiesFor(url: String): Map<String, String> =
        cookieStore[cookieDomain(url)]?.toMap().orEmpty()

    private fun rememberCookies(url: String, res: NiceResponse) {
        if (res.cookies.isEmpty()) return
        val domain = cookieDomain(url)
        val store = cookieStore.getOrPut(domain) { ConcurrentHashMap() }
        store.putAll(res.cookies)
        // حفظ دائم: cf_clearance/__cf_bm يجب أن تبقى بين جلسات التطبيق.
        Shahid4uStore.saveCookies(domain, store)
    }

    // =====================================================================
    // HTTP + Cloudflare + rate limiting
    // =====================================================================

    private fun isBlockPage(body: String): Boolean =
        body.contains("Sorry, you have been blocked", true) ||
                body.contains("Attention Required", true) ||
                body.contains("cf-error-details", true) ||
                body.contains("You are unable to access", true)

    /**
     * صفحات تحدي Cloudflare قد تصل أحياناً بحالة HTTP 200 بدل 403،
     * فلا يكفي الاعتماد على كود الحالة. نرفضها حتى لا تُفسَّر كصفحة فارغة.
     * ملاحظة: challenge-platform يظهر أيضاً في الصفحات العادية، لذلك لا نعتمد عليه
     * إلا مع 403 بعد التأكد من أن الصفحة ليست صفحة حظر.
     */
    private fun isChallengePage(body: String): Boolean =
        body.contains("<title>Just a moment", true) ||
                body.contains("cf_chl_opt", true) ||
                body.contains("جارٍ التحقق من المتصفح") ||
                body.contains("سيتم فتح الصفحة تلقائيًا")

    private fun isChallenge(res: NiceResponse, body: String): Boolean {
        if (res.headers["cf-mitigated"]?.contains("challenge", true) == true) return true
        if (isChallengePage(body)) return true
        return res.code == 403 && body.contains("challenge-platform", true)
    }

    private suspend fun siteGet(
        url: String,
        referer: String? = null,
        allowRedirects: Boolean = true
    ): NiceResponse {
        Log.d(logTag, "GET $url")
        val res = try {
            SiteGate.run {
                app.get(
                    url,
                    headers = browserHeaders(referer),
                    referer = referer?.let { headerSafe(it) },
                    cookies = cookiesFor(url),
                    interceptor = cfInterceptor,
                    allowRedirects = allowRedirects,
                    timeout = 30L
                )
            }
        } catch (e: SiteCooldownException) {
            val minutes = (e.remainingMs + 59_999L) / 60_000L
            throw ErrorLoadingException(
                "تم إيقاف الطلبات مؤقتاً بسبب حد الموقع (باقٍ ${minutes} دقيقة). " +
                        "لا تعِد المحاولة الآن كي لا يطول الحظر."
            )
        }

        rememberCookies(url, res)
        val body = runCatching { res.text }.getOrDefault("")

        if (res.code == 429) {
            val retryAfter = res.headers["retry-after"]?.trim()?.toLongOrNull()
            SiteGate.penalizeRateLimit(retryAfter)
            throw ErrorLoadingException(
                "الموقع رفض الطلبات (429: تجاوز الحد). تم إيقاف الطلبات مؤقتاً لحماية الـIP."
            )
        }

        if (isBlockPage(body)) {
            SiteGate.penalizeBlocked()
            throw ErrorLoadingException(
                "الموقع حظر الـIP عبر Cloudflare. تم إيقاف كل الطلبات 24 ساعة " +
                        "لعدم إطالة الحظر. غيّر الشبكة/VPN ثم أعد المحاولة لاحقاً."
            )
        }

        val challenge = isChallenge(res, body)
        if (res.code == 403 || challenge) {
            if (res.code == 403 && !challenge) {
                SiteGate.penalizeBlocked()
                throw ErrorLoadingException(
                    "الموقع حظر الـIP عبر Cloudflare (403). تم إيقاف كل الطلبات 24 ساعة " +
                            "لعدم إطالة الحظر. غيّر الشبكة/VPN ثم أعد المحاولة لاحقاً."
                )
            }
            SiteGate.penalizeChallenge()
            throw ErrorLoadingException(
                "حماية Cloudflare رفضت الطلب. تم إيقاف الطلبات 10 دقائق لحماية الـIP."
            )
        }

        if (res.code >= 400) {
            Log.w(logTag, "HTTP ${res.code} for $url")
            throw ErrorLoadingException("فشل الطلب (HTTP ${res.code}) على $url")
        }
        return res
    }

    private suspend fun getDocument(
        url: String,
        referer: String? = null,
        allowCache: Boolean = true
    ): Document {
        if (allowCache) {
            docCache[url]?.let { (at, doc) ->
                if (isFresh(at, 5 * 60_000L)) return doc
            }
        }
        val doc = siteGet(url, referer).document
        if (allowCache) docCache[url] = now() to doc
        return doc
    }

    // =====================================================================
    // Cards / search
    // =====================================================================

    private fun parseCard(element: Element): SearchResponse? {
        val a = if (element.tagName() == "a" && element.hasAttr("href")) {
            element
        } else {
            element.selectFirst("a.show-card[href]")
                ?: element.selectFirst("a.show.card[href]")
                ?: element.selectFirst("a.glide_post[href]")
                ?: element.selectFirst("a.glide-item[href]")
                ?: element.selectFirst(
                    "a[href*='/watch/'], a[href*='/film/'], a[href*='/episode/'], " +
                            "a[href*='/series/'], a[href*='/season/']"
                )
                ?: element.selectFirst("a[href]")
                ?: return null
        }

        if (isHiddenElement(a)) return null
        val href = makeAbsoluteUrl(a.attr("href")) ?: return null
        if (href.contains("/m/")) return null
        if (isNavigationHref(href)) return null

        val title = sequenceOf(
            a.selectFirst("p.title"),
            a.selectFirst(".card-content .title"),
            a.selectFirst(".title"),
            a.selectFirst("h2"),
            a.selectFirst("h3"),
            element.selectFirst("p.title"),
            element.selectFirst(".card-content .title"),
            element.selectFirst("h2"),
            element.selectFirst("h3")
        ).mapNotNull { it?.text()?.trim()?.takeIf { text -> text.isNotBlank() } }
            .firstOrNull()
            ?: a.attr("title").trim().takeIf { it.isNotBlank() }
            ?: a.attr("aria-label").trim().takeIf { it.isNotBlank() }
            ?: a.attr("data-title").trim().takeIf { it.isNotBlank() }
            ?: a.selectFirst("img[alt]")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: element.selectFirst("img[alt]")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        val style = a.attr("style") + " " + element.attr("style")
        val fromStyle = Regex("""url\(\s*['"]?(.*?)['"]?\s*\)""")
            .find(style)?.groupValues?.get(1)
        val poster = sequenceOf(
            fromStyle,
            a.selectFirst("img[data-src]")?.attr("data-src"),
            a.selectFirst("img[data-original]")?.attr("data-original"),
            a.selectFirst("img[data-lazy-src]")?.attr("data-lazy-src"),
            a.selectFirst("img[data-image]")?.attr("data-image"),
            a.selectFirst("img[src]")?.attr("src"),
            element.selectFirst("img[data-src]")?.attr("data-src"),
            element.selectFirst("img[data-original]")?.attr("data-original"),
            element.selectFirst("img[data-lazy-src]")?.attr("data-lazy-src"),
            element.selectFirst("img[data-image]")?.attr("data-image"),
            element.selectFirst("img[src]")?.attr("src")
        ).mapNotNull { makeAbsoluteUrl(it) }.firstOrNull { isSafeImage(it) }
            ?: TRANSPARENT_PNG

        val decoded = decodeUrl(href)
        val isAnime = decoded.contains("انمي") || decoded.contains("anime", true)
        val isSeries = href.contains("/episode/") || href.contains("/series/") ||
                href.contains("/season/") ||
                element.selectFirst(".ep_num, .ep, .episode-number") != null
        val type = when {
            isAnime -> TvType.Anime
            isSeries -> TvType.TvSeries
            else -> TvType.Movie
        }

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = poster
                this.posterHeaders = posterHeaders()
            }
        } else {
            newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = poster
                this.posterHeaders = posterHeaders()
            }
        }
    }

    /** روابط التنقّل/التصنيفات التي قد تُلتقط عند البحث داخل حاوية البطاقة. */
    private fun isNavigationHref(href: String): Boolean {
        val path = runCatching { URI(href).path.orEmpty() }.getOrDefault(href).lowercase()
        if (path.isBlank() || path == "/") return true
        val skip = listOf(
            "/category/", "/tag/", "/genre/", "/search", "/actor/", "/country/",
            "/year/", "/page/", "/login", "/register", "/contact", "/about",
            "/privacy", "/dmca", "/feed", "/cdn-cgi/", "/assets/", "/photos/"
        )
        return skip.any { path.contains(it) }
    }

    private fun parseCards(doc: Document, selector: String? = null): List<SearchResponse> {
        val selectors = if (selector != null) listOf(selector) else cardSelectors
        val cards = LinkedHashMap<String, SearchResponse>()
        for (current in selectors) {
            doc.select(current).forEach { element ->
                val card = parseCard(element) ?: return@forEach
                cards.putIfAbsent(card.url, card)
            }
        }
        return cards.values.toList()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (SiteGate.isCoolingDown()) {
            val minutes = (SiteGate.cooldownRemaining() + 59_999L) / 60_000L
            throw ErrorLoadingException(
                "تم إيقاف الطلبات مؤقتاً بسبب حد الموقع (باقٍ $minutes دقيقة)."
            )
        }
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return try {
            val doc = getDocument("${mainUrl}search?s=$encoded", referer = mainUrl)
            parseCards(doc)
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: Exception) {
            Log.w(logTag, "search failed: ${e.message}")
            // محاولة fallback مع ترميز مختلف
            try {
                val doc2 = getDocument("${mainUrl}?s=${URLEncoder.encode(query, "UTF-8")}", referer = mainUrl)
                val res = parseCards(doc2)
                if (res.isNotEmpty()) res else emptyList()
            } catch (e2: Exception) {
                emptyList()
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (SiteGate.isCoolingDown()) {
            throw ErrorLoadingException("تم إيقاف الطلبات مؤقتاً بسبب حد الموقع. أعد المحاولة لاحقاً.")
        }
        val url = if (request.data.isBlank()) withPage(mainUrl, page) else withPage(request.data, page)
        val doc = try {
            getDocument(url, referer = mainUrl)
        } catch (e: Exception) {
            Log.w(logTag, "getMainPage getDocument failed for $url: ${e.message}")
            // محاولة بدون ريفرير في حالة مشكلة الترويسات
            getDocument(url, referer = null)
        }
        val lists = ArrayList<HomePageList>()

        if (request.data.isBlank() && page <= 1) {
            val featured = parseCards(
                doc,
                "div.glide li.glide__slide:not(.glide__slide--clone)"
            )
            if (featured.isNotEmpty()) {
                lists.add(HomePageList("أبرز العروض", featured, false))
            }
        }

        val latest = parseCards(doc)
        if (latest.isNotEmpty()) {
            lists.add(HomePageList(request.name, latest, hasNextPage(doc)))
        }

        if (lists.isEmpty()) {
            Log.w(logTag, "no cards parsed from $url (html=${doc.html().length} chars)")
            throw ErrorLoadingException(
                "تعذّر قراءة بطاقات الصفحة من الموقع. حدّث الإضافة أو أعد المحاولة بعد قليل."
            )
        }
        return newHomePageResponse(lists)
    }

    private fun hasNextPage(doc: Document): Boolean =
        doc.selectFirst("ul.pagination li.page-item.active + li.page-item a") != null ||
                doc.selectFirst("ul.pagination a[rel=next]") != null

    // =====================================================================
    // Load (series / season / episode / film)
    // =====================================================================

    private data class Info(
        val title: String,
        val poster: String?,
        val plot: String?,
        val tags: List<String>,
        val isAnime: Boolean
    )

    private fun parseInfo(doc: Document, url: String): Info {
        val title = doc.select("h1.title")
            .firstOrNull { !it.hasAttr("data-href") && it.text().trim() != "title" }
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.trim()?.takeIf { it.isNotBlank() && it != "title" }
            ?: doc.title().substringBefore(" - ").trim().takeIf { it.isNotBlank() }
            ?: "غير متوفر"

        val style = doc.selectFirst("div.poster-side div.poster")?.attr("style").orEmpty()
        val fromStyle = Regex("""url\(\s*['"]?(.*?)['"]?\s*\)""")
            .find(style)?.groupValues?.get(1)
        val poster = sequenceOf(
            fromStyle,
            doc.selectFirst("meta[property=og:image]")?.attr("content"),
            doc.selectFirst("div.poster-side img[src]")?.attr("src")
        ).mapNotNull { makeAbsoluteUrl(it) }.firstOrNull { isSafeImage(it) }

        val plot = doc.select("div.description")
            .firstOrNull { !it.hasAttr("data-href") && it.text().trim().length > 5 }
            ?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val tags = doc.select(".info-side a[href*='/genre/'], .info-side a[href*='/category/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(12)

        val decoded = runCatching { java.net.URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        val isAnime = decoded.contains("انمي") || decoded.contains("anime", true) ||
                title.contains("انمي")

        return Info(title, poster, plot, tags, isAnime)
    }

    override suspend fun load(url: String): LoadResponse {
        loadCache[url]?.let { (at, value) ->
            if (isFresh(at, 60 * 60_000L)) return value
        }

        val doc = getDocument(url)
        val info = parseInfo(doc, url)

        val episodes = when {
            url.contains("/series/") -> collectSeriesEpisodes(url, info.poster)

            url.contains("/season/") -> {
                val seasonNumber = currentSeasonNumber(doc) ?: parseSeasonNumber(info.title, url)
                parseEpisodeAnchors(doc, seasonNumber, info.poster)
            }

            url.contains("/episode/") -> {
                val seasonAnchors = doc.select("a.epss[href*='/season/']")
                    .distinctBy { it.attr("href") }
                val currentSeason = currentSeasonNumber(doc) ?: parseSeasonNumber(info.title, url)
                if (seasonAnchors.isNotEmpty()) {
                    val cacheKey = doc.selectFirst("a[href*='/series/']")
                        ?.let { makeAbsoluteUrl(it.attr("href")) } ?: url
                    collectSeasons(seasonAnchors, cacheKey, info.poster).ifEmpty {
                        parseEpisodeAnchors(doc, currentSeason, info.poster)
                    }
                } else {
                    parseEpisodeAnchors(doc, currentSeason, info.poster)
                }
            }

            else -> emptyList()
        }

        val sorted = episodes
            .distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        val response: LoadResponse = if (sorted.isNotEmpty()) {
            newTvSeriesLoadResponse(
                info.title,
                url,
                if (info.isAnime) TvType.Anime else TvType.TvSeries,
                sorted
            ) {
                this.posterUrl = info.poster
                this.posterHeaders = posterHeaders()
                this.plot = info.plot
                this.tags = info.tags
            }
        } else {
            newMovieLoadResponse(
                info.title,
                url,
                if (info.isAnime) TvType.Anime else TvType.Movie,
                url
            ) {
                this.posterUrl = info.poster
                this.posterHeaders = posterHeaders()
                this.plot = info.plot
                this.tags = info.tags
            }
        }

        loadCache[url] = now() to response
        return response
    }

    private fun restoreSeries(
        stored: List<Shahid4uStore.StoredEpisode>
    ): List<com.lagradost.cloudstream3.Episode> =
        stored.map { item ->
            newEpisode(item.data) {
                name = item.name
                season = item.season
                episode = item.episode
                posterUrl = item.posterUrl
            }
        }

    private suspend fun collectSeriesEpisodes(
        seriesUrl: String,
        poster: String?
    ): List<com.lagradost.cloudstream3.Episode> {
        seriesCache[seriesUrl]?.let { (at, eps) ->
            if (isFresh(at, 24 * 60 * 60_000L)) return eps
        }
        Shahid4uStore.loadSeries(seriesUrl)?.let { stored ->
            val cached = restoreSeries(stored)
            seriesCache[seriesUrl] = now() to cached
            return cached
        }

        val seriesDoc = try {
            getDocument(seriesUrl)
        } catch (e: SiteCooldownException) {
            throw e
        } catch (e: Exception) {
            Log.w(logTag, "series page failed: ${e.message}")
            return emptyList()
        }

        val anchors = seriesDoc.select("a.epss[href*='/season/']")
            .distinctBy { it.attr("href") }
        if (anchors.isEmpty()) {
            return parseEpisodeAnchors(seriesDoc, 1, poster)
        }
        return collectSeasons(anchors, seriesUrl, poster)
    }

    /**
     * يجلب كل المواسم بالتوالي (طلب واحد لكل موسم) ويعيد الحلقات موسومة
     * برقم الموسم. الجلب متسلسل عبر SiteGate؛ والنتيجة تُخزَّن 24 ساعة حتى
     * لا يتكرر الجلب عند فتح حلقة أخرى من نفس المسلسل.
     */
    private suspend fun collectSeasons(
        anchors: List<Element>,
        cacheKey: String,
        poster: String?
    ): List<com.lagradost.cloudstream3.Episode> {
        seriesCache[cacheKey]?.let { (at, eps) ->
            if (isFresh(at, 24 * 60 * 60_000L)) return eps
        }
        Shahid4uStore.loadSeries(cacheKey)?.let { stored ->
            val cached = restoreSeries(stored)
            seriesCache[cacheKey] = now() to cached
            return cached
        }

        val result = ArrayList<com.lagradost.cloudstream3.Episode>()
        var complete = true

        for (anchor in anchors) {
            if (SiteGate.isCoolingDown()) {
                Log.w(logTag, "stop fetching seasons: ${SiteGate.describe()}")
                complete = false
                break
            }
            val seasonUrl = makeAbsoluteUrl(anchor.attr("href")) ?: continue
            val seasonNumber = anchorNumber(anchor)
                ?: parseSeasonNumber(anchor.text(), seasonUrl)
            val seasonDoc = try {
                getDocument(seasonUrl, referer = cacheKey)
            } catch (e: SiteCooldownException) {
                throw e
            } catch (e: Exception) {
                Log.w(logTag, "season failed $seasonUrl: ${e.message}")
                complete = false
                if (SiteGate.isCoolingDown()) break
                continue
            }

            var episodes = parseEpisodeAnchors(seasonDoc, seasonNumber, poster)
            if (episodes.isEmpty()) {
                val allEpisodesUrl = seasonUrl.trimEnd('/') + "/episodes"
                try {
                    val allDoc = getDocument(allEpisodesUrl, referer = seasonUrl)
                    episodes = parseEpisodeCards(allDoc, seasonNumber, poster)
                } catch (e: SiteCooldownException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(logTag, "season episodes failed: ${e.message}")
                    complete = false
                    if (SiteGate.isCoolingDown()) break
                }
            }
            if (episodes.isEmpty()) complete = false
            result.addAll(episodes)
        }

        val sorted = result
            .distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        if (sorted.isNotEmpty() && complete) {
            seriesCache[cacheKey] = now() to sorted
            // كاش دائم 24 ساعة: فتح Supernatural مرة ثانية لا يرسل أي طلب مواسم.
            Shahid4uStore.saveSeries(cacheKey, sorted)
        }
        Log.d(logTag, "seasons=${sorted.mapNotNull { it.season }.distinct().sorted()} episodes=${sorted.size} complete=$complete")
        return sorted
    }

    private fun currentSeasonNumber(doc: Document): Int? {
        val active = doc.selectFirst("a.epss.active[href*='/season/']")
            ?: doc.selectFirst("a.epss.active")
        return active?.let { anchorNumber(it) }
    }

    private fun anchorNumber(anchor: Element): Int? =
        anchor.selectFirst("span.fs-2")?.text()?.trim()?.let { toIntLoose(it) }

    private fun episodeNumber(text: String?): Int? {
        val value = text.orEmpty()
        Regex("""(?:الحلقة|حلقة|Episode)\s*([0-9٠-٩]+)""", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.get(1)?.let { return toIntLoose(it) }
        return Regex("""(?:الحلقة|حلقة)[-_ ]?([0-9٠-٩]+)""")
            .find(value)?.groupValues?.get(1)?.let { toIntLoose(it) }
    }

    private fun parseEpisodeAnchors(
        doc: Document,
        seasonNumber: Int?,
        poster: String?
    ): List<com.lagradost.cloudstream3.Episode> =
        doc.select("a.epss[href*='/episode/']").mapNotNull { anchor ->
            val href = makeAbsoluteUrl(anchor.attr("href")) ?: return@mapNotNull null
            val decoded = decodeUrl(href)
            val season = parseSeasonNumber(null, decoded) ?: seasonNumber
            val number = anchorNumber(anchor)
                ?: episodeNumber(anchor.text())
                ?: episodeNumber(decoded)
            newEpisode(href) {
                this.name = if (number != null) "الحلقة $number" else "حلقة"
                this.episode = number
                this.season = season
                this.posterUrl = poster
            }
        }

    private fun parseEpisodeCards(
        doc: Document,
        seasonNumber: Int?,
        poster: String?
    ): List<com.lagradost.cloudstream3.Episode> =
        doc.select("div.shows-container.row a.show-card[href*='/episode/']").mapNotNull { anchor ->
            val href = makeAbsoluteUrl(anchor.attr("href")) ?: return@mapNotNull null
            val name = anchor.selectFirst("p.title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "حلقة"
            val decoded = decodeUrl(href)
            val season = parseSeasonNumber(null, decoded) ?: seasonNumber
            val number = episodeNumber(name) ?: episodeNumber(decoded)
            newEpisode(href) {
                this.name = name
                this.episode = number
                this.season = season
                this.posterUrl = poster
            }
        }

    private fun parseSeasonNumber(title: String?, url: String): Int? {
        val decodedUrl = decodeUrl(url)
        val path = when {
            decodedUrl.contains("/episode/") ->
                decodedUrl.substringAfter("/episode/").substringBefore("-الحلقة-")
            decodedUrl.contains("/season/") -> decodedUrl.substringAfter("/season/")
            else -> decodedUrl
        }
        // Never mix the episode title into the season match: titles contain
        // episode ordinals such as "العشرون" that are not season numbers.
        val text = (if (path != decodedUrl) path else "${title.orEmpty()} $path")
            .replace('-', ' ').replace('_', ' ')
        Regex("""(?:الموسم|season)\s*([0-9٠-٩]+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.let { return toIntLoose(it) }
        val ordinals = listOf(
            "الخامس عشر" to 15, "الرابع عشر" to 14, "الثالث عشر" to 13,
            "الثاني عشر" to 12, "الحادي عشر" to 11,
            "التاسع عشر" to 19, "الثامن عشر" to 18, "السابع عشر" to 17,
            "السادس عشر" to 16, "العشرون" to 20,
            "الاول" to 1, "الأول" to 1, "الثاني" to 2, "الثالث" to 3, "الرابع" to 4,
            "الخامس" to 5, "السادس" to 6, "السابع" to 7, "الثامن" to 8,
            "التاسع" to 9, "العاشر" to 10, "الحادي" to 11
        )
        for ((key, value) in ordinals) if (text.contains(key)) return value
        return null
    }

    private fun toIntLoose(value: String): Int? {
        val normalized = value.map { c ->
            if (c in '\u0660'..'\u0669') '0' + (c - '\u0660') else c
        }.joinToString("")
        return normalized.toIntOrNull()
    }

    // =====================================================================
    // Links (watch servers + download fallback)
    // =====================================================================

    private fun toWatchUrl(url: String): String = url.trim()
        .replace("/download/", "/watch/")
        .replace("/film/", "/watch/")
        .replace("/episode/", "/watch/")

    private suspend fun getWatchData(watchUrl: String): WatchData? {
        watchCache[watchUrl]?.let { (at, value) ->
            if (isFresh(at, 30 * 60_000L)) return value
        }

        val res = siteGet(watchUrl, referer = watchUrl)
        val html = res.text

        val servers = parseServers(html)
        if (servers.isEmpty()) {
            Log.w(logTag, "no servers on $watchUrl")
            return null
        }

        val key = Regex("""watchResolveKey\s*=\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)
        val csrf = Regex("""csrfToken\s*=\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)
            ?: Regex("""<meta\s+name=["']csrf-token["']\s+content=["']([^"']+)["']""")
                .find(html)?.groupValues?.get(1)
        val resolveUrl = Regex("""resolveUrl\s*=\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.let { makeAbsoluteUrl(it) }
            ?: "${mainUrl}watch/resolve"

        if (key.isNullOrBlank() || csrf.isNullOrBlank()) {
            Log.w(logTag, "missing resolve key/csrf on $watchUrl")
            return null
        }

        val data = WatchData(
            watchUrl = watchUrl,
            servers = servers,
            key = key,
            csrf = csrf,
            resolveUrl = resolveUrl,
            cookies = cookiesFor(watchUrl)
        )
        watchCache[watchUrl] = now() to data
        return data
    }

    private fun parseServers(html: String): List<ServerEntry> {
        val cleaned = html
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")

        val block = Regex("""(?:const|let|var)\s+servers\s*=\s*(\[[\s\S]*?])\s*;""")
            .find(cleaned)?.groupValues?.get(1)

        val out = LinkedHashMap<String, ServerEntry>()

        if (block != null) {
            runCatching {
                val array = JSONArray(block.replace("\\/", "/"))
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val token = obj.optString("resolve_token")
                    if (token.isBlank()) continue
                    val serverName = obj.optString("name").ifBlank { "Server ${i + 1}" }
                    val rank = obj.optInt("rank", i)
                    val serverUrl = obj.optString("url").ifBlank { obj.optString("src") }
                    if (isCanaryServer(serverName, rank, serverUrl)) {
                        Log.w(logTag, "skip canary server '$serverName' rank=$rank")
                        continue
                    }
                    out[token] = ServerEntry(serverName, token, rank)
                }
            }.onFailure { Log.w(logTag, "servers JSON: ${it.message}") }
        }

        if (out.isEmpty()) {
            Regex("""data-token=["']([^"']+)["']""").findAll(cleaned).forEachIndexed { i, m ->
                val token = m.groupValues[1]
                if (token.isNotBlank()) out[token] = ServerEntry("Server ${i + 1}", token, i)
            }
        }

        return out.values.sortedBy { it.rank }
    }

    private suspend fun resolveServer(watch: WatchData, server: ServerEntry): String? {
        val cacheKey = "${watch.watchUrl}#${server.token}"
        resolveCache[cacheKey]?.let { (at, url) ->
            if (isFresh(at, 60 * 60_000L)) return url
        }

        val body = JSONObject().apply {
            put("key", watch.key)
            put("token", server.token)
            put("human_signal", JSONObject.NULL)
        }.toString()

        val headers = mapOf(
            "User-Agent" to ua(),
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7",
            "Content-Type" to "application/json",
            "X-CSRF-TOKEN" to watch.csrf,
            "X-Requested-With" to "XMLHttpRequest",
            "X-FP-Event-ID" to fpEventId,
            "Origin" to mainUrl.trimEnd('/'),
            "Referer" to headerSafe(watch.watchUrl),
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty",
            "sec-ch-ua" to secChUa(),
            "sec-ch-ua-mobile" to secChMobile(),
            "sec-ch-ua-platform" to "\"Android\""
        )

        val res = try {
            SiteGate.run {
                app.post(
                    watch.resolveUrl,
                    headers = headers,
                    cookies = watch.cookies,
                    referer = headerSafe(watch.watchUrl),
                    requestBody = body.toRequestBody(jsonMediaType),
                    interceptor = cfInterceptor,
                    timeout = 30L
                )
            }
        } catch (e: SiteCooldownException) {
            return null
        } catch (e: Exception) {
            Log.w(logTag, "resolve '${server.name}' transport: ${e.message}")
            return null
        }

        rememberCookies(watch.resolveUrl, res)

        val text = runCatching { res.text }.getOrDefault("")
        if (res.code == 429) {
            val retryAfter = res.headers["retry-after"]?.trim()?.toLongOrNull()
            SiteGate.penalizeRateLimit(retryAfter)
            return null
        }
        val challenge = isChallenge(res, text)
        if (res.code == 403 || challenge) {
            if (isBlockPage(text) || (res.code == 403 && !challenge)) {
                SiteGate.penalizeBlocked()
            } else {
                SiteGate.penalizeChallenge()
            }
            return null
        }
        if (res.code >= 400) {
            Log.w(logTag, "resolve '${server.name}' HTTP ${res.code}")
            return null
        }

        val json = runCatching { JSONObject(text) }.getOrNull()
        if (json == null) {
            Log.w(logTag, "resolve '${server.name}' non-JSON response")
            return null
        }
        if (!json.optBoolean("ok")) {
            Log.w(logTag, "resolve '${server.name}' refused: ${json.optString("message")}")
            return null
        }

        val raw = json.optString("url").trim()
        if (raw.isBlank()) return null

        val resolved = followInternalRedirect(makeAbsoluteUrl(raw) ?: return null, watch.watchUrl)
            ?: return null

        resolveCache[cacheKey] = now() to resolved
        return resolved
    }

    /**
     * بعض السيرفرات تُرجع رابط `/m/<hash>` داخلي (نفس أسلوب أزرار التحميل)؛
     * نتبعه مرة واحدة كي نصل إلى رابط المضيف الخارجي.
     */
    private suspend fun followInternalRedirect(url: String, referer: String): String? {
        if (!isSameSite(url) || !url.contains("/m/")) return url
        return try {
            val res = SiteGate.run {
                app.get(
                    url,
                    headers = browserHeaders(referer),
                    referer = headerSafe(referer),
                    cookies = cookiesFor(url),
                    interceptor = cfInterceptor,
                    allowRedirects = false,
                    timeout = 30L
                )
            }
            rememberCookies(url, res)
            when {
                res.code == 429 -> {
                    val retryAfter = res.headers["retry-after"]?.trim()?.toLongOrNull()
                    SiteGate.penalizeRateLimit(retryAfter)
                    null
                }
                res.code == 403 -> {
                    val body = runCatching { res.text }.getOrDefault("")
                    val challenge = isChallenge(res, body)
                    if (isBlockPage(body) || !challenge) {
                        SiteGate.penalizeBlocked()
                    } else {
                        SiteGate.penalizeChallenge()
                    }
                    null
                }
                res.code in 300..399 -> {
                    val location = res.headers["Location"] ?: res.headers["location"]
                    if (!location.isNullOrBlank()) {
                        makeAbsoluteUrl(location)?.takeIf { !isSameSite(it) }
                    } else {
                        null
                    }
                }
                res.code == 200 -> {
                    // صفحة HTML بدل تحويل = غالباً canary أو توكن منتهي. لا نعيد المحاولة.
                    Log.w(logTag, "internal redirect returned 200 (canary/expired?) for $url")
                    null
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(logTag, "internal redirect failed: ${e.message}")
            null
        }
    }

    private fun isDirectVideo(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4") ||
                lower.contains(".mkv") || lower.contains(".webm") ||
                lower.contains("/hls/")
    }

    private fun isEarnVidsHost(url: String): Boolean {
        val host = hostOf(url).orEmpty()
        return host.contains("earnvids") || host.contains("fastvid") ||
                host.contains("fastved") || host.contains("fdewsdc")
    }

    private suspend fun emitLink(
        url: String,
        serverName: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        quality: Int? = null
    ): Boolean {
        var produced = false
        val deliver: (ExtractorLink) -> Unit = {
            produced = true
            callback(it)
        }

        if (isDirectVideo(url)) {
            deliver(
                newExtractorLink(
                    source = this.name,
                    name = "$serverName • مباشر",
                    url = url
                ) {
                    this.referer = referer
                    this.quality = quality ?: Qualities.Unknown.value
                    this.headers = browserHeaders(referer).toMutableMap()
                    this.type = if (url.contains(".m3u8", true)) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }
                }
            )
            return true
        }

        return try {
            if (isEarnVidsHost(url)) {
                earnVids.getUrl(url, referer, subtitleCallback, deliver)
            } else {
                loadExtractor(url, referer, subtitleCallback, deliver)
            }
            produced
        } catch (e: Exception) {
            Log.w(logTag, "extract '$serverName' failed: ${e.message}")
            false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        linkCache[data]?.let { (at, links) ->
            if (isFresh(at, 45 * 60_000L)) {
                var ok = 0
                for ((label, url, referer) in links) {
                    if (emitLink(url, label, referer, subtitleCallback, callback)) ok++
                }
                if (ok > 0) return true
            }
            linkCache.remove(data)
        }

        if (SiteGate.isCoolingDown()) return false

        val emitted = try {
            emitFromDownloadPage(data, subtitleCallback, callback).ifEmpty {
                if (SiteGate.isCoolingDown()) emptyList()
                else emitFromWatchPage(data, subtitleCallback, callback)
            }
        } catch (e: SiteCooldownException) {
            Log.w(logTag, "loadLinks cooldown")
            emptyList()
        } catch (e: Exception) {
            Log.w(logTag, "loadLinks failed: ${e.message}")
            emptyList()
        }

        if (emitted.isNotEmpty()) {
            linkCache[data] = now() to emitted
            return true
        }
        return false
    }

    private fun toDownloadUrl(url: String): String = url.trim()
        .replace("/watch/", "/download/")
        .replace("/episode/", "/download/")
        .replace("/film/", "/download/")

    /**
     * المسار الأساسي: صفحة التحميل (طلب GET واحد) ثم متابعة أول زر مرئي ناجح
     * عبر /m/<hash>. لا POST ولا بصمة إصبع هنا، ولذلك لا يُعتبر بوت.
     * نتجاهل #download-primary-server وأي رابط من خصائص data أو template (فخ canary).
     */
    private suspend fun emitFromDownloadPage(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): List<Triple<String, String, String>> {
        val downloadUrl = toDownloadUrl(data)
        if (downloadUrl == data && !data.contains("/download/")) return emptyList()

        val doc = try {
            getDocument(downloadUrl, referer = data)
        } catch (e: SiteCooldownException) {
            throw e
        } catch (e: Exception) {
            Log.w(logTag, "download page failed: ${e.message}")
            return emptyList()
        }

        data class Entry(val name: String, val quality: Int, val href: String)

        val streamingHosts = listOf(
            "earnvids", "fastvid", "fastved", "fdewsdc", "vinovo", "vidaraa",
            "doodstream", "voe", "streamtape", "mixdrop", "m1xdrop", "luluvdo", "ok"
        )

        val entries = doc.select("div.down-container a.btn-down[href]")
            .mapNotNull { anchor ->
                if (isHiddenElement(anchor)) return@mapNotNull null
                if (anchor.id() == "download-primary-server") return@mapNotNull null
                val href = makeAbsoluteUrl(anchor.attr("href")) ?: return@mapNotNull null
                if (!isSameSite(href) || !href.contains("/m/")) return@mapNotNull null
                if (isCanaryHref(href)) return@mapNotNull null
                val info = anchor.selectFirst(".info")?.text().orEmpty()
                val name = anchor.selectFirst(".info span")?.text()?.trim()
                    .orEmpty().ifBlank { "سيرفر" }
                val quality = Regex("""\b(1080|720|480|360)\b""")
                    .find(info)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                Entry(name, quality, href)
            }
            .filter { !it.name.contains("usersdrive", true) }
            .sortedWith(
                compareByDescending<Entry> { it.quality }.thenBy { entry ->
                    val i = streamingHosts.indexOfFirst { entry.name.contains(it, true) }
                    if (i < 0) 99 else i
                }
            )

        val emitted = ArrayList<Triple<String, String, String>>()
        var attempts = 0
        for (entry in entries) {
            if (attempts >= 2 || emitted.isNotEmpty() || SiteGate.isCoolingDown()) break
            attempts++
            val location = followInternalRedirect(entry.href, downloadUrl) ?: continue
            val label = if (entry.quality > 0) "${entry.name} • ${entry.quality}p" else entry.name
            val quality = entry.quality.takeIf { it > 0 }
            if (emitLink(location, label, downloadUrl, subtitleCallback, callback, quality)) {
                emitted.add(Triple(label, location, downloadUrl))
            }
        }
        return emitted
    }

    /**
     * احتياطي فقط إن فشلت صفحة التحميل: سيرفرات صفحة المشاهدة، بحد أقصى
     * سيرفرين وبالتسلسل (طلب GET + POST واحد لكل محاولة). الوقوف عند أول نجاح.
     */
    private suspend fun emitFromWatchPage(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): List<Triple<String, String, String>> {
        val watchUrl = toWatchUrl(data)
        val watch = try {
            getWatchData(watchUrl)
        } catch (e: SiteCooldownException) {
            throw e
        } catch (e: Exception) {
            Log.w(logTag, "watch page failed: ${e.message}")
            null
        } ?: return emptyList()

        val emitted = ArrayList<Triple<String, String, String>>()
        var attempts = 0
        for (server in watch.servers) {
            if (attempts >= 1 || emitted.isNotEmpty() || SiteGate.isCoolingDown()) break
            attempts++
            val resolved = resolveServer(watch, server) ?: continue
            if (emitLink(resolved, server.name, watchUrl, subtitleCallback, callback)) {
                emitted.add(Triple(server.name, resolved, watchUrl))
            }
        }
        return emitted
    }

    companion object {
        private val TRANSPARENT_PNG =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg=="

        private fun isSafeImage(url: String): Boolean {
            if (url.isBlank()) return false
            if (url.contains("/m/")) return false
            if (url.startsWith("data:", true)) return false
            return url.startsWith("http://", true) || url.startsWith("https://", true)
        }
    }
}
