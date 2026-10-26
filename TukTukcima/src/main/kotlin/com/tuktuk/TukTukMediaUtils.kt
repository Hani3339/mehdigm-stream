package com.tuktuk

import java.net.URI

/** Small shared helpers for media URLs embedded in player pages. */
internal object TukTukMediaUtils {
    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val absoluteMediaUrl = Regex(
        """(?i)(?:https?:)?//[^\s"'<>\\]+?\.(?:m3u8|m3u|mp4|m4v|mpd)(?:\?[^\s"'<>\\]*)?"""
    )
    private val relativeMediaUrl = Regex(
        """(?i)["']((?:/|\./|\.\./)[^"'<>\s\\]+?\.(?:m3u8|m3u|mp4|m4v|mpd)(?:\?[^"'<>\s\\]*)?)["']"""
    )
    private val masterTextUrl = Regex(
        """(?i)(?:https?:)?//[^\s"'<>\\]+?/master\.txt(?:\?[^\s"'<>\\]*)?"""
    )

    fun originOf(url: String, fallback: String): String {
        val parsed = runCatching { URI(url) }.getOrNull() ?: return fallback.trimEnd('/')
        val host = parsed.host?.takeIf { it.isNotBlank() } ?: return fallback.trimEnd('/')
        val scheme = parsed.scheme?.takeIf { it.equals("http", true) || it.equals("https", true) } ?: "https"
        return "$scheme://$host"
    }

    fun isHls(url: String): Boolean = Regex(
        """(?i)\.(?:m3u8|m3u)(?:$|[?#])|/master\.txt(?:$|[?#])"""
    ).containsMatchIn(url)

    fun qualityHint(value: String?): Int {
        val label = value?.lowercase().orEmpty()
        return when {
            "4k" in label || "2160" in label -> 2160
            "2k" in label || "1440" in label -> 1440
            "fhd" in label || "1080" in label -> 1080
            "hd" in label || "720" in label -> 720
            "sd" in label || "480" in label -> 480
            else -> -1
        }
    }

    fun resolveMediaUrl(baseUrl: String, rawUrl: String): String? {
        val clean = rawUrl.trim().replace("\\/", "/").replace("&amp;", "&")
        if (clean.isBlank()) return null
        val absolute = when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrNull()
        } ?: return null
        val uri = runCatching { URI(absolute) }.getOrNull() ?: return null
        return absolute.takeIf { uri.scheme.equals("http", true) || uri.scheme.equals("https", true) }
    }

    fun mediaUrls(pageUrl: String, html: String): List<String> {
        val cleaned = html
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
        val candidates = buildList {
            absoluteMediaUrl.findAll(cleaned).forEach { add(it.value) }
            masterTextUrl.findAll(cleaned).forEach { add(it.value) }
            relativeMediaUrl.findAll(cleaned).forEach { add(it.groupValues[1]) }
        }
        return candidates.mapNotNull { raw ->
            val candidate = raw.trim().trimEnd(',', ';', ')', ']', '}')
            resolveMediaUrl(pageUrl, candidate)
        }.distinct()
    }
}
