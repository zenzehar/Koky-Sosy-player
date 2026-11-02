package app.sokyiptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VlcNativeSeekCorrectionTest {
    @Test fun clearlyWrongLandingCanTriggerOneProportionalRetry() {
        val correction = VlcNativeSeekCorrection()
        correction.requested(600_000L, 100_000L, 1_000L, 2_000_000L)
        assertNull(correction.observe(1_200_000L, 2_000L, true, false))
        assertNull(correction.observe(1_200_300L, 2_800L, true, false))
        val outcome = correction.observe(1_200_800L, 3_300L, true, false)
        assertTrue(outcome is VlcNativeSeekCorrection.Decision.RetryAt)
        val retry = outcome as VlcNativeSeekCorrection.Decision.RetryAt
        assertEquals(600_000L, retry.intendedMs)
        assertTrue(retry.commandTimeMs in 299_000L..302_000L)
    }

    @Test fun accurateSeekWithinThreeSecondsNeverRetunes() {
        val correction = VlcNativeSeekCorrection()
        correction.requested(60_000L, 20_000L, 1_000L, 2_000_000L)
        assertNull(correction.observe(62_300L, 2_800L, true, false))
        assertNull(correction.observe(62_800L, 3_300L, true, false))
        assertNull(correction.observe(63_300L, 3_800L, true, false))
    }

    @Test fun threeSecondsIsFineMarginButLargerVerifiedErrorCanCorrect() {
        val within = VlcNativeSeekCorrection()
        within.requested(600_000L, 20_000L, 1_000L, 2_000_000L)
        assertNull(within.observe(603_000L, 2_800L, true, false))
        assertNull(within.observe(603_500L, 3_300L, true, false))
        val outside = VlcNativeSeekCorrection()
        outside.requested(600_000L, 20_000L, 1_000L, 2_000_000L)
        assertNull(outside.observe(604_000L, 2_800L, true, false))
        assertTrue(outside.observe(604_500L, 3_300L, true, false)
            is VlcNativeSeekCorrection.Decision.RetryAt)
    }

    @Test fun bufferingWallTimeMustNotForceAnotherSeek() {
        val correction = VlcNativeSeekCorrection()
        correction.requested(600_000L, 10_000L, 1_000L, 2_000_000L)
        assertNull(correction.observe(10_000L, 3_000L, false, true))
        assertNull(correction.observe(10_000L, 6_000L, false, true))
        assertNull(correction.observe(600_100L, 7_000L, true, false))
        assertNull(correction.observe(600_600L, 7_500L, true, false))
        assertNull(correction.observe(601_100L, 8_000L, true, false))
    }

    @Test fun wrongLandingStillCorrectsOnceAfterLongBuffering() {
        val correction = VlcNativeSeekCorrection()
        correction.requested(600_000L, 10_000L, 1_000L, 2_000_000L)
        assertNull(correction.observe(10_000L, 6_000L, false, true))
        assertNull(correction.observe(1_200_000L, 8_000L, true, false))
        val result = correction.observe(1_200_500L, 8_500L, true, false)
        assertTrue(result is VlcNativeSeekCorrection.Decision.RetryAt)
    }

    @Test fun pauseDoesNotCountAsPlaybackTimeOrTriggerAutomaticSeek() {
        val correction = VlcNativeSeekCorrection()
        correction.requested(500_000L, 20_000L, 1_000L, 2_000_000L)
        assertNull(correction.observe(20_000L, 4_000L, false, false))
        assertNull(correction.observe(500_000L, 10_000L, true, false))
        assertNull(correction.observe(500_500L, 10_500L, true, false))
    }

    @Test fun frozenClockNeverProofOfWrongLanding() {
        val correction = VlcNativeSeekCorrection()
        correction.requested(500_000L, 20_000L, 1_000L, 2_000_000L)
        assertNull(correction.observe(1_000_000L, 2_800L, true, false))
        assertNull(correction.observe(1_000_000L, 3_300L, true, false))
        assertNull(correction.observe(1_000_000L, 3_800L, true, false))
    }

    @Test fun oldOriginClockIsNeverAnAcceptedSeekLanding() {
        val correction = VlcNativeSeekCorrection()
        correction.requested(900_000L, 200_000L, 1_000L, 2_000_000L)
        assertNull(correction.observe(200_000L, 2_900L, true, true))
        assertNull(correction.observe(200_200L, 3_300L, true, false))
        assertNull(correction.observe(200_600L, 3_900L, true, false))
        assertNull(correction.observe(200_800L, 4_500L, false, false))
        assertNull(correction.observe(201_000L, 9_000L, true, false))
    }

    @Test fun secondWrongLandingNeverTriggersAnotherCorrection() {
        val correction = VlcNativeSeekCorrection()
        correction.requested(600_000L, 100_000L, 1_000L, 2_000_000L)
        assertNull(correction.observe(1_200_000L, 2_800L, true, false))
        assertTrue(correction.observe(1_200_500L, 3_300L, true, false)
            is VlcNativeSeekCorrection.Decision.RetryAt)
        assertNull(correction.observe(1_100_000L, 5_100L, true, false))
        assertTrue(correction.observe(1_100_600L, 5_600L, true, false)
            is VlcNativeSeekCorrection.Decision.Unreliable)
        assertNull(correction.observe(1_101_000L, 7_000L, true, false))
    }

    @Test fun selectedBeyondDeclaredDurationNeverCorrects() {
        val correction = VlcNativeSeekCorrection()
        correction.requested(90_000L, 10_000L, 1_000L, 60_000L)
        assertNull(correction.observe(20_000L, 3_000L, true, false))
    }

    @Test fun landedBeyondDeclaredStillCanCorrectIfSelectedWithinDuration() {
        val correction = VlcNativeSeekCorrection()
        correction.requested(50_000L, 10_000L, 1_000L, 60_000L)
        assertNull(correction.observe(75_000L, 2_800L, true, false))
        val response = correction.observe(75_500L, 3_300L, true, false)
        assertTrue(response is VlcNativeSeekCorrection.Decision.RetryAt)
        assertEquals(50_000L, (response as VlcNativeSeekCorrection.Decision.RetryAt).intendedMs)
    }

    @Test fun compensatedCommandMayExceedDeclEndWhenOriginalSeekWasEligible() {
        val correction = VlcNativeSeekCorrection()
        correction.requested(40_000L, 0L, 1_000L, 60_000L)
        assertNull(correction.observe(5_000L, 2_800L, true, false))
        val result = correction.observe(5_500L, 3_300L, true, false)
        assertTrue(result is VlcNativeSeekCorrection.Decision.RetryAt)
        assertTrue((result as VlcNativeSeekCorrection.Decision.RetryAt).commandTimeMs > 60_000L)
    }

    @Test fun exactDeclaredEndIsEligibleButUnknownDurationIsNot() {
        val correction = VlcNativeSeekCorrection()
        correction.requested(60_000L, 10_000L, 1_000L, 60_000L)
        assertNull(correction.observe(90_000L, 2_800L, true, false))
        assertTrue(correction.observe(90_500L, 3_300L, true, false)
            is VlcNativeSeekCorrection.Decision.RetryAt)
        correction.requested(90_000L, 10_000L, 5_000L, 0L)
        assertNull(correction.observe(20_000L, 8_000L, true, false))
    }

    @Test fun freshUserSeekSupersedesPreviousPendingCorrection() {
        val correction = VlcNativeSeekCorrection()
        correction.requested(700_000L, 50_000L, 1_000L, 2_000_000L)
        correction.observe(1_400_000L, 2_800L, true, false)
        correction.requested(90_000L, 80_000L, 3_000L, 2_000_000L)
        assertNull(correction.observe(90_000L, 4_200L, true, false))
        assertNull(correction.observe(92_000L, 5_000L, true, false))
        assertNull(correction.observe(92_500L, 5_500L, true, false))
    }
}
