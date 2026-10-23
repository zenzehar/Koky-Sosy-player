/* GPL-3.0-or-later: bounded correction for inaccurate VLC seek demux timestamps. */
package app.sokyiptv.player

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A conservative one-shot feedback controller. LibVLC's precise setTime(milliseconds)
 * may still land at a wrong native timestamp when a provider exposes bad duration/index
 * metadata. Observe two *new*, advancing native clock samples after the seek before
 * attempting exactly one compensated absolute-time request.
 *
 * Does not own the player; cannot guarantee pixel-perfect seeks on defective media.
 */
internal class VlcNativeSeekCorrection {
    sealed interface Decision {
        data class RetryAt(val commandTimeMs: Long, val intendedMs: Long, val landedMs: Long) : Decision
        data class Unreliable(val intendedMs: Long, val landedMs: Long) : Decision
    }

    private data class Pending(
        val intendedMs: Long,
        val originMs: Long,
        val startedAtMs: Long,
        var lastNativeMs: Long? = null,
        var lastSampleAtMs: Long = 0L,
        var retries: Int = 0,
        var lastCommandAtMs: Long = startedAtMs,
    )

    private var pending: Pending? = null
    fun clear() { pending = null }

    /** Any fresh user request cancels correction of an older request. */
    fun requested(intendedMs: Long, originNativeMs: Long, nowMs: Long, declaredDurationMs: Long) {
        // Seeking past an understated duration is permitted, but never corrected.
        if (declaredDurationMs <= 0L || intendedMs > declaredDurationMs) {
            clear()
            return
        }
        pending = Pending(intendedMs.coerceAtLeast(0L), originNativeMs.coerceAtLeast(0L), nowMs)
    }

    fun observe(
        nativeMs: Long, nowMs: Long, playing: Boolean, buffering: Boolean,
        declaredDurationMs: Long,
    ): Decision? {
        val p = pending ?: return null
        // A native clock beyond declared EOF cannot safely calibrate retries.
        if (declaredDurationMs <= 0L || p.intendedMs > declaredDurationMs ||
            nativeMs > declaredDurationMs) {
            clear()
            return null
        }
        if (!playing || buffering || nativeMs < 0L || nowMs < p.lastCommandAtMs) return null
        val elapsed = nowMs - p.lastCommandAtMs
        // A demuxer may report stale pre-seek timestamps for several polls.
        if (elapsed < MIN_SETTLE_MS) return null
        if (elapsed > GIVE_UP_MS) {
            pending = null
            return Decision.Unreliable(p.intendedMs, nativeMs)
        }
        val previous = p.lastNativeMs
        val previousAt = p.lastSampleAtMs
        p.lastNativeMs = nativeMs
        p.lastSampleAtMs = nowMs
        if (previous == null || nowMs - previousAt < SAMPLE_GAP_MS) return null
        // A second seek/rebase or decoder clock discontinuity is not stable evidence.
        val diff = nativeMs - previous
        if (diff < -500L || diff > (nowMs - previousAt) + 2_500L) return null
        // Don't mistake an untouched clock for proof that the new seek landed.
        val movedFromOrigin = abs(nativeMs - p.originMs) > 3_000L
        if (p.retries == 0 && !movedFromOrigin && abs(p.intendedMs - p.originMs) > 10_000L) {
            return null
        }
        val elapsedSinceCommand = elapsed.coerceAtMost(5_000L)
        val errorMs = nativeMs - (p.intendedMs + elapsedSinceCommand)
        if (abs(errorMs) <= ACCEPTABLE_ERROR_MS) {
            pending = null
            return null
        }
        if (p.retries != 0) {
            pending = null
            return Decision.Unreliable(p.intendedMs, nativeMs)
        }
        val compensated = correctionTarget(p.intendedMs, nativeMs, elapsedSinceCommand)
        if (compensated == null || compensated > declaredDurationMs) {
            pending = null
            return null // Never command a corrective seek beyond the declared endpoint.
        }
        if (abs(compensated - p.intendedMs) < 2_000L) {
            pending = null
            return Decision.Unreliable(p.intendedMs, nativeMs)
        }
        p.retries = 1
        p.lastNativeMs = null
        p.lastSampleAtMs = 0L
        p.lastCommandAtMs = nowMs
        return Decision.RetryAt(compensated, p.intendedMs, nativeMs)
    }

    companion object {
        private const val MIN_SETTLE_MS = 1_650L
        private const val SAMPLE_GAP_MS = 300L
        private const val GIVE_UP_MS = 7_000L
        private const val ACCEPTABLE_ERROR_MS = 6_000L

        /** Output is a player-command timestamp, never a UI percentage. */
        internal fun correctionTarget(intendedMs: Long, observedMs: Long, elapsedMs: Long): Long? {
            if (intendedMs <= 0L || observedMs <= 0L) return null
            val landedAtSeek = (observedMs - elapsedMs).coerceAtLeast(0L)
            val drift = landedAtSeek - intendedMs
            if (abs(drift) <= ACCEPTABLE_ERROR_MS) return null

            // If the demuxer scales seeks using an understated duration, an
            // empirical proportional correction is more useful than another
            // naive +/- offset. Only use this for large same-origin discrepancies.
            val ratio = landedAtSeek.toDouble() / intendedMs.toDouble()
            val estimate = if (intendedMs >= 30_000L && ratio in 0.3..4.0 &&
                (ratio > 1.15 || ratio < 0.85)) {
                intendedMs.toDouble() / ratio
            } else {
                intendedMs.toDouble() - drift.toDouble()
            }
            if (!estimate.isFinite()) return null
            val candidate = estimate.roundToLong().coerceAtLeast(0L)
            // One retry only, and never extrapolate to a wildly different location.
            if (abs(candidate - intendedMs) > maxOf(120_000L, intendedMs * 3L / 4L)) return null
            return candidate
        }
    }
}
