package app.sokyiptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipCuePolicyTest {
    @Test fun chapterRanges_areEpisodeSpecificAndNotFixed() {
        val cues = SkipCuePolicy.fromChapters(listOf(
            VideoChapter(50_000, 83_000, "Opening Theme"),
            VideoChapter(2_500_000, 260_000, "End Credits"),
        ), 2_900_000)
        assertEquals(50_000L, cues.introStartMs)
        assertEquals(133_000L, cues.introEndMs)
        assertEquals(2_500_000L, cues.creditsStartMs)
        assertTrue(SkipCuePolicy.showIntro(60_000, cues))
        assertFalse(SkipCuePolicy.showIntro(135_000, cues))
        assertTrue(SkipCuePolicy.showCredits(2_700_000, 2_900_000, cues))
    }
    @Test fun noMetadataNeverInventsIntro() {
        assertEquals(SkipCues(), SkipCuePolicy.fromChapters(emptyList(), 3_500_000))
        assertFalse(SkipCuePolicy.showIntro(40_000, SkipCues()))
    }
    @Test fun arbitraryDialogueAndLongOpeningAreRejected() {
        val cues = SkipCuePolicy.fromChapters(listOf(
            VideoChapter(0, 3_000_000, "Opening Chapter"),
            VideoChapter(2_700_000, 120_000, "Chapter 15"),
        ), 3_600_000)
        assertFalse(cues.hasIntro())
        assertFalse(cues.hasCredits())
    }
    @Test fun truncatedOrNoIntroDoNotBorrowAnotherEpisodesMarkers() {
        val short = SkipCuePolicy.fromChapters(
            listOf(VideoChapter(13_000, 16_000, "Intro")), 1_800_000)
        val absent = SkipCuePolicy.fromChapters(
            listOf(VideoChapter(13_000, 16_000, "Chapter 1")), 1_800_000)
        assertTrue(short.hasIntro())
        assertFalse(absent.hasIntro())
    }
    @Test fun creditsBeforeMidpointAreRejected() {
        assertFalse(SkipCuePolicy.fromChapters(
            listOf(VideoChapter(90_000, 80_000, "End Credits")),
            3_000_000).hasCredits())
    }
}
