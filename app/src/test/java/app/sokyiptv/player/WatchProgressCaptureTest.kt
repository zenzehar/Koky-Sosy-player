package app.sokyiptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Real provider case: elapsed VLC time exists, reported movie length is 0. */
class WatchProgressCaptureTest {
    @Test fun savesPositionWithUnknownDurationSoResumeAndContinueWatchingCanFindIt() {
        val p = WatchProgressCapture.snapshot(1, "movie:234", 245_000L, 0L, 1_789_000_000_000L)
        assertNotNull(p)
        assertEquals(245_000L, p!!.positionMillis)
        assertEquals(0L, p.durationMillis)
        assertFalse(p.isFinished)
    }

    @Test fun seriesEpisodeAlsoSavesWhenProviderReportsNoLength() {
        val p = WatchProgressCapture.snapshot(7, "ep:987", 1_030_000L, -1L, 1_789_000_000_001L)
        assertNotNull(p)
        assertEquals("ep:987", p!!.mediaKey)
        assertEquals(7L, p.profileId)
    }

    @Test fun contradictoryDurationDoesNotFalselyMarkUnfinishedTitleAsCompleted() {
        // A stream reports only 5 minutes of length after VLC has played 40 minutes.
        val p = WatchProgressCapture.snapshot(1, "ep:5", 2_400_000L, 300_000L, 1_789_000_000_000L)
        assertNotNull(p)
        assertEquals(0L, p!!.durationMillis)
        assertFalse(p.isFinished)
    }

    @Test fun knownDurationAndCompletionRemainCompatibleWithPriorBehavior() {
        val p = WatchProgressCapture.snapshot(1, "movie:1", 600_000L, 3_600_000L, 1_789_000_000_000L)
        assertEquals(3_600_000L, p!!.durationMillis)
        assertFalse(p.isFinished)
        assertEquals(600_000L, p.positionMillis)
    }

    @Test fun doesNotCreateContinueWatchingForUnplayedMedia() {
        assertNull(WatchProgressCapture.snapshot(1, "movie:1", 2_000L, 300_000L, 1_789_000_000_000L))
        assertNull(WatchProgressCapture.snapshot(1, "ep:4", -200L, 0L, 1_789_000_000_000L))
        assertNull(WatchProgressCapture.snapshot(0, "ep:4", 65_000L, 0L, 1_789_000_000_000L))
    }
}
