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

    /**
     * Replays a genuine touch (DOWN then UP) on [view] at the given CSS-pixel
     * coordinates. Used to satisfy Cloudflare's Turnstile checkbox, which often
     * ignores synthetic JS clicks. Coordinates are converted to device pixels
     * using the view's display density.
     */
    private fun simulateRealTouch(view: View, cssX: Float, cssY: Float) {
        val density = view.resources.displayMetrics.density
        val x = cssX * density
        val y = cssY * density
        val downTime = android.os.SystemClock.uptimeMillis()
        val upTime = downTime + 60
        val down = android.view.MotionEvent.obtain(
            downTime, downTime, android.view.MotionEvent.ACTION_DOWN, x, y, 0
        )
        val up = android.view.MotionEvent.obtain(
            downTime, upTime, android.view.MotionEvent.ACTION_UP, x, y, 0
        )
        runCatching {
            view.dispatchTouchEvent(down)
            view.postDelayed({ runCatching { view.dispatchTouchEvent(up) } }, 60)
        }
        view.postDelayed({ runCatching { down.recycle(); up.recycle() } }, 200)
    }

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
                // Persist any Cloudflare clearance cookie the page earned so later
                // OkHttp requests (and future sessions) can reuse it without ever
                // opening this WebView again.
                runCatching {
                    CookieManager.getInstance().flush()
                    val c = CookieManager.getInstance().getCookie(watchUrl)
                    println("[WitAnime][WebView] cookies after solve: ${c?.take(200)}")
                }
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
                // Turnstile renders an image/graphics widget; blockNetworkImage
                // would leave it blank, so it must stay off while we solve.
                blockNetworkImage = false
            }

            // Cloudflare's Turnstile will not render or accept touches unless the
            // WebView is actually laid out at a real size and visible. We keep the
            // view 1x1 (so the user never sees the automation) and only expand it
            // to full screen while a challenge is on screen, shrinking back once
            // the challenge clears.
            var expanded = false
            fun setExpanded(on: Boolean) {
                if (expanded == on) return
                expanded = on
                handler.post {
                    runCatching {
                        view.visibility = if (on) View.VISIBLE else View.INVISIBLE
                        view.layoutParams = if (on) ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        ) else ViewGroup.LayoutParams(1, 1)
                        view.requestLayout()
                        dialog.window?.apply {
                            setBackgroundDrawableResource(
                                if (on) android.R.color.black else android.R.color.transparent
                            )
                            if (on) {
                                clearFlags(
                                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                )
                            } else {
                                addFlags(
                                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                )
                            }
                            attributes = attributes?.apply {
                                width = if (on) ViewGroup.LayoutParams.MATCH_PARENT else 1
                                height = if (on) ViewGroup.LayoutParams.MATCH_PARENT else 1
                                x = if (on) 0 else -10000
                                y = if (on) 0 else -10000
                            }
                        }
                    }
                }
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

                /**
                 * Cloudflare's managed/Turnstile checkbox frequently ignores a
                 * synthetic `element.click()` and only reacts to a genuine touch
                 * sequence. When the page reports the checkbox position we replay
                 * a real ACTION_DOWN/ACTION_UP pair at those (CSS px) coordinates.
                 */
                @JavascriptInterface
                fun onTap(cssX: Float, cssY: Float) = handler.post {
                    runCatching { simulateRealTouch(view, cssX, cssY) }
                }

                @JavascriptInterface
                fun onChallenge() = handler.post {
                    println("[WitAnime][WebView] cloudflare challenge detected, expanding view")
                    setExpanded(true)
                }

                @JavascriptInterface
                fun onChallengeCleared() = handler.post {
                    println("[WitAnime][WebView] cloudflare challenge cleared, shrinking view")
                    setExpanded(false)
                }
            }, BRIDGE)

            view.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                    println("[WitAnime][WebView][console] ${msg?.message()}")
                    return true
                }
            }

            // Cloudflare "Just a moment..." / Turnstile solver. Injected on every
            // main-frame navigation (not only the watch page) so the challenge on
            // the homepage warm-up is cleared too. It locates the Turnstile
            // checkbox and asks Kotlin to replay a real touch, which the widget
            // trusts far more than a synthetic element.click().
            val solverJs = """
                (function() {
                    var B = $BRIDGE;
                    if (window.__witanimeSolver) return;
                    window.__witanimeSolver = true;
                    // Coordinated clicks are only allowed on the visible challenge
                    // widget; we never tap the real watch page.
                    function isChallenge() {
                        var t = (document.title || '').toLowerCase();
                        if (t.indexOf('just a moment') >= 0 || t.indexOf('attention required') >= 0) return true;
                        if (document.querySelector('#challenge-form, #challenge-stage, #cf-chl-widget-container, .cf-turnstile, [id^=cf-chl-widget]')) return true;
                        if (document.querySelector('iframe[src*="challenges.cloudflare.com"], iframe[src*="turnstile"]')) return true;
                        return false;
                    }
                    function boxEl() {
                        // The clickable checkbox inside the Turnstile widget.
                        var sel = [
                            '.cf-turnstile input[type=checkbox]',
                            '#cf-chl-widget-container input[type=checkbox]',
                            '[id^=cf-chl-widget] input[type=checkbox]',
                            'input[type=checkbox]',
                            '.cf-turnstile',
                            '[id^=cf-chl-widget]',
                            '#challenge-stage',
                            '#challenge-form'
                        ];
                        for (var i = 0; i < sel.length; i++) {
                            var e = document.querySelector(sel[i]);
                            if (e) return e;
                        }
                        return null;
                    }
                    var attempts = 0;
                    var announced = false;
                    var iv = setInterval(function() {
                        if (!isChallenge()) {
                            if (announced) { announced = false; try { B.onChallengeCleared(); } catch (e) {} }
                            clearInterval(iv);
                            return;
                        }
                        attempts++;
                        if (!announced) {
                            announced = true;
                            try { B.onChallenge(); } catch (e) {}
                            try { B.onProgress('cloudflare challenge page: title="' + document.title + '"'); } catch (e) {}
                        }
                        if (attempts > 120) { clearInterval(iv); return; }
                        var el = boxEl();
                        if (!el) return;
                        try {
                            var r = el.getBoundingClientRect();
                            if (r.width > 0 && r.height > 0) {
                                var cx = r.left + r.width / 2;
                                var cy = r.top + r.height / 2;
                                if (attempts % 2 === 0) {
                                    try { B.onProgress('cf challenge: tapping ' + Math.round(cx) + ',' + Math.round(cy)); } catch (e) {}
                                    try { B.onTap(cx, cy); } catch (e) {}
                                }
                            }
                        } catch (e) {}
                        // Also try a plain click + focus on the widget itself.
                        try { el.click(); } catch (e) {}
                        try { var cb = document.querySelector('input[type=checkbox]'); if (cb) { cb.focus(); cb.click(); } } catch (e) {}
                    }, 1500);
                })();
            """.trimIndent()

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

                    // The site authorises a token by POSTing /watch/stream-source/<token>
                    // (returns {sandbox, referrerPolicy}) and only then loads
                    // /watch/stream-gate/<token> in an iframe. Both are same-origin, so
                    // we can drive them here and read the resulting frame's DOM. Skipping
                    // the authorisation POST (as the OkHttp path did) leaves the gate
                    // hanging with no content — the "webview gates=[]" symptom.
                    function sleep(ms){ return new Promise(function(r){ setTimeout(r, ms); }); }

                    // Load /watch/stream-gate/<token> once and read the same-origin
                    // frame DOM. Returns {url} on success, {status} when the site
                    // answered with its rate-limit/error page (data-status), or null.
                    function loadGate(token) {
                        return new Promise(function(resolve) {
                            var done = false;
                            var ifr = document.createElement('iframe');
                            ifr.style.cssText = 'position:fixed;width:1px;height:1px;left:-9999px;top:-9999px;border:0';
                            ifr.referrerPolicy = 'no-referrer';
                            function finish(v) {
                                if (done) return;
                                done = true;
                                try { ifr.remove(); } catch (e) {}
                                resolve(v);
                            }
                            function inspect() {
                                try {
                                    var doc = ifr.contentDocument;
                                    if (!doc || !doc.documentElement) { finish({status: null, url: null}); return; }
                                    var status = doc.documentElement.dataset ? doc.documentElement.dataset.status : null;
                                    if (status) { finish({status: status, url: null}); return; }
                                    var ifr2 = doc.querySelector('iframe[src]');
                                    if (ifr2 && ifr2.src && ifr2.src.indexOf('about:') !== 0) { finish({status: null, url: ifr2.src}); return; }
                                    var m = doc.querySelector('meta[http-equiv=refresh]');
                                    if (m) {
                                        var c = m.getAttribute('content') || '';
                                        var i = c.indexOf('url=');
                                        if (i >= 0) { finish({status: null, url: c.substring(i + 4).replace(/^['"]|['"]$/g, '')}); return; }
                                    }
                                    var a = doc.querySelector('a[href^=http]');
                                    if (a && /embed|player|\/e\/|watch|stream/.test(a.href)) { finish({status: null, url: a.href}); return; }
                                } catch (e) {}
                                finish({status: null, url: null});
                            }
                            ifr.addEventListener('load', function() { setTimeout(inspect, 250); });
                            setTimeout(inspect, 1500);
                            setTimeout(function() { finish({status: null, url: null}); }, 8000);
                            ifr.src = '/watch/stream-gate/' + token;
                            document.body.appendChild(ifr);
                        });
                    }

                    async function resolveGate(token, getCsrf) {
                        if (!/^[a-f0-9]{64}$/.test(token)) return null;
                        // Authorise the token. Retry while the site rate-limits.
                        for (var a = 0; a < 3; a++) {
                            try {
                                var s = await fetch('/watch/stream-source/' + token, {
                                    method: 'POST',
                                    headers: { 'Accept': 'application/json', 'X-CSRF-TOKEN': getCsrf() }
                                });
                                log('stream-source ' + token.slice(0, 8) + ' -> ' + s.status);
                                if (s.status === 429 || s.status === 419) { await sleep(3000 * (a + 1)); continue; }
                                if (!s.ok) return null;
                                break;
                            } catch (e) { await sleep(1500); }
                        }
                        // Load the gate; on a rate-limit status page wait and retry.
                        for (var b = 0; b < 3; b++) {
                            var res = await loadGate(token);
                            if (res.status === '429' || res.status === '419') {
                                log('gate ' + token.slice(0, 8) + ' rate-limited (' + res.status + '), retry #' + (b + 1));
                                await sleep(3000 * (b + 1));
                                continue;
                            }
                            if (res.status) { log('gate status=' + res.status); return null; }
                            if (res.url) return res.url;
                            return null;
                        }
                        return null;
                    }

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

                            // Resolve every player token to its real embed URL here, in
                            // the browser session, then hand the map to Kotlin so it can
                            // skip the (bot-blocked) OkHttp gate request entirely.
                            var tokens = [];
                            try {
                                var players = data.players || {};
                                for (var q in players) {
                                    var list = players[q] || [];
                                    for (var i = 0; i < list.length; i++) {
                                        var tk = list[i] && list[i].token;
                                        if (tk && tokens.indexOf(tk) < 0) tokens.push(tk);
                                    }
                                }
                            } catch (e) {}
                            var gates = {};
                            var csrfNow = function() {
                                var c = document.querySelector('meta[name=csrf-token]');
                                return c ? c.content : csrf;
                            };
                            for (var k = 0; k < tokens.length; k++) {
                                // Pace the requests: the site rate-limits aggressively
                                // and each token costs an authorisation POST + a gate load.
                                if (k > 0) await sleep(1200);
                                var real = await resolveGate(tokens[k], csrfNow);
                                log('gate ' + tokens[k].slice(0, 8) + ' -> ' + real);
                                if (real) gates[tokens[k]] = real;
                                // The first successfully resolved server is usually all
                                // the user needs; stop hammering once we have a few.
                                if (Object.keys(gates).length >= 3) break;
                            }
                            data.gates = gates;
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
                        runCatching { v?.evaluateJavascript(solverJs, null) }
                        if ((url ?: "").contains("/watch/")) {
                            runCatching { v?.evaluateJavascript(injectedJs, null) }
                        }
                    }

                    override fun onPageFinished(v: WebView?, url: String?) {
                        super.onPageFinished(v, url)
                        println("[WitAnime][WebView] onPageFinished $url")
                        runCatching { v?.evaluateJavascript(solverJs, null) }
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
