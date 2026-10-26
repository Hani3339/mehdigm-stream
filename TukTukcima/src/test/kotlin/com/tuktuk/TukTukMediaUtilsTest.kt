package com.tuktuk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TukTukMediaUtilsTest {
    @Test
    fun detectsMegaMaxAndRotatingMirrorDomains() {
        assertTrue(TukTukMediaUtils.isMegaMaxUrl("https://megamax.me/iframe/Uw0W7pOtd12FY"))
        assertTrue(TukTukMediaUtils.isMegaMaxUrl("https://share4max.com/download/Uw0W7pOtd12FY"))
        assertTrue(TukTukMediaUtils.isMegaMaxUrl("https://z9x8c7v6b5.top/iframe/Uw0W7pOtd12FY"))
        assertTrue(TukTukMediaUtils.isMegaMaxUrl("https://rotating-host.example/iframe/Uw0W7pOtd12FY"))
        assertTrue(TukTukMediaUtils.isMegaMaxUrl("https://embed.example/video/abc", "MegaMax"))
        assertFalse(TukTukMediaUtils.isMegaMaxUrl("https://example.org/watch/movie-2026/"))
    }

    @Test
    fun extractsMegaMaxIdsFromWatchAndDownloadChannels() {
        assertEquals(
            "Uw0W7pOtd12FY",
            TukTukMediaUtils.extractMegaMaxId("https://q7v3k8m2p1.top/iframe/Uw0W7pOtd12FY")
        )
        assertEquals(
            "Uw0W7pOtd12FY",
            TukTukMediaUtils.extractMegaMaxId("https://megamax.me/download/Uw0W7pOtd12FY/")
        )
        assertEquals(
            "Uw0W7pOtd12FY",
            TukTukMediaUtils.extractMegaMaxId("https://megamax.me/e/Uw0W7pOtd12FY")
        )
        assertEquals(null, TukTukMediaUtils.extractMegaMaxId("https://megamax.me/"))
    }

    @Test
    fun normalizesEscapedAndRelativeMediaUrls() {
        val html = """{"hls":"https:\/\/cdn.example/master.m3u8?token=abc\u0026x=1","mp4":"/media/movie.mp4"}"""
        val urls = TukTukMediaUtils.mediaUrls("https://player.example/embed/1", html)
        assertTrue(urls.contains("https://cdn.example/master.m3u8?token=abc&x=1"))
        assertTrue(urls.contains("https://player.example/media/movie.mp4"))
    }

    @Test
    fun detectsStreamTypesAndQualityHints() {
        assertTrue(TukTukMediaUtils.isHls("https://cdn.example/master.m3u8?token=x"))
        assertTrue(TukTukMediaUtils.isHls("https://cdn.example/hls/master.txt"))
        assertEquals(2160, TukTukMediaUtils.qualityHint("4K"))
        assertEquals(1080, TukTukMediaUtils.qualityHint("FHD 1080p"))
        assertEquals(720, TukTukMediaUtils.qualityHint("HD 720p"))
    }
}
