package app.sokyiptv.ui.vod

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VodRowVisibilityTest {
    @Test fun everyVodGridHasFivePostersPerRow() {
        assertThat(VOD_COLUMNS).isEqualTo(5)
    }

    @Test fun alreadyFullyVisibleRowNeverScrolls() {
        val row = (5..9).map { PosterBounds(it, 140, 460) }
        assertThat(fullPosterRowDelta(8, 100, 5, row, 0, 500)).isEqualTo(0)
    }

    @Test fun lastFocusedPosterRevealsEntireRowBelowViewport() {
        val row = (10..14).map { PosterBounds(it, 380, 700) }
        assertThat(fullPosterRowDelta(14, 100, 5, row, 0, 560)).isEqualTo(140)
    }

    @Test fun firstFocusedPosterRevealsEntireRowAboveViewport() {
        val row = (5..9).map { PosterBounds(it, -115, 215) }
        assertThat(fullPosterRowDelta(5, 100, 5, row, 0, 560)).isEqualTo(-115)
    }

    @Test fun extraRowMembersDetermineFullyVisibleBounds() {
        val row = (0..4).map {
            PosterBounds(it, 120, if (it == 4) 566 else 540)
        }
        assertThat(fullPosterRowDelta(0, 100, 5, row, 0, 550)).isEqualTo(16)
    }

    @Test fun partlyPopulatedLastRowUsesOnlyExistingTiles() {
        val last = (10..11).map { PosterBounds(it, 350, 700) }
        assertThat(fullPosterRowDelta(11, 12, 5, last, 0, 600)).isEqualTo(100)
    }

    @Test fun abnormallyTallRowPrioritisesItsTop() {
        val row = (5..9).map { PosterBounds(it, 50, 900) }
        assertThat(fullPosterRowDelta(7, 100, 5, row, 100, 600)).isEqualTo(-50)
    }

    @Test fun ignoreUnknownOrOffscreenFocus() {
        assertThat(fullPosterRowDelta(99, 20, 5, emptyList(), 0, 500)).isEqualTo(0)
        assertThat(fullPosterRowDelta(5, 20, 5, emptyList(), 0, 500)).isEqualTo(0)
    }
}
