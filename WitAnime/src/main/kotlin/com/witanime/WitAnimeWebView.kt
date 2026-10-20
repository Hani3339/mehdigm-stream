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
 * Loads a WitAnime watch page inside a real (hidden) WebView so the request
 * carries a genuine browser TLS/HTTP2 fingerprint, then triggers the site's own
 * `/sources` POST from page context and returns the JSON manifest. This is the
 * reliable path when Cloudflare rejects OkHttp/MainAPI requests with a 404.
 */
object WitAnimeWebView {

    private const val BRIDGE = "WitAnimeBridge"

    data class Result(val json: String?, val error: String?)

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchSources(
        activity: Activity,
        watchUrl: String,
        cookies: Map<String, String>,
        userAgent: String,
        timeoutMs: Long = 25_000L
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

            // Seed the WebView with the cookies CloudStream already holds so the
            // watch page renders authenticated and the CSRF/session line up.
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
            }, BRIDGE)

            view.webChromeClient = WebChromeClient()

            val injectedJs = """
                (function() {
                    function report(json) { $BRIDGE.onResult(typeof json === 'string' ? json : JSON.stringify(json)); }
                    function fail(msg) { $BRIDGE.onError(String(msg)); }
                    function grab() {
                        try {
                            var csrfEl = document.querySelector('meta[name=csrf-token]');
                            var csrf = csrfEl ? csrfEl.content : '';
                            var path = location.pathname.replace(/\/$/, '') + '/sources';
                            fetch(path, {
                                method: 'POST',
                                headers: { 'Accept': 'application/json', 'X-CSRF-TOKEN': csrf, 'X-Requested-With': 'mark.via.gp' }
                            }).then(function(r) {
                                return r.text().then(function(t) {
                                    if (r.status >= 200 && r.status < 300) report(t);
                                    else fail('HTTP ' + r.status + ' ' + t.slice(0, 200));
                                });
                            }).catch(function(e) { fail(e && e.message ? e.message : 'fetch failed'); });
                        } catch (e) { fail(e && e.message ? e.message : 'unknown'); }
                    }
                    var tries = 0;
                    var iv = setInterval(function() {
                        tries++;
                        if (document.querySelector('meta[name=csrf-token]') && tries > 6) {
                            clearInterval(iv);
                            grab();
                        }
                        if (tries > 60) { clearInterval(iv); fail('csrf not found'); }
                    }, 500);
                })();
            """.trimIndent()

            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, url: String?) {
                    super.onPageFinished(v, url)
                    runCatching { v?.evaluateJavascript(injectedJs, null) }
                }

                override fun shouldInterceptRequest(
                    v: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? = super.shouldInterceptRequest(v, request)
            }

            handler.postDelayed({
                if (!finished) deliver(Result(null, "timeout"))
            }, timeoutMs)

            cont.invokeOnCancellation { handler.post { deliver(Result(null, "cancelled")) } }

            runCatching { view.loadUrl(watchUrl) }
        }
    }
}
