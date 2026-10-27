package app.sokyiptv.ui.vod

import app.sokyiptv.player.ManualSeriesSkips
import app.sokyiptv.player.ManualTimelineModel
import app.sokyiptv.player.MarkerRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualMarkerInputAndTimelineTest {
    @Test fun opensOnlyOnLongPressRelease() {
        val gate = MarkerHoldGate()
        assertEquals(MarkerHoldGate.Result.PASS, gate.handle(23, true, false, 0, 1_000, true))
        assertEquals(MarkerHoldGate.Result.CONSUME, gate.handle(23, true, false, 1, 1_400, true))
        assertEquals(MarkerHoldGate.Result.OPEN, gate.handle(23, false, true, 0, 1_810, true))
        assertEquals(MarkerHoldGate.Result.PASS, gate.handle(23, false, true, 0, 1_815, true))
    }

    @Test fun shortOkGoesThroughToOriginalTransportControl() {
        val gate = MarkerHoldGate()
        assertEquals(MarkerHoldGate.Result.PASS, gate.handle(23, true, false, 0, 1_000, true))
        assertEquals(MarkerHoldGate.Result.PASS, gate.handle(23, false, true, 0, 1_290, true))
    }

    @Test fun cannotOpenFromDisabledOrOtherKey() {
        val gate = MarkerHoldGate()
        assertEquals(MarkerHoldGate.Result.PASS, gate.handle(23, true, false, 0, 1_000, false))
        assertEquals(MarkerHoldGate.Result.PASS, gate.handle(23, false, true, 0, 2_000, false))
        assertEquals(MarkerHoldGate.Result.PASS, gate.handle(23, true, false, 0, 3_000, true))
        gate.cancel()
        assertEquals(MarkerHoldGate.Result.PASS, gate.handle(23, false, true, 0, 4_000, true))
    }

    @Test fun firstMarkMapsStartToIntroEndAcrossEpisodes() {
        val marks = ManualSeriesSkips(introEndMs = 90_000L, creditsStartMs = 2_400_000L)
        val a = ManualTimelineModel.regions(marks, 2_600_000L)
        val b = ManualTimelineModel.regions(marks, 2_800_000L)
        assertEquals(2, a.size)
        assertEquals(2, b.size)
        assertEquals(MarkerRegion.INTRO, a[0].kind)
        assertEquals(0f, a[0].startFraction, 0.0001f)
        assertEquals(90_000f / 2_600_000f, a[0].endFraction, 0.0001f)
        assertEquals(90_000f / 2_800_000f, b[0].endFraction, 0.0001f)
        assertEquals(MarkerRegion.CREDITS, a[1].kind)
        assertEquals(1f, a[1].endFraction, 0.0001f)
        assertEquals(2, ManualTimelineModel.boundaries(marks, 2_600_000L).size)
    }

    @Test fun onePointAlreadyMakesOneRegionNotIncomplete() {
        val one = ManualSeriesSkips(introEndMs = 35_000L)
        assertEquals(1, ManualTimelineModel.regions(one, 2_700_000L).size)
        assertEquals(1, ManualTimelineModel.boundaries(one, 2_700_000L).size)
        assertTrue(one.showIntro(0L))
        assertFalse(one.showCredits(2_650_000L))
    }

    @Test fun creditsPointFillsRemainingTimelineAndClears() {
        val marks = ManualSeriesSkips(introEndMs = 80_000L, creditsStartMs = 2_400_000L)
        val shorter = ManualTimelineModel.regions(marks, 2_200_000L)
        assertEquals(1, shorter.size)
        assertEquals(MarkerRegion.INTRO, shorter[0].kind)
        val longer = ManualTimelineModel.regions(marks, 2_500_000L)
        assertEquals(2, longer.size)
        assertEquals(1f, longer[1].endFraction, 0.0001f)
        assertTrue(ManualTimelineModel.regions(marks.clearIntro().clearCredits(), 2_600_000L).isEmpty())
    }
}
