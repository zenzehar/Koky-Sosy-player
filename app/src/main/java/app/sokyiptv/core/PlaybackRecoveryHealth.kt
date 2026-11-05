package app.sokyiptv.core

/** A genuine forward native playback clock, not just a buffered/Playing event,
 * is required to clear the reconnect UI. Four 500 ms samples avoid transient
 * fake-ready responses. Do not require a nonfailed classifier state: the video
 * may recover while a previous failure flag is still set.
 */
internal object PlaybackRecoveryHealth {
    const val REQUIRED_TICKS = 4

    fun accumulate(
        previous: Int,
        nativeDeltaMs: Long,
        isPlaying: Boolean,
        paused: Boolean,
        ended: Boolean,
        suspended: Boolean,
        scrubbing: Boolean,
        initialSeekPending: Boolean,
    ): Int {
        if (!isPlaying || paused || ended || suspended || scrubbing ||
            initialSeekPending || nativeDeltaMs !in 100L..1_600L) return 0
        return (previous + 1).coerceAtMost(REQUIRED_TICKS)
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
