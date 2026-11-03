package app.sokyiptv.ui.vod

/**
 * Some IPTV servers close a broken VOD connection in a way that a decoder
 * reports EndReached/ENDED. Completion must not be inferred from that signal
 * alone: an episode only auto-advances when there was genuine playback and the
 * final position is close to a real, positive declared duration.
 *
 * Deliberately conservative: unknown-length or interrupted VOD must stay on the
 * current episode and offer a retry, never silently advance to a new episode.
 */
internal object VodEndGuard {
    fun confirmed(
        hadPlayback: Boolean,
        playbackFailed: Boolean,
        durationMs: Long,
        lastPositionMs: Long,
    ): Boolean {
        if (!hadPlayback || playbackFailed || durationMs <= 0L || lastPositionMs < 0L)
            return false
        // Allow final decoder clock quantisation and buffering while rejecting
        // early termination. A 2% tolerance is capped at ten seconds.
        val tolerance = (durationMs / 50L).coerceIn(2_000L, 10_000L)
        return lastPositionMs >= durationMs - tolerance
    }
}
