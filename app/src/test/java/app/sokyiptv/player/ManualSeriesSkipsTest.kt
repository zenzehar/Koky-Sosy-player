package app.sokyiptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualSeriesSkipsTest {
    @Test fun emptyMarksNeverShowPrompts() {
        val initial = ManualSeriesSkips()
        assertFalse(initial.showIntro(0L))
        assertFalse(initial.showCredits(2_000_000L))
    }

    @Test fun skipIntroAppearsFromStartForExactlyTenSecondsAndTargetsEndMark() {
        val marks = ManualSeriesSkips().mark(SkipMark.INTRO_END, 98_000L)
        assertTrue(marks.showIntro(0L))
        assertTrue(marks.showIntro(1L))
        assertTrue(marks.showIntro(9_999L))
        assertFalse(marks.showIntro(10_000L))
        assertFalse(marks.showIntro(50_000L))
        assertFalse(marks.showIntro(-1L))
        assertEquals(98_000L, marks.introEndMs)
        assertFalse(marks.clearIntro().showIntro(0L))
    }

    @Test fun introNeverOffersToSeekBackwardsIfIntroEndsBeforeTenSeconds() {
        val marks = ManualSeriesSkips(introEndMs = 5_000L)
        assertTrue(marks.showIntro(4_999L))
        assertFalse(marks.showIntro(5_000L))
        assertFalse(marks.showIntro(9_000L))
        assertFalse(ManualSeriesSkips(introEndMs = 0L).showIntro(0L))
    }

    @Test fun creditsPromptBeginsExactlyAtStartMarkAndLastsUntilEpisodeEnds() {
        val marks = ManualSeriesSkips().mark(SkipMark.CREDITS_START, 2_000_000L)
        assertFalse(marks.showCredits(1_999_999L))
        assertTrue(marks.showCredits(2_000_000L))
        assertTrue(marks.showCredits(2_100_000L))
        assertTrue(marks.showCredits(2_250_000L))
        assertFalse(marks.clearCredits().showCredits(2_050_000L))
    }

    @Test fun eachMarkIsIndependentAndClearAllRemovesBoth() {
        val both = ManualSeriesSkips(introEndMs = 100_000L, creditsStartMs = 2_000_000L)
        assertTrue(both.clearIntro().creditsValid())
        assertTrue(both.clearCredits().introValid())
        assertFalse(both.clearIntro().clearCredits().showIntro(0L))
        assertFalse(both.clearIntro().clearCredits().showCredits(2_100_000L))
    }

    @Test fun markersUseCorrectDistinctNames() {
        val marks = ManualSeriesSkips(introEndMs = 87_000L, creditsStartMs = 2_140_000L)
        assertTrue(marks.display().contains("Intro ends: 1:27"))
        assertTrue(marks.display().contains("Credits/outro starts: 35:40"))
    }
}
