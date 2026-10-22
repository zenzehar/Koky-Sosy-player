package app.sokyiptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvSeekPolicyTest {
    @Test fun tapsSeekExactlyTenSecondsAndClampToBounds() {
        assertEquals(20_000L, TvSeekPolicy.tap(10_000L, 100_000L, SeekDirection.FORWARD))
        assertEquals(0L, TvSeekPolicy.tap(5_000L, 100_000L, SeekDirection.BACKWARD))
        assertEquals(100_000L, TvSeekPolicy.tap(95_000L, 100_000L, SeekDirection.FORWARD))
    }
    @Test fun unknownOrIncorrectDurationDoesNotChangeTimeStep() {
        // A bad 12-minute provider length cannot transform a +10s request
        // into a percentage jump or clamp a 45-minute playback position.
        assertEquals(2_710_000L, TvSeekPolicy.tap(2_700_000L, null, SeekDirection.FORWARD))
        assertEquals(2_690_000L, TvSeekPolicy.tap(2_700_000L, null, SeekDirection.BACKWARD))
        assertEquals(10_000L, TvSeekPolicy.tap(0L, null, SeekDirection.FORWARD))
        assertEquals(0L, TvSeekPolicy.tap(5_000L, null, SeekDirection.BACKWARD))
    }

    @Test fun repeatedTimeBasedTapsRemainTenSecondsApart() {
        val a = TvSeekPolicy.tap(90_000L, null, SeekDirection.FORWARD)
        val b = TvSeekPolicy.tap(a, null, SeekDirection.FORWARD)
        val c = TvSeekPolicy.tap(b, null, SeekDirection.BACKWARD)
        assertEquals(100_000L, a)
        assertEquals(110_000L, b)
        assertEquals(100_000L, c)
    }

    @Test fun heldPreviewStartsOnlyAfterLongPressThreshold() {
        assertEquals(50_000L, TvSeekPolicy.heldPreview(50_000L, 500L, SeekDirection.FORWARD, 180_000L))
    }
    @Test fun heldPreviewAcceleratesWithHoldTime() {
        val p1 = TvSeekPolicy.heldPreview(0L, 1_200L, SeekDirection.FORWARD, 10_000_000L)
        val p2 = TvSeekPolicy.heldPreview(0L, 3_000L, SeekDirection.FORWARD, 10_000_000L)
        val p3 = TvSeekPolicy.heldPreview(0L, 5_500L, SeekDirection.FORWARD, 10_000_000L)
        assertTrue(p1 < p2 && p2 < p3)
        assertTrue(p3 - p2 > p2 - p1)
    }
    @Test fun longHoldUsesThreefoldAccelerationWithoutChangingTapStep() {
        assertEquals(10_000L, TvSeekPolicy.tap(0L, 2_000_000L, SeekDirection.FORWARD))
        assertEquals(602_400L,
            TvSeekPolicy.heldPreview(0L, 5_000L, SeekDirection.FORWARD, 2_000_000L))
    }

    @Test fun heldPreviewClampsToDuration() {
        assertEquals(90_000L, TvSeekPolicy.heldPreview(5_000L, 5000L, SeekDirection.FORWARD, 90_000L))
        assertEquals(0L, TvSeekPolicy.heldPreview(5_000L, 5000L, SeekDirection.BACKWARD, 90_000L))
    }
}
