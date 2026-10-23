package com.anime4up

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Resilient networking layer for Anime4Up:
 *  1. Direct HTTP with realistic Chrome Mobile headers & persisted `cf_clearance` cookies.
 *  2. Silent Android WebView Turnstile/JS solver when running inside CloudStream on Android.
 *  3. Multi-source HTTP/SOCKS5 proxy harvester & concurrent tester when Cloudflare blocks IP/VPN.
 */
object Anime4UpNetwork {
    private const val TAG = "Anime4UpNet"
    private const val PREFS_NAME = "anime4up_net_prefs_v2"
    private const val KEY_CF_COOKIES = "cf_cookies"
    private const val KEY_CF_UA = "cf_ua"
    private const val KEY_WORKING_PROXY = "working_proxy"

    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val PROXY_SOURCES = listOf(
        "http" to "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=http&timeout=6500&country=all&ssl=yes&anonymity=all",
        "http" to "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt",
        "socks5" to "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks5.txt",
        "http" to "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt",
        "socks5" to "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt",
        "http" to "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/https.txt",
        "socks5" to "https://raw.githubusercontent.com/hookzof/socks5_list/master/proxy.txt"
    )

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedCookies: String? = null

    @Volatile
    private var cachedUa: String = DEFAULT_UA

    @Volatile
    private var cachedProxySpec: String? = null

    private val proxyMutex = Mutex()
    private val cfMutex = Mutex()
    private val deadProxies = ConcurrentHashMap.newKeySet<String>()

