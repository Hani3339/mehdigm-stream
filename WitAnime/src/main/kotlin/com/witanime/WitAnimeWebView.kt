package com.witanime

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Drives the whole WitAnime play flow inside a real (hidden) WebView so the
 * requests carry a genuine browser TLS/HTTP2/JA3 fingerprint. Cloudflare rejects
 * the OkHttp/MainAPI client for `/sources` with a 404, but a WebView passes.
 *
 * The page's own JS is already capable of:
 *   1. POST /watch/<slug>/<ep>/sources          -> { players, downloads }
 *   2. POST /watch/stream-source/<token>        -> { sandbox, referrerPolicy }
 *   3. GET  /watch/stream-gate/<token>          -> 302 to the real embed URL
 *
 * We inject a script that performs exactly these steps and posts the resulting
 * JSON (sources + a token->embedUrl map) back over a JS bridge.
 */
object WitAnimeWebView {

    private const val BRIDGE = "WitAnimeBridge"

    data class Result(val json: String?, val error: String?)

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetch(
        activity: Activity,
        watchUrl: String,
        cookies: Map<String, String>,
        userAgent: String,
        timeoutMs: Long = 120_000L
    ): Result = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            var finished = false
            val handler = Handler(Looper.getMainLooper())

            var webView: WebView? = null
            val dialog = Dialog(activity)

            fun cleanup() {
                runCatching { webView?.stopLoading() }
                runCatching { webView?.destroy() }
                runCatching { dialog.dismiss() }
            }

            fun deliver(result: Result) {
                if (finished) return
                finished = true
                runCatching { if (cont.isActive) cont.resume(result) }
                cleanup()
            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)

