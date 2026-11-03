package app.sokyiptv.core

/** Exactly five user-visible attempts, excluding original failed stream. */
internal object PlaybackRecoveryPolicy {
    const val MAX_RETRIES = 5
    fun shouldRetry(attempts: Int, stopped: Boolean, maxRetries: Int = MAX_RETRIES): Boolean =
        !stopped && attempts in 0 until maxRetries.coerceIn(1, 10)

    fun retryDelaySeconds(gap: Int): Int = gap.coerceIn(2, 10)

    fun canAdvanceAfterExhaustion(
        attempts: Int, autoSkipSelected: Boolean, series: Boolean, nextAvailable: Boolean,
        maxRetries: Int = MAX_RETRIES
    ): Boolean = attempts >= maxRetries.coerceIn(1, 10) &&
        autoSkipSelected && series && nextAvailable
}
