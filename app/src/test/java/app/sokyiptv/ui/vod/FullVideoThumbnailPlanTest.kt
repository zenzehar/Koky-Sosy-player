package app.sokyiptv.ui.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullVideoThumbnailPlanTest {
    @Test fun entireTwoHourMovieIsIndexedEveryFiveSecondsWithout96Limit() {
        val positions = FullVideoThumbnailPlan.orderedTimeline(7_200_000L)
        assertEquals(1439, positions.size)
        assertEquals(positions.size, positions.distinct().size)
        assertTrue(positions.all { it in 5_000L until 7_200_000L })
        assertTrue((1L..1439L).all { it * 5_000L in positions })
        assertTrue(positions.take(24).any { it in 3_000_000L..4_000_000L })
        assertTrue(positions.take(24).any { it > 6_000_000L })
    }

    @Test fun fiveSecondPlanHasNo2880Or96FrameTotalCap() {
        val sixHours = FullVideoThumbnailPlan.orderedTimeline(21_600_000L)
        assertEquals(4319, sixHours.size)
        assertTrue(sixHours.all { it % 5_000L == 0L })
    }

    @Test fun noPlaybackPositionEntersTimelinePlanner() {
        val duration = 1_800_000L
        val first = FullVideoThumbnailPlan.orderedTimeline(duration)
        assertEquals(first, FullVideoThumbnailPlan.orderedTimeline(duration))
        assertEquals(359, first.size)
        assertTrue(FullVideoThumbnailPlan.orderedTimeline(30_000L).isEmpty())
    }

    @Test fun remoteMetadataOnlyForRecognisedVodUrls() {
        assertTrue(FullVideoThumbnailPlan.supportedSource("https://provider.example/movie/123.mp4", 33))
        assertTrue(FullVideoThumbnailPlan.supportedSource("https://provider.example/series/u/p/12345", 33))
        assertTrue(FullVideoThumbnailPlan.supportedSource("http://provider.example/movie/123.ts", 34))
        assertFalse(FullVideoThumbnailPlan.supportedSource("https://provider.example/live/123.ts", 34))
        assertFalse(FullVideoThumbnailPlan.supportedSource("https://provider.example/movie/123.m3u8", 34))
        assertFalse(FullVideoThumbnailPlan.supportedSource("https://provider.example/movie/123.mp4", 26))
    }

    @Test fun selectedTimestampSnapsToValidFiveSecondBucket() {
        assertEquals(5_000L, FullVideoThumbnailPlan.nearestBucket(2_000L, 100_000L))
        assertEquals(40_000L, FullVideoThumbnailPlan.nearestBucket(38_000L, 100_000L))
        assertEquals(95_000L, FullVideoThumbnailPlan.nearestBucket(99_000L, 100_000L))
    }
}
