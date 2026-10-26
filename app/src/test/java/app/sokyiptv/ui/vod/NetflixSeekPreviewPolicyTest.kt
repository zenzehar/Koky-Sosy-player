package app.sokyiptv.ui.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetflixSeekPreviewPolicyTest {
    @Test fun matchingFrameIsWithinTwoPointSixSeconds() {
        assertTrue(NetflixSeekPreviewPolicy.frameMatches(35_000L, 37_599L))
        assertTrue(NetflixSeekPreviewPolicy.frameMatches(35_000L, 32_400L))
        assertFalse(NetflixSeekPreviewPolicy.frameMatches(35_000L, 37_601L))
        assertFalse(NetflixSeekPreviewPolicy.frameMatches(8_000L, 120_000L))
        assertFalse(NetflixSeekPreviewPolicy.frameMatches(-1L, 3_000L))
    }
    @Test fun scrubBoundsAndRemainingAreAlwaysConsistent() {
        assertEquals(0L, NetflixSeekPreviewPolicy.clampPreview(-10_000L, 100_000L))
        assertEquals(100_000L, NetflixSeekPreviewPolicy.clampPreview(130_000L, 100_000L))
        assertEquals(35_000L, NetflixSeekPreviewPolicy.remainingMs(65_000L, 100_000L))
        assertEquals(0L, NetflixSeekPreviewPolicy.remainingMs(130_000L, 100_000L))
    }
}
