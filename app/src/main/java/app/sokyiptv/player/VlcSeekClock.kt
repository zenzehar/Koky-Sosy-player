/*
 * GPL-3.0-or-later.
 * Protects the UI's logical position briefly while LibVLC settles an asynchronous seek.
 * This does not change LibVLC playback, decoding, caching, seek distance or event lifecycle.
 */
package app.sokyiptv.player

import kotlin.math.abs

internal class VlcSeekClock(
    private val toleranceMs: Long = 1_250L,
    private val settleTimeoutMs: Long = 3_000L,
) {
    private var pendingTargetMs: Long? = null
    private var lastSeekAtMs: Long = 0L

    /** Make the newest requested position immediately authoritative for subsequent taps. */
    fun requested(targetMs: Long, nowMs: Long): Long {
        pendingTargetMs = targetMs.coerceAtLeast(0L)
        lastSeekAtMs = nowMs
        return pendingTargetMs!!
    }

    /**
     * Call only after the native player has resumed moving following this
     * particular seek. If it landed incorrectly, display its real clock
     * instead of the optimistic requested timestamp.
     */
    fun confirmNative(nativeMs: Long): Long {
        pendingTargetMs = null
        return nativeMs.coerceAtLeast(0L)
    }

    /** Ignore a stale native clock only while the latest seek remains unacknowledged. */
    fun reconcile(nativeMs: Long, nowMs: Long): Long {
        val observed = nativeMs.coerceAtLeast(0L)
        val pending = pendingTargetMs ?: return observed
        val settled = abs(observed - pending) <= toleranceMs
        if (settled || nowMs < lastSeekAtMs ||
            nowMs - lastSeekAtMs >= settleTimeoutMs) {
            pendingTargetMs = null
            return observed
        }
        return pending
    }
}
