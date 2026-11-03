package app.sokyiptv.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRecoveryPolicyTest {
    @Test fun exactlyFiveAutomaticAttempts() {
        (0..4).forEach { assertTrue(PlaybackRecoveryPolicy.shouldRetry(it, false)) }
        assertFalse(PlaybackRecoveryPolicy.shouldRetry(5, false))
        assertFalse(PlaybackRecoveryPolicy.shouldRetry(6, false))
        assertFalse(PlaybackRecoveryPolicy.shouldRetry(0, true))
    }
    @Test fun onlyExplicitSeriesAutoSkip() {
        assertFalse(PlaybackRecoveryPolicy.canAdvanceAfterExhaustion(5, false, true, true))
        assertFalse(PlaybackRecoveryPolicy.canAdvanceAfterExhaustion(4, true, true, true))
        assertFalse(PlaybackRecoveryPolicy.canAdvanceAfterExhaustion(5, true, false, true))
        assertFalse(PlaybackRecoveryPolicy.canAdvanceAfterExhaustion(5, true, true, false))
        assertTrue(PlaybackRecoveryPolicy.canAdvanceAfterExhaustion(5, true, true, true))
    }
    @Test fun boundedDelay() {
        assertEquals(3, PlaybackRecoveryPolicy.retryDelaySeconds(3))
        assertEquals(2, PlaybackRecoveryPolicy.retryDelaySeconds(0))
        assertEquals(10, PlaybackRecoveryPolicy.retryDelaySeconds(99))
    }
}
