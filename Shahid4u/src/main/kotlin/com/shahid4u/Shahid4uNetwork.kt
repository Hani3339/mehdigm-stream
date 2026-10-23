package com.shahid4u

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.lang.ref.WeakReference
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.random.Random

/**
 * Resilient, session-sticky network & anti-Cloudflare / anti-canary layer for Shahid4u.
 *
 * Key guarantees:
 * 1. Hybrid System + DNS-over-HTTPS (1.1.1.1 / 8.8.8.8) bypasses ISP DNS poisoning.
 * 2. Session-sticky CookieJar preserves `XSRF-TOKEN`, `laravel_session`, and `cf_clearance`
 *    across `/watch/<slug>` -> `POST /watch/resolve` -> `/m/<hash>` and `/download/<slug>` -> `/m/<hash>`.
 * 3. 4-tier anti-Cloudflare transport:
 *      Tier 1: Direct OkHttpClient with browser headers + polite adaptive pacing.
 *      Tier 2: Silent Android WebView solver + TLS-bound WebView `fetch` & 302 redirect catcher
 *              (defeats JA3/JA4 TLS-bound `cType: 'managed'` Cloudflare challenges).
 *      Tier 3: CloudStream `CloudflareKiller` interceptor.
 *      Tier 4: Session-sticky SOCKS/HTTP proxy harvester for ASN-blocked VPN/datacenter IPs.
 * 4. 100% Canary & Honeytoken immunity (`data-honeytoken`, `<template data-trap>`, `#download-primary-server`,
 *    JS `.remove()` traps, hidden elements, `/media/` paths, and `/m/` poster traps).
 */
object Shahid4uNetwork {

    private const val TAG = "Shahid4uNet"
    private const val PREFS_NAME = "shahid4u_net_v12"
    private const val KEY_CF_COOKIES = "cf_cookies"
    private const val KEY_USER_AGENT = "user_agent"
    private const val KEY_ACTIVE_DOMAIN = "active_domain"

    const val DEFAULT_MAIN_URL = "https://shaahidd4u.net"

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    @Volatile
    var mainUrl: String = DEFAULT_MAIN_URL
        private set

    @Volatile
    var userAgent: String = DEFAULT_UA
        private set

    @Volatile
    private var prefsLoaded = false

    @Volatile
    private var preferWebViewTransport = false

    private val cfMutex = Mutex()
    private val proxyMutex = Mutex()
    private val hostPaceLock = Mutex()
    private val lastHostCallAt = AtomicLong(0L)

    // DNS cache: host -> (expiresAtMs, addresses)
    private val dnsCache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()

    // Proxy pool for ASN-blocked VPN / datacenter exits
    private val workingProxies = CopyOnWriteArrayList<String>()
    private val deadProxies = ConcurrentHashMap.newKeySet<String>()
    private val proxyFailCounts = ConcurrentHashMap<String, Int>()
    private val proxyClients = ConcurrentHashMap<String, ProxySessionClient>()

    private val PROXY_FEEDS = listOf(
        "socks5" to "https://raw.githubusercontent.com/TheSpeedX/SOCKS-List/master/socks5.txt",
        "socks4" to "https://raw.githubusercontent.com/TheSpeedX/SOCKS-List/master/socks4.txt",
        "http" to "https://raw.githubusercontent.com/TheSpeedX/SOCKS-List/master/http.txt",
        "socks5" to "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt",
        "http" to "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt"
    )

    // ── Session-Sticky Cookie Jar ────────────────────────────────────────────

    class SessionCookieStore(private val syncWithWebKit: Boolean = false) : CookieJar {
        private val store = ConcurrentHashMap<String, ConcurrentHashMap<String, Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val hostKey = baseDomain(url.host)
            val bucket = store.getOrPut(hostKey) { ConcurrentHashMap() }
            val now = System.currentTimeMillis()
            for (c in cookies) {
                if (c.expiresAt < now || c.value.equals("deleted", ignoreCase = true)) {
                    bucket.remove(c.name)
                } else {
                    bucket[c.name] = c
                }
            }
            if (syncWithWebKit) {
                runCatching {
                    val cm = CookieManager.getInstance()
                    val origin = "${url.scheme}://${url.host}"
                    cookies.forEach { cm.setCookie(origin, it.toString()) }
                }
                if (cookies.any { it.name == "cf_clearance" }) {
                    savePrefs()
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            if (syncWithWebKit) {
                importFromWebKit("${url.scheme}://${url.host}", url)
            }
            val hostKey = baseDomain(url.host)
            val bucket = store[hostKey] ?: return emptyList()
            val now = System.currentTimeMillis()
            val valid = ArrayList<Cookie>(bucket.size)
            for ((name, cookie) in bucket) {
                if (cookie.expiresAt < now) {
                    bucket.remove(name)
                } else {
                    valid.add(cookie)
                }
            }
            return valid
        }

        fun importCookieHeader(urlStr: String, rawCookieHeader: String?) {
            if (rawCookieHeader.isNullOrBlank()) return
            val httpUrl = urlStr.toHttpUrlOrNull() ?: return
            val hostKey = baseDomain(httpUrl.host)
            val bucket = store.getOrPut(hostKey) { ConcurrentHashMap() }
            rawCookieHeader.split(";").forEach { part ->
                val trimmed = part.trim()
                val eq = trimmed.indexOf('=')
                if (eq > 0) {
                    val k = trimmed.substring(0, eq).trim()
                    val v = trimmed.substring(eq + 1).trim()
                    if (k.isNotEmpty() && v.isNotEmpty()) {
                        Cookie.Builder()
                            .name(k)
                            .value(v)
                            .domain(httpUrl.host)
                            .path("/")
                            .build()
                            .let { bucket[k] = it }
                    }
                }
            }
        }

        fun exportCookieHeader(urlStr: String): String {
            val httpUrl = urlStr.toHttpUrlOrNull() ?: return ""
            return loadForRequest(httpUrl).joinToString("; ") { "${it.name}=${it.value}" }
        }

        private fun importFromWebKit(origin: String, httpUrl: HttpUrl) {
            runCatching {
                val raw = CookieManager.getInstance().getCookie(origin)
                if (!raw.isNullOrBlank()) {
                    val hostKey = baseDomain(httpUrl.host)
                    val bucket = store.getOrPut(hostKey) { ConcurrentHashMap() }
                    raw.split(";").forEach { seg ->
                        val eq = seg.indexOf('=')
                        if (eq > 0) {
                            val name = seg.substring(0, eq).trim()
                            val value = seg.substring(eq + 1).trim()
                            if (name.isNotEmpty() && value.isNotEmpty() && !bucket.containsKey(name)) {
                                bucket[name] = Cookie.Builder()
                                    .name(name)
                                    .value(value)
                                    .domain(httpUrl.host)
                                    .path("/")
                                    .build()
                            }
                        }
                    }
                }
            }
        }

        private fun baseDomain(host: String): String {
            val parts = host.lowercase().trimStart('.').split('.')
            return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host.lowercase()
        }
    }

