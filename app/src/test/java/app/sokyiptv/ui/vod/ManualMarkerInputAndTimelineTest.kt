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

    @Test fun sharedTimesMapOntoDifferentEpisodeSeekbars() {
        val marks = ManualSeriesSkips(30_000L, 90_000L, 2_400_000L, 2_500_000L)
        val first = ManualTimelineModel.regions(marks, 2_600_000L)
        val second = ManualTimelineModel.regions(marks, 2_800_000L)
        assertEquals(2, first.size)
        assertEquals(2, second.size)
        assertEquals(MarkerRegion.INTRO, first[0].kind)
        assertEquals(MarkerRegion.CREDITS, first[1].kind)
        assertEquals(30_000f / 2_600_000f, first[0].startFraction, 0.0001f)
        assertEquals(30_000f / 2_800_000f, second[0].startFraction, 0.0001f)
        assertEquals(4, ManualTimelineModel.boundaries(marks, 2_600_000L).size)
    }

    @Test fun incompleteRangesShowTicksButNeverSkipRegion() {
        val incomplete = ManualSeriesSkips(introStartMs = 35_000L, creditsEndMs = 2_000_000L)
        assertTrue(ManualTimelineModel.regions(incomplete, 2_700_000L).isEmpty())
        assertEquals(2, ManualTimelineModel.boundaries(incomplete, 2_700_000L).size)
        assertFalse(incomplete.showIntro(40_000L))
    }

    @Test fun clipsRangesToShorterFutureEpisodesAndClears() {
        val marks = ManualSeriesSkips(20_000L, 80_000L, 2_400_000L, 2_500_000L)
        val shorter = ManualTimelineModel.regions(marks, 2_200_000L)
        assertEquals(1, shorter.size)
        assertEquals(MarkerRegion.INTRO, shorter[0].kind)
        assertEquals(0, ManualTimelineModel.regions(marks.clearIntro().clearCredits(), 2_600_000L).size)
    }
}
