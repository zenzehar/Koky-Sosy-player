package app.sokyiptv.core

/** A genuine forward native playback clock, not just a buffered/Playing event,
 * is required to clear the reconnect UI. IPTV clocks often update only
 * once per second even when sampled every 500ms; zero-delta samples between
 * successive valid advances MUST NOT reset the healthy-playback window. Do not require a nonfailed classifier state: the video
 * may recover while a previous failure flag is still set.
 */
internal object PlaybackRecoveryHealth {
    const val REQUIRED_TICKS = 3

    fun accumulate(
        previous: Int,
        nativeDeltaMs: Long,
        timeSinceLastAdvanceMs: Long,
        isPlaying: Boolean,
        paused: Boolean,
        ended: Boolean,
        suspended: Boolean,
        scrubbing: Boolean,
        initialSeekPending: Boolean,
    ): Int {
        if (!isPlaying || paused || ended || suspended || scrubbing ||
            initialSeekPending) return 0
        if (nativeDeltaMs in 100L..1_600L)
            return (previous + 1).coerceAtMost(REQUIRED_TICKS)
        if (nativeDeltaMs == 0L && timeSinceLastAdvanceMs <= 2_500L)
            return previous // Normal sparse native time updates.
        return 0
    }

    fun confirmed(ticks: Int): Boolean = ticks >= REQUIRED_TICKS
}

/** Use elapsedRealtime deadlines instead of repeated delay(1000) calls, which
 * can drift under native decoder/UI load on older Android TV hardware.
 */
internal object PlaybackRetryClock {
    fun remainingSeconds(deadlineMs: Long, nowElapsedMs: Long): Int =
        (((deadlineMs - nowElapsedMs).coerceAtLeast(0L) + 999L) / 1_000L)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
