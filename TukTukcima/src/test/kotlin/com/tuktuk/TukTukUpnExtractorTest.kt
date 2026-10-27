package com.tuktuk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TukTukUpnExtractorTest {
    private val origin = "https://tuktuk.upns.one"

    @Test
    fun selectsOnlyThePreferredOriginalPlaylistInsteadOfAllFallbackCopies() {
        val selected = TukTukUpnExtractor.selectMediaUrl(
            listOf(
                "https://cdn.upns.one/watch/master.m3u8?token=good", // cfNative
                "https://origin.upns.one/watch/master.m3u8?token=good", // source
                "https://cf.upns.one/watch/master.m3u8?token=stale", // cf
            ),
            origin
        )

        assertEquals("https://cdn.upns.one/watch/master.m3u8?token=good", selected)
    }

    @Test
    fun fallsBackToTheNextOriginalFieldOnlyWhenThePreferredOneIsMissing() {
        assertEquals(
            "$origin/watch/master.m3u8?token=source",
            TukTukUpnExtractor.selectMediaUrl(
                listOf("null", "/watch/master.m3u8?token=source", "/watch/master.m3u8?token=cf"),
                origin
            )
        )
        assertEquals(
            "$origin/watch/master.txt?token=cf",
            TukTukUpnExtractor.selectMediaUrl(listOf(null, null, "/watch/master.txt?token=cf"), origin)
        )
    }

    @Test
    fun ignoresMissingNullAndInvalidCandidates() {
        assertNull(TukTukUpnExtractor.selectMediaUrl(emptyList(), origin))
        assertNull(TukTukUpnExtractor.selectMediaUrl(listOf(null, "null", "javascript:alert(1)"), origin))
    }
}
