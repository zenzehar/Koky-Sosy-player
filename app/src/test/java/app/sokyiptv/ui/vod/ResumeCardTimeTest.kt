package app.sokyiptv.ui.vod

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ResumeCardTimeTest {
    @Test fun savedResumePositionAlwaysShowsEvenWhenDurationUnknown() {
        assertThat(ResumeCardTime.format(29 * 60_000L, 0L)).isEqualTo("Resume at 29:00")
        assertThat(ResumeCardTime.format(29 * 60_000L + 12_000L, -1L))
            .isEqualTo("Resume at 29:12")
        assertThat(ResumeCardTime.fraction(29 * 60_000L, 0L)).isNull()
    }

    @Test fun contradictoryProviderDurationNeverCreatesMisleadingPercent() {
        assertThat(ResumeCardTime.format(29 * 60_000L, 25 * 60_000L))
            .isEqualTo("Resume at 29:00")
        assertThat(ResumeCardTime.fraction(29 * 60_000L, 25 * 60_000L)).isNull()
    }

    @Test fun credibleDurationShowsActualSavedPositionAndPercentage() {
        assertThat(ResumeCardTime.format(29 * 60_000L, 58 * 60_000L))
            .isEqualTo("29:00 / 58:00 · 50%")
        assertThat(ResumeCardTime.fraction(29 * 60_000L, 58 * 60_000L)).isEqualTo(.5f)
    }

    @Test fun hourLengthMediaUsesHoursMinutesSeconds() {
        assertThat(ResumeCardTime.format(3_723_000L, 0L)).isEqualTo("Resume at 1:02:03")
    }
}
