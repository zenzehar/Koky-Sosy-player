package app.sokyiptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearEndSeekWindowTest {
    @Test fun normalMovieUsesNormalScaleBeforeReportedEnd() {
        val w = NearEndSeekWindow()
        val a = w.window(3_600_000L, 600_000L)
        assertFalse(a.zoomed)
        assertEquals(0L, a.fromMs)
        assertEquals(3_600_000L, a.toMs)
    }

    @Test fun nearIncorrectEndUsesLocalTenMinuteRulerAndNeverAssumesEnd() {
        val w = NearEndSeekWindow()
        val a = w.window(3_600_000L, 3_200_000L)
        assertTrue(a.zoomed)
        assertEquals(600_000L, a.toMs - a.fromMs)
        val b = w.window(3_600_000L, 3_205_000L)
        // No moving/oscillating UI scale with each native clock tick.
        assertEquals(a.fromMs, b.fromMs)
        assertEquals(a.toMs, b.toMs)
        assertTrue(b.fraction(3_205_000L) > a.fraction(3_200_000L))
    }

    @Test fun declaredEndIsNotHardSeekLimit() {
        val w = NearEndSeekWindow()
        val a = w.window(3_600_000L, 4_800_000L)
        assertTrue(a.zoomed)
        assertTrue(a.toMs > 3_600_000L)
        assertTrue(a.fraction(4_800_000L) in 0f..1f)
    }

    @Test fun largeUserSeekRelocatesLocalTimeRuler() {
        val w = NearEndSeekWindow()
        val first = w.window(3_600_000L, 3_200_000L)
        val second = w.window(3_600_000L, 3_200_000L, 4_800_000L)
        assertTrue(second.fromMs > first.fromMs)
    }

    @Test fun rewindingWellBeforeEndReturnsToOrdinaryScale() {
        val w = NearEndSeekWindow()
        assertTrue(w.window(3_600_000L, 3_200_000L).zoomed)
        assertFalse(w.window(3_600_000L, 300_000L).zoomed)
    }

    @Test fun confirmedNativeLandingWinsOverOptimisticRequest() {
        val clock = VlcSeekClock()
        assertEquals(5_400_000L, clock.requested(5_400_000L, 1_000L))
        // An unreliable demuxer lands at 3m, never silently show 90m.
        assertEquals(180_000L, clock.confirmNative(180_000L))
        assertEquals(180_500L, clock.reconcile(180_500L, 2_000L))
    }
}
