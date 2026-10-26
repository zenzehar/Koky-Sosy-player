package app.sokyiptv.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VodWaitIndicatorTest {
    @Test fun bufferingShowsIndependentOfChromeVisibilityAfterRealWait() {
        val health = VlcPlaybackHealth()
        health.onPlaying(0)
        health.onBuffering(12f, 2_000)
        val transient = health.sample(2_500, 1_000, false, false)
        assertThat(VodWaitIndicator.show(true, false, false, false, false, false, transient.state))
            .isEqualTo(VodWaitIndicator.Kind.NONE)
        val sustained = health.sample(3_000, 1_000, false, false)
        assertThat(VodWaitIndicator.show(true, false, false, false, false, false, sustained.state))
            .isEqualTo(VodWaitIndicator.Kind.BUFFERING)
    }

    @Test fun noOverlayWhenPlayingPausedOrStillOpening() {
        val states = listOf(VlcPlaybackHealth.State.PLAYING, VlcPlaybackHealth.State.STARTING,
            VlcPlaybackHealth.State.PAUSED)
        for (state in states) assertThat(
            VodWaitIndicator.show(true, false, false, false, false, false, state)
        ).isEqualTo(VodWaitIndicator.Kind.NONE)
        assertThat(VodWaitIndicator.show(false, false, false, false, false, false,
            VlcPlaybackHealth.State.BUFFERING)).isEqualTo(VodWaitIndicator.Kind.NONE)
    }

    @Test fun suppressDuringUserPauseScrubOrAppBackground() {
        for (paused in listOf(false,true)) {
            for (suspended in listOf(false,true)) {
                for (scrubbing in listOf(false,true)) {
                    val kind = VodWaitIndicator.show(true,false,false,paused,suspended,scrubbing,
                        VlcPlaybackHealth.State.BUFFERING)
                    if (paused || suspended || scrubbing)
                        assertThat(kind).isEqualTo(VodWaitIndicator.Kind.NONE)
                    else assertThat(kind).isEqualTo(VodWaitIndicator.Kind.BUFFERING)
                }
            }
        }
    }

    @Test fun stalledVideoShowsNeutralWordingStateNotBuffering() {
        assertThat(VodWaitIndicator.show(true,false,false,false,false,false,
            VlcPlaybackHealth.State.STALLED)).isEqualTo(VodWaitIndicator.Kind.STALLED)
        assertThat(VodWaitIndicator.show(true,true,false,false,false,false,
            VlcPlaybackHealth.State.BUFFERING)).isEqualTo(VodWaitIndicator.Kind.NONE)
        assertThat(VodWaitIndicator.show(true,false,true,false,false,false,
            VlcPlaybackHealth.State.BUFFERING)).isEqualTo(VodWaitIndicator.Kind.NONE)
    }
}
