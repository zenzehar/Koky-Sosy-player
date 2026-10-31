package app.sokyiptv.data.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiProgressThrottleTest {
    @Test fun thousandsOfSmallImportTicksDoNotWriteThousandsOfWorkManagerRows() {
        val gate = UiProgressThrottle(160L)
        var published = 0
        repeat(5_000) { batch ->
            if (gate.shouldPublish(batch.toFloat() / 5_000f, batch.toLong())) published++
        }
        assertTrue("Expected significant coalescing, got $published", published < 40)
        assertTrue(gate.shouldPublish(1f, 5_000L))
        assertEquals(100, gate.publishedPercent())
    }

    @Test fun consecutiveUpdatesOfSameWholePercentAreIgnored() {
        val gate = UiProgressThrottle()
        assertTrue(gate.shouldPublish(0.121f, 0L))
        assertFalse(gate.shouldPublish(0.122f, 400L))
        assertTrue(gate.shouldPublish(0.135f, 401L))
    }

    @Test fun terminalProgressIsNeverDelayed() {
        val gate = UiProgressThrottle(5_000L)
        assertTrue(gate.shouldPublish(0.11f, 500L))
        assertFalse(gate.shouldPublish(0.12f, 700L))
        assertTrue(gate.shouldPublish(1f, 701L))
        assertEquals(100, gate.publishedPercent())
    }
}
