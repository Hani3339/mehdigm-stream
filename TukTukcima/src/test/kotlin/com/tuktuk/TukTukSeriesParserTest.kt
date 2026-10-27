package com.tuktuk

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TukTukSeriesParserTest {
    @Test
    fun readsSeasonAndEpisodeNumbersFromArabicPersianAndSxxExxLabels() {
        assertEquals(2, TukTukSeriesParser.seasonNumber("الموسم الثاني"))
        assertEquals(1, TukTukSeriesParser.seasonNumber("الموسم الأول"))
        assertEquals(1, TukTukSeriesParser.seasonNumber("Season one"))
        assertEquals(7, TukTukSeriesParser.seasonNumber("https://tuktukcinemas.com/show?season_id=7"))
        assertEquals(null, TukTukSeriesParser.seasonNumber("Season 2020"))
        assertEquals(2, TukTukSeriesParser.seasonNumber("S۰۲E۰۷"))
        assertEquals(7, TukTukSeriesParser.episodeNumber("S۰۲E۰۷"))
        assertEquals(8, TukTukSeriesParser.episodeNumber("الحلقة ۸"))
        assertEquals(1, TukTukSeriesParser.episodeNumber("الحلقة الأولى"))
        assertTrue(TukTukSeriesParser.isEpisode("https://tuktukcinemas.com/watch/show/", "الحلقة الأولى"))
        assertEquals(12, TukTukSeriesParser.episodeNumber("https://tuktukcinemas.com/episode/show-episode-12/"))
        assertTrue(TukTukSeriesParser.isEpisode("https://tuktukcinemas.com/watch/show-s02e07/"))
        assertFalse(TukTukSeriesParser.isEpisode("https://tuktukcinemas.com/series/show/season-2/"))
    }

    @Test
    fun findsSeasonLinksWithoutAssumingTheSeriesRouteOrNumericLabels() {
        val showUrl = "https://tuktukcinemas.com/watch/the-long-show/"
        val document = Jsoup.parse(
            """
            <html><body>
              <div class="SeriesSeasons">
                <a href="/watch/the-long-show/season-1/">الموسم الأول</a>
                <a href="/watch/the-long-show/season-2/">الموسم الثاني</a>
                <a href="/watch/the-long-show/season-3/">3</a>
                <a data-season="4" href="/watch/the-long-show/special/">حلقة خاصة</a>
                <a href="https://unrelated.example/season-5/">الموسم الخامس</a>
                <a href="/watch/the-long-show/episode/episode-1/">الحلقة ١</a>
              </div>
            </body></html>
            """.trimIndent(),
            showUrl,
        )

        val seasons = TukTukSeriesParser.findSeasonPages(document, showUrl)
        assertEquals(listOf(1, 2, 3, 4), seasons.map { it.number })
        assertEquals(4, seasons.map { TukTukSeriesParser.pageKey(it.url) }.distinct().size)
        assertTrue(seasons.all { it.url.startsWith("https://tuktukcinemas.com/") })
    }

    @Test
    fun followsOnlyPaginationWithinTheEpisodeCollection() {
        val seasonUrl = "https://tuktukcinemas.com/series/the-long-show/season-2/"
        val document = Jsoup.parse(
            """
            <div class="pagination">
              <a href="/series/the-long-show/season-2/">1</a>
              <a class="next" href="/series/the-long-show/season-2/page/2/">التالي</a>
            </div>
            """.trimIndent(),
            seasonUrl,
        )
        assertEquals(
            "https://tuktukcinemas.com/series/the-long-show/season-2/page/2/",
            TukTukSeriesParser.nextPageUrl(document, seasonUrl),
        )

        val unrelatedPagination = Jsoup.parse(
            "<div class='pagination'><a class='next' href='/category/series/page/2/'>التالي</a></div>",
            seasonUrl,
        )
        assertEquals(null, TukTukSeriesParser.nextPageUrl(unrelatedPagination, seasonUrl))
    }

    @Test
    fun recognizesCurrentSeasonPagesAndNormalizesOldDomainLinks() {
        assertTrue(TukTukSeriesParser.isSeasonPage("https://tuktukcinemas.com/series/show/season-3/"))
        assertEquals(
            "https://tuktukcinemas.com/series/show/season-3/",
            TukTukSeriesParser.normalizeSiteUrl("https://www.tuktukhd.com/series/show/season-3/"),
        )
    }
}
