package com.tuktuk

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.text.Normalizer
import java.util.Locale

internal data class TukTukSeasonPage(
    val number: Int,
    val title: String,
    val url: String,
)

/** Focused helpers for season/episode navigation; the provider's other behavior stays unchanged. */
internal object TukTukSeriesParser {
    private const val SITE_HOST = "tuktukcinemas.com"
    private const val BASE_URL = "https://$SITE_HOST/"

    private val episodeLabel = Regex(
        "(?:الحلقة|حلقه|حلقة|episode|ep)\\s*[-:#_]*\\s*([0-9٠-٩۰-۹]+)",
        RegexOption.IGNORE_CASE,
    )
    private val sxe = Regex(
        "(?:^|[^a-z0-9])s([0-9٠-٩۰-۹]{1,2})e([0-9٠-٩۰-۹]{1,4})(?:$|[^a-z0-9])",
        RegexOption.IGNORE_CASE,
    )
    private val seasonNumeric = Regex(
        "(?:الموسم|موسم|season|seasons)[/\\s=:#_-]*([0-9٠-٩۰-۹]{1,2})(?:$|[^0-9٠-٩۰-۹])",
        RegexOption.IGNORE_CASE,
    )
    private val seasonSlug = Regex(
        "(?:^|[/_-])seasons?[-_/ ]*([0-9٠-٩۰-۹]{1,2})(?:$|[/_-])",
        RegexOption.IGNORE_CASE,
    )
    private val seasonQuery = Regex("[?&]season(?:[_-]?id)?=([0-9٠-٩۰-۹]{1,2})(?:$|&)", RegexOption.IGNORE_CASE)
    private val shortSeason = Regex(
        "(?:^|[^a-z0-9])s([0-9٠-٩۰-۹]{1,2})(?:$|[^a-z0-9])",
        RegexOption.IGNORE_CASE,
    )
    private val episodePath = Regex(
        "(?:^|/)(?:episodes?|ep)[/_-]*([0-9٠-٩۰-۹]{1,4})(?:$|/|[-_])",
        RegexOption.IGNORE_CASE,
    )
    private val episodePagination = Regex("(?:/page/(\\d+)|[?&](?:paged|page)=(\\d+))", RegexOption.IGNORE_CASE)
    private val pagePath = Regex("/page/(\\d+)$", RegexOption.IGNORE_CASE)

    fun normalizeSiteUrl(raw: String, baseUrl: String = BASE_URL): String? {
        val value = raw.trim().replace("&amp;", "&").replace("\\/", "/")
            .takeIf { it.isNotBlank() } ?: return null
        val absolute = when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            else -> runCatching { URI(baseUrl).resolve(value).toString() }.getOrNull() ?: return null
        }
        val uri = runCatching { URI(absolute) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return null
        val supportedHost = host == SITE_HOST || host.endsWith(".$SITE_HOST") ||
            host == "tuktukhd.com" || host.endsWith(".tuktukhd.com")
        if (scheme !in setOf("http", "https") || !supportedHost) return null
        val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "https://$SITE_HOST$path$query"
    }

