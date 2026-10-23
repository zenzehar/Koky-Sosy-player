package app.sokyiptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VlcNativeSeekCorrectionTest {
    @Test fun understatedDurationCanTriggerSingleProportionalRetry() {
        val monitor = VlcNativeSeekCorrection()
        monitor.requested(600_000L, 100_000L, 1_000L, declaredDurationMs = 2_000_000L)
        assertNull(monitor.observe(1_200_000L, 2_000L, playing = true, buffering = false))
        assertNull(monitor.observe(1_200_300L, 2_800L, playing = true, buffering = false))
        val decision = monitor.observe(1_200_800L, 3_300L, playing = true, buffering = false)
        assertTrue(decision is VlcNativeSeekCorrection.Decision.RetryAt)
        val correction = decision as VlcNativeSeekCorrection.Decision.RetryAt
        assertEquals(600_000L, correction.intendedMs)
        assertTrue(correction.commandTimeMs in 299_000L..302_000L)
        assertNull(monitor.observe(600_000L, 4_000L, playing = true, buffering = false))
        assertNull(monitor.observe(600_500L, 5_200L, playing = true, buffering = false))
        assertNull(monitor.observe(601_000L, 5_700L, playing = true, buffering = false))
    }

    @Test fun nearCorrectTimestampIsNeverCorrected() {
        val monitor = VlcNativeSeekCorrection()
        monitor.requested(60_000L, 20_000L, 1_000L, declaredDurationMs = 2_000_000L)
        assertNull(monitor.observe(62_300L, 2_800L, true, false))
        assertNull(monitor.observe(62_800L, 3_300L, true, false))
        assertNull(monitor.observe(65_000L, 5_000L, true, false))
    }

    @Test fun ignoresOldNativeClockAndBuffering() {
        val monitor = VlcNativeSeekCorrection()
        monitor.requested(900_000L, 200_000L, 1_000L, declaredDurationMs = 2_000_000L)
        assertNull(monitor.observe(200_000L, 2_900L, true, true))
        assertNull(monitor.observe(200_200L, 3_300L, true, false))
        assertNull(monitor.observe(200_600L, 3_900L, true, false))
        assertNull(monitor.observe(200_800L, 4_500L, false, false))
        val eventual = monitor.observe(201_000L, 9_000L, true, false)
        assertTrue(eventual is VlcNativeSeekCorrection.Decision.Unreliable)
    }

    @Test fun secondWrongLandingNeverCausesEndlessSeekLoop() {
        val monitor = VlcNativeSeekCorrection()
        monitor.requested(600_000L, 100_000L, 1_000L, declaredDurationMs = 2_000_000L)
        monitor.observe(1_200_000L, 2_800L, true, false)
        assertTrue(monitor.observe(1_200_500L, 3_300L, true, false)
            is VlcNativeSeekCorrection.Decision.RetryAt)
        assertNull(monitor.observe(1_100_000L, 5_100L, true, false))
        val result = monitor.observe(1_100_600L, 5_600L, true, false)
        assertTrue(result is VlcNativeSeekCorrection.Decision.Unreliable)
        assertNull(monitor.observe(1_101_000L, 7_000L, true, false))
    }

    @Test fun seekingBeyondDeclaredDurationNeverTriggersRetry() {
        val monitor = VlcNativeSeekCorrection()
        monitor.requested(90_000L, 10_000L, 1_000L, declaredDurationMs = 60_000L)
        assertNull(monitor.observe(20_000L, 3_000L, true, false))
    }

    @Test fun landedBeyondDeclaredEndStillCorrectsWhenUserSelectedWithinEnd() {
        val monitor = VlcNativeSeekCorrection()
        // Declared 60s. The user asked for 50s. VLC landed at 75-76s.
        monitor.requested(50_000L, 10_000L, 1_000L, declaredDurationMs = 60_000L)
        assertNull(monitor.observe(75_000L, 2_800L, true, false))
        val decision = monitor.observe(76_000L, 3_300L, true, false)
        assertTrue(decision is VlcNativeSeekCorrection.Decision.RetryAt)
        assertEquals(50_000L, (decision as VlcNativeSeekCorrection.Decision.RetryAt).intendedMs)
    }

    @Test fun compensatingCommandBeyondDeclaredEndDoesNotDisableEligibleRequest() {
        val monitor = VlcNativeSeekCorrection()
        // User's original target is <=60s. The corrective command may exceed 60s.
        monitor.requested(40_000L, 0L, 1_000L, declaredDurationMs = 60_000L)
        assertNull(monitor.observe(5_000L, 2_800L, true, false))
        val decision = monitor.observe(5_500L, 3_300L, true, false)
        assertTrue(decision is VlcNativeSeekCorrection.Decision.RetryAt)
        assertTrue((decision as VlcNativeSeekCorrection.Decision.RetryAt).commandTimeMs > 60_000L)
    }

    @Test fun exactDeclaredEndIsEligibleForCorrection() {
        val monitor = VlcNativeSeekCorrection()
        monitor.requested(60_000L, 10_000L, 1_000L, declaredDurationMs = 60_000L)
        assertNull(monitor.observe(90_000L, 2_800L, true, false))
        assertTrue(monitor.observe(90_500L, 3_300L, true, false)
            is VlcNativeSeekCorrection.Decision.RetryAt)
    }

    @Test fun unknownDurationAlsoDisablesCorrection() {
        val monitor = VlcNativeSeekCorrection()
        monitor.requested(90_000L, 10_000L, 1_000L, declaredDurationMs = 0L)
        assertNull(monitor.observe(20_000L, 3_000L, true, false))
    }

    @Test fun newUserRequestCancelsOldCorrection() {
        val monitor = VlcNativeSeekCorrection()
        monitor.requested(700_000L, 50_000L, 1_000L, declaredDurationMs = 2_000_000L)
        monitor.observe(1_400_000L, 2_800L, true, false)
        monitor.requested(90_000L, 80_000L, 3_000L, declaredDurationMs = 2_000_000L)
        assertNull(monitor.observe(90_000L, 4_200L, true, false))
        assertNull(monitor.observe(92_000L, 5_000L, true, false))
        assertNull(monitor.observe(92_500L, 5_500L, true, false))
    }
}
