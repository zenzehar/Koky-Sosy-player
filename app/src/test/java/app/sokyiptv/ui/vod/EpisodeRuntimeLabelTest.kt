package app.sokyiptv.ui.vod

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EpisodeRuntimeLabelTest {
    @Test fun providerMetadataWinsWhenAvailable() {
        assertThat(EpisodeRuntimeLabel.fromSources(2886, 3_000_000L)).isEqualTo("48m")
    }

    @Test fun previouslyPlayedEpisodeGetsEstimatedLengthWithoutProviderMetadata() {
        assertThat(EpisodeRuntimeLabel.fromSources(null, 2_886_000L)).isEqualTo("≈48m")
    }

    @Test fun missingEpisodeRemainsUnknownRatherThanInheritingAnotherEpisode() {
        assertThat(EpisodeRuntimeLabel.fromSources(null, null)).isEqualTo("Duration unavailable")
        assertThat(EpisodeRuntimeLabel.fromSources(null, 0L)).isEqualTo("Duration unavailable")
    }

    @Test fun nonsenseNativeLengthsCannotBeDisplayed() {
        assertThat(EpisodeRuntimeLabel.fromSources(null, 100L)).isEqualTo("Duration unavailable")
        assertThat(EpisodeRuntimeLabel.fromSources(null, 100_000_000L)).isEqualTo("Duration unavailable")
    }
}
