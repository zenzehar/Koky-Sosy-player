package app.sokyiptv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class VlcSeekClockTest {
    @Test fun rapidForwardTapsAccumulateAgainstLogicalTarget() {
        val clock = VlcSeekClock()
        var displayed = 40_000L
        displayed = clock.requested(TvSeekPolicy.tap(displayed, 240_000L, SeekDirection.FORWARD), 1_000L)
        assertEquals(50_000L, displayed)
        assertEquals(50_000L, clock.reconcile(40_500L, 1_500L))
        displayed = clock.requested(TvSeekPolicy.tap(displayed, 240_000L, SeekDirection.FORWARD), 1_650L)
        assertEquals(60_000L, displayed)
        assertEquals(60_000L, clock.reconcile(49_000L, 1_900L))
        assertEquals(60_600L, clock.reconcile(60_600L, 2_300L))
    }

    @Test fun backwardTapsDoNotJumpForwardToOldNativeTime() {
        val clock = VlcSeekClock()
        val first = clock.requested(90_000L, 2_000L)
        assertEquals(first, clock.reconcile(100_000L, 2_200L))
        assertEquals(80_000L, clock.requested(first - 10_000L, 2_300L))
        assertEquals(80_000L, clock.reconcile(91_000L, 2_600L))
    }

    @Test fun timeoutAllowsNativeClockToRecoverFromFailedSeek() {
        val clock = VlcSeekClock()
        clock.requested(100_000L, 5_000L)
        assertEquals(100_000L, clock.reconcile(40_000L, 6_000L))
        assertEquals(43_000L, clock.reconcile(43_000L, 8_000L))
        assertEquals(44_000L, clock.reconcile(44_000L, 8_100L))
    }
}