    fun seasonNumber(vararg values: String): Int? {
        for (value in values) {
            val text = asciiDigits(value.lowercase(Locale.ROOT))
            sxe.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..99 }?.let { return it }
            seasonNumeric.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..99 }?.let { return it }
            seasonQuery.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..99 }?.let { return it }
            seasonSlug.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..99 }?.let { return it }
            shortSeason.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..99 }?.let { return it }
            ordinalNumber(text, "(?:موسم|season)")?.let { return it }
        }
        return null
    }

    fun episodeNumber(vararg values: String): Int? {
        for (value in values) {
            val text = asciiDigits(value.lowercase(Locale.ROOT))
            sxe.find(text)?.groupValues?.getOrNull(2)?.toIntOrNull()?.takeIf { it in 0..9999 }?.let { return it }
            episodeLabel.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..9999 }?.let { return it }
            episodePath.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..9999 }?.let { return it }
            ordinalNumber(text, "(?:الحلقة|حلقه|حلقة|episode|ep)")?.let { return it }
        }
        return null
    }

    fun isEpisode(url: String, title: String = ""): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        val searchable = "$title $path"
        return path.contains("/episode/") || path.contains("/episodes/") ||
            episodePath.containsMatchIn(path) ||
            sxe.containsMatchIn(asciiDigits(searchable.lowercase(Locale.ROOT))) ||
            episodeLabel.containsMatchIn(searchable) ||
            ordinalNumber(searchable, "(?:الحلقة|حلقه|حلقة|episode|ep)") != null
    }

    fun isSeasonPage(url: String, title: String = ""): Boolean {
        if (isEpisode(url, title) || seasonNumber(title, url) == null) return false
        val searchable = "$title $url"
        return Regex("(?:الموسم|موسم|seasons?|s[0-9٠-٩۰-۹]{1,2})", RegexOption.IGNORE_CASE)
            .containsMatchIn(searchable)
    }

    fun findSeasonPages(document: Document, currentUrl: String): List<TukTukSeasonPage> {
        val selectors = ".SeriesSeasons, section.SeriesSeasons, .allseasonss, .SeasonList, .seasons, .season-list, .seasonList, [data-seasons]"
        val containers = document.select(selectors)
        val scopedAnchors = linkedSetOf<Element>()
        for (container in containers) {
            if (container.tagName().equals("a", true)) scopedAnchors.add(container)
            container.select("a[href], a[data-url], a[data-href], [data-season][data-url], [data-season][data-href]")
                .forEach { scopedAnchors.add(it) }
        }
        val heading = document.selectFirst("h1")?.text().orEmpty()
        val hasSeriesContext = currentUrl.contains("/series/", true) || currentUrl.contains("/show/", true) ||
            heading.contains("مسلسل") || heading.contains("series", true) ||
            document.select(".SeriesEpisodesGrid, .EpisodesList, .episodes-list, .SeriesEpisodeCard").isNotEmpty()
        val candidates = when {
            scopedAnchors.isNotEmpty() -> scopedAnchors
            hasSeriesContext -> document.select("a[href], a[data-url], a[data-href]").toList()
            else -> emptyList()
        }
        val result = linkedMapOf<String, TukTukSeasonPage>()
        for (element in candidates) {
            val rawUrl = element.attr("href").ifBlank { element.attr("data-url") }.ifBlank { element.attr("data-href") }
            val url = normalizeSiteUrl(rawUrl, currentUrl) ?: continue
            if (samePage(url, currentUrl) || isEpisode(url, element.text())) continue
            val label = element.attr("title").ifBlank {
                element.selectFirst(".title, .Title, h2, h3")?.text().orEmpty()
            }.ifBlank { element.selectFirst("img")?.attr("alt").orEmpty() }
                .ifBlank { element.text() }.trim()
            val dataSeason = element.attr("data-season")
            val containerNumber = if (containers.isNotEmpty()) {
                plainNumber(asciiDigits(label))?.takeIf { it in 1..99 }
                    ?: ordinalNumber(label, "(?:موسم|season)", allowBare = true)
            } else null
            val number = seasonNumber(label, url, dataSeason)
                ?: plainNumber(asciiDigits(dataSeason))?.takeIf { it in 1..99 }
                ?: containerNumber
                ?: continue
            val hasSeasonCue = dataSeason.isNotBlank() ||
                Regex("(?:الموسم|موسم|season|seasons?|s[0-9٠-٩۰-۹]{1,2})", RegexOption.IGNORE_CASE)
                    .containsMatchIn("$label $url") || containers.isNotEmpty()
            if (!hasSeasonCue) continue
            val title = label.ifBlank { "الموسم $number" }
            result.putIfAbsent(pageKey(url), TukTukSeasonPage(number, title, url))
        }
        return result.values.sortedWith(compareBy<TukTukSeasonPage>({ it.number }, { it.url }))
    }

    fun nextPageUrl(document: Document, currentUrl: String): String? {
        val path = runCatching { URI(currentUrl).path.orEmpty().trimEnd('/') }.getOrDefault("")
        val currentUri = runCatching { URI(currentUrl) }.getOrNull()
        val currentPage = pagePath.find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: currentUri?.rawQuery?.let { query ->
                Regex("(?:^|&)(?:paged|page)=(\\d+)", RegexOption.IGNORE_CASE)
                    .find(query)?.groupValues?.getOrNull(1)?.toIntOrNull()
            } ?: 1
        val links = document.select("a[rel=next][href], a.next[href], .pagination a[href], .pagination-next a[href], a.page-numbers[href]")
        val semanticNext = links.firstOrNull { link ->
            link.text().contains("التالي") || link.text().trim().equals("next", true) ||
                link.attr("rel").contains("next", true) || link.attr("class").contains("next", true)
        }
        val selected = semanticNext ?: links.firstOrNull { link ->
            val href = link.attr("href")
            val targetPage = episodePagination.find(href)?.let { match ->
                match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }?.toIntOrNull()
                    ?: match.groupValues.getOrNull(2)?.toIntOrNull()
            }
            targetPage == currentPage + 1
        } ?: return null
        val nextUrl = normalizeSiteUrl(selected.attr("href"), currentUrl) ?: return null
        if (samePage(nextUrl, currentUrl)) return null

        // Avoid following the site's outer category pagination from an episode list.
        val validCurrentUri = currentUri ?: return null
        val nextUri = runCatching { URI(nextUrl) }.getOrNull() ?: return null
        val currentPath = validCurrentUri.path.orEmpty().trimEnd('/').ifBlank { "/" }
        val nextPath = nextUri.path.orEmpty().trimEnd('/').ifBlank { "/" }
        val isSameCollection = nextPath == currentPath ||
            nextPath.startsWith("$currentPath/page/", true) ||
            currentPath.startsWith("$nextPath/page/", true)
        val hasPageQuery = nextUri.rawQuery?.contains(Regex("(?:^|&)(?:page|paged)=\\d+", RegexOption.IGNORE_CASE)) == true
        return nextUrl.takeIf { isSameCollection || hasPageQuery }
    }

    fun pageKey(rawUrl: String): String {
        val normalized = normalizeSiteUrl(rawUrl) ?: rawUrl
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return normalized
        val path = uri.path.orEmpty().trimEnd('/').ifBlank { "/" }
        return "${uri.host?.lowercase(Locale.ROOT)}$path${uri.rawQuery?.let { "?$it" }.orEmpty()}"
    }

    private fun samePage(first: String, second: String): Boolean = pageKey(first) == pageKey(second)

    private fun plainNumber(value: String): Int? {
        val trimmed = value.trim()
        if (!trimmed.matches(Regex("[0-9]{1,2}"))) return null
        return trimmed.toIntOrNull()
    }

    private fun ordinalNumber(value: String, cuePattern: String, allowBare: Boolean = false): Int? {
        if (!allowBare) {
            val hasCue = if (cuePattern.contains("season", true)) {
                value.contains("موسم") || value.contains("season", true)
            } else {
                value.contains("حلقة") || value.contains("حلقه") || value.contains("ep", true)
            }
            if (!hasCue) return null
        }
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .replace("ال", "")
            .replace("أ", "ا")
            .replace("إ", "ا")
            .replace("آ", "ا")
            .replace("ى", "ي")
        val entries = listOf(
            listOf("اول", "اولي", "اولى", "first", "one", "1st") to 1,
            listOf("ثاني", "ثانيه", "second", "two", "2nd") to 2,
            listOf("ثالث", "ثالثه", "third", "three", "3rd") to 3,
            listOf("رابع", "رابعه", "fourth", "four", "4th") to 4,
            listOf("خامس", "خامسه", "fifth", "five", "5th") to 5,
            listOf("سادس", "سادسه", "sixth", "six", "6th") to 6,
            listOf("سابع", "سابعه", "seventh", "seven", "7th") to 7,
            listOf("ثامن", "ثامنه", "eighth", "eight", "8th") to 8,
            listOf("تاسع", "تاسعه", "ninth", "nine", "9th") to 9,
            listOf("عاشر", "عاشره", "tenth", "ten", "10th") to 10,
        )
        return entries.firstOrNull { (forms, _) ->
            forms.any { form ->
                val pattern = if (allowBare) {
                    Regex("(?:^|[^\\p{L}])$form(?:$|[^\\p{L}])", RegexOption.IGNORE_CASE)
                } else {
                    Regex("$cuePattern[\\s_-]*$form(?:$|[^\\p{L}])", RegexOption.IGNORE_CASE)
                }
                pattern.containsMatchIn(normalized)
            }
        }?.second
    }

    private fun asciiDigits(value: String): String = buildString(value.length) {
        for (char in value) {
            val digit = when (char) {
                in '٠'..'٩' -> char.code - '٠'.code
                in '۰'..'۹' -> char.code - '۰'.code
                else -> -1
            }
            append(if (digit >= 0) ('0'.code + digit).toChar() else char)
        }
    }
}
