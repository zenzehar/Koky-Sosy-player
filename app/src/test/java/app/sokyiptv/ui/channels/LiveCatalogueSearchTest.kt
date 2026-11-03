package app.sokyiptv.ui.channels

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCatalogueSearchTest {
    @Test fun emptyInputKeepsChannelsVisible() {
        assertTrue(LiveCatalogueSearch.matches("BBC News", ""))
        assertTrue(LiveCatalogueSearch.matches("BBC News", "   "))
    }

    @Test fun usesTheMoviesNativeArabicOrthographicRules() {
        assertTrue(LiveCatalogueSearch.matches("أشغال شقه", "اشغال شقة"))
        assertTrue(LiveCatalogueSearch.matches("اشغال شقة", "أشغال شقه"))
        assertTrue(LiveCatalogueSearch.matches("قناة چ", "قناة ج"))
        assertFalse(LiveCatalogueSearch.matches("قناة ق", "قناة ف"))
    }

    @Test fun ordinarySearchIsCaseInsensitiveAndNotFuzzy() {
        assertTrue(LiveCatalogueSearch.matches("BBC World News", "bbc"))
        assertFalse(LiveCatalogueSearch.matches("BBC World News", "bbd"))
    }
}
