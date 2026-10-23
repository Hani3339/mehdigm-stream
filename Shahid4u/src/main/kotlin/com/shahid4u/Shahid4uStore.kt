package com.shahid4u

import android.content.Context
import android.content.SharedPreferences
import android.webkit.WebSettings
import com.lagradost.cloudstream3.Episode
import org.json.JSONArray
import org.json.JSONObject

/**
 * تخزين دائم لكل ما يمنع تكرار الطلبات على الموقع:
 *
 *  - كوكيز الجلسة (خصوصاً cf_clearance و __cf_bm) حتى لا نبدأ جلسة جديدة كل مرة.
 *  - حلقات المسلسل لمدة 24 ساعة حتى لا نعيد سحب كل المواسم عند كل فتح.
 *
 * التخزين هنا مقصود: الكاش في الذاكرة فقط كان يضيع مع إعادة تشغيل التطبيق،
 * فيعود البوت لسحب 15 موسماً من جديد ويستحق الحظر.
 */
object Shahid4uStore {
    private const val PREFS = "shahid4u_store_v1"
    private const val SERIES_TTL_MS = 24 * 60 * 60_000L
    private const val KEY_SERIES_PREFIX = "series_"
    private const val KEY_SERIES_AT_PREFIX = "series_at_"

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedUserAgent: String? = null

    fun init(context: Context) {
        if (prefs == null) {
            appContext = context.applicationContext
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    /**
     * نفس UA الذي يستخدمه WebView على الجهاز. استخدام UA ثابت (Pixel 7) مع
     * WebView مختلف يجعل بصمة Cloudflare متناقضة، وقد يُبطل cf_clearance.
     */
    fun deviceUserAgent(): String? {
        cachedUserAgent?.let { return it }
        val ua = appContext?.let { context ->
            runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
        }?.takeIf { it.isNotBlank() }
        if (ua != null) cachedUserAgent = ua
        return ua
    }

    private fun p(): SharedPreferences? = prefs

    // =====================================================================
    // Cookies
    // =====================================================================

    fun loadCookies(domain: String): Map<String, String> {
        val raw = p()?.getString("cookies:$domain", null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, obj.optString(key))
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun saveCookies(domain: String, cookies: Map<String, String>) {
        if (cookies.isEmpty()) return
        val obj = JSONObject()
        cookies.forEach { (key, value) -> obj.put(key, value) }
        p()?.edit()?.putString("cookies:$domain", obj.toString())?.apply()
    }

    // =====================================================================
    // Series episodes
    // =====================================================================

    private fun seriesKey(url: String): String =
        Integer.toHexString(url.hashCode()) + "_" + url.length

    data class StoredEpisode(
        val data: String,
        val name: String,
        val season: Int?,
        val episode: Int?,
        val posterUrl: String?
    )

    fun loadSeries(url: String): List<StoredEpisode>? {
        val prefs = p() ?: return null
        val key = seriesKey(url)
        val savedAt = prefs.getLong(KEY_SERIES_AT_PREFIX + key, 0L)
        if (savedAt <= 0L) return null
        val age = System.currentTimeMillis() - savedAt
        if (age !in 0 until SERIES_TTL_MS) return null

        val raw = prefs.getString(KEY_SERIES_PREFIX + key, null) ?: return null
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val data = obj.optString("data")
                    if (data.isBlank()) continue
                    add(
                        StoredEpisode(
                            data = data,
                            name = obj.optString("name").ifBlank { "حلقة" },
                            season = obj.optInt("season", 0).takeIf { it > 0 },
                            episode = obj.optInt("episode", 0).takeIf { it > 0 },
                            posterUrl = obj.optString("poster").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    fun saveSeries(url: String, episodes: List<Episode>) {
        if (episodes.isEmpty()) return
        val key = seriesKey(url)
        val array = JSONArray()
        episodes.forEach { episode ->
            array.put(JSONObject().apply {
                put("name", episode.name)
                put("data", episode.data)
                put("season", episode.season ?: 0)
                put("episode", episode.episode ?: 0)
                put("poster", episode.posterUrl ?: "")
            })
        }
        p()?.edit()
            ?.putString(KEY_SERIES_PREFIX + key, array.toString())
            ?.putLong(KEY_SERIES_AT_PREFIX + key, System.currentTimeMillis())
            ?.apply()
    }
}
