package app.sokyiptv.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VlcAdaptiveBufferPolicyTest {
    @Test fun respectsPresetAndOnlyRaisesCacheAtNextPlayback() {
        assertThat(VlcAdaptiveBufferPolicy.cacheMillis(1_500, 0)).isEqualTo(1_500)
        assertThat(VlcAdaptiveBufferPolicy.cacheMillis(5_000, 0)).isEqualTo(5_000)
        assertThat(VlcAdaptiveBufferPolicy.cacheMillis(5_000, 1)).isEqualTo(8_000)
        assertThat(VlcAdaptiveBufferPolicy.cacheMillis(5_000, 2)).isEqualTo(12_000)
        assertThat(VlcAdaptiveBufferPolicy.cacheMillis(12_000, 2)).isEqualTo(12_000)
    }

    @Test fun doesntEscalateAfterOneOrdinaryBufferingEvent() {
        assertThat(VlcAdaptiveBufferPolicy.nextTier(0, 1, false, 0)).isEqualTo(0)
        assertThat(VlcAdaptiveBufferPolicy.nextTier(0, 2, false, 0)).isEqualTo(1)
        assertThat(VlcAdaptiveBufferPolicy.nextTier(0, 0, true, 120_000L)).isEqualTo(1)
        assertThat(VlcAdaptiveBufferPolicy.nextTier(1, 1, true, 0)).isEqualTo(2)
        assertThat(VlcAdaptiveBufferPolicy.nextTier(2, 10, true, 0)).isEqualTo(2)
    }

    @Test fun stableSessionGraduallyRemovesUnneededCache() {
        assertThat(VlcAdaptiveBufferPolicy.nextTier(2, 0, false, 25 * 60_000L)).isEqualTo(1)
        assertThat(VlcAdaptiveBufferPolicy.nextTier(1, 0, false, 20 * 60_000L)).isEqualTo(1)
        assertThat(VlcAdaptiveBufferPolicy.nextTier(0, 0, false, 60 * 60_000L)).isEqualTo(0)
    }

    @Test fun throughputPressureRequiresThreeConsecutiveWeakSamples() {
        val monitor = VlcAdaptiveBufferPolicy()
        assertThat(monitor.observe(1f, 2f, false)).isEqualTo(VlcAdaptiveBufferPolicy.Pressure.UNKNOWN)
        assertThat(monitor.observe(1f, 2f, false)).isEqualTo(VlcAdaptiveBufferPolicy.Pressure.UNKNOWN)
        assertThat(monitor.observe(1f, 2f, false)).isEqualTo(VlcAdaptiveBufferPolicy.Pressure.THIN)
        assertThat(monitor.sustainedRisk).isFalse()
        monitor.observe(1f, 2f, false)
        monitor.observe(1f, 2f, false)
        assertThat(monitor.sustainedRisk).isTrue()
        assertThat(monitor.observe(4f, 2f, false)).isEqualTo(VlcAdaptiveBufferPolicy.Pressure.THIN)
        assertThat(monitor.observe(4f, 2f, false)).isEqualTo(VlcAdaptiveBufferPolicy.Pressure.HEALTHY)
    }

    @Test fun eventAndUnavailableStatsDoNotInventBufferedAhead() {
        val monitor = VlcAdaptiveBufferPolicy()
        assertThat(monitor.observe(null, null, false)).isEqualTo(VlcAdaptiveBufferPolicy.Pressure.UNKNOWN)
        assertThat(monitor.observe(0f, 0f, true)).isEqualTo(VlcAdaptiveBufferPolicy.Pressure.REFILLING)
        assertThat(monitor.observe(Float.NaN, 1f, false)).isEqualTo(VlcAdaptiveBufferPolicy.Pressure.UNKNOWN)
        assertThat(monitor.sustainedRisk).isFalse()
    }
}
