package app.sokyiptv.ui.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactivePreviewPolicyTest {
    @Test fun onlyProgressiveHttpVideoOnSupportedAndroidIsEligible() {
        assertTrue(ProactivePreviewPolicy.eligible(
            "https://provider.example/movie/12345.mp4?token=secret", 27))
        assertTrue(ProactivePreviewPolicy.eligible(
            "http://provider.example/MOVIE/123.mkv", 33))
        assertFalse(ProactivePreviewPolicy.eligible(
            "https://provider.example/hls/123.m3u8", 35))
        assertTrue(ProactivePreviewPolicy.eligible(
            "https://provider.example/movie/123.ts", 35))
        assertTrue(ProactivePreviewPolicy.eligible(
            "https://provider.example/series/demo/pass/12345", 35))
        assertTrue(ProactivePreviewPolicy.eligible(
            "https://provider.example/vod/demo/pass/9098?token=secret", 35))
        assertFalse(ProactivePreviewPolicy.eligible(
            "https://provider.example/live/demo/pass/9098.ts", 35))
        assertFalse(ProactivePreviewPolicy.eligible(
            "https://provider.example/series/demo/pass/abc", 35))
        assertFalse(ProactivePreviewPolicy.eligible(
            "https://provider.example/movie/123.mp4", 26))
        assertFalse(ProactivePreviewPolicy.eligible(
            "optvrec://recording/100", 35))
        assertFalse(ProactivePreviewPolicy.eligible(
            "https://provider.example/unknown/123", 35))
    }

    @Test fun storyboardIsBoundedAndCoversWholeDurationFirst() {
        val total = 7_200_000L
        val plan = ProactivePreviewPolicy.storyboard(total, 1_000_000L)
        assertEquals(24, plan.size)
        assertEquals(3_600_000L, plan[0])
        assertEquals(1_800_000L, plan[1])
        assertEquals(5_400_000L, plan[2])
        assertTrue(plan.any { it in 3_000_000L..4_000_000L })
        assertTrue(plan.any { it > 6_000_000L })
        assertTrue(plan.all { it in 2_000L until total })
        assertEquals(plan.size, plan.distinct().size)
        assertTrue(ProactivePreviewPolicy.storyboard(30_000L, 0L).isEmpty())
    }

    @Test fun detailedStoryboardsPrecache96DistinctScenesAcrossEntireVideo() {
        val duration = 1_800_000L
        val initial = ProactivePreviewPolicy.storyboard(duration, 0L)
        val plan = ProactivePreviewPolicy.detailedStoryboard(duration, 0L)
        assertEquals(96, plan.size)
        assertEquals(initial, plan.take(24))
        assertEquals(plan.size, plan.distinct().size)
        assertTrue(plan.all { it in 2_000L..(duration - 2_000L) })
        assertTrue(plan.any { it in 0L..180_000L })
        assertTrue(plan.any { it in 1_620_000L..duration })
        assertTrue(ProactivePreviewPolicy.detailedStoryboard(30_000L, 0L).isEmpty())
    }

    @Test fun tenSecondStoryboardCoversVideoCoarseToFineWithoutUnboundedFrames() {
        val duration = 1_800_000L // 30-minute episode
        val grid = ProactivePreviewPolicy.tenSecondStoryboard(duration, 0L)
        assertEquals(179, grid.size)
        assertEquals(grid.size, grid.distinct().size)
        assertTrue(grid.all { it % 10_000L == 0L })
        assertTrue((1L..179L).all { 10_000L * it in grid })
        assertTrue(grid.take(30).any { it in 850_000L..950_000L })
        assertTrue(grid.take(30).any { it >= 1_600_000L })
        assertTrue(ProactivePreviewPolicy.tenSecondStoryboard(30_000L, 0L).isEmpty())
        val longVideo = ProactivePreviewPolicy.tenSecondStoryboard(21_600_000L, 0L)
        assertTrue(longVideo.size <= 1_440)
        assertTrue(longVideo.all { it in 2_000L until 21_600_000L })
    }

    @Test fun requestedFramesPrioritizeSelectedTimestampAndStaySmall() {
        assertEquals(listOf(200_000L, 225_000L, 175_000L),
            ProactivePreviewPolicy.requestedFrames(200_000L, 1_720_000L))
        assertTrue(ProactivePreviewPolicy.requestedFrames(0L, 30_000L).isEmpty())
        assertEquals(listOf(2_000L, 26_000L),
            ProactivePreviewPolicy.requestedFrames(1_000L, 60_000L))
    }

    @Test fun approximateThumbnailDistanceIsClampedForDeviceUsability() {
        assertEquals(40_000L, ProactivePreviewPolicy.maxApproximateDistanceMs(60_000L))
        assertEquals(240_000L, ProactivePreviewPolicy.maxApproximateDistanceMs(7_200_000L))
        assertEquals(240_000L, ProactivePreviewPolicy.maxApproximateDistanceMs(32_000_000L))
    }
}
