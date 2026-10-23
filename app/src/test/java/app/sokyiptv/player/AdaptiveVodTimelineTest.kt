package app.sokyiptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveVodTimelineTest {
    @Test fun goodDurationRemainsStableUntilNearEnd() {
        val scale = AdaptiveVodTimeline.scale(7_200_000L, 2_000_000L)
        assertEquals(7_200_000L, scale.maximumMs)
        assertFalse(scale.approximate)
    }
    @Test fun nearingDeclaredEndDoesNotExpandSeekbar() {
        val scale = AdaptiveVodTimeline.scale(3_600_000L, 3_200_000L)
        assertEquals(3_600_000L, scale.maximumMs)
        assertFalse(scale.approximate)
    }
    @Test fun beyondDeclaredEndBarStaysFullWithoutExpanding() {
        val scale = AdaptiveVodTimeline.scale(3_600_000L, 5_400_000L)
        assertEquals(3_600_000L, scale.maximumMs)
        assertFalse(scale.approximate)
        assertEquals(1f, AdaptiveVodTimeline.fraction(5_400_000L, scale))
    }
    @Test fun draggingNearEndDoesNotExpandSlider() {
        val scale = AdaptiveVodTimeline.scale(3_600_000L, 500_000L, 3_400_000L)
        assertFalse(scale.approximate)
        assertEquals(3_600_000L, scale.maximumMs)
    }
    @Test fun unknownLengthGetsSafeShortHorizon() {
        val scale = AdaptiveVodTimeline.scale(0L, 0L)
        assertFalse(scale.approximate)
        assertEquals(60_000L, scale.maximumMs)
        assertEquals(0f, AdaptiveVodTimeline.fraction(0L, scale))
    }
}
class PostSeekPlaybackGateTest {
    @Test fun staysPendingThroughSeekBufferingAndStartsHideTimerOnlyAfterPlaybackMoves() {
        val gate = PostSeekPlaybackGate()
        gate.requested(1_000L)
        assertTrue(gate.waiting)
        assertFalse(gate.observe(2_000L, 120_000L, true, true))
        assertFalse(gate.observe(2_500L, 130_000L, false, false))
        assertFalse(gate.observe(3_000L, 130_100L, true, false))
        assertTrue(gate.observe(3_500L, 130_600L, true, false))
        assertFalse(gate.waiting)
    }
    @Test fun shortOldClockSamplesDoNotDismissControls() {
        val gate = PostSeekPlaybackGate()
        gate.requested(5_000L)
        assertFalse(gate.observe(5_200L, 900_000L, true, false))
        assertFalse(gate.observe(5_900L, 900_100L, true, false))
        assertTrue(gate.waiting)
        assertTrue(gate.observe(6_400L, 900_600L, true, false))
    }
    @Test fun ordinaryBufferingWithoutSeekDoesNotBecomeSeekBuffering() {
        val gate = PostSeekPlaybackGate()
        assertFalse(gate.observe(10_000L, 200_000L, false, true))
        assertFalse(gate.waiting)
    }
    @Test fun secondSeekResetsWaitingCountdown() {
        val gate = PostSeekPlaybackGate()
        gate.requested(1_000L)
        gate.observe(2_000L, 10_000L, true, false)
        gate.requested(2_100L)
        assertFalse(gate.observe(2_300L, 99_000L, true, false))
        assertTrue(gate.waiting)
    }
}
