/* GPL-3.0-or-later: conservative, device-local VOD cache adaptation. */
package app.sokyiptv.player

/**
 * LibVLC does not publish an exact playable-ahead position for arbitrary Xtream/HLS streams.
 * This monitor uses delivery-vs-demux throughput as a *risk signal*, not an estimate of
 * downloaded video time. It never issues network requests, seeks, pauses or restarts playback.
 */
internal class VlcAdaptiveBufferPolicy {
    enum class Pressure { UNKNOWN, HEALTHY, THIN, REFILLING }

    private var weakSamples = 0
    private var goodSamples = 0
    private var observations = 0
    private var sawConfirmedRisk = false

    var pressure: Pressure = Pressure.UNKNOWN
        private set

    val sustainedRisk: Boolean get() = sawConfirmedRisk

    /**
     * Bitrates are sourced from one media's native stats (same units, ratio-only).
     * Ignore zero/missing/invalid values; HLS may report noncomparable input/demux
     * rates, so only *sustained* mismatches are treated as advisory risk.
     */
    fun observe(inputBitrate: Float?, demuxBitrate: Float?, buffering: Boolean): Pressure {
        if (buffering) {
            pressure = Pressure.REFILLING
            weakSamples = 0
            goodSamples = 0
            return pressure
        }
        val input = inputBitrate?.toDouble() ?: 0.0
        val consumption = demuxBitrate?.toDouble() ?: 0.0
        if (!input.isFinite() || !consumption.isFinite() || input <= 0.0 || consumption <= 0.0) {
            if (pressure == Pressure.REFILLING) pressure = Pressure.UNKNOWN
            return pressure
        }
        observations++
        val ratio = input / consumption
        if (ratio < 0.85) {
            weakSamples++
            goodSamples = 0
            if (weakSamples >= 3) {
                pressure = Pressure.THIN
                sawConfirmedRisk = true
            }
        } else if (ratio >= 1.10) {
            goodSamples++
            weakSamples = 0
            if (goodSamples >= 2) pressure = Pressure.HEALTHY
        } else {
            weakSamples = 0
            goodSamples = 0
            if (pressure == Pressure.REFILLING) pressure = Pressure.UNKNOWN
        }
        return pressure
    }

    companion object {
        const val PREF_FILE = "soky_vlc_adaptive_cache"
        const val PREF_TIER = "native_vod_cache_tier"
        private const val MAX_TIER = 2

        /**
         * The saved Settings preset remains the minimum. Only *future* playback
         * sessions use a larger cache after repeated, observed interruptions.
         * LibVLC cannot safely resize a running media's native input cache.
         */
        fun cacheMillis(presetMs: Int, savedTier: Int): Int {
            val floor = presetMs.coerceIn(1_400, 12_000)
            val boosted = when (savedTier.coerceIn(0, MAX_TIER)) {
                0 -> floor
                1 -> maxOf(floor, 8_000)
                else -> maxOf(floor, 12_000)
            }
            return boosted.coerceAtMost(12_000)
        }

        /**
         * Don't learn from one brief jitter or manual seek. A problematic playback
         * session increases one tier only; long stable sessions slowly de-escalate.
         */
        fun nextTier(
            currentTier: Int,
            confirmedRebuffers: Int,
            sustainedRisk: Boolean,
            stablePlaybackMillis: Long,
        ): Int {
            val tier = currentTier.coerceIn(0, MAX_TIER)
            return when {
                confirmedRebuffers >= 2 || (confirmedRebuffers >= 1 && sustainedRisk) ->
                    (tier + 1).coerceAtMost(MAX_TIER)
                stablePlaybackMillis >= 25 * 60_000L && confirmedRebuffers == 0 ->
                    (tier - 1).coerceAtLeast(0)
                else -> tier
            }
        }
    }
}
