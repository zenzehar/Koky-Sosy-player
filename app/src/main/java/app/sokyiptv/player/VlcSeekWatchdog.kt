/* GPL-3.0-or-later: pure state machine for VLC seek health monitoring. */
package app.sokyiptv.player

/**
 * Detects lack of forward native-time progression after VLC seek.
 * A seek jump alone is not proof of actual resumed playback.
 * This class never owns a player, view or native surface.
 */
class VlcSeekWatchdog(
    private val nudgeAfterMillis: Long = 7_000L,
    private val restartAfterMillis: Long = 17_000L,
) {
    enum class Action { NONE, NUDGE, RESTART }

    private var issuedAtMillis: Long? = null
    private var baselinePositionMillis: Long? = null
    private var nudged = false

    val isIdle: Boolean get() = issuedAtMillis == null

    fun begin(nowMillis: Long) {
        issuedAtMillis = nowMillis
        baselinePositionMillis = null
        nudged = false
    }

    fun reset() {
        issuedAtMillis = null
        baselinePositionMillis = null
        nudged = false
    }

    fun observe(nowMillis: Long, nativePositionMillis: Long, shouldBePlaying: Boolean): Action {
        val startedAt = issuedAtMillis ?: return Action.NONE
        if (!shouldBePlaying) {
            // Deliberate Pause must never provoke decoder restart.
            issuedAtMillis = nowMillis
            baselinePositionMillis = null
            nudged = false
            return Action.NONE
        }
        val baseline = baselinePositionMillis
        if (baseline == null) {
            baselinePositionMillis = nativePositionMillis
        } else if (kotlin.math.abs(nativePositionMillis - baseline) > 2_000L) {
            // Large discontinuity is probably the initial seek jump, not decoded playback.
            baselinePositionMillis = nativePositionMillis
        } else if (nativePositionMillis - baseline >= 800L) {
            reset()
            return Action.NONE
        }

        val elapsed = (nowMillis - startedAt).coerceAtLeast(0L)
        if (nudged && elapsed >= restartAfterMillis) {
            reset()
            return Action.RESTART
        }
        if (!nudged && elapsed >= nudgeAfterMillis) {
            nudged = true
            return Action.NUDGE
        }
        return Action.NONE
    }
}
