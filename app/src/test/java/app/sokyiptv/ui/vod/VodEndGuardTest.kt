package app.sokyiptv.ui.vod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodEndGuardTest {
    @Test fun providerDropDoesNotAdvanceEpisode() {
        assertFalse(VodEndGuard.confirmed(
            hadPlayback = true, playbackFailed = false,
            durationMs = 2_400_000L, lastPositionMs = 700_000L))
    }
    @Test fun noDurationOrNeverPlayedCannotComplete() {
        assertFalse(VodEndGuard.confirmed(true, false, 0L, 30_000L))
        assertFalse(VodEndGuard.confirmed(false, false, 60_000L, 60_000L))
        assertFalse(VodEndGuard.confirmed(true, true, 60_000L, 60_000L))
    }
    @Test fun genuineEndCanAdvance() {
        assertTrue(VodEndGuard.confirmed(true, false, 1_800_000L, 1_798_000L))
        assertTrue(VodEndGuard.confirmed(true, false, 1_800_000L, 1_800_100L))
    }
    @Test fun networkFailureNearEndStillBlocksAdvance() {
        assertFalse(VodEndGuard.confirmed(true, true, 1_800_000L, 1_799_000L))
    }
}
