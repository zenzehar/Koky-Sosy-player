package app.sokyiptv.ui.vod

import kotlin.math.abs

/**
 * OK on the seekbar has one firm distinction:
 *
 * - An UNCOMMITTED cursor move needs a seek command.
 * - An already submitted seek (including decoder buffering/settling) needs
 *   Play/Pause, never a duplicate seek that resets the network/decoder work.
 *
 * Native playback position can remain stale or optimistic while buffering.
 * It must not be used as a reason to reissue a committed seek. A separate
 * narrowly gated native feedback controller handles genuinely wrong landings.
 */
internal object SeekbarOkPolicy {
    const val POSITION_TOLERANCE_MS = 2_000L
    const val MIN_CONFIRMATION_MS = 900L

    enum class Action { COMMIT_SEEK, TOGGLE_PLAYBACK }

    @Suppress("UNUSED_PARAMETER")
    fun action(
        selectedMs: Long,
        reportedPlaybackMs: Long,
        activelySelecting: Boolean,
        seekStillSettling: Boolean,
        submittedAtUptimeMs: Long,
        nowUptimeMs: Long,
    ): Action =
        if (activelySelecting && selectedMs >= 0L &&
            abs(selectedMs - reportedPlaybackMs) > POSITION_TOLERANCE_MS)
            Action.COMMIT_SEEK
        else
            Action.TOGGLE_PLAYBACK

    /**
     * Only dismiss the pending highlight when playback has actually caught up.
     * This is display-only, not a condition that blocks pausing or resuming.
     */
    fun caughtUp(
        selectedMs: Long,
        reportedPlaybackMs: Long,
        seekStillSettling: Boolean,
        submittedAtUptimeMs: Long,
        nowUptimeMs: Long,
    ): Boolean =
        selectedMs >= 0L && !seekStillSettling &&
        submittedAtUptimeMs > 0L &&
        nowUptimeMs - submittedAtUptimeMs >= MIN_CONFIRMATION_MS &&
        abs(selectedMs - reportedPlaybackMs) <= POSITION_TOLERANCE_MS
}
