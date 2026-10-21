package com.animerift

import android.content.Context
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Client of the official AnimeRift gateway — the very same API the Android
 * application talks to (`gateway.anime-rift.com/api/v4`).
 *
 * The gateway only answers requests that look like they come from the
 * official Flutter client, so the following are required:
 *
 *  - a Dart user agent (`Dart/3.10 (dart:io)`),
 *  - the device headers: `x-os`, `x-device-language`, `x-installation-source`,
 *    `x-device-release-version`, `x-device-timezone`,
 *  - a registered device: `x-firebase-id` + `x-device-id`.
 *
 * The device id is generated once per installation and registered through
 * `/auth/register/device` exactly the way the app does it on first launch.
 * Without a registered id the gateway answers `DEVICE_SUSPENDED`.
 */
class RiftApi(context: Context) {

    companion object {
        const val TAG = "AnimeRift"
        const val APP_VERSION = "3.13.13"
        const val CLIENT_UA = "Dart/3.10 (dart:io)"
        const val DEVICE_OS_ID = "UQ1A.240205.004"
        const val STREAMTAPE_API = "https://api.streamtape.com"

        val GATEWAYS = listOf(
            "https://gateway.anime-rift.com/api/v4",
            "https://gateway.embrassme.top/api/v4",
        )
    }

    private val prefs = context.applicationContext
        .getSharedPreferences("animerift_device", Context.MODE_PRIVATE)

    /** Firebase installation id, used as the device identity of the gateway. */
    private val firebaseId: String
        get() = prefs.getString("firebase_id", null) ?: newToken(22).also {
            prefs.edit().putString("firebase_id", it).apply()
        }

    /** Device id: `<firebase id>:APA91b<token>` like the app sends it. */
    private val deviceId: String
        get() = prefs.getString("device_id", null)
            ?: (firebaseId + ":APA91b" + newToken(40)).also {
                prefs.edit().putString("device_id", it).apply()
            }

    private val gatewayIndex: Int
        get() = prefs.getInt("gateway", 0).coerceIn(0, GATEWAYS.lastIndex)

    private fun setGateway(index: Int) {
        if (gatewayIndex != index) prefs.edit().putInt("gateway", index).apply()
    }

