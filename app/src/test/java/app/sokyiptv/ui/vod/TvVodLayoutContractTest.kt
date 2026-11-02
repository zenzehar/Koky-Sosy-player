package app.sokyiptv.ui.vod

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * VOD layout contract. Full UI/focus/scroll behaviour requires an actual Android TV
 * instrumentation/physical remote test; this unit test protects the grid count.
 */
class TvVodLayoutContractTest {
    @Test fun categoryMoviesSeriesAndResumeUseFiveColumns() {
        assertThat(VOD_COLUMNS).isEqualTo(5)
    }
}
