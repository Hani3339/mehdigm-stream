package com.egydead

import android.content.Context
import com.lagradost.cloudstream3.APIHolder

object EgyDeadPrefs {
    private const val PREFS_NAME = "egydead_cf_prefs"
    private const val KEY_COOKIES = "cf_cookies"
    private const val KEY_LAST_HOST = "last_host"
    private const val KEY_TS = "last_ts"

    private fun ctx(): Context? = APIHolder.getApiFromNameNull("إيجي ديد (EgyDead)")?.let { null } ?: APIHolder.apis.firstOrNull { it.name.contains("EgyDead") || it.javaClass.packageName.contains("egydead") }?.let { null }

    fun getContext(): Context? {
        return try {
            Class.forName("com.lagradost.cloudstream3.AcraApplication")
                .getMethod("getCurrentActivity")
                .invoke(null) as? Context
        } catch (e: Exception) {
            try {
                Class.forName("com.lagradost.cloudstream3.MainActivity")
                    .getDeclaredField("context")
                    .apply { isAccessible = true }
                    .get(null) as? Context
            } catch (e2: Exception) {
                null
            }
        }
    }

    fun saveCookies(cookies: String?) {
        if (cookies.isNullOrBlank()) return
        val c = getContext() ?: return
        c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_COOKIES, cookies)
            .putLong(KEY_TS, System.currentTimeMillis())
            .apply()
    }

    fun loadCookies(): String? {
        val c = getContext() ?: return null
        val prefs = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cookies = prefs.getString(KEY_COOKIES, null) ?: return null
        val ts = prefs.getLong(KEY_TS, 0)
        if (ts == 0L) return cookies
        // Keep for 7 days
        if (System.currentTimeMillis() - ts > 7L * 24 * 60 * 60 * 1000) return null
        return cookies
    }

    fun saveHost(host: String?) {
        if (host.isNullOrBlank()) return
        val c = getContext() ?: return
        c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_LAST_HOST, host).apply()
    }

    fun loadHost(): String? {
        val c = getContext() ?: return null
        return c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LAST_HOST, null)
    }
}
