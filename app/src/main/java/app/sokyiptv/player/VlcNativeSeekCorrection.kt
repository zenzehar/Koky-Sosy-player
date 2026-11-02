/* GPL-3.0-or-later: bounded correction for inaccurate VLC seek demux timestamps. */
package app.sokyiptv.player

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A conservative one-shot feedback controller. LibVLC's precise setTime(milliseconds)
 * may still land at a wrong native timestamp when a provider exposes bad duration/index
 * metadata. Observe two *new*, advancing native clock samples after the seek before
 * attempting exactly one compensated absolute-time request. The elapsed playback clock
 * EXCLUDES buffering and deliberate pause; counting wall time while stalled previously
 * produced unnecessary secondary seeks for otherwise accurate streams.
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
        var activeElapsedMs: Long = 0L,
        var lastActiveAtMs: Long? = null,
    )

    private var pending: Pending? = null
    fun clear() { pending = null }

    /** Any fresh user request cancels correction of an older request. */
    fun requested(intendedMs: Long, originNativeMs: Long, nowMs: Long, declaredDurationMs: Long) {
        // Eligibility is based solely on the user-selected time versus the
        // duration declared at that request, never on VLC's later landing.
        if (declaredDurationMs <= 0L || intendedMs > declaredDurationMs) {
            clear()
            return
        }
        pending = Pending(intendedMs.coerceAtLeast(0L), originNativeMs.coerceAtLeast(0L), nowMs)
    }

    fun observe(
        nativeMs: Long, nowMs: Long, playing: Boolean, buffering: Boolean,
    ): Decision? {
        val p = pending ?: return null
        // The original requested() check is final for this seek.
        // A wrong landing beyond declared EOF must not disable correction.
        if (nowMs < p.lastCommandAtMs) return null
        // Do not allow indefinite correction after the user has moved on.
        if (nowMs - p.startedAtMs > MAX_REQUEST_LIFETIME_MS) {
            pending = null
            return null
        }
        if (!playing || buffering || nativeMs < 0L) {
            // A pause or buffering interval must never be counted as elapsed
            // PLAYBACK time, or an accurate seek can look several seconds late.
            p.lastActiveAtMs = null
            p.lastNativeMs = null
            p.lastSampleAtMs = 0L
            return null
        }
        val previousActive = p.lastActiveAtMs
        if (previousActive != null) {
            val gap = nowMs - previousActive
            if (gap in 0L..MAX_ACTIVE_SAMPLE_GAP_MS) p.activeElapsedMs += gap
        }
        p.lastActiveAtMs = nowMs
        val elapsed = nowMs - p.lastCommandAtMs
        // The demuxer can briefly report stale pre-seek timestamps while it
        // finishes moving. Require a settle window and successive new samples.
        if (elapsed < MIN_SETTLE_MS) return null
        if (p.activeElapsedMs > GIVE_UP_ACTIVE_MS) {
            pending = null
            return Decision.Unreliable(p.intendedMs, nativeMs)
        }
        val previous = p.lastNativeMs
        val previousAt = p.lastSampleAtMs
        p.lastNativeMs = nativeMs
        p.lastSampleAtMs = nowMs
        if (previous == null || nowMs - previousAt < SAMPLE_GAP_MS) return null
        // Clock must be ADVANCING normally; a stuck position or demux rebase
        // cannot prove that the decoded video landed at this position.
        val diff = nativeMs - previous
        if (diff < MIN_CLOCK_ADVANCE_MS ||
            diff > (nowMs - previousAt) + MAX_CLOCK_JITTER_MS) return null
        val movedFromOrigin = abs(nativeMs - p.originMs) > 3_000L
        if (p.retries == 0 && !movedFromOrigin &&
            abs(p.intendedMs - p.originMs) > 10_000L) return null

        // Compare with actual active playback time, never wall-clock delay
        // spent buffering/paused. If already within a fine 3-second margin,
        // accept the seek and stop observing; do not issue a second command.
        val expectedNativeMs = p.intendedMs + p.activeElapsedMs
        val errorMs = nativeMs - expectedNativeMs
        if (abs(errorMs) <= ACCEPTABLE_ERROR_MS) {
            pending = null
            return null
        }
        if (p.retries != 0) {
            pending = null
            return Decision.Unreliable(p.intendedMs, nativeMs)
        }
        val compensated = correctionTarget(p.intendedMs, nativeMs, p.activeElapsedMs)
        // Eligibility cannot be changed by a compensated command crossing
        // the declared endpoint. Existing one-retry and error bounds remain.
        if (compensated == null || abs(compensated - p.intendedMs) < 2_000L) {
            pending = null
            return Decision.Unreliable(p.intendedMs, nativeMs)
        }
        p.retries = 1
        p.lastNativeMs = null
        p.lastSampleAtMs = 0L
        p.lastCommandAtMs = nowMs
        p.activeElapsedMs = 0L
        p.lastActiveAtMs = null
        return Decision.RetryAt(compensated, p.intendedMs, nativeMs)
    }

    companion object {
        private const val MIN_SETTLE_MS = 1_650L
        private const val SAMPLE_GAP_MS = 300L
        private const val GIVE_UP_ACTIVE_MS = 7_000L
        private const val MAX_REQUEST_LIFETIME_MS = 30_000L
        private const val MAX_ACTIVE_SAMPLE_GAP_MS = 1_500L
        private const val MIN_CLOCK_ADVANCE_MS = 100L
        private const val MAX_CLOCK_JITTER_MS = 1_000L
        private const val ACCEPTABLE_ERROR_MS = 3_000L

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
