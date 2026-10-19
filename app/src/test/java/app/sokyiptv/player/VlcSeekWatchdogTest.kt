package app.sokyiptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VlcSeekWatchdogTest {
    @Test fun nativeSeekJumpAloneIsNotPlaybackProgress() {
        val w = VlcSeekWatchdog()
        w.begin(100)
        assertEquals(VlcSeekWatchdog.Action.NONE, w.observe(200, 10_000, true))
        assertEquals(VlcSeekWatchdog.Action.NONE, w.observe(300, 180_000, true))
        assertEquals(VlcSeekWatchdog.Action.NUDGE, w.observe(7_200, 180_000, true))
        assertEquals(VlcSeekWatchdog.Action.RESTART, w.observe(17_200, 180_000, true))
        assertTrue(w.isIdle)
        assertEquals(VlcSeekWatchdog.Action.NONE, w.observe(30_000, 180_000, true))
    }

    @Test fun advancingPlaybackClearsRecovery() {
        val w = VlcSeekWatchdog()
        w.begin(1_000)
        w.observe(1_500, 30_000, true)
        w.observe(2_000, 30_550, true)
        assertEquals(VlcSeekWatchdog.Action.NONE, w.observe(2_500, 31_050, true))
        assertTrue(w.isIdle)
    }

    @Test fun deliberatePauseDoesNotTriggerRecovery() {
        val w = VlcSeekWatchdog()
        w.begin(1_000)
        w.observe(2_000, 90_000, true)
        assertEquals(VlcSeekWatchdog.Action.NONE, w.observe(30_000, 90_000, false))
        assertEquals(VlcSeekWatchdog.Action.NONE, w.observe(30_200, 90_000, true))
        assertEquals(VlcSeekWatchdog.Action.NONE, w.observe(36_999, 90_000, true))
        assertEquals(VlcSeekWatchdog.Action.NUDGE, w.observe(37_100, 90_000, true))
    }

    @Test fun networkBufferingDoesNotTriggerSeekRecovery() {
        val watchdog = VlcSeekWatchdog()
        watchdog.begin(1000)
        assertEquals(VlcSeekWatchdog.Action.NONE, watchdog.observe(2000, 90_000, true))
        // Caller correctly reports shouldBePlaying=false during native Buffering events.
        assertEquals(VlcSeekWatchdog.Action.NONE, watchdog.observe(20_000, 90_000, false))
        assertEquals(VlcSeekWatchdog.Action.NONE, watchdog.observe(20_100, 90_000, true))
        assertEquals(VlcSeekWatchdog.Action.NONE, watchdog.observe(26_800, 90_000, true))
    }

    @Test fun repeatedSeekReplacesOldWatchAndRecoveryIsBounded() {
        val w = VlcSeekWatchdog()
        w.begin(0)
        w.observe(100, 1_000, true)
        w.begin(6_000)
        w.observe(6_100, 500_000, true)
        assertEquals(VlcSeekWatchdog.Action.NONE, w.observe(12_000, 500_000, true))
        assertEquals(VlcSeekWatchdog.Action.NUDGE, w.observe(13_200, 500_000, true))
        assertEquals(VlcSeekWatchdog.Action.RESTART, w.observe(23_200, 500_000, true))
        assertEquals(VlcSeekWatchdog.Action.NONE, w.observe(40_000, 500_000, true))
    }
}
