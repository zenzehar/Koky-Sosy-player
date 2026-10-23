/* GPL-3.0-or-later. Safe seek correction only when no indexed byte seek is available. */
package app.sokyiptv.player

import kotlin.math.abs

/**
 * Time seeking is a fallback to TS indexed-byte seeking. The provider's
 * advertised duration may be too short. Near/after that boundary do NOT
 * issue any automatic second jump, even if native time is inaccurate.
 *
 * One small retry is permitted well inside a credible declared duration,
 * only after two advancing native-clock samples and only for moderate misses.
 * A user request always invalidates any older correction. The corrective
 * command MUST also remain well inside the declared end.
 */
internal class GuardedSeekFallback {
    sealed interface Decision {
        data class RetryAt(val commandMs: Long, val intendedMs: Long) : Decision
        data class Finished(val intendedMs: Long, val landedMs: Long, val succeeded: Boolean) : Decision
    }

    private data class Pending(
        val intendedMs: Long,
        val declaredMs: Long,
        val originMs: Long,
        var commandAtMs: Long,
        var retry: Boolean = false,
        var firstNativeMs: Long? = null,
        var firstAtMs: Long = 0L,
    )

    private var pending: Pending? = null
    var disabledForSession: Boolean = false
        private set

    fun cancel() { pending = null }

    fun requested(
        targetMs: Long, declaredMs: Long, originMs: Long, nowMs: Long,
        indexedSeekAvailable: Boolean,
    ): Boolean {
        pending = null
        // Short +/-10s D-pad taps are never allowed to trigger delayed
        // automatic seeks. Preserve the user's selected step precisely.
        if (indexedSeekAvailable || disabledForSession ||
            abs(targetMs - originMs) < 25_000L ||
            !wellInside(targetMs, declaredMs)) return false
        pending = Pending(targetMs, declaredMs, originMs.coerceAtLeast(0), nowMs)
        return true
    }

    fun observe(
        nativeMs: Long, nowMs: Long, playing: Boolean, buffering: Boolean,
        currentDeclaredMs: Long,
    ): Decision? {
        val p = pending ?: return null
        // Any changed/unknown duration invalidates assumptions used to
        // establish the safe correction boundary.
        if (currentDeclaredMs <= 0L ||
            !wellInside(p.intendedMs, currentDeclaredMs) ||
            currentDeclaredMs != p.declaredMs) {
            pending = null
            return null
        }
        if (!playing || buffering || nativeMs < 0L || nowMs < p.commandAtMs) {
            p.firstNativeMs = null
            return null
        }
        val elapsed = nowMs - p.commandAtMs
        if (elapsed > GIVE_UP_MS) {
            pending = null
            return null
        }
        if (elapsed < SETTLE_MS) return null
        val prev = p.firstNativeMs
        val prevAt = p.firstAtMs
        p.firstNativeMs = nativeMs
        p.firstAtMs = nowMs
        if (prev == null || nowMs - prevAt < SAMPLE_GAP_MS) return null
        val advance = nativeMs - prev
        if (advance < 150L || advance > nowMs - prevAt + 1_500L) return null

        // The old pre-seek native clock can continue ticking before VLC
        // reports a new landing. Never correct on an unchanged origin clock.
        if (!p.retry && abs(p.intendedMs - p.originMs) >= 15_000L &&
            abs(nativeMs - p.originMs) <= 3_000L) return null

        val errorMs = nativeMs - (p.intendedMs + elapsed.coerceAtMost(3_000L))
        if (p.retry) {
            pending = null
            val success = abs(errorMs) <= ALLOWED_ERROR_MS
            if (!success) disabledForSession = true
            return Decision.Finished(p.intendedMs, nativeMs, success)
        }
        if (abs(errorMs) <= ALLOWED_ERROR_MS) {
            pending = null
            return null
        }
        // Very large jumps are evidence of a broken demux/server index, not
        // an error a small time adjustment can safely resolve.
        if (abs(errorMs) > MAX_MEASURABLE_MISS_MS) {
            pending = null
            disabledForSession = true
            return Decision.Finished(p.intendedMs, nativeMs, false)
        }
        // Proportional gain <1 avoids ping-pong, without trying to infer an
        // unbounded scaling ratio from incorrect stream clocks.
        val adjustment = (errorMs / 2L).coerceIn(-MAX_RETRY_DELTA_MS, MAX_RETRY_DELTA_MS)
        val nextCommand = p.intendedMs - adjustment
        if (!wellInside(nextCommand, p.declaredMs) || abs(adjustment) < 2_000L) {
            pending = null
            return Decision.Finished(p.intendedMs, nativeMs, false)
        }
        p.retry = true
        p.commandAtMs = nowMs
        p.firstNativeMs = null
        p.firstAtMs = 0L
        return Decision.RetryAt(nextCommand, p.intendedMs)
    }

    companion object {
        private const val SETTLE_MS = 1_800L
        private const val SAMPLE_GAP_MS = 350L
        private const val GIVE_UP_MS = 9_000L
        private const val ALLOWED_ERROR_MS = 7_000L
        private const val MAX_MEASURABLE_MISS_MS = 35_000L
        private const val MAX_RETRY_DELTA_MS = 12_000L
        private const val MIN_DECLARED_MS = 5 * 60_000L

        /** Suppress corrections in the last 12.5% or last 90 seconds. */
        fun safeUpperMs(declaredMs: Long): Long =
            declaredMs - maxOf(90_000L, declaredMs / 8L)

        fun wellInside(targetMs: Long, declaredMs: Long): Boolean =
            declaredMs >= MIN_DECLARED_MS &&
                targetMs >= 60_000L &&
                targetMs < safeUpperMs(declaredMs)
    }
}
