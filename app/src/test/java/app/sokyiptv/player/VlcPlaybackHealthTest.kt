package app.sokyiptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlcPlaybackHealthTest {
    @Test fun startupBufferingDoesNotCountAsRebuffer() {
        val h = VlcPlaybackHealth()
        h.onBuffering(10f, 0)
        assertEquals(VlcPlaybackHealth.State.STARTING, h.sample(1000, 0, false, false).state)
        h.onPlaying(2000)
        assertEquals(0, h.sample(2100, 500, true, false).interruptions)
    }
    @Test fun multipleEventsWithinOneRebufferCountOnce() {
        val h = VlcPlaybackHealth()
        h.onPlaying(0)
        h.onBuffering(10f, 2000)
        h.onBuffering(50f, 2100)
        val s = h.sample(3000, 1000, false, false)
        assertEquals(VlcPlaybackHealth.State.BUFFERING, s.state)
        assertEquals(1, s.interruptions)
        assertEquals(50, s.bufferPercent)
        h.onPlaying(4000)
        h.onBuffering(20f, 4500)
        assertEquals(2, h.sample(5500, 1000, false, false).interruptions)
    }
    @Test fun stoppedTimeWithoutBufferingIsDistinctFromNativeBuffering() {
        val h = VlcPlaybackHealth()
        h.onPlaying(0)
        h.sample(1000, 1000, true, false)
        assertEquals(VlcPlaybackHealth.State.STALLED, h.sample(9000, 1000, true, false).state)
        assertEquals(VlcPlaybackHealth.State.PAUSED, h.sample(10000, 1000, false, true).state)
        assertEquals(VlcPlaybackHealth.State.PLAYING, h.sample(11000, 2000, true, false).state)
        assertFalse(h.networkBuffering)
    }
    @Test fun nativeIsPlayingFalseStillShowsRealClockStallAfterTimeout() {
        val h = VlcPlaybackHealth()
        h.onPlaying(0L)
        assertEquals(VlcPlaybackHealth.State.PLAYING,
            h.sample(2_000L, 0L, false, false).state)
        assertEquals(VlcPlaybackHealth.State.STALLED,
            h.sample(9_500L, 0L, false, false).state)
        assertEquals(VlcPlaybackHealth.State.PAUSED,
            h.sample(10_000L, 0L, false, true).state)
        assertEquals(VlcPlaybackHealth.State.PLAYING,
            h.sample(10_500L, 900L, true, false).state)
    }

    @Test fun briefRebufferDoesNotFlashOverlay() {
        val h = VlcPlaybackHealth()
        h.onPlaying(0)
        h.onBuffering(15f, 1000)
        assertEquals(VlcPlaybackHealth.State.PLAYING, h.sample(1200, 1000, false, false).state)
        assertTrue(h.networkBuffering)
        h.onPlaying(1600)
        assertFalse(h.networkBuffering)
    }
}
