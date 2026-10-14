package app.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvSeekPolicyTest {
    @Test fun tapsSeekExactlyTenSecondsAndClampToBounds() {
        assertEquals(20_000L, TvSeekPolicy.tap(10_000L, 100_000L, SeekDirection.FORWARD))
        assertEquals(0L, TvSeekPolicy.tap(5_000L, 100_000L, SeekDirection.BACKWARD))
        assertEquals(100_000L, TvSeekPolicy.tap(95_000L, 100_000L, SeekDirection.FORWARD))
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
    @Test fun heldPreviewClampsToDuration() {
        assertEquals(90_000L, TvSeekPolicy.heldPreview(5_000L, 5000L, SeekDirection.FORWARD, 90_000L))
        assertEquals(0L, TvSeekPolicy.heldPreview(5_000L, 5000L, SeekDirection.BACKWARD, 90_000L))
    }
}