    private fun newToken(length: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun deviceInfo(): Map<String, Any?> = mapOf(
        "model" to "M2006C3MG",
        "brand" to "Redmi",
        "manufacturer" to "Xiaomi",
        "device" to "angelica",
        "hardware" to "mt6765",
        "product" to "angelica_global",
        "androidVersion" to "14",
        "sdkInt" to 34,
        "isPhysicalDevice" to true,
        "supportedAbis" to listOf("arm64-v8a", "armeabi-v7a"),
        "tags" to "release-keys",
        "type" to "user",
        "buildId" to DEVICE_OS_ID,
    )

    private fun headers(): Map<String, String> = mapOf(
        "User-Agent" to CLIENT_UA,
        "Accept" to "application/json",
        "Content-Type" to "application/json; charset=UTF-8",
        "x-os" to "android",
        "x-platform" to "Mobile",
        "x-device-language" to "ar",
        "x-installation-source" to "IS_INSTALLED_FROM_OTHER_SOURCE",
        "x-device-release-version" to APP_VERSION,
        "x-device-os-id" to DEVICE_OS_ID,
        "x-device-timezone" to isoTimestamp(),
        "x-firebase-app-check" to "null",
        "x-firebase-id" to firebaseId,
        "x-device-id" to deviceId,
    )

    private fun isoTimestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS", Locale.US).format(Date())

    /**
     * Registers the generated device on every gateway, so the same id keeps
     * working no matter which host answers the next request.
     */
    private suspend fun registerDevice(): Boolean {
        val body = mapOf(
            "deviceId" to deviceId,
            "current_app_version" to APP_VERSION,
            "device_os" to "android",
            "device_environment" to "production",
            "device_info" to JSONObject(deviceInfo()).toString(),
        )
        var ok = false
        for ((index, base) in GATEWAYS.withIndex()) {
            try {
                val res = withContext(Dispatchers.IO) {
                    app.post("$base/auth/register/device", headers = headers(), json = body)
                }
                if (res.code in 200..299) {
                    ok = true
                    setGateway(index)
                }
            } catch (_: Exception) {
                // try the next gateway
            }
        }
        if (ok) prefs.edit().putBoolean("registered", true).apply()
        return ok
    }

    private suspend fun ensureRegistered() {
        if (prefs.getBoolean("registered", false)) return
        registerDevice()
    }

    /**
     * Performs a request against the gateway, transparently falling back to the
     * second host when the first one fails.
     */
    private suspend fun request(
        method: String,
        path: String,
        body: Map<String, Any?>? = null
    ): JSONObject? {
        ensureRegistered()
        val order = listOf(gatewayIndex) + GATEWAYS.indices.filter { it != gatewayIndex }
        var retried = false
        for (index in order) {
            val base = GATEWAYS[index]
            try {
                val res = withContext(Dispatchers.IO) {
                    if (method.equals("GET", true)) {
                        app.get("$base$path", headers = headers())
                    } else {
                        app.post("$base$path", headers = headers(), json = body ?: emptyMap<String, Any?>())
                    }
                }
                if (res.code in 200..299) {
                    setGateway(index)
                    return runCatching { JSONObject(res.text) }.getOrNull()
                }
                // The gateway may forget the device (expired / suspended id):
                // register it again and give the request a second chance.
                if (!retried && res.text.contains("DEVICE_SUSPENDED")) {
                    retried = true
                    prefs.edit().putBoolean("registered", false).apply()
                    registerDevice()
                    continue
                }
                android.util.Log.i(TAG, "$method $path -> ${res.code}")
            } catch (e: Exception) {
                android.util.Log.i(TAG, "$method $path failed: ${e.message}")
            }
        }
        return null
    }

    suspend fun get(path: String): JSONObject? = request("GET", path)

    suspend fun post(path: String, body: Map<String, Any?>): JSONObject? = request("POST", path, body)

    // ---------------------------------------------------------------------
    // Endpoints used by the extension
    // ---------------------------------------------------------------------

    suspend fun search(query: String, page: Int = 0): JSONObject? =
        post(
            "/library/search?page=$page&sort_by=release_year&sort_direction=1&text_direction=jp",
            mapOf("query" to query)
        )

    suspend fun homeContent(): JSONObject? = get("/library/home_content?with_genres=true")

    suspend fun browse(page: Int): JSONObject? =
        post(
            "/library/all?page=$page&sort_by=name&sort_direction=1&filter_by=library&text_direction=jp",
            mapOf<String, Any?>("country_origin" to null)
        )

    suspend fun details(animeId: String): JSONObject? = get("/library/details/$animeId")

    suspend fun episodes(animeId: String): JSONObject? =
        get("/library/episodes/$animeId?sort_by_latest=1&with_arcs=true&with_favorites=true")

    suspend fun sources(animeId: String, episodeId: String, episodeNumber: Int): JSONObject? =
        post(
            "/library/episode/sources",
            mapOf(
                "animeId" to animeId,
                "episodeId" to episodeId,
                "episode_number" to episodeNumber,
            )
        )

    suspend fun canPlay(
        episodeId: String,
        hostId: String,
        isDownload: Boolean = false
    ): JSONObject? =
        post(
            "/library/episode/source/can_play",
            mapOf(
                "episodeId" to episodeId,
                "hostId" to hostId,
                "is_download" to isDownload,
                "event_name" to if (isDownload) "download_episode" else "play_episode_unlocked",
            )
        )

    /**
     * يطلب الرابط المباشر. المزوّدات التي لا تملك جودة محددة (السيرفرات
     * "realtime") ترفض وجود حقل `quality` من الأساس، لذلك لا يُرسل إلا إذا
     * كانت الجودة معروفة.
     */
    suspend fun directLink(
        hostId: String,
        quality: String?,
        sessionId: String?
    ): JSONObject? {
        val body = HashMap<String, Any?>()
        body["id"] = hostId
        body["with_internal_player"] = "1"
        body["sessionId"] = sessionId ?: ""
        if (!quality.isNullOrBlank()) body["quality"] = quality
        return post("/library/episode/source/direct_link", body)
    }

    /**
     * Streamtape hands out a ticket instead of the final url. The ticket is
     * only valid after `wait_time` seconds and has to be exchanged against the
     * real cdn url through the public streamtape api.
     */
    suspend fun resolveTicket(fileId: String, ticket: String, waitTime: Int): Pair<String, String>? {
        if (waitTime > 0) kotlinx.coroutines.delay(waitTime * 1000L)
        return try {
            val res = withContext(Dispatchers.IO) {
                app.get(
                    "$STREAMTAPE_API/file/dl?file=$fileId&ticket=$ticket",
                    headers = mapOf("User-Agent" to CLIENT_UA, "Accept" to "application/json")
                )
            }
            if (res.code !in 200..299) return null
            val result = JSONObject(res.text).optJSONObject("result") ?: return null
            val url = result.optString("url")
            if (url.isBlank()) null else url to result.optString("name")
        } catch (_: Exception) {
            null
        }
    }
}
