package app.sokyiptv.ui.vod

import app.sokyiptv.player.ManualSeriesSkips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipCountdownPolicyTest {
    @Test fun countdownAdvancesOnlyWhilePlayingAndCompletesAtTenSeconds() {
        assertEquals(2_500L, SkipCountdownPolicy.advance(2_500L, 4_000L, false))
        assertEquals(6_500L, SkipCountdownPolicy.advance(2_500L, 4_000L, true))
        assertEquals(10_000L, SkipCountdownPolicy.advance(9_900L, 800L, true))
        assertEquals(0f, SkipCountdownPolicy.fraction(0L))
        assertEquals(0.5f, SkipCountdownPolicy.fraction(5_000L))
        assertEquals(1f, SkipCountdownPolicy.fraction(10_000L))
        assertEquals(10, SkipCountdownPolicy.remainingSeconds(0L))
        assertEquals(1, SkipCountdownPolicy.remainingSeconds(9_001L))
        assertEquals(0, SkipCountdownPolicy.remainingSeconds(10_000L))
    }

    @Test fun introNeverTargetsOutroEvenWithInconsistentSeriesMarkers() {
        val good = ManualSeriesSkips(introEndMs = 75_000L, creditsStartMs = 1_200_000L)
        assertEquals(75_000L, good.safeIntroTarget(1_500_000L))
        assertFalse(good.showCredits(75_000L, 1_500_000L))
        assertTrue(good.showCredits(1_200_000L, 1_500_000L))

        val bad = ManualSeriesSkips(introEndMs = 90_000L, creditsStartMs = 45_000L)
        assertNull(bad.safeIntroTarget(1_500_000L))
        assertFalse(bad.showCredits(90_000L, 1_500_000L))
        assertFalse(good.showCredits(1_200_000L, 900_000L))
        assertNull(good.safeIntroTarget(76_000L))
    }

    @Test fun creditsAreNotMistakenForIntroOrPreviousEpisodeProgress() {
        val marks = ManualSeriesSkips(introEndMs = 65_000L, creditsStartMs = 1_500_000L)
        assertTrue(marks.showIntro(0L))
        assertFalse(marks.showIntro(10_000L))
        assertFalse(marks.showCredits(65_000L, 1_800_000L))
        assertTrue(marks.showCredits(1_510_000L, 1_800_000L))
        assertFalse(marks.showCredits(1_510_000L, 1_000_000L))
    }
}