    val directCookies = SessionCookieStore(syncWithWebKit = true)

    // ── Hybrid System + DoH DNS Resolver ─────────────────────────────────────

    private val dohBootstrapClient: OkHttpClient by lazy {
        app.baseClient.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    val hybridDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val key = hostname.lowercase()
            val now = System.currentTimeMillis()
            dnsCache[key]?.let { (exp, addrs) ->
                if (exp > now && addrs.isNotEmpty()) return addrs
            }

            // 1. System DNS unless poisoned/bogon
            runCatching {
                val sys = Dns.SYSTEM.lookup(hostname)
                val clean = sys.filterNot { addr ->
                    addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isSiteLocalAddress ||
                        addr.hostAddress == "0.0.0.0"
                }
                if (clean.isNotEmpty()) {
                    dnsCache[key] = (now + 600_000L) to clean
                    return clean
                }
            }

            // 2. Cloudflare & Google DoH JSON fallback
            val dohEndpoints = listOf(
                "https://1.1.1.1/dns-query?name=${URLEncoder.encode(hostname, "UTF-8")}&type=A",
                "https://8.8.8.8/resolve?name=${URLEncoder.encode(hostname, "UTF-8")}&type=A"
            )
            for (ep in dohEndpoints) {
                val resolved = runCatching {
                    val req = Request.Builder()
                        .url(ep)
                        .header("Accept", "application/dns-json")
                        .build()
                    dohBootstrapClient.newCall(req).execute().use { res ->
                        if (!res.isSuccessful) return@use emptyList()
                        val json = JSONObject(res.body?.string().orEmpty())
                        val answers = json.optJSONArray("Answer") ?: JSONArray()
                        val ips = ArrayList<InetAddress>()
                        for (i in 0 until answers.length()) {
                            val obj = answers.optJSONObject(i) ?: continue
                            if (obj.optInt("type") == 1) {
                                val data = obj.optString("data").trim()
                                if (data.isNotEmpty()) {
                                    ips.add(InetAddress.getByName(data))
                                }
                            }
                        }
                        ips
                    }
                }.getOrDefault(emptyList())

                if (resolved.isNotEmpty()) {
                    dnsCache[key] = (now + 600_000L) to resolved
                    return resolved
                }
            }

            return Dns.SYSTEM.lookup(hostname)
        }
    }

    // ── Clients ──────────────────────────────────────────────────────────────