            val view = WebView(activity)
            webView = view
            view.layoutParams = ViewGroup.LayoutParams(1, 1)
            view.visibility = View.INVISIBLE

            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setDimAmount(0f)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                )
                attributes = attributes?.apply {
                    width = 1
                    height = 1
                    x = -10000
                    y = -10000
                    gravity = Gravity.START or Gravity.TOP
                }
            }
            try {
                dialog.setContentView(view, ViewGroup.LayoutParams(1, 1))
                dialog.show()
            } catch (_: Exception) {
                try {
                    val decor = activity.window?.decorView as? ViewGroup
                    decor?.addView(view, ViewGroup.LayoutParams(1, 1))
                } catch (_: Exception) {
                }
            }

            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowContentAccess = true
                allowFileAccess = true
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(false)
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = userAgent
                blockNetworkImage = true
            }

            cookieManager.setAcceptThirdPartyCookies(view, true)
            runCatching {
                for ((name, value) in cookies) {
                    cookieManager.setCookie(watchUrl, "$name=$value")
                }
                cookieManager.flush()
            }

            view.addJavascriptInterface(object {
                @JavascriptInterface
                fun onResult(json: String) = handler.post { deliver(Result(json, null)) }

                @JavascriptInterface
                fun onError(message: String) = handler.post { deliver(Result(null, message)) }

                @JavascriptInterface
                fun onProgress(message: String) = handler.post {
                    println("[WitAnime][WebView] $message")
                }
            }, BRIDGE)

            view.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                    println("[WitAnime][WebView][console] ${msg?.message()}")
                    return true
                }
            }

            val injectedJs = """
                (function() {
                    // Only the watch page exposes the player + /sources endpoint.
                    // The warm-up navigation to the homepage must be ignored.
                    if (!/\/watch\//.test(location.pathname)) return;
                    if (window.__witanimeRunning) return;
                    window.__witanimeRunning = true;
                    var B = $BRIDGE;
                    function log(m){ try { B.onProgress(String(m)); } catch(e){} }
                    function report(o){ B.onResult(JSON.stringify(o)); }
                    function fail(m){ B.onError(String(m)); }

                    async function run() {
                        try {
                            var csrfEl = document.querySelector('meta[name=csrf-token]');
                            var csrf = csrfEl ? csrfEl.content : '';
                            if (!csrf) { fail('no csrf'); return; }
                            var path = location.pathname.replace(/\/$/, '') + '/sources';
                            var attempt = 0;
                            var r, t;
                            while (true) {
                                r = await fetch(path, {
                                    method: 'POST',
                                    headers: { 'Accept': 'application/json', 'X-CSRF-TOKEN': csrf, 'X-Requested-With': 'mark.via.gp' }
                                });
                                t = await r.text();
                                log('sources -> ' + r.status + (r.status === 429 ? ' (rate-limited, retrying)' : ''));
                                if (r.status !== 429) break;
                                attempt++;
                                if (attempt >= 4) break;
                                await new Promise(function(res){ setTimeout(res, 2500 * attempt); });
                                var c2 = document.querySelector('meta[name=csrf-token]');
                                if (c2) csrf = c2.content;
                            }
                            if (r.status < 200 || r.status >= 300) { fail('HTTP ' + r.status + ' ' + t.slice(0, 200)); return; }
                            var data = JSON.parse(t);

                            // Report the sources immediately. Gates are resolved by
                            // the Kotlin side (reading the 302 Location) so we do not
                            // spend extra requests here, which only worsens the site's
                            // aggressive rate limiting.
                            data.gates = {};
                            report(data);
                        } catch (e) { fail(e && e.message ? e.message : 'unknown'); }
                    }

                    var tries = 0;
                    var announced = false;
                    var reloadKey = '__witanimeReloads::' + location.pathname;
                    var reloads = 0;
                    try { reloads = parseInt(sessionStorage.getItem(reloadKey) || '0') || 0; } catch (e) {}
                    var reloadPending = false;
                    var iv = setInterval(function() {
                        tries++;
                        if (!announced) {
                            announced = true;
                            log('title="' + document.title + '" url=' + location.href);
                        }
                        var title = document.title || '';
                        // The site answers bursts of requests with a 429 page
                        // ("طلبات كثيرة جدًا" = too many requests). It has no CSRF
                        // meta and no player, so reload after a backoff instead of
                        // giving up. The count lives in sessionStorage so it
                        // survives the reload (a page-local counter would reset).
                        var rateLimited = /كثيرة|too many|429/i.test(title) ||
                            (!document.querySelector('meta[name=csrf-token]') && /WitAnime/.test(title) && tries > 8);
                        if (rateLimited && !reloadPending && reloads < 4) {
                            reloadPending = true;
                            reloads++;
                            try { sessionStorage.setItem(reloadKey, String(reloads)); } catch (e) {}
                            var wait = 4000 * reloads;
                            log('rate-limited (title="' + title + '"), reload #' + reloads + ' in ' + wait + 'ms');
                            setTimeout(function() {
                                reloadPending = false;
                                location.reload();
                            }, wait);
                            return;
                        }
                        if (document.querySelector('meta[name=csrf-token]')) {
                            try { sessionStorage.removeItem(reloadKey); } catch (e) {}
                        }
                        var csrf = document.querySelector('meta[name=csrf-token]');
                        if (csrf && tries > 4) {
                            clearInterval(iv);
                            run();
                        }
                        if (tries === 20 || tries === 40 || tries === 60) {
                            log('waiting t=' + tries + ' title="' + title + '" csrf=' + (!!csrf));
                        }
                        if (tries > 100) { clearInterval(iv); fail('csrf not found: title=' + title); }
                    }, 500);
                })();
            """.trimIndent()

            handler.postDelayed({
                if (!finished) deliver(Result(null, "timeout"))
            }, timeoutMs)

            cont.invokeOnCancellation { handler.post { deliver(Result(null, "cancelled")) } }

            // Warm the session on the homepage first (like a real user), then go
            // to the watch page. Hitting the site's bot/rate limiter cold on the
            // watch URL is what produced the "طلبات كثيرة جدًا" (429) page.
            val origin = runCatching {
                val u = java.net.URI(watchUrl)
                "${u.scheme}://${u.host}/"
            }.getOrNull()
            if (!origin.isNullOrBlank()) {
                var navigated = false
                view.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(v, url, favicon)
                        println("[WitAnime][WebView] onPageStarted $url")
                        if ((url ?: "").contains("/watch/")) {
                            runCatching { v?.evaluateJavascript(injectedJs, null) }
                        }
                    }

                    override fun onPageFinished(v: WebView?, url: String?) {
                        super.onPageFinished(v, url)
                        println("[WitAnime][WebView] onPageFinished $url")
                        if (!navigated) {
                            navigated = true
                            handler.postDelayed({ runCatching { v?.loadUrl(watchUrl) } }, 300)
                        } else {
                            runCatching { v?.evaluateJavascript(injectedJs, null) }
                        }
                    }

                    override fun onReceivedError(
                        v: WebView?,
                        request: WebResourceRequest?,
                        error: android.webkit.WebResourceError?
                    ) {
                        super.onReceivedError(v, request, error)
                        if (request?.isForMainFrame == true) {
                            println("[WitAnime][WebView] onReceivedError ${error?.description} ${request.url}")
                        }
                    }
                }
                runCatching { view.loadUrl(origin) }
            } else {
                runCatching { view.loadUrl(watchUrl) }
            }
        }
    }

    /**
     * Loads a same-origin stream-gate URL in a throwaway WebView and returns the
     * URL it redirects to (the real embed). Running the navigation in the browser
     * bypasses any Cloudflare bot check that rejects the OkHttp client.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolveGate(
        activity: Activity,
        gateUrl: String,
        cookies: Map<String, String>,
        userAgent: String,
        timeoutMs: Long = 20_000L
    ): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            var finished = false
            val handler = Handler(Looper.getMainLooper())
            var webView: WebView? = null
            val dialog = Dialog(activity)
            var captured: String? = null

            fun cleanup() {
                runCatching { webView?.stopLoading() }
                runCatching { webView?.destroy() }
                runCatching { dialog.dismiss() }
            }

            fun deliver(result: String?) {
                if (finished) return
                finished = true
                runCatching { if (cont.isActive) cont.resume(result) }
                cleanup()
            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)

            val view = WebView(activity)
            webView = view
            view.visibility = View.INVISIBLE

            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setDimAmount(0f)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                )
                attributes = attributes?.apply {
                    width = 1
                    height = 1
                    x = -10000
                    y = -10000
                    gravity = Gravity.START or Gravity.TOP
                }
            }
            try {
                dialog.setContentView(view, ViewGroup.LayoutParams(1, 1))
                dialog.show()
            } catch (_: Exception) {
            }

            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = userAgent
                blockNetworkImage = true
            }
            cookieManager.setAcceptThirdPartyCookies(view, true)
            runCatching {
                for ((name, value) in cookies) {
                    cookieManager.setCookie(gateUrl, "$name=$value")
                }
                cookieManager.flush()
            }

            view.webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(v, url, favicon)
                    val u = url ?: return
                    println("[WitAnime][WebView] gate nav $u")
                    if (u != gateUrl && !u.startsWith("about:")) {
                        captured = u
                    }
                }

                override fun onPageFinished(v: WebView?, url: String?) {
                    super.onPageFinished(v, url)
                    val u = url ?: return
                    if (u != gateUrl && !u.startsWith("about:")) {
                        captured = u
                    }
                    // Not every gate is a 302. Some return a tiny HTML page whose
                    // only content is an <iframe> (or a meta refresh) pointing at
                    // the real embed. Pull that out of the DOM before reporting.
                    v?.evaluateJavascript(
                        """(function(){
                            try {
                                var ifr = document.querySelector('iframe[src]');
                                if (ifr && ifr.src && ifr.src.indexOf('about:') !== 0) return ifr.src;
                                var m = document.querySelector('meta[http-equiv=refresh]');
                                if (m) {
                                    var c = m.getAttribute('content') || '';
                                    var i = c.indexOf('url=');
                                    if (i >= 0) return c.substring(i + 4).replace(/^['"]|['"]$/g, '');
                                }
                                var a = document.querySelector('a[href^=http]');
                                if (a && /embed|player|\/e\/|watch/.test(a.href)) return a.href;
                            } catch (e) {}
                            return '';
                        })()"""
                    ) { result ->
                        val nested = result?.trim('"')?.replace("\\/", "/")
                        if (!nested.isNullOrBlank() && nested.startsWith("http") &&
                            nested != gateUrl && nested != captured
                        ) {
                            println("[WitAnime][WebView] gate nested -> $nested")
                            captured = nested
                        }
                        handler.postDelayed({ if (!finished) deliver(captured) }, 400)
                    }
                }

                override fun onReceivedError(
                    v: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    super.onReceivedError(v, request, error)
                    if (request?.isForMainFrame == true) {
                        deliver(captured)
                    }
                }
            }

            handler.postDelayed({ if (!finished) deliver(captured) }, timeoutMs)
            cont.invokeOnCancellation { handler.post { deliver(null) } }

            runCatching { view.loadUrl(gateUrl) }
        }
    }
}
