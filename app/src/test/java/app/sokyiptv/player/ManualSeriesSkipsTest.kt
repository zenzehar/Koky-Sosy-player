package app.sokyiptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualSeriesSkipsTest {
    @Test fun incompleteMarkNeverShowsSkip() {
        val initial = ManualSeriesSkips()
        assertFalse(initial.showIntro(35_000L))
        assertFalse(initial.showCredits(2_000_000L))
        val onlyStart = initial.mark(SkipMark.INTRO_START, 40_000L)
        assertFalse(onlyStart.showIntro(50_000L))
        val reverseEnd = onlyStart.mark(SkipMark.INTRO_END, 30_000L)
        assertFalse(reverseEnd.showIntro(41_000L))
    }

    @Test fun completedIntroOnlyAppearsWithinChosenInterval() {
        val marks = ManualSeriesSkips().mark(SkipMark.INTRO_START, 20_000L)
            .mark(SkipMark.INTRO_END, 98_000L)
        assertFalse(marks.showIntro(19_999L))
        assertTrue(marks.showIntro(20_000L))
        assertTrue(marks.showIntro(55_000L))
        assertFalse(marks.showIntro(97_000L))
        assertFalse(marks.showIntro(100_000L))
        assertEquals(98_000L, marks.introEndMs)
        assertFalse(marks.clearIntro().showIntro(50_000L))
    }

    @Test fun creditsOnlyAppearBetweenTwoExplicitTimes() {
        val marks = ManualSeriesSkips().mark(SkipMark.CREDITS_START, 2_000_000L)
            .mark(SkipMark.CREDITS_END, 2_130_000L)
        assertFalse(marks.showCredits(1_999_999L))
        assertTrue(marks.showCredits(2_050_000L))
        assertFalse(marks.showCredits(2_130_000L))
        assertFalse(marks.clearCredits().showCredits(2_050_000L))
    }

    @Test fun localMarkersAreIsolatedByProviderAndSeriesAndSurviveReload() {
        val store = ManualSeriesSkipStore(RuntimeEnvironment.getApplication())
        val sourceOne = "test-provider-one:series-test-137"
        val sourceTwo = "test-provider-two:series-test-137"
        store.write(sourceOne, ManualSeriesSkips())
        store.write(sourceTwo, ManualSeriesSkips())
        val marks = ManualSeriesSkips().mark(SkipMark.INTRO_START, 12_000L)
            .mark(SkipMark.INTRO_END, 90_000L)
        store.write(sourceOne, marks)
        val reloaded = ManualSeriesSkipStore(RuntimeEnvironment.getApplication())
        assertEquals(marks, reloaded.read(sourceOne))
        assertFalse(reloaded.read(sourceTwo).introValid())
        store.write(sourceOne, ManualSeriesSkips())
        assertFalse(reloaded.read(sourceOne).introValid())
    }

    @Test fun changingOneRangeDoesNotDestroyTheOther() {
        val both = ManualSeriesSkips(20_000L, 100_000L, 2_000_000L, 2_100_000L)
        assertTrue(both.clearIntro().creditsValid())
        assertTrue(both.clearCredits().introValid())
    }
}
