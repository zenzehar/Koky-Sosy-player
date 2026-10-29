package app.sokyiptv.ui.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetflixColdStartPolicyTest {
    @Test fun coldFrameIsNotBlacklistedAfterInitialMiss() {
        assertTrue(NetflixColdStartPolicy.canTryFrame(0, 0L, 0L))
        assertFalse(NetflixColdStartPolicy.canTryFrame(1, 1_000L, 2_000L))
        assertTrue(NetflixColdStartPolicy.canTryFrame(1, 1_000L, 2_200L))
        assertFalse(NetflixColdStartPolicy.canTryFrame(2, 1_000L, 3_000L))
        assertTrue(NetflixColdStartPolicy.canTryFrame(2, 1_000L, 4_000L))
        assertTrue(NetflixColdStartPolicy.canTryFrame(3, 1_000L, 8_000L))
        assertFalse(NetflixColdStartPolicy.canTryFrame(4, 1_000L, 10_000L))
    }

    @Test fun fiveTransientFailuresNeverStopFirstPlayIndexing() {
        assertFalse(NetflixColdStartPolicy.shouldGiveUp(0, 5, 5_000L))
        assertFalse(NetflixColdStartPolicy.shouldGiveUp(0, 35, 50_000L))
        assertFalse(NetflixColdStartPolicy.shouldGiveUp(0, 36, 10_000L))
        assertTrue(NetflixColdStartPolicy.shouldGiveUp(0, 36, 50_000L))
        assertFalse(NetflixColdStartPolicy.shouldGiveUp(30, 36, 50_000L))
        assertTrue(NetflixColdStartPolicy.shouldGiveUp(30, 180, 50_000L))
    }

    @Test fun firstPreviewFramesGetEarlyPriorityEvenDuringBuffering() {
        assertEquals(75L, NetflixColdStartPolicy.pacingMs(false, 0, 200L))
        assertEquals(75L, NetflixColdStartPolicy.pacingMs(false, 11, 850L))
        assertEquals(450L, NetflixColdStartPolicy.pacingMs(false, 12, 100L))
        assertEquals(160L, NetflixColdStartPolicy.pacingMs(true, 20, 800L))
    }

    @Test fun currentCursorPriorityCannotStarveWholeMovieIndex() {
        assertTrue(NetflixColdStartPolicy.usePriority(true, 0))
        assertTrue(NetflixColdStartPolicy.usePriority(true, 1))
        assertFalse(NetflixColdStartPolicy.usePriority(true, 2))
        assertFalse(NetflixColdStartPolicy.usePriority(false, 0))
    }
}
