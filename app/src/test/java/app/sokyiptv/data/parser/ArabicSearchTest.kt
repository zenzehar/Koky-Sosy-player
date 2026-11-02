package app.sokyiptv.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArabicSearchTest {
    @Test fun exactExampleWorksInBothDirections() {
        assertTrue(ArabicSearch.matches("أشغال شقه", "اشغال شقة"))
        assertTrue(ArabicSearch.matches("اشغال شقة", "أشغال شقه"))
        assertTrue(ArabicSearch.matches("موسم أشغال شقه الحلقة 1", "اشغال شقة"))
    }

    @Test fun alefHamzaVariantsAreFullyInterchangeable() {
        val forms = listOf('ا','أ','إ','ٱ','آ','ء','ٲ','ٳ','ٵ')
        for (a in forms) for (b in forms) {
            assertEquals("Mismatch $a <-> $b", ArabicSearch.fold("م${a}ن"),
                ArabicSearch.fold("م${b}ن"))
        }
    }

    @Test fun writtenYehAndAlefMaqsurahFormsAreEquivalent() {
        val forms = listOf('ي','ى','ئ','ی','ے')
        for (a in forms) for (b in forms) {
            assertTrue("Mismatch $a <-> $b", ArabicSearch.matches("ع${a}ل", "ع${b}ل"))
        }
    }

    @Test fun writtenTaaMarbutaHehVariantsAreEquivalent() {
        val forms = listOf('ة','ه','ہ','ھ')
        for (a in forms) for (b in forms) {
            assertTrue("Mismatch $a <-> $b", ArabicSearch.matches("شقق${a}", "شقق${b}"))
        }
    }

    @Test fun hamzaOnWawAndPersianKafAreEquivalentToTheirSeats() {
        assertTrue(ArabicSearch.matches("رؤية", "رويه"))
        assertTrue(ArabicSearch.matches("كوكب", "کوکب"))
        assertTrue(ArabicSearch.matches("كوكب", "ڪوكب"))
    }

    @Test fun removeOptionalArabicMarksAndTatweelWithoutChangingLetters() {
        assertTrue(ArabicSearch.matches("أَشْغَال شُقَّة", "اشغال شقه"))
        assertTrue(ArabicSearch.matches("أشـغــال شقة", "اشغال شقه"))
        assertTrue(ArabicSearch.matches("أشغال\u200F شقة", "اشغال شقه"))
        assertTrue(ArabicSearch.matches("ﻻ", "لا"))
        assertTrue(ArabicSearch.matches("ﻵ", "لا"))
        assertEquals("اشغال شقه", ArabicSearch.fold("  أَشْغَال    شُقَّة "))
    }

    @Test fun persianArabicIndicDigitsMatchWesternInArabicQueries() {
        assertTrue(ArabicSearch.matches("شقة ٢٠٢٦", "شقه 2026"))
        assertTrue(ArabicSearch.matches("شقة ۲۰۲۶", "شقه 2026"))
        assertTrue(ArabicSearch.matches("شقة 2026", "شقه ٢٠٢٦"))
    }

    @Test fun unrelatedConsonantsAndMorphologicalLettersMustNotMatch() {
        assertFalse(ArabicSearch.matches("قمر", "فمر"))
        assertFalse(ArabicSearch.matches("فيلم", "قيلم"))
        assertFalse(ArabicSearch.matches("جبل", "چبل"))
        assertFalse(ArabicSearch.matches("بيت", "پيت"))
        assertFalse(ArabicSearch.matches("كلب", "گلب"))
        assertFalse(ArabicSearch.matches("تاريخ", "ةاريخ"))
        assertFalse(ArabicSearch.matches("حياة", "حيات"))
        assertFalse(ArabicSearch.matches("سر", "زر"))
        assertFalse(ArabicSearch.matches("علم", "غلم"))
        assertFalse(ArabicSearch.matches("كتاب", "كباب"))
    }

    @Test fun noArbitraryEditDistanceOrSoundAlikeMatching() {
        assertFalse(ArabicSearch.matches("اشغال شقه", "اشغال سقة"))
        assertFalse(ArabicSearch.matches("اشغال شقه", "اشغال شقاق"))
        assertFalse(ArabicSearch.matches("اشغال شقه", "اشغل شقه"))
        assertTrue(ArabicSearch.matches("AR - أشغال شقة [2026]", "اشغال شقه"))
        assertFalse(ArabicSearch.matches("ق", "ف"))
    }

    @Test fun doesNotChangeOrdinaryNonArabicQueries() {
        assertFalse(ArabicSearch.hasArabic("Movies 4K"))
        assertFalse(ArabicSearch.matches("فيلم", "فيم"))
        assertEquals("movies 4k", ArabicSearch.fold("  MoViEs 4K  "))
    }
}
