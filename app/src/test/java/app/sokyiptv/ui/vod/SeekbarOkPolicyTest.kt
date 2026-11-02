package app.sokyiptv.ui.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekbarOkPolicyTest {
    private val SEEK = SeekbarOkPolicy.Action.COMMIT_SEEK
    private val TOGGLE = SeekbarOkPolicy.Action.TOGGLE_PLAYBACK

    @Test fun uncommittedMovedCursorNeedsExactlyOneSeek() {
        assertEquals(SEEK, SeekbarOkPolicy.action(
            120_000L, 30_000L, true, false, 0L, 4_000L))
        assertEquals(SEEK, SeekbarOkPolicy.action(
            120_000L, 30_000L, true, true, 1_000L, 1_800L))
    }

    @Test fun okPausesWhenNativeDecoderIsStillBufferingAfterSubmission() {
        // The old code repeatedly reissued this seek rather than pause playback.
        assertEquals(TOGGLE, SeekbarOkPolicy.action(
            120_000L, 30_000L, false, true, 1_000L, 1_800L))
        assertEquals(TOGGLE, SeekbarOkPolicy.action(
            120_000L, 120_000L, false, true, 1_000L, 1_500L))
        assertEquals(TOGGLE, SeekbarOkPolicy.action(
            120_000L, 30_000L, false, false, 1_000L, 3_000L))
    }

    @Test fun recentlySubmittedSeekCanBePausedWithoutWaitingForClockConfirmation() {
        assertEquals(TOGGLE, SeekbarOkPolicy.action(
            120_000L, 120_000L, false, false, 1_000L, 1_100L))
    }

    @Test fun normalOkTogglesWithNoSeekOrAfterNaturalCatchUp() {
        assertEquals(TOGGLE, SeekbarOkPolicy.action(
            -1L, 88_000L, false, false, 0L, 3_000L))
        assertEquals(TOGGLE, SeekbarOkPolicy.action(
            120_000L, 121_000L, false, false, 1_000L, 3_000L))
        assertEquals(TOGGLE, SeekbarOkPolicy.action(
            120_000L, 121_000L, true, false, 1_000L, 3_000L))
        assertTrue(SeekbarOkPolicy.caughtUp(
            120_000L, 121_000L, false, 1_000L, 3_000L))
    }

    @Test fun bufferingNeverPretendsTheSubmittedCursorHasSettled() {
        assertFalse(SeekbarOkPolicy.caughtUp(
            120_000L, 120_000L, true, 1_000L, 3_000L))
        assertFalse(SeekbarOkPolicy.caughtUp(
            120_000L, 120_000L, false, 1_000L, 1_500L))
    }
}
