package com.tuktuk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TukTukStreamRubyExtractorTest {
    @Test
    fun recognizesStreamRubyMirrorHostsAndMegaMaxDriverLabels() {
        assertTrue(TukTukMediaUtils.isStreamRubyProvider("https://streamruby.com/e/abc123", ""))
        assertTrue(TukTukMediaUtils.isStreamRubyProvider("https://rubyvidhub.com/e/abc123", ""))
        assertTrue(TukTukMediaUtils.isStreamRubyProvider("https://megatuktuk.example/iframe/abc123", "Stream Ruby"))
        assertFalse(TukTukMediaUtils.isStreamRubyProvider("https://megamax.me/iframe/abc123", "MegaMax"))
    }

    @Test
    fun extractsFileCodesFromEmbedRoutes() {
        assertEquals("abc123", TukTukStreamRubyExtractor.fileCodeFromUrl("https://streamruby.com/e/abc123.html"))
        assertEquals("abc123", TukTukStreamRubyExtractor.fileCodeFromUrl("https://rubyvidhub.com/embed/show-abc123/"))
        assertEquals("abc123", TukTukStreamRubyExtractor.fileCodeFromUrl("https://streamruby.com/d/?file_code=abc123"))
        assertNull(TukTukStreamRubyExtractor.fileCodeFromUrl("https://streamruby.com/e/"))
    }
}