    fun init(context: Context) {
        appContext = context.applicationContext
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            cachedCookies = prefs.getString(KEY_CF_COOKIES, null)
            cachedUa = prefs.getString(KEY_CF_UA, DEFAULT_UA) ?: DEFAULT_UA
            cachedProxySpec = prefs.getString(KEY_WORKING_PROXY, null)
        }
    }

    private fun savePrefs() {
        val ctx = appContext ?: return
        runCatching {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_CF_COOKIES, cachedCookies)
                .putString(KEY_CF_UA, cachedUa)
                .putString(KEY_WORKING_PROXY, cachedProxySpec)
                .apply()
        }
    }

    fun buildHeaders(referer: String? = null, extraHeaders: Map<String, String> = emptyMap()): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = cachedUa
        headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        headers["Accept-Language"] = "ar,en-US;q=0.9,en;q=0.8"
        headers["Sec-Ch-Ua"] = "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\""
        headers["Sec-Ch-Ua-Mobile"] = "?1"
        headers["Sec-Ch-Ua-Platform"] = "\"Android\""
        headers["Sec-Fetch-Dest"] = "document"
        headers["Sec-Fetch-Mode"] = "navigate"
        headers["Sec-Fetch-Site"] = if (referer != null) "same-origin" else "none"
        headers["Upgrade-Insecure-Requests"] = "1"
        if (!referer.isNullOrBlank()) headers["Referer"] = referer
        if (!cachedCookies.isNullOrBlank()) headers["Cookie"] = cachedCookies!!
        headers.putAll(extraHeaders)
        return headers
    }

    fun isCloudflareChallenge(code: Int, body: String): Boolean {
        if (code == 403 || code == 503 || code == 429) {
            val low = body.lowercase()
            if (low.contains("just a moment") ||
                low.contains("cf-browser-verification") ||
                low.contains("attention required") ||
                low.contains("_cf_chl_opt") ||
                body.length < 1500
            ) {
                return true
            }
        }
        val low = body.lowercase()
        return low.contains("<title>just a moment") ||
            low.contains("cf-browser-verification") ||
            (low.contains("_cf_chl_opt") && !low.contains("anime-card-container") && !low.contains("episode-servers") && !low.contains("anime-details"))
    }

    fun isValidAnime4UpHtml(body: String): Boolean {
        if (body.length < 800) return false
        val low = body.lowercase()
        if (low.contains("<title>just a moment") || low.contains("<title>attention required")) return false
        return low.contains("anime4up") ||
            low.contains("anime-card-container") ||
            low.contains("anime-details") ||
            low.contains("episode-servers") ||
            body.contains("الحلقة")
    }

    suspend fun fetchDocument(url: String, referer: String? = null): Document? = withContext(Dispatchers.IO) {
        val html = fetchHtml(url, referer) ?: return@withContext null
        Jsoup.parse(html, url)
    }

    suspend fun fetchHtml(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        requireSiteSignature: Boolean = true
    ): String? = withContext(Dispatchers.IO) {
        // 1. Try direct connection first (fastest path for residential/mobile connections)
        directGet(url, referer, extraHeaders)?.let { (code, body) ->
            if (!isCloudflareChallenge(code, body) && (!requireSiteSignature || isValidAnime4UpHtml(body))) {
                return@withContext body
            }
        }

        // 2. Try cached working proxy immediately if one was already discovered
        val currentProxy = cachedProxySpec
        if (!currentProxy.isNullOrBlank() && !deadProxies.contains(currentProxy)) {
            proxyGet(url, currentProxy, referer, extraHeaders, timeoutSec = 14)?.let { (code, body) ->
                if (code in 200..399 && !isCloudflareChallenge(code, body) && (!requireSiteSignature || isValidAnime4UpHtml(body))) {
                    return@withContext body
                }
            }
            deadProxies.add(currentProxy)
        }

        // 3. Try silent Android WebView Cloudflare solver if an Activity is available
        cfMutex.withLock {
            // Re-check direct in case another coroutine just solved cf_clearance
            directGet(url, referer, extraHeaders)?.let { (code, body) ->
                if (!isCloudflareChallenge(code, body) && (!requireSiteSignature || isValidAnime4UpHtml(body))) {
                    return@withContext body
                }
            }

            val activity = findActiveActivity()
            if (activity != null) {
                val solved = solveCloudflareWithWebView(activity, url, cachedUa)
                if (!solved?.cookies.isNullOrBlank()) {
                    cachedCookies = solved!!.cookies
                    if (!solved.userAgent.isNullOrBlank()) cachedUa = solved.userAgent
                    savePrefs()

                    directGet(url, referer, extraHeaders)?.let { (code, body) ->
                        if (!isCloudflareChallenge(code, body) && (!requireSiteSignature || isValidAnime4UpHtml(body))) {
                            return@withContext body
                        }
                    }
                }
            }
        }

        // 4. Multi-source Proxy Harvester & Concurrent Failover
        proxyMutex.withLock {
            val refreshedProxy = cachedProxySpec
            if (!refreshedProxy.isNullOrBlank() && !deadProxies.contains(refreshedProxy)) {
                proxyGet(url, refreshedProxy, referer, extraHeaders, timeoutSec = 14)?.let { (code, body) ->
                    if (code in 200..399 && !isCloudflareChallenge(code, body) && (!requireSiteSignature || isValidAnime4UpHtml(body))) {
                        return@withContext body
                    }
                }
                deadProxies.add(refreshedProxy)
            }

            discoverWorkingProxyAndFetch(url, referer, extraHeaders, requireSiteSignature)
        }
    }

    private fun directGet(
        url: String,
        referer: String?,
        extraHeaders: Map<String, String>
    ): Pair<Int, String>? {
        return try {
            val reqBuilder = Request.Builder().url(url)
            buildHeaders(referer, extraHeaders).forEach { (k, v) -> reqBuilder.header(k, v) }
            val client = app.baseClient.newBuilder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
            client.newCall(reqBuilder.build()).execute().use { res ->
                val body = res.body?.string().orEmpty()
                res.code to body
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Log.d(TAG, "directGet failed for $url: ${e.message}")
            null
        }
    }

    private fun parseProxySpec(spec: String): Proxy? {
        return try {
            val scheme = spec.substringBefore("://", "http").lowercase()
            val hostPort = spec.substringAfter("://")
            val host = hostPort.substringBeforeLast(":").trim()
            val port = hostPort.substringAfterLast(":").trim().toIntOrNull() ?: return null
            if (host.isBlank() || port !in 1..65535) return null
            val type = if (scheme.startsWith("socks")) Proxy.Type.SOCKS else Proxy.Type.HTTP
            Proxy(type, InetSocketAddress.createUnresolved(host, port))
        } catch (_: Throwable) {
            null
        }
    }

    private fun proxyGet(
        url: String,
        proxySpec: String,
        referer: String?,
        extraHeaders: Map<String, String>,
        timeoutSec: Long = 10
    ): Pair<Int, String>? {
        val javaProxy = parseProxySpec(proxySpec) ?: return null
        return try {
            val client: OkHttpClient = app.baseClient.newBuilder()
                .proxy(javaProxy)
                .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .callTimeout(timeoutSec + 4, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

            val reqBuilder = Request.Builder().url(url)
            buildHeaders(referer, extraHeaders).forEach { (k, v) -> reqBuilder.header(k, v) }
            client.newCall(reqBuilder.build()).execute().use { res ->
                val body = res.body?.string().orEmpty()
                res.code to body
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
        }
    }

    private suspend fun harvestCandidateProxies(): List<String> = coroutineScope {
        val jobs = PROXY_SOURCES.map { (proto, feedUrl) ->
            async(Dispatchers.IO) {
                try {
                    val req = Request.Builder()
                        .url(feedUrl)
                        .header("User-Agent", DESKTOP_UA)
                        .build()
                    val client = app.baseClient.newBuilder()
                        .connectTimeout(7, TimeUnit.SECONDS)
                        .readTimeout(8, TimeUnit.SECONDS)
                        .build()
                    client.newCall(req).execute().use { res ->
                        if (!res.isSuccessful) return@async emptyList()
                        res.body?.string().orEmpty()
                            .lineSequence()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains(":") }
                            .take(120)
                            .map { line -> if (line.contains("://")) line else "$proto://$line" }
                            .toList()
                    }
                } catch (_: Throwable) {
                    emptyList()
                }
            }
        }

        val combined = LinkedHashSet<String>()
        for (job in jobs) {
            combined.addAll(job.await())
        }
        combined.filterNot { deadProxies.contains(it) }.shuffled().take(240)
    }

    private suspend fun discoverWorkingProxyAndFetch(
        url: String,
        referer: String?,
        extraHeaders: Map<String, String>,
        requireSiteSignature: Boolean
    ): String? {
        val candidates = harvestCandidateProxies()
        if (candidates.isEmpty()) return null

        Log.i(TAG, "Testing ${candidates.size} multi-source proxies for $url")
        val winner = CompletableDeferred<Pair<String, String>?>()
        val sem = Semaphore(24)

        try {
            coroutineScope {
                val tasks = candidates.map { spec ->
                    async(Dispatchers.IO) {
                        if (winner.isCompleted) return@async
                        sem.withPermit {
                            if (winner.isCompleted) return@withPermit
                            val res = proxyGet(url, spec, referer, extraHeaders, timeoutSec = 9)
                            if (res != null) {
                                val (code, body) = res
                                val valid = code in 200..399 &&
                                    !isCloudflareChallenge(code, body) &&
                                    (!requireSiteSignature || isValidAnime4UpHtml(body))
                                if (valid) {
                                    winner.complete(spec to body)
                                } else {
                                    deadProxies.add(spec)
                                }
                            } else {
                                deadProxies.add(spec)
                            }
                        }
                    }
                }

                // Complete with null once all tasks finish if none succeeded
                async {
                    tasks.forEach { runCatching { it.await() } }
                    if (!winner.isCompleted) winner.complete(null)
                }

                val result = winner.await()
                tasks.forEach { it.cancel() }
                if (result != null) {
                    cachedProxySpec = result.first
                    savePrefs()
                    Log.i(TAG, "Selected working proxy: ${result.first}")
                    return@coroutineScope result.second
                }
                null
            }
        } catch (_: Throwable) {
            null
        }
        return if (winner.isCompleted) winner.getCompleted()?.second else null
    }

    // =========================================================================
    // Silent Android WebView Cloudflare Cookie Solver
    // =========================================================================

    private data class WebViewSolveResult(val cookies: String?, val userAgent: String?)

    private fun findActiveActivity(): Activity? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            val activitiesField = activityThreadClass.getDeclaredField("mActivities").apply { isAccessible = true }
            val activities = activitiesField.get(activityThread) as? Map<*, *> ?: return null
            for (record in activities.values) {
                if (record == null) continue
                val actField = record.javaClass.getDeclaredField("activity").apply { isAccessible = true }
                val act = actField.get(record) as? Activity
                if (act != null && !act.isFinishing && !act.isDestroyed) return act
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun solveCloudflareWithWebView(
        activity: Activity,
        targetUrl: String,
        initialUa: String
    ): WebViewSolveResult? = suspendCoroutine { continuation ->
        Handler(Looper.getMainLooper()).post {
            val rootView = activity.findViewById<ViewGroup>(android.R.id.content) ?: run {
                continuation.resume(null)
                return@post
            }

            var finished = false
            val handler = Handler(Looper.getMainLooper())
            val webView = try {
                WebView(activity)
            } catch (_: Throwable) {
                continuation.resume(null)
                return@post
            }

            webView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            webView.alpha = 0.01f
            webView.translationX = 10000f
            webView.translationY = 10000f

            val actualUa = runCatching {
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    userAgentString = initialUa
                }
                webView.settings.userAgentString
            }.getOrDefault(initialUa)

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)

            fun finish() {
                if (finished) return
                finished = true
                runCatching { cookieManager.flush() }
                val cookies = runCatching { cookieManager.getCookie(targetUrl) }.getOrNull()
                runCatching {
                    handler.removeCallbacksAndMessages(null)
                    rootView.removeView(webView)
                    webView.destroy()
                }
                continuation.resume(WebViewSolveResult(cookies, actualUa))
            }

            handler.postDelayed({ finish() }, 18_000L)

            fun pollClearance() {
                if (finished) return
                val c = runCatching { cookieManager.getCookie(webView.url ?: targetUrl) }.getOrNull()
                if (!c.isNullOrBlank() && c.contains("cf_clearance")) {
                    finish()
                    return
                }
                val clickBoxJs = """
                    (function(){
                        try {
                            var box = document.querySelector("html > body > div:nth-of-type(1) > div > div:nth-of-type(2) > div");
                            if (!box) return "";
                            var r = box.getBoundingClientRect();
                            if (r.width === 0 && r.height === 0) return "";
                            return (r.left + 28) + "," + (r.top + r.height / 2);
                        } catch (e) { return ""; }
                    })();
                """.trimIndent()
                runCatching {
                    webView.evaluateJavascript(clickBoxJs) { res ->
                        val coords = res?.removeSurrounding("\"")?.split(",")
                        val cx = coords?.getOrNull(0)?.toFloatOrNull()
                        val cy = coords?.getOrNull(1)?.toFloatOrNull()
                        if (cx != null && cy != null) {
                            val density = activity.resources.displayMetrics.density
                            val now = SystemClock.uptimeMillis()
                            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, cx * density, cy * density, 0)
                            val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, cx * density, cy * density, 0)
                            webView.dispatchTouchEvent(down)
                            webView.dispatchTouchEvent(up)
                            down.recycle()
                            up.recycle()
                        }
                    }
                }
                handler.postDelayed({ pollClearance() }, 1500L)
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    pollClearance()
                }
            }

            runCatching {
                rootView.addView(webView)
                webView.loadUrl(targetUrl)
            }.onFailure {
                finish()
            }
        }
    }
}