    val directClient: OkHttpClient by lazy {
        app.baseClient.newBuilder()
            .dns(hybridDns)
            .cookieJar(directCookies)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val directNoRedirectClient: OkHttpClient by lazy {
        directClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private val cfKillerInterceptor by lazy { CloudflareKiller() }

    private val cfKillerClient: OkHttpClient by lazy {
        directClient.newBuilder()
            .addInterceptor(cfKillerInterceptor)
            .build()
    }

    private val cfKillerNoRedirectClient: OkHttpClient by lazy {
        cfKillerClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /**
     * Trust-all SSL client strictly for video sub-hosts with broken intermediate cert chains
     * (such as `vids.st`, whose leaf cert omits the intermediate CA).
     */
    val trustAllClient: OkHttpClient by lazy {
        @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslCtx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
        app.baseClient.newBuilder()
            .sslSocketFactory(sslCtx.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private class ProxySessionClient(val proxySpec: String, javaProxy: Proxy) {
        val cookieStore = SessionCookieStore(syncWithWebKit = false)
        val client: OkHttpClient = app.baseClient.newBuilder()
            .proxy(javaProxy)
            .cookieJar(cookieStore)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(16, TimeUnit.SECONDS)
            .callTimeout(22, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val noRedirectClient: OkHttpClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    // ── Transport Abstraction (Session-Sticky!) ──────────────────────────────

    enum class TransportKind { DIRECT, CF_KILLER, WEBVIEW, PROXY }

    data class TransportHandle(
        val kind: TransportKind,
        val proxySpec: String? = null
    )

    data class SanitizedPage(
        val rawHtml: String,
        val document: Document,
        val finalUrl: String,
        val trapTokens: Set<String>,
        val transport: TransportHandle
    )

    data class PostResult(
        val code: Int,
        val body: String
    )

    // ── Prefs Initialization ─────────────────────────────────────────────────

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        ensurePrefsLoaded()
    }

    private fun ensurePrefsLoaded() {
        if (prefsLoaded) return
        val ctx = appContext ?: return
        synchronized(this) {
            if (prefsLoaded) return
            prefsLoaded = true
            runCatching {
                val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                sp.getString(KEY_USER_AGENT, null)?.takeIf { it.isNotBlank() }?.let { userAgent = it }
                sp.getString(KEY_ACTIVE_DOMAIN, null)?.takeIf { it.startsWith("http") }?.let { mainUrl = it.trimEnd('/') }
                sp.getString(KEY_CF_COOKIES, null)?.takeIf { it.isNotBlank() }?.let {
                    directCookies.importCookieHeader(mainUrl, it)
                }
            }
        }
    }

    private fun savePrefs() {
        runCatching {
            val ctx = appContext ?: return@runCatching
            val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cookieHeader = directCookies.exportCookieHeader(mainUrl)
            sp.edit()
                .putString(KEY_USER_AGENT, userAgent)
                .putString(KEY_ACTIVE_DOMAIN, mainUrl)
                .putString(KEY_CF_COOKIES, cookieHeader)
                .apply()
        }
    }

    private fun updateDomainIfRedirected(finalUrl: String) {
        val host = finalUrl.toHttpUrlOrNull()?.host?.lowercase() ?: return
        if ((host.contains("shahid") || host.contains("shaahid")) && !host.contains("google")) {
            val scheme = finalUrl.substringBefore("://", "https")
            val newBase = "$scheme://$host"
            if (newBase != mainUrl) {
                mainUrl = newBase
                savePrefs()
            }
        }
    }

    // ── URL & Anti-Bot Helpers ───────────────────────────────────────────────

    fun resolveUrl(href: String?, base: String = mainUrl): String? {
        val raw = href?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.startsWith("javascript:", ignoreCase = true) || raw.startsWith("#")) return null
        val full = when {
            raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "${base.trimEnd('/')}$raw"
            else -> "${base.trimEnd('/')}/$raw"
        }
        return encodeUrl(full)
    }

    fun encodeUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        return try {
            val schemeEnd = trimmed.indexOf("://")
            if (schemeEnd <= 0) return trimmed
            val scheme = trimmed.substring(0, schemeEnd)
            val rest = trimmed.substring(schemeEnd + 3)
            val slashIdx = rest.indexOf('/')
            val qIdx = rest.indexOf('?')
            val authorityEnd = when {
                slashIdx >= 0 && qIdx >= 0 -> minOf(slashIdx, qIdx)
                slashIdx >= 0 -> slashIdx
                qIdx >= 0 -> qIdx
                else -> rest.length
            }
            val authority = rest.substring(0, authorityEnd)
            val pathAndMore = rest.substring(authorityEnd)
            val hashIdx = pathAndMore.indexOf('#')
            val beforeHash = if (hashIdx >= 0) pathAndMore.substring(0, hashIdx) else pathAndMore
            val fragment = if (hashIdx >= 0) pathAndMore.substring(hashIdx) else ""
            val queryIdx = beforeHash.indexOf('?')
            val rawPath = if (queryIdx >= 0) beforeHash.substring(0, queryIdx) else beforeHash
            val rawQuery = if (queryIdx >= 0) beforeHash.substring(queryIdx + 1) else null

            val encodedPath = rawPath.split('/').joinToString("/") { seg ->
                if (seg.isEmpty()) ""
                else {
                    val decoded = runCatching { URLDecoder.decode(seg, "UTF-8") }.getOrDefault(seg)
                    URLEncoder.encode(decoded, "UTF-8").replace("+", "%20")
                }
            }

            val encodedQuery = rawQuery?.split('&')?.joinToString("&") { pair ->
                val eq = pair.indexOf('=')
                if (eq < 0) pair
                else {
                    val k = pair.substring(0, eq)
                    val v = pair.substring(eq + 1)
                    val decV = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
                    "$k=${URLEncoder.encode(decV, "UTF-8")}"
                }
            }

            buildString {
                append(scheme).append("://").append(authority)
                if (encodedPath.isNotEmpty()) {
                    if (!encodedPath.startsWith("/")) append('/')
                    append(encodedPath)
                }
                if (encodedQuery != null) append('?').append(encodedQuery)
                append(fragment)
            }
        } catch (_: Throwable) {
            runCatching { URI(trimmed).toASCIIString() }.getOrDefault(trimmed)
        }
    }

    fun isCloudflareOrBlocked(code: Int, body: String): Boolean {
        if (code == 429 || code == 503) return true
        val trimmed = body.trim()
        if (code == 403 && (trimmed.length < 12000 || trimmed.equals("Forbidden", ignoreCase = true))) return true
        if (trimmed.length < 400 && code >= 400) return true
        val head = if (body.length > 8192) body.substring(0, 8192).lowercase() else body.lowercase()
        return head.contains("<title>just a moment") ||
            head.contains("attention required! | cloudflare") ||
            head.contains("window._cf_chl_opt") ||
            head.contains("/cdn-cgi/challenge-platform/") && head.contains("chl_page") ||
            head.contains("sorry, you have been blocked") ||
            head.contains("cf-browser-verification")
    }

    fun isValidShahid4uHtml(code: Int, body: String): Boolean {
        if (code !in 200..299 || body.length < 1500) return false
        if (isCloudflareOrBlocked(code, body)) return false
        val low = if (body.length > 20000) body.substring(0, 20000).lowercase() else body.lowercase()
        return low.contains("shahid4u") || low.contains("shaahidd4u") || body.contains("شاهد فور يو")
    }

    fun isCanaryUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val low = url.lowercase()
        return low.contains("/media/watch/") ||
            low.contains("/media/api/") ||
            low.contains("/media/page/") ||
            low.contains("honeytoken") ||
            low.contains("scraper-content")
    }

    fun isSafePosterUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val low = url.lowercase()
        if (low.contains("/m/") || low.contains("/media/") || low.contains("avatar.png") || low.contains("servers-images")) {
            return false
        }
        return low.startsWith("http://") || low.startsWith("https://")
    }

    fun isHiddenOrTrapElement(el: Element?): Boolean {
        if (el == null) return true
        var cur: Element? = el
        while (cur != null) {
            val tag = cur.tagName().lowercase()
            if (tag == "template") return true
            if (cur.hasAttr("hidden") || cur.hasAttr("data-honeytoken") || cur.hasAttr("data-trap")) return true
            if (cur.attr("aria-hidden").equals("true", ignoreCase = true)) return true
            if (cur.attr("tabindex").trim() == "-1") return true
            val id = cur.id().lowercase()
            if (id == "download-primary-server" || id.contains("canary") || id.contains("honeypot") || id.contains("trap")) {
                return true
            }
            val style = cur.attr("style").replace(" ", "").lowercase()
            if (style.contains("display:none") || style.contains("visibility:hidden") || style.contains("opacity:0")) {
                return true
            }
            val cls = cur.className().lowercase()
            if (cls.contains("d-none") || cls.contains("visually-hidden")) {
                return true
            }
            cur = cur.parent()
        }
        return false
    }

    fun sanitizeHtmlToDoc(rawHtml: String, pageUrl: String): Pair<Document, Set<String>> {
        val doc = Jsoup.parse(rawHtml, pageUrl)
        val trapTokens = LinkedHashSet<String>()

        // 1. Detect inline JS DOM removals such as `document.getElementById('download-primary-server')?.remove()`
        val removedIds = HashSet<String>()
        val getByIdRemoveRegex = Regex("""getElementById\(\s*['"]([^'"]+)['"]\s*\)\s*\??\s*\.remove\s*\(""")
        val queryIdRemoveRegex = Regex("""querySelector\(\s*['"]#([^'"]+)['"]\s*\)\s*\??\s*\.remove\s*\(""")
        doc.select("script").forEach { sc ->
            val js = sc.data()
            if (js.contains(".remove(")) {
                getByIdRemoveRegex.findAll(js).forEach { removedIds.add(it.groupValues[1]) }
                queryIdRemoveRegex.findAll(js).forEach { removedIds.add(it.groupValues[1]) }
            }
        }
        for (rid in removedIds) {
            doc.getElementById(rid)?.let { el ->
                el.attr("data-token").trim().takeIf { it.isNotEmpty() }?.let { trapTokens.add(it) }
                el.remove()
            }
        }

        // 2. Collect honeytokens from any hidden/trap element BEFORE removing them
        doc.select("[data-token]").forEach { el ->
            val tok = el.attr("data-token").trim()
            if (tok.isNotEmpty() && isHiddenOrTrapElement(el)) {
                trapTokens.add(tok)
            }
        }

        // 3. Remove all scraper trap templates & hidden canary containers
        doc.select(
            "template, [data-honeytoken], [data-trap], #download-primary-server, .media-links[hidden]"
        ).forEach { el ->
            el.select("[data-token]").forEach { child ->
                child.attr("data-token").trim().takeIf { it.isNotEmpty() }?.let { trapTokens.add(it) }
            }
            el.remove()
        }

        // 4. Neutralize `/m/` canary attributes on `.poster` and cards
        doc.select("[data-poster-source], [data-details-source], [data-href]").forEach { el ->
            if (el.attr("data-poster-source").contains("/m/")) el.removeAttr("data-poster-source")
            if (el.attr("data-details-source").contains("/m/")) el.removeAttr("data-details-source")
            if (el.attr("data-href").contains("/m/")) el.removeAttr("data-href")
        }

        return doc to trapTokens
    }

    /**
     * Generates a realistic `SHAHID4U_HUMAN_SIGNAL_V1` snapshot matching `window.__s4HumanSnapshot()`.
     */
    fun buildHumanSignal(serverIndex: Int): JSONObject {
        val baseElapsed = 420 + (serverIndex * Random.nextInt(380, 740)) + Random.nextInt(25, 140)
        val clicks = 2 + serverIndex
        return JSONObject().apply {
            put("version", 1)
            put("elapsed_ms", baseElapsed)
            put("visible_ms", (baseElapsed - Random.nextInt(10, 45)).coerceAtLeast(100))
            put("pointer", 4 + clicks)
            put("click", clicks)
            put("touch", Random.nextInt(1, 5))
            put("scroll", Random.nextInt(1, 4))
            put("key", 0)
        }
    }

    // ── Polite Non-Destructive Pacing ────────────────────────────────────────

    private suspend fun paceSameSiteCall(minGapMs: Long = 160L) {
        val waitMs = hostPaceLock.withLock {
            val now = System.currentTimeMillis()
            val prev = lastHostCallAt.get()
            val earliest = prev + minGapMs + Random.nextLong(20L, 95L)
            val target = maxOf(now, earliest)
            lastHostCallAt.set(target)
            (target - now).coerceAtLeast(0L)
        }
        if (waitMs > 0L) delay(waitMs)
    }

    private fun browserHeaders(referer: String?, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        map["User-Agent"] = userAgent
        map["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        map["Accept-Language"] = "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7"
        map["Upgrade-Insecure-Requests"] = "1"
        map["Sec-Fetch-Dest"] = "document"
        map["Sec-Fetch-Mode"] = "navigate"
        map["Sec-Fetch-Site"] = if (referer.isNullOrBlank()) "none" else "same-origin"
        if (!referer.isNullOrBlank()) {
            map["Referer"] = encodeUrl(referer)
        }
        map.putAll(extra)
        return map
    }

    // ── Public High-Level API ────────────────────────────────────────────────

    /**
     * Fetches a `shaahidd4u.net` HTML page, automatically overcoming Cloudflare, DNS blocks,
     * TLS-bound challenges, and ASN blocks, then returns a sanitized Jsoup [Document] + sticky [TransportHandle].
     */
    suspend fun getPage(
        url: String,
        referer: String? = "$mainUrl/"
    ): SanitizedPage? = withContext(Dispatchers.IO) {
        ensurePrefsLoaded()
        val encodedUrl = encodeUrl(url)
        if (isCanaryUrl(encodedUrl)) return@withContext null

        // 0. If previous calls proved Cloudflare requires TLS-bound WebView on this device/network, try WebView first
        if (preferWebViewTransport) {
            findActiveActivity()?.let { act ->
                webViewFetchHtml(act, encodedUrl, referer)?.let { (finalUrl, html) ->
                    if (isValidShahid4uHtml(200, html)) {
                        updateDomainIfRedirected(finalUrl)
                        val (doc, traps) = sanitizeHtmlToDoc(html, finalUrl)
                        return@withContext SanitizedPage(html, doc, finalUrl, traps, TransportHandle(TransportKind.WEBVIEW))
                    }
                }
            }
        }

        // 1. Fast Path: Direct session-sticky OkHttpClient
        executeOkHttpGet(directClient, encodedUrl, referer)?.let { (code, finalUrl, body) ->
            if (isValidShahid4uHtml(code, body)) {
                updateDomainIfRedirected(finalUrl)
                val (doc, traps) = sanitizeHtmlToDoc(body, finalUrl)
                return@withContext SanitizedPage(body, doc, finalUrl, traps, TransportHandle(TransportKind.DIRECT))
            }
        }

        // 2. Previously verified sticky proxy (for ASN-blocked VPN / datacenter exits)
        for (proxySpec in workingProxies.toList()) {
            if (deadProxies.contains(proxySpec)) continue
            val ps = getOrCreateProxySession(proxySpec) ?: continue
            val res = executeOkHttpGet(ps.client, encodedUrl, referer, pace = false)
            if (res != null && isValidShahid4uHtml(res.first, res.third)) {
                proxyFailCounts.remove(proxySpec)
                val (doc, traps) = sanitizeHtmlToDoc(res.third, res.second)
                return@withContext SanitizedPage(
                    res.third,
                    doc,
                    res.second,
                    traps,
                    TransportHandle(TransportKind.PROXY, proxySpec)
                )
            }
            recordProxyFailure(proxySpec)
        }

        // 3. Silent Android WebView Solver + TLS-Bound WebView Transport Fallback
        cfMutex.withLock {
            // Re-check direct in case another coroutine just solved Cloudflare
            executeOkHttpGet(directClient, encodedUrl, referer, pace = false)?.let { (code, finalUrl, body) ->
                if (isValidShahid4uHtml(code, body)) {
                    updateDomainIfRedirected(finalUrl)
                    val (doc, traps) = sanitizeHtmlToDoc(body, finalUrl)
                    return@withContext SanitizedPage(body, doc, finalUrl, traps, TransportHandle(TransportKind.DIRECT))
                }
            }

            val activity = findActiveActivity()
            if (activity != null) {
                val solved = solveCloudflareAndCaptureHtml(activity, encodedUrl)
                if (solved != null) {
                    if (!solved.userAgent.isNullOrBlank()) userAgent = solved.userAgent
                    if (!solved.cookies.isNullOrBlank()) {
                        directCookies.importCookieHeader(solved.finalUrl, solved.cookies)
                        savePrefs()
                    }
                    // Test whether OkHttp can reuse cf_clearance directly (non-TLS-bound) or if WebView TLS is required
                    val directRetry = executeOkHttpGet(directClient, encodedUrl, referer, pace = false)
                    if (directRetry != null && isValidShahid4uHtml(directRetry.first, directRetry.third)) {
                        updateDomainIfRedirected(directRetry.second)
                        val (doc, traps) = sanitizeHtmlToDoc(directRetry.third, directRetry.second)
                        return@withContext SanitizedPage(
                            directRetry.third,
                            doc,
                            directRetry.second,
                            traps,
                            TransportHandle(TransportKind.DIRECT)
                        )
                    }
                    // OkHttp TLS fingerprint was rejected despite valid WebView clearance -> use WebView transport!
                    if (isValidShahid4uHtml(200, solved.html)) {
                        preferWebViewTransport = true
                        updateDomainIfRedirected(solved.finalUrl)
                        val (doc, traps) = sanitizeHtmlToDoc(solved.html, solved.finalUrl)
                        return@withContext SanitizedPage(
                            solved.html,
                            doc,
                            solved.finalUrl,
                            traps,
                            TransportHandle(TransportKind.WEBVIEW)
                        )
                    }
                }
            }

            // 3b. CloudStream CloudflareKiller fallback
            executeOkHttpGet(cfKillerClient, encodedUrl, referer, pace = false)?.let { (code, finalUrl, body) ->
                if (isValidShahid4uHtml(code, body)) {
                    updateDomainIfRedirected(finalUrl)
                    val (doc, traps) = sanitizeHtmlToDoc(body, finalUrl)
                    return@withContext SanitizedPage(body, doc, finalUrl, traps, TransportHandle(TransportKind.CF_KILLER))
                }
            }
        }

        // 4. ASN-Blocked Exit Fallback: Harvest & verify a session-sticky proxy
        proxyMutex.withLock {
            for (proxySpec in workingProxies.toList()) {
                if (deadProxies.contains(proxySpec)) continue
                val ps = getOrCreateProxySession(proxySpec) ?: continue
                val res = executeOkHttpGet(ps.client, encodedUrl, referer, pace = false)
                if (res != null && isValidShahid4uHtml(res.first, res.third)) {
                    proxyFailCounts.remove(proxySpec)
                    val (doc, traps) = sanitizeHtmlToDoc(res.third, res.second)
                    return@withContext SanitizedPage(
                        res.third,
                        doc,
                        res.second,
                        traps,
                        TransportHandle(TransportKind.PROXY, proxySpec)
                    )
                }
            }
            discoverStickyProxyAndFetch(encodedUrl, referer)
        }
    }

    /**
     * Executes a session-sticky JSON POST (specifically `POST /watch/resolve`) on the exact same
     * [TransportHandle] (cookies + exit IP + TLS stack) that fetched `/watch/<slug>`.
     */
    suspend fun postJsonSticky(
        url: String,
        jsonBody: String,
        referer: String,
        csrfToken: String,
        transport: TransportHandle
    ): PostResult? = withContext(Dispatchers.IO) {
        val encodedUrl = encodeUrl(url)
        if (isCanaryUrl(encodedUrl)) return@withContext null
        paceSameSiteCall(220L)

        val headers = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "X-CSRF-TOKEN" to csrfToken,
            "X-Requested-With" to "XMLHttpRequest",
            "X-FP-Event-ID" to "",
            "Origin" to mainUrl,
            "Referer" to encodeUrl(referer),
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin"
        )

        when (transport.kind) {
            TransportKind.WEBVIEW -> {
                val act = findActiveActivity()
                if (act != null) {
                    webViewPostJson(act, encodedUrl, jsonBody, referer, csrfToken)?.let { return@withContext it }
                }
                executeOkHttpPostJson(directClient, encodedUrl, jsonBody, headers)
            }
            TransportKind.PROXY -> {
                val ps = transport.proxySpec?.let { getOrCreateProxySession(it) }
                if (ps != null) {
                    executeOkHttpPostJson(ps.client, encodedUrl, jsonBody, headers)
                } else {
                    executeOkHttpPostJson(directClient, encodedUrl, jsonBody, headers)
                }
            }
            TransportKind.CF_KILLER -> executeOkHttpPostJson(cfKillerClient, encodedUrl, jsonBody, headers)
            TransportKind.DIRECT -> executeOkHttpPostJson(directClient, encodedUrl, jsonBody, headers)
        }
    }

    /**
     * Resolves a single-hop `/m/<hash>` redirect on the exact same [TransportHandle] (same cookies & IP)
     * that loaded `/watch/<slug>` or `/download/<slug>`, returning the external video host URL.
     */
    suspend fun resolveRedirectSticky(
        url: String,
        referer: String,
        transport: TransportHandle
    ): String? = withContext(Dispatchers.IO) {
        val absUrl = resolveUrl(url) ?: return@withContext null
        if (isCanaryUrl(absUrl)) return@withContext null

        val host = absUrl.toHttpUrlOrNull()?.host?.lowercase().orEmpty()
        val isInternalM = (host.contains("shahid") || host.contains("shaahid")) && absUrl.contains("/m/")
        if (!isInternalM) return@withContext absUrl

        paceSameSiteCall(150L)

        val resolved = when (transport.kind) {
            TransportKind.WEBVIEW -> {
                // Try direct no-redirect first (often /m/ doesn't enforce TLS binding once cookies exist),
                // then fall back to WebView shouldOverrideUrlLoading redirect catcher!
                executeNoRedirectOnce(directNoRedirectClient, absUrl, referer)
                    ?: findActiveActivity()?.let { act -> webViewCatchExternalRedirect(act, absUrl, referer) }
            }
            TransportKind.PROXY -> {
                val ps = transport.proxySpec?.let { getOrCreateProxySession(it) }
                if (ps != null) {
                    executeNoRedirectOnce(ps.noRedirectClient, absUrl, referer)
                } else {
                    executeNoRedirectOnce(directNoRedirectClient, absUrl, referer)
                }
            }
            TransportKind.CF_KILLER -> executeNoRedirectOnce(cfKillerNoRedirectClient, absUrl, referer)
            TransportKind.DIRECT -> executeNoRedirectOnce(directNoRedirectClient, absUrl, referer)
        }

        val clean = resolved?.trim()?.takeIf { it.isNotEmpty() && !isCanaryUrl(it) } ?: return@withContext null
        val targetHost = clean.toHttpUrlOrNull()?.host?.lowercase().orEmpty()
        if (targetHost.contains("shahid") || targetHost.contains("shaahid")) {
            return@withContext null
        }
        clean
    }

    // ── Low-Level OkHttp Execution ───────────────────────────────────────────

    private suspend fun executeOkHttpGet(
        client: OkHttpClient,
        url: String,
        referer: String?,
        pace: Boolean = true
    ): Triple<Int, String, String>? {
        if (pace) paceSameSiteCall(150L)
        return try {
            val reqBuilder = Request.Builder().url(url)
            browserHeaders(referer).forEach { (k, v) -> reqBuilder.header(k, v) }
            client.newCall(reqBuilder.build()).execute().use { res ->
                val body = res.body?.string().orEmpty()
                Triple(res.code, res.request.url.toString(), body)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Log.d(TAG, "GET error on $url: ${e.message}")
            null
        }
    }

    private fun executeOkHttpPostJson(
        client: OkHttpClient,
        url: String,
        jsonBody: String,
        headers: Map<String, String>
    ): PostResult? {
        return try {
            val reqBuilder = Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            reqBuilder.header("User-Agent", userAgent)
            reqBuilder.header("Accept-Language", "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7")
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            client.newCall(reqBuilder.build()).execute().use { res ->
                PostResult(res.code, res.body?.string().orEmpty())
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Log.d(TAG, "POST error on $url: ${e.message}")
            null
        }
    }

    private fun executeNoRedirectOnce(
        noRedirectClient: OkHttpClient,
        url: String,
        referer: String
    ): String? {
        return try {
            val reqBuilder = Request.Builder().url(url)
            browserHeaders(referer).forEach { (k, v) -> reqBuilder.header(k, v) }
            noRedirectClient.newCall(reqBuilder.build()).execute().use { res ->
                val loc = res.header("Location") ?: res.header("location")
                if (!loc.isNullOrBlank()) {
                    resolveUrl(loc, url)
                } else null
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
        }
    }

    // ── Silent Android WebView Solver + TLS-Bound Transport ──────────────────

    private data class WebViewSolvedResult(
        val finalUrl: String,
        val html: String,
        val cookies: String?,
        val userAgent: String?
    )

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun findActiveActivity(): Activity? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            val activitiesField = activityThreadClass.getDeclaredField("mActivities").apply { isAccessible = true }
            val activities = activitiesField.get(activityThread) as? Map<*, *> ?: return null
            for (record in activities.values) {
                if (record == null) continue
                val recordClass = record.javaClass
                val pausedField = recordClass.getDeclaredField("paused").apply { isAccessible = true }
                if (!pausedField.getBoolean(record)) {
                    val activityField = recordClass.getDeclaredField("activity").apply { isAccessible = true }
                    val act = activityField.get(record) as? Activity
                    if (act != null && !act.isFinishing && !act.isDestroyed) return act
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureStealthWebView(wv: WebView, cookieMgr: CookieManager) {
        cookieMgr.setAcceptCookie(true)
        cookieMgr.setAcceptThirdPartyCookies(wv, true)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = false
            blockNetworkImage = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = userAgentString.replace("; wv)", ")").replace("Version/4.0 ", "")
        }
    }

    private fun attachHiddenWebView(activity: Activity, wv: WebView) {
        runCatching {
            val root = activity.window?.decorView?.findViewById<ViewGroup>(android.R.id.content)
            wv.alpha = 0f
            wv.visibility = View.INVISIBLE
            root?.addView(wv, FrameLayout.LayoutParams(1, 1))
        }
    }

    private fun destroyHiddenWebView(wv: WebView?) {
        if (wv == null) return
        runCatching {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.clearHistory()
            wv.removeAllViews()
            wv.destroy()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun solveCloudflareAndCaptureHtml(
        activity: Activity,
        targetUrl: String,
        timeoutMs: Long = 22_000L
    ): WebViewSolvedResult? {
        val deferred = CompletableDeferred<WebViewSolvedResult?>()
        val mainHandler = Handler(Looper.getMainLooper())
        val actRef = WeakReference(activity)
        var webViewRef: WebView? = null

        val pollRunnable = object : Runnable {
            override fun run() {
                val wv = webViewRef ?: return
                if (deferred.isCompleted) return
                runCatching {
                    val currentUrl = wv.url ?: targetUrl
                    val cookieMgr = CookieManager.getInstance()
                    val cookies = cookieMgr.getCookie(currentUrl) ?: cookieMgr.getCookie(mainUrl)
                    val uaNow = wv.settings.userAgentString
                    wv.evaluateJavascript(
                        "(function(){return JSON.stringify({t:document.title||'',h:document.documentElement?document.documentElement.outerHTML:''});})()"
                    ) { raw ->
                        if (deferred.isCompleted) return@evaluateJavascript
                        val parsed = decodeJsJsonString(raw)
                        if (parsed != null) {
                            val obj = runCatching { JSONObject(parsed) }.getOrNull()
                            val html = obj?.optString("h").orEmpty()
                            if (isValidShahid4uHtml(200, html)) {
                                cookieMgr.flush()
                                deferred.complete(WebViewSolvedResult(currentUrl, html, cookies, uaNow))
                                return@evaluateJavascript
                            }
                        }
                        if (!deferred.isCompleted) {
                            mainHandler.postDelayed(this, 900L)
                        }
                    }
                }
            }
        }

        mainHandler.post {
            val act = actRef.get()
            if (act == null || act.isFinishing || act.isDestroyed) {
                deferred.complete(null)
                return@post
            }
            try {
                val cookieMgr = CookieManager.getInstance()
                val wv = WebView(act)
                webViewRef = wv
                configureStealthWebView(wv, cookieMgr)
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        mainHandler.postDelayed(pollRunnable, 400L)
                    }
                }
                attachHiddenWebView(act, wv)
                wv.loadUrl(targetUrl, mapOf("Referer" to "$mainUrl/"))
                mainHandler.postDelayed(pollRunnable, 1500L)
            } catch (t: Throwable) {
                deferred.complete(null)
            }
        }

        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            mainHandler.removeCallbacksAndMessages(null)
            mainHandler.post { destroyHiddenWebView(webViewRef); webViewRef = null }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun webViewFetchHtml(
        activity: Activity,
        targetUrl: String,
        referer: String?,
        timeoutMs: Long = 18_000L
    ): Pair<String, String>? {
        val solved = solveCloudflareAndCaptureHtml(activity, targetUrl, timeoutMs) ?: return null
        if (!solved.cookies.isNullOrBlank()) {
            directCookies.importCookieHeader(solved.finalUrl, solved.cookies)
        }
        return solved.finalUrl to solved.html
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun webViewPostJson(
        activity: Activity,
        postUrl: String,
        jsonBody: String,
        refererUrl: String,
        csrfToken: String,
        timeoutMs: Long = 16_000L
    ): PostResult? {
        val deferred = CompletableDeferred<PostResult?>()
        val mainHandler = Handler(Looper.getMainLooper())
        val actRef = WeakReference(activity)
        var webViewRef: WebView? = null

        mainHandler.post {
            val act = actRef.get()
            if (act == null || act.isFinishing || act.isDestroyed) {
                deferred.complete(null)
                return@post
            }
            try {
                val cookieMgr = CookieManager.getInstance()
                val wv = WebView(act)
                webViewRef = wv
                configureStealthWebView(wv, cookieMgr)
                var dispatched = false
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (dispatched || deferred.isCompleted || view == null) return
                        dispatched = true
                        val jsPayload = JSONObject.quote(jsonBody)
                        val jsUrl = JSONObject.quote(postUrl)
                        val jsCsrf = JSONObject.quote(csrfToken)
                        val script = """
                            (function() {
                                fetch($jsUrl, {
                                    method: 'POST',
                                    credentials: 'include',
                                    headers: {
                                        'Accept': 'application/json',
                                        'Content-Type': 'application/json',
                                        'X-CSRF-TOKEN': $jsCsrf,
                                        'X-Requested-With': 'XMLHttpRequest',
                                        'X-FP-Event-ID': ''
                                    },
                                    body: $jsPayload
                                }).then(function(r) {
                                    return r.text().then(function(t) {
                                        window.__s4PostDone = JSON.stringify({c: r.status, b: t});
                                    });
                                }).catch(function(e) {
                                    window.__s4PostDone = JSON.stringify({c: 0, b: ''});
                                });
                            })();
                        """.trimIndent()
                        view.evaluateJavascript(script, null)
                        val poll = object : Runnable {
                            override fun run() {
                                if (deferred.isCompleted) return
                                view.evaluateJavascript("window.__s4PostDone || ''") { raw ->
                                    val dec = decodeJsJsonString(raw)
                                    if (!dec.isNullOrBlank()) {
                                        val o = runCatching { JSONObject(dec) }.getOrNull()
                                        if (o != null) {
                                            deferred.complete(PostResult(o.optInt("c", 0), o.optString("b", "")))
                                            return@evaluateJavascript
                                        }
                                    }
                                    if (!deferred.isCompleted) mainHandler.postDelayed(this, 200L)
                                }
                            }
                        }
                        mainHandler.postDelayed(poll, 220L)
                    }
                }
                attachHiddenWebView(act, wv)
                // Load lightweight origin context (no external assets) anchored at refererUrl so same-origin credentials & TLS match!
                wv.loadDataWithBaseURL(refererUrl, "<html><body></body></html>", "text/html", "UTF-8", refererUrl)
            } catch (_: Throwable) {
                deferred.complete(null)
            }
        }

        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            mainHandler.removeCallbacksAndMessages(null)
            mainHandler.post { destroyHiddenWebView(webViewRef); webViewRef = null }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun webViewCatchExternalRedirect(
        activity: Activity,
        mUrl: String,
        refererUrl: String,
        timeoutMs: Long = 10_000L
    ): String? {
        val deferred = CompletableDeferred<String?>()
        val mainHandler = Handler(Looper.getMainLooper())
        val actRef = WeakReference(activity)
        var webViewRef: WebView? = null

        mainHandler.post {
            val act = actRef.get()
            if (act == null || act.isFinishing || act.isDestroyed) {
                deferred.complete(null)
                return@post
            }
            try {
                val cookieMgr = CookieManager.getInstance()
                val wv = WebView(act)
                webViewRef = wv
                configureStealthWebView(wv, cookieMgr)
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val nextUrl = request?.url?.toString().orEmpty()
                        val nextHost = request?.url?.host?.lowercase().orEmpty()
                        if (nextUrl.startsWith("http") &&
                            nextHost.isNotEmpty() &&
                            !nextHost.contains("shahid") &&
                            !nextHost.contains("shaahid") &&
                            !nextHost.contains("cloudflare")
                        ) {
                            view?.stopLoading()
                            if (!deferred.isCompleted) deferred.complete(nextUrl)
                            return true
                        }
                        return false
                    }
                }
                attachHiddenWebView(act, wv)
                wv.loadUrl(mUrl, mapOf("Referer" to refererUrl))
            } catch (_: Throwable) {
                deferred.complete(null)
            }
        }

        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            mainHandler.removeCallbacksAndMessages(null)
            mainHandler.post { destroyHiddenWebView(webViewRef); webViewRef = null }
        }
    }

    private fun decodeJsJsonString(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching {
            JSONArray("[$raw]").optString(0)
        }.getOrNull()
    }

    // ── Session-Sticky Proxy Harvester (ASN-Blocked VPN / Datacenter Fallback) ─

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

    private fun getOrCreateProxySession(proxySpec: String): ProxySessionClient? {
        proxyClients[proxySpec]?.let { return it }
        val javaProxy = parseProxySpec(proxySpec) ?: return null
        val created = ProxySessionClient(proxySpec, javaProxy)
        proxyClients[proxySpec] = created
        return created
    }

    private fun recordProxyFailure(proxySpec: String) {
        val strikes = (proxyFailCounts[proxySpec] ?: 0) + 1
        proxyFailCounts[proxySpec] = strikes
        if (strikes >= 3) {
            deadProxies.add(proxySpec)
            workingProxies.remove(proxySpec)
            proxyClients.remove(proxySpec)
        } else if (workingProxies.size > 1 && workingProxies.remove(proxySpec)) {
            workingProxies.add(proxySpec)
        }
    }

    private suspend fun discoverStickyProxyAndFetch(
        targetUrl: String,
        referer: String?
    ): SanitizedPage? = coroutineScope {
        if (deadProxies.size > 500) deadProxies.clear()

        val feeds = PROXY_FEEDS.map { (proto, feedUrl) ->
            async(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder().url(feedUrl).header("User-Agent", userAgent).build()
                    dohBootstrapClient.newCall(req).execute().use { res ->
                        if (!res.isSuccessful) emptyList()
                        else res.body?.string().orEmpty()
                            .lineSequence()
                            .map { it.trim() }
                            .filter { it.contains(":") && !it.startsWith("#") }
                            .take(120)
                            .map { "$proto://$it" }
                            .filterNot { deadProxies.contains(it) }
                            .toList()
                    }
                }.getOrDefault(emptyList())
            }
        }.awaitAll()

        val candidates = ArrayList<String>()
        val maxLen = feeds.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxLen) {
            for (list in feeds) {
                if (i < list.size) candidates.add(list[i])
            }
        }
        val pool = candidates.distinct().take(240)
        if (pool.isEmpty()) return@coroutineScope null

        val sem = Semaphore(24)
        for (batch in pool.chunked(48)) {
            val winner = CompletableDeferred<SanitizedPage?>()
            val jobs = batch.map { spec ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        if (winner.isCompleted) return@withPermit
                        val ps = getOrCreateProxySession(spec) ?: return@withPermit
                        val res = executeOkHttpGet(ps.client, targetUrl, referer, pace = false)
                        if (res != null && isValidShahid4uHtml(res.first, res.third)) {
                            if (!workingProxies.contains(spec)) workingProxies.add(0, spec)
                            val (doc, traps) = sanitizeHtmlToDoc(res.third, res.second)
                            winner.complete(
                                SanitizedPage(
                                    res.third,
                                    doc,
                                    res.second,
                                    traps,
                                    TransportHandle(TransportKind.PROXY, spec)
                                )
                            )
                        } else {
                            deadProxies.add(spec)
                            proxyClients.remove(spec)
                        }
                    }
                }
            }
            val waiter = async {
                jobs.awaitAll()
                if (!winner.isCompleted) winner.complete(null)
            }
            val page = winner.await()
            if (page != null) {
                jobs.forEach { it.cancel() }
                waiter.cancel()
                return@coroutineScope page
            }
        }
        null
    }
}
