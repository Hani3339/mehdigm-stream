package com.tuktuk

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TukTukSiteParserTest {
    @Test
    fun discoversArabicOrdinalSeasonsAndAllEpisodeNumbers() {
        val showUrl = "https://tuktukcinemas.com/series/the-city-shadow/"
        val document = Jsoup.parse(
            """
            <html><head><title>ظل المدينة</title></head><body>
              <h1 class="series-title">ظل المدينة</h1>
              <div class="SeriesSeasons">
                <a href="/series/the-city-shadow/season-1/">الموسم الأول</a>
                <a href="/series/the-city-shadow/season-2/">الموسم الثاني</a>
                <a href="/series/the-city-shadow/season-3/">الموسم الثالث</a>
              </div>
              <div class="SeriesEpisodesGrid">
                <a class="SeriesEpisodeCard" href="/episode/the-city-shadow-s01e01/">
                  <img src="/images/s01e01.jpg"/><span class="title">الحلقة ١</span>
                </a>
                <a class="SeriesEpisodeCard" href="/episode/the-city-shadow-s01e02/">
                  <span class="title">الحلقة ٢</span>
                </a>
              </div>
            </body></html>
            """.trimIndent(),
            showUrl,
        )

        val seasons = TukTukSiteParser.parseSeasonRefs(document, showUrl)
        assertEquals(listOf(1, 2, 3), seasons.map { it.number })
        assertEquals(3, seasons.map { it.url }.distinct().size)

        val episodes = TukTukSiteParser.parseEpisodes(document, showUrl, defaultSeason = 1)
        assertEquals(listOf(1, 2), episodes.map { it.number })
        assertEquals(listOf(1, 1), episodes.map { it.season })
        assertNotNull(episodes.first().poster)

        val metadata = TukTukSiteParser.parseMetadata(document, showUrl)
        assertTrue(TukTukSiteParser.isSeriesPage(document, showUrl, metadata))
    }

    @Test
    fun handlesSeasonTwoPaginationAndPersianArabicDigits() {
        val seasonUrl = "https://tuktukcinemas.com/series/the-city-shadow/season-2/"
        val document = Jsoup.parse(
            """
            <html><body>
              <div class="EpisodesList">
                <a href="/episode/the-city-shadow-s02e۰۷/">الحلقة ۷</a>
                <a href="/episode/the-city-shadow-s02e۰۸/">الحلقة ۸</a>
              </div>
              <nav class="pagination"><a rel="next" href="/series/the-city-shadow/season-2/page/2/">التالي</a></nav>
            </body></html>
            """.trimIndent(),
            seasonUrl,
        )

        assertEquals(2, TukTukSiteParser.seasonNumber("الموسم الثاني"))
        val episodes = TukTukSiteParser.parseEpisodes(
            document,
            seasonUrl,
            defaultSeason = 2,
            defaultSeasonIsExplicit = true,
        )
        assertEquals(listOf(7, 8), episodes.map { it.number })
        assertEquals(listOf(2, 2), episodes.map { it.season })
        assertTrue(episodes.all { it.explicitSeason })
        assertEquals(
            "https://tuktukcinemas.com/series/the-city-shadow/season-2/page/2/",
            TukTukSiteParser.nextPageUrl(document, seasonUrl),
        )
    }

    @Test
    fun searchCardsAreTitleMatchedAndExternalLinksAreRejected() {
        val searchUrl = "https://tuktukcinemas.com/?s=dog+stars"
        val document = Jsoup.parse(
            """
            <ul>
              <li class="Small--Box"><a href="/movie/the-dog-stars/"><img alt="The Dog Stars" src="/poster/dog.jpg"/></a></li>
              <li class="Small--Box"><a href="/movie/dog-galaxy/"><img alt="Dog Galaxy" src="/poster/galaxy.jpg"/></a></li>
              <li class="Small--Box"><a href="https://unrelated.example/movie/other"><img alt="Dog Stars"/></a></li>
            </ul>
            """.trimIndent(),
            searchUrl,
        )

        val cards = TukTukSiteParser.parseCards(document, "dog stars", searchUrl)
        assertEquals(listOf("The Dog Stars"), cards.map { it.title })
        assertEquals("https://tuktukcinemas.com/movie/the-dog-stars/", cards.single().url)
        assertFalse(TukTukSiteParser.isSiteUrl("https://unrelated.example/movie/other"))
        assertEquals(
            "https://tuktukcinemas.com/movie/the-dog-stars/",
            TukTukSiteParser.normalizeUrl("https://www.tuktukcinemas.com/movie/the-dog-stars/"),
        )
    }

    @Test
    fun findsTheShowBeforeItsSeasonInEpisodeBreadcrumbs() {
        val episodeUrl = "https://tuktukcinemas.com/episode/the-city-shadow-s02e03/"
        val document = Jsoup.parse(
            """
            <html><body>
              <nav class="breadcrumbs">
                <a href="/series/the-city-shadow/">ظل المدينة</a>
                <a href="/series/the-city-shadow/season-2/">الموسم الثاني</a>
                <span>الحلقة ٣</span>
              </nav>
              <h1>ظل المدينة الحلقة ٣</h1>
            </body></html>
            """.trimIndent(),
            episodeUrl,
        )

        assertEquals(
            "https://tuktukcinemas.com/series/the-city-shadow/",
            TukTukSiteParser.findParentSeriesUrl(document, episodeUrl),
        )
    }

    @Test
    fun pageRoutesWorkForRootAndMainFrontPage() {
        assertEquals(
            "https://tuktukcinemas.com/main/page/3/",
            TukTukSiteParser.pageUrl("https://tuktukcinemas.com/main/", 3),
        )
        assertEquals(
            "https://tuktukcinemas.com/category/series-1/page/3/",
            TukTukSiteParser.pageUrl("https://tuktukcinemas.com/category/series-1/page/", 3),
        )
        assertEquals(
            "https://tuktukcinemas.com/page/3/?s=dog",
            TukTukSiteParser.pageUrl("https://tuktukcinemas.com/?s=dog", 3),
        )
    }
}
