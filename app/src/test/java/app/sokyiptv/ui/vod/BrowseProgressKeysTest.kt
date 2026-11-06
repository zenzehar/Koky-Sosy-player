package app.sokyiptv.ui.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BrowseProgressKeysTest {
    @Test fun sameMovieInEveryCategoryUsesOneLogicalProgress() {
        val a = BrowseProgressKeys.movie(7, "  TEST FILM  ", 2024)
        val b = BrowseProgressKeys.movie(7, "test film", 2024)
        assertEquals(a, b)
        assertNotEquals(a, BrowseProgressKeys.movie(7, "test film", 2025))
        assertNotEquals(a, BrowseProgressKeys.movie(8, "test film", 2024))
    }

    @Test fun allEpisodesOfAProviderSeriesShareOneCard() {
        assertEquals(BrowseProgressKeys.series(1, "S9"),
            BrowseProgressKeys.series(1, "S9"))
        assertNotEquals(BrowseProgressKeys.series(1, "S9"),
            BrowseProgressKeys.series(2, "S9"))
    }
}
