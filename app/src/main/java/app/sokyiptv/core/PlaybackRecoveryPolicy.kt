package app.sokyiptv.core

/** Exactly five user-visible attempts, excluding original failed stream. */
internal object PlaybackRecoveryPolicy {
    const val MAX_RETRIES = 5
    fun shouldRetry(attempts: Int, stopped: Boolean): Boolean =
        !stopped && attempts in 0 until MAX_RETRIES

    fun retryDelaySeconds(gap: Int): Int = gap.coerceIn(2, 10)

    fun canAdvanceAfterExhaustion(
        attempts: Int, autoSkipSelected: Boolean, series: Boolean, nextAvailable: Boolean
    ): Boolean = attempts >= MAX_RETRIES && autoSkipSelected && series && nextAvailable
}
