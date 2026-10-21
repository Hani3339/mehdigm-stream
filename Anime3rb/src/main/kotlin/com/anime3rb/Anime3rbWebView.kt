package com.anime3rb

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Loads [url] in a real WebView so requests carry a genuine browser
 * TLS/HTTP2/JA3 fingerprint, letting them pass Cloudflare's managed challenge.
 *
 * The WebView is attached off-screen to the supplied [Context]'s window when
 * possible (an Activity), otherwise hosted in a minimal 1x1 container. It polls
 * the page title until the Cloudflare interstitial disappears (or [timeoutMs]
 * elapses) and then returns the rendered HTML. Solved cookies (incl.
 * `cf_clearance`) stay in the app-wide CookieManager so later OkHttp calls can
 * reuse them.
 */
object Anime3rbWebView {

    private const val TAG = "Anime3rbWebView"

    private fun isChallengeTitle(title: String?): Boolean {
        if (title.isNullOrBlank()) return true
        val t = title.lowercase()
        return t.contains("just a moment") ||
            t.contains("attention required") ||
            title.contains("لحظة") ||
            t.contains("checking your browser")
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetch(
        context: Context,
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long = 90_000L
    ): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            var finished = false
            val ua = headers["User-Agent"]
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

            val webView = WebView(context.applicationContext)
            val settings = webView.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.blockNetworkImage = false
            settings.userAgentString = ua
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

            // Attach off-screen so the page believes it is visible (Cloudflare
            // refuses to render Turnstile for display:none WebViews).
            val parent = (context as? android.app.Activity)?.window?.decorView as? ViewGroup
            if (parent != null) {
                try {
                    webView.layoutParams = ViewGroup.LayoutParams(1, 1)
                    webView.alpha = 0f
                    parent.addView(webView)
                } catch (_: Throwable) {
                }
            }

            val handler = Handler(Looper.getMainLooper())

            fun finish(html: String?) {
                if (finished) return
                finished = true
                handler.removeCallbacksAndMessages(null)
                runCatching { CookieManager.getInstance().flush() }
                runCatching {
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    webView.stopLoading()
                    webView.destroy()
                }
                if (cont.isActive) cont.resume(html)
            }

            var attempts = 0
            val maxAttempts = (timeoutMs / 1500L).toInt()

            val poller = object : Runnable {
                override fun run() {
                    if (finished) return
                    attempts++
                    val title = runCatching { webView.title }.getOrNull()
                    val cookies = runCatching {
                        CookieManager.getInstance().getCookie(url)
                    }.getOrNull()
                    val cleared = cookies?.contains("cf_clearance") == true

                    if ((!isChallengeTitle(title) && !title.isNullOrBlank()) || cleared) {
                        // Give the page a beat to finish rendering dynamic parts.
                        handler.postDelayed({
                            webView.evaluateJavascript(
                                "(function(){try{return document.documentElement.outerHTML;}catch(e){return null;}})()"
                            ) { value ->
                                val html = value
                                    ?.takeIf { it != "null" }
                                    ?.let { decodeJsString(it) }
                                finish(html)
                            }
                        }, 1500)
                        return
                    }

                    if (attempts >= maxAttempts) {
                        webView.evaluateJavascript(
                            "(function(){try{return document.documentElement.outerHTML;}catch(e){return null;}})()"
                        ) { value ->
                            finish(value?.takeIf { it != "null" }?.let { decodeJsString(it) })
                        }
                        return
                    }

                    handler.postDelayed(this, 1500)
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, u: String?) {
                    super.onPageFinished(view, u)
                    handler.removeCallbacks(poller)
                    handler.postDelayed(poller, 1500)
                }
            }
            webView.webChromeClient = WebChromeClient()

            try {
                webView.loadUrl(url, headers)
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "loadUrl failed", e)
                finish(null)
            }
        }
    }

    /** `evaluateJavascript` returns a JSON-encoded string literal; undo that. */
    private fun decodeJsString(value: String): String {
        if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length - 1)
                .replace("\\u003C", "<")
                .replace("\\u003E", ">")
                .replace("\\u0026", "&")
                .replace("\\/", "/")
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
        return value
    }
}
