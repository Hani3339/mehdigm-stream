package com.egydead

import android.content.Context
import com.lagradost.cloudstream3.APIHolder

object EgyDeadPrefsEncoder {
    private const val PREFS_NAME = "egydead_cf_prefs"
    private const val KEY_COOKIES = "cf_cookies"
    private const val KEY_TS = "last_ts"

    fun saveCookies(cookies: String?) {
        if (cookies.isNullOrBlank()) return
        val ctx = getContext() ?: return
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_COOKIES, cookies)
            .putLong(KEY_TS, System.currentTimeMillis())
            .apply()
    }

    fun loadCookies(): String? {
        val ctx = getContext() ?: return null
        val p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val c = p.getString(KEY_COOKIES, null) ?: return null
        val ts = p.getLong(KEY_TS, 0)
        if (System.currentTimeMillis() - ts > 7 * 24 * 60 * 60 * 1000) return null
        return c
    }

    private fun getContext(): Context? {
        return try {
            Class.forName("com.lagradost.cloudstream3.AcraApplication")
                .getMethod("getApplicationContext")
                .invoke(null) as? Context
        } catch (e: Exception) {
            null
        }
    }
}
