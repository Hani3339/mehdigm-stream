package com.tuktuk

import java.text.Normalizer
import java.util.Locale

/** Search normalization and strict title matching for TukTuk's HTML search pages. */
internal object TukTukSearchUtils {
    private val combiningMarks = Regex("\\p{M}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    private val whitespace = Regex("\\s+")

    fun normalize(value: String): String {
        var normalized = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .lowercase(Locale.ROOT)
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ٱ', 'ا')
            .replace('ى', 'ي')
            .replace('ة', 'ه')
        normalized = combiningMarks.replace(normalized, "")
        return whitespace.replace(separators.replace(normalized, " "), " ").trim()
    }

    /**
     * A result card is retained only when every query term occurs in its title.
     * This prevents homepage/sidebar cards from leaking into search results when a
     * mirror/template returns a page that ignored the search parameter.
     */
    fun matchesTitle(title: String, query: String): Boolean {
        val normalizedTitle = normalize(title)
        val queryTerms = normalize(query).split(' ').filter { it.isNotBlank() }.distinct()
        if (normalizedTitle.isBlank() || queryTerms.isEmpty()) return false

        val titleTerms = normalizedTitle.split(' ').filter { it.isNotBlank() }
        return queryTerms.all { queryTerm ->
            titleTerms.any { titleTerm ->
                titleTerm == queryTerm || (
                    queryTerm.length >= 3 && titleTerm.startsWith(queryTerm) &&
                        titleTerm.length - queryTerm.length <= 2
                    )
            }
        }
    }
}
