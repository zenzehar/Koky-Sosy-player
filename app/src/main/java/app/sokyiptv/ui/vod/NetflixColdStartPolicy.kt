package app.sokyiptv.ui.vod

/**
 * Pure scheduling rules for an initially empty VOD thumbnail cache.
 *
 * A temporary failure when a provider is opening the main stream must not
 * permanently blacklist a timestamp, nor end indexing after five misses.
 */
internal object NetflixColdStartPolicy {
    const val MAX_FRAME_ATTEMPTS = 4
    const val MAX_OPEN_ATTEMPTS = 3
    const val MAX_INITIAL_FAILURES = 36
    const val MAX_TOTAL_FAILURES = 180
    const val MIN_COLD_TRIAL_MS = 45_000L
    const val MAX_SESSION_MS = 90 * 60 * 1000L
    const val CURSOR_BURST = 2

    fun retryDelayMs(attemptsAlreadyMade: Int): Long = when {
        attemptsAlreadyMade <= 1 -> 1_200L
        attemptsAlreadyMade == 2 -> 3_000L
        else -> 7_000L
    }

    fun canTryFrame(
        attemptsMade: Int,
        lastAttemptMs: Long,
        nowMs: Long,
    ): Boolean = attemptsMade < MAX_FRAME_ATTEMPTS &&
        (attemptsMade == 0 || (nowMs - lastAttemptMs) >= retryDelayMs(attemptsMade))

    fun shouldGiveUp(successes: Int, failures: Int, elapsedMs: Long): Boolean =
        if (successes == 0)
            elapsedMs >= MIN_COLD_TRIAL_MS && failures >= MAX_INITIAL_FAILURES
        else failures >= MAX_TOTAL_FAILURES

    fun pacingMs(playbackHealthy: Boolean, successes: Int, extractionMs: Long): Long =
        when {
            // First scenes must appear quickly, even when the video buffers.
            successes < 12 && extractionMs < 1_000L -> 75L
            !playbackHealthy -> 450L
            extractionMs >= 2_000L -> 350L
            extractionMs >= 750L -> 160L
            else -> 85L
        }

    /** Limit how many neighbouring frames may defer the full-video scan. */
    fun usePriority(cursorCandidatesRemaining: Boolean, usedInBurst: Int): Boolean =
        cursorCandidatesRemaining && usedInBurst < CURSOR_BURST
}
