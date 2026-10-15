/* GPL-3.0-or-later: deterministic TV seeking policy. */
package app.opentv.player

enum class SeekDirection { BACKWARD, FORWARD }

/** Pure target preview calculations; physical player integration is separate. */
object TvSeekPolicy {
    const val TAP_STEP_MS = 10_000L
    const val HOLD_THRESHOLD_MS = 600L

    fun tap(positionMs: Long, durationMs: Long?, direction: SeekDirection): Long =
        relative(positionMs, direction, TAP_STEP_MS, durationMs)

    /** Continuous acceleration, independent of remote repeat-event cadence. */
    fun heldPreview(
        originalPositionMs: Long,
        holdElapsedMs: Long,
        direction: SeekDirection,
        durationMs: Long?,
    ): Long {
        val elapsed = (holdElapsedMs - HOLD_THRESHOLD_MS).coerceAtLeast(0L)
        val delta = when {
            elapsed <= 900L -> elapsed * 12L
            elapsed <= 3_400L -> 10_800L + (elapsed - 900L) * 40L
            else -> 110_800L + (elapsed - 3_400L).coerceAtMost(Long.MAX_VALUE / 90L) * 90L
        }
        return relative(originalPositionMs, direction, delta, durationMs)
    }

    private fun relative(position: Long, direction: SeekDirection, delta: Long, duration: Long?): Long {
        val max = duration?.takeIf { it >= 0L } ?: Long.MAX_VALUE
        val origin = position.coerceIn(0L, max)
        return when (direction) {
            SeekDirection.BACKWARD -> (origin - delta.coerceAtMost(origin)).coerceAtLeast(0L)
            SeekDirection.FORWARD -> if (delta >= max - origin) max else origin + delta
        }
    }
}
