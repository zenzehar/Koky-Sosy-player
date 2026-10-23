package app.sokyiptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardedSeekFallbackTest {
    private val oneHour = 3_600_000L

    @Test fun neverCorrectsNearOrBeyondDeclaredEndButDoesNotClampManualTarget() {
        val fallback = GuardedSeekFallback()
        assertEquals(3_150_000L, GuardedSeekFallback.safeUpperMs(oneHour))
        for (target in listOf(3_150_000L, 3_500_000L, oneHour, 4_800_000L)) {
            assertFalse(fallback.requested(target, oneHour, 1_000_000L, 1_000L, false))
            assertNull(fallback.observe(target + 20_000L, 4_000L, true, false, oneHour))
        }
        assertFalse(fallback.disabledForSession)
    }

    @Test fun neverCorrectsWhenIndexedAvailableOrDurationUnknown() {
        val f = GuardedSeekFallback()
        assertFalse(f.requested(1_800_000L, oneHour, 100_000L, 1_000L, true))
        assertFalse(f.requested(1_800_000L, 0L, 100_000L, 1_000L, false))
        assertFalse(f.requested(1_800_000L, 240_000L, 100_000L, 1_000L, false))
    }

    @Test fun tenSecondDpadTapNeverGetsDelayedAutoCorrection() {
        val f = GuardedSeekFallback()
        assertFalse(f.requested(1_810_000L, oneHour, 1_800_000L, 1_000L, false))
        assertNull(f.observe(1_825_000L, 3_000L, true, false, oneHour))
    }

    @Test fun oneSmallRetryAfterTwoStableNativeSamplesAndNoFurtherRetry() {
        val f = GuardedSeekFallback()
        assertTrue(f.requested(1_800_000L, oneHour, 600_000L, 1_000L, false))
        assertNull(f.observe(1_818_000L, 2_000L, true, false, oneHour))
        assertNull(f.observe(1_818_300L, 3_000L, true, true, oneHour))
        assertNull(f.observe(1_818_500L, 3_200L, true, false, oneHour))
        val decision = f.observe(1_819_000L, 3_700L, true, false, oneHour)
        assertTrue(decision is GuardedSeekFallback.Decision.RetryAt)
        val retry = decision as GuardedSeekFallback.Decision.RetryAt
        assertEquals(1_800_000L, retry.intendedMs)
        assertTrue(retry.commandMs in 1_788_000L..1_800_000L)
        assertNull(f.observe(1_802_000L, 4_300L, true, false, oneHour))
        assertNull(f.observe(1_803_000L, 5_600L, true, false, oneHour))
        val finish = f.observe(1_803_500L, 6_100L, true, false, oneHour)
        assertTrue(finish is GuardedSeekFallback.Decision.Finished)
        assertTrue((finish as GuardedSeekFallback.Decision.Finished).succeeded)
        assertNull(f.observe(1_805_000L, 7_000L, true, false, oneHour))
    }

    @Test fun discontinuousOrBufferedClockCannotTriggerRetry() {
        val f = GuardedSeekFallback()
        assertTrue(f.requested(1_800_000L, oneHour, 600_000L, 1_000L, false))
        assertNull(f.observe(1_840_000L, 3_000L, false, true, oneHour))
        assertNull(f.observe(1_840_000L, 3_500L, true, false, oneHour))
        assertNull(f.observe(1_900_000L, 4_000L, true, false, oneHour))
        assertFalse(f.disabledForSession)
    }

    @Test fun hugeInitialMissDisablesFurtherCorrections() {
        val f = GuardedSeekFallback()
        assertTrue(f.requested(1_800_000L, oneHour, 600_000L, 1_000L, false))
        assertNull(f.observe(2_300_000L, 3_000L, true, false, oneHour))
        val finish = f.observe(2_300_500L, 3_500L, true, false, oneHour)
        assertTrue(finish is GuardedSeekFallback.Decision.Finished)
        assertFalse((finish as GuardedSeekFallback.Decision.Finished).succeeded)
        assertTrue(f.disabledForSession)
        assertFalse(f.requested(1_500_000L, oneHour, 500_000L, 4_000L, false))
    }

    @Test fun retryCannotCrossDeclaredEndSafetyBoundary() {
        val f = GuardedSeekFallback()
        assertTrue(f.requested(3_140_000L, oneHour, 2_800_000L, 1_000L, false))
        assertNull(f.observe(3_118_000L, 3_000L, true, false, oneHour))
        val result = f.observe(3_118_500L, 3_500L, true, false, oneHour)
        assertTrue(result is GuardedSeekFallback.Decision.Finished)
        assertFalse((result as GuardedSeekFallback.Decision.Finished).succeeded)
    }

    @Test fun changedProviderLengthCancelsCorrectionAndNewUserSeekReplacesOld() {
        val f = GuardedSeekFallback()
        assertTrue(f.requested(1_800_000L, oneHour, 600_000L, 1_000L, false))
        assertNull(f.observe(1_820_000L, 4_000L, true, false, oneHour - 5_000L))
        assertNull(f.observe(1_821_000L, 4_500L, true, false, oneHour))
        assertTrue(f.requested(900_000L, oneHour, 500_000L, 5_000L, false))
        f.cancel()
        assertNull(f.observe(930_000L, 8_000L, true, false, oneHour))
    }

    @Test fun oldPreSeekClockAdvancingDoesNotTriggerWrongCorrection() {
        val f = GuardedSeekFallback()
        assertTrue(f.requested(1_800_000L, oneHour, 400_000L, 1_000L, false))
        assertNull(f.observe(400_000L, 3_000L, true, false, oneHour))
        assertNull(f.observe(400_500L, 3_500L, true, false, oneHour))
        assertFalse(f.disabledForSession)
    }
}
