package app.opentv.ui.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Screenshot uses Arabic series title and S01 E05 even if provider repeats S01E05. */
class CinematicTitlePartsTest {
    @Test fun repeatedEpisodeMarkersAreRemovedButArabicNameRemains() {
        val result = CinematicTitleParts.parse("S01E05 - اللعبة S01 E05", isEpisode = true)
        assertEquals("اللعبة", result.title)
        assertEquals("S01 E05", result.episode)
    }

    @Test fun seasonAndEpisodeAcrossFormats() {
        val result = CinematicTitleParts.parse("Sample Show - Season 2 Episode 13", true)
        assertEquals("Sample Show", result.title)
        assertEquals("S02 E13", result.episode)
    }

    @Test fun movieTitleIsNotAltered() {
        val result = CinematicTitleParts.parse("Some Film 2026", false)
        assertEquals("Some Film 2026", result.title)
        assertNull(result.episode)
    }

    @Test fun titleWithoutEpisodeStaysUnchanged() {
        val result = CinematicTitleParts.parse("حكاية", true)
        assertEquals("حكاية", result.title)
        assertNull(result.episode)
    }
}
