package app.sokyiptv.ui.vod

import kotlin.math.abs

/**
 * D-pad centre/OK on a focused seekbar serves two distinct actions:
 *  - Commit an outstanding moved playhead / reassert a seek still settling.
 *  - Toggle Play/Pause only after the decoder has caught up.
 *
 * UI clock values may jump to a seek target before the video renders it.
 * Keep the last submitted target and a short confirmation guard instead of
 * comparing two optimistic UI clocks alone.
 */
internal object SeekbarOkPolicy {
    const val POSITION_TOLERANCE_MS = 2_000L
    const val MIN_CONFIRMATION_MS = 900L

    enum class Action { COMMIT_SEEK, TOGGLE_PLAYBACK }

    fun action(
        selectedMs: Long,
        reportedPlaybackMs: Long,
        activelySelecting: Boolean,
        seekStillSettling: Boolean,
        submittedAtUptimeMs: Long,
        nowUptimeMs: Long,
    ): Action {
        if (selectedMs < 0L) return Action.TOGGLE_PLAYBACK
        if (activelySelecting &&
            abs(selectedMs - reportedPlaybackMs) > POSITION_TOLERANCE_MS)
            return Action.COMMIT_SEEK
        val recentlySubmitted = submittedAtUptimeMs > 0L &&
            nowUptimeMs >= submittedAtUptimeMs &&
            nowUptimeMs - submittedAtUptimeMs < MIN_CONFIRMATION_MS
        if (seekStillSettling || recentlySubmitted ||
            abs(selectedMs - reportedPlaybackMs) > POSITION_TOLERANCE_MS)
            return Action.COMMIT_SEEK
        return Action.TOGGLE_PLAYBACK
    }

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
