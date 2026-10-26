package app.sokyiptv.ui.vod

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekPreviewTimecodeTest {
    @Test fun alwaysShowsHoursAndExactSelectedSeconds() {
        assertEquals("00:00:00", seekTimecode(0L))
        assertEquals("00:00:09", seekTimecode(9_999L))
        assertEquals("01:23:45", seekTimecode(5_025_999L))
        assertEquals("27:00:01", seekTimecode(97_201_001L))
        assertEquals("00:00:00", seekTimecode(-1L))
    }

    @Test fun remainingTimeIsDerivedFromPreviewPosition() {
        val duration = 7_740_000L
        val preview = 5_025_000L
        assertEquals("01:23:45", seekTimecode(preview))
        assertEquals("00:45:15", seekTimecode(duration - preview))
    }
}
