package app.sokyiptv.ui

import android.content.res.Configuration
import app.sokyiptv.ui.vod.HandheldSeekPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandheldLayoutPolicyTest {
    @Test fun portraitIsMobileOnly() {
        assertTrue(HandheldLayoutPolicy.portrait(false, Configuration.ORIENTATION_PORTRAIT))
        assertFalse(HandheldLayoutPolicy.portrait(true, Configuration.ORIENTATION_PORTRAIT))
        assertFalse(HandheldLayoutPolicy.portrait(false, Configuration.ORIENTATION_LANDSCAPE))
        assertFalse(HandheldLayoutPolicy.portrait(true, Configuration.ORIENTATION_LANDSCAPE))
    }

    @Test fun touchControlsNeverActivateOnTelevision() {
        assertTrue(HandheldLayoutPolicy.touch(false))
        assertFalse(HandheldLayoutPolicy.touch(true))
    }

    @Test fun touchSeekClampsAndIsLinear() {
        assertEquals(0L, HandheldSeekPosition.at(-40f, 400, 120_000L))
        assertEquals(30_000L, HandheldSeekPosition.at(100f, 400, 120_000L))
        assertEquals(60_000L, HandheldSeekPosition.at(200f, 400, 120_000L))
        assertEquals(120_000L, HandheldSeekPosition.at(600f, 400, 120_000L))
    }

    @Test fun unknownDurationOrWidthNeverSeek() {
        assertEquals(0L, HandheldSeekPosition.at(100f, 0, 120_000L))
        assertEquals(0L, HandheldSeekPosition.at(100f, 400, 0L))
    }
}
