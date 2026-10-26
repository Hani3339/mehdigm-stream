package com.tuktuk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TukTukSearchUtilsTest {
    @Test
    fun normalizesArabicDiacriticsAndCommonLetterVariants() {
        assertEquals("افلام انمي", TukTukSearchUtils.normalize("أَفْلام أنمي"))
        assertEquals("مدينه", TukTukSearchUtils.normalize("مَدِينَة"))
    }

    @Test
    fun matchesOnlyWhenEveryQueryTermAppearsInTheTitle() {
        assertTrue(TukTukSearchUtils.matchesTitle("The Dog Stars 2026 مترجم", "dog stars"))
        assertTrue(TukTukSearchUtils.matchesTitle("فيلم مدينة النجوم", "مدينه النجوم"))
        assertFalse(TukTukSearchUtils.matchesTitle("The Dog Stars 2026", "dog galaxy"))
        assertFalse(TukTukSearchUtils.matchesTitle("The Dog Stars 2026", "doggy stars"))
        assertFalse(TukTukSearchUtils.matchesTitle("فيلم شتوي 2025", "مسلسل النجوم"))
    }

    @Test
    fun rejectsBlankQueriesAndBlankTitles() {
        assertFalse(TukTukSearchUtils.matchesTitle("The Dog Stars", "   "))
        assertFalse(TukTukSearchUtils.matchesTitle("  ", "dog"))
        assertTrue(TukTukSearchUtils.normalize("  The—Dog   Stars! ") == "the dog stars")
    }
}
