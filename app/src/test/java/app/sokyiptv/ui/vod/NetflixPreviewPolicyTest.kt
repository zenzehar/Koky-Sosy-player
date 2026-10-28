package app.sokyiptv.ui.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetflixPreviewPolicyTest {
    @Test fun fullDurationIsCoveredCoarseToFineWithoutRamLimit() {
        val movieMs = 7_200_000L
        val plan = NetflixPreviewPolicy.progressive(movieMs)
        assertEquals(1439, plan.size)
        assertEquals(plan.size, plan.distinct().size)
        assertTrue(plan.take(24).any { it in 3_000_000L..4_000_000L })
        assertTrue(plan.take(24).any { it > 6_000_000L })
        assertTrue((1L..1439L).all { it * 5_000L in plan })
        assertEquals(4319, NetflixPreviewPolicy.progressive(21_600_000L).size)
    }

    @Test fun newestCursorLocationAndNeighboursHavePriority() {
        val positions = NetflixPreviewPolicy.aroundCursor(1_001_000L, 3_600_000L)
        assertEquals(1_000_000L, positions[0])
        assertEquals(1_005_000L, positions[1])
        assertEquals(995_000L, positions[2])
        assertEquals(7, positions.size)
        assertTrue(NetflixPreviewPolicy.shouldPrioritize(1_020_000L, 1_000_000L, 3_600_000L))
        assertFalse(NetflixPreviewPolicy.shouldPrioritize(1_002_000L, 1_000_000L, 3_600_000L))
    }

    @Test fun fastScrubbingSkipsOnlyPreviewSamplesNotTheSeekDestination() {
        assertEquals(5_000L, NetflixPreviewPolicy.displayGranularity(0L))
        assertEquals(10_000L, NetflixPreviewPolicy.displayGranularity(1_000L))
        assertEquals(20_000L, NetflixPreviewPolicy.displayGranularity(3_000L))
        assertEquals(5_000L, NetflixPreviewPolicy.bucket(2_000L, 100_000L))
    }

    @Test fun emptyOrShortMediaHasNoIndex() {
        assertTrue(NetflixPreviewPolicy.progressive(30_000L).isEmpty())
        assertTrue(NetflixPreviewPolicy.aroundCursor(-1L, 3_600_000L).isEmpty())
    }
}
