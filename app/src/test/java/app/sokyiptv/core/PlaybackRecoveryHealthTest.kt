package app.sokyiptv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRecoveryHealthTest {
    private fun tick(previous: Int, delta: Long = 500L, playing: Boolean = true,
        paused: Boolean = false, suspended: Boolean = false, seeking: Boolean = false,
        sinceAdvance: Long = 500L
    ) = PlaybackRecoveryHealth.accumulate(
        previous, delta, sinceAdvance, playing, paused, false, suspended, false, seeking)

    @Test fun sparseRealClockAdvancesClearRecovery() {
        var samples = tick(0)
        assertFalse(PlaybackRecoveryHealth.confirmed(samples))
        samples = tick(samples, delta = 0L, sinceAdvance = 500L)
        assertEquals(1, samples) // TV native time updates can be sparse.
        samples = tick(samples)
        assertFalse(PlaybackRecoveryHealth.confirmed(samples))
        samples = tick(samples, delta = 0L, sinceAdvance = 1_000L)
        assertEquals(2, samples)
        samples = tick(samples)
        assertTrue(PlaybackRecoveryHealth.confirmed(samples))
        assertEquals(3, tick(samples))
    }

    @Test fun staleClockAndSeekJumpNeverCountAsRecoveredVideo() {
        assertEquals(0, tick(3, 0L, sinceAdvance = 2_501L))
        assertEquals(0, tick(3, 20_000L))
        assertEquals(0, tick(3, 500L, playing = false))
        assertEquals(0, tick(3, 500L, paused = true))
        assertEquals(0, tick(3, 500L, suspended = true))
        assertEquals(0, tick(3, 500L, seeking = true))
    }

    @Test fun secondBasedTimersUseRealDeadlines() {
        assertEquals(3, PlaybackRetryClock.remainingSeconds(6_000L, 3_001L))
        assertEquals(1, PlaybackRetryClock.remainingSeconds(6_000L, 5_999L))
        assertEquals(0, PlaybackRetryClock.remainingSeconds(6_000L, 6_000L))
        assertEquals(0, PlaybackRetryClock.remainingSeconds(6_000L, 6_500L))
    }
}
