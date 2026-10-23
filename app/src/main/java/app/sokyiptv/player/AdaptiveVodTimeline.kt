/* GPL-3.0-or-later: declared-duration VOD timeline; adaptive growth is disabled. */
package app.sokyiptv.player

/** Fixed visual duration. Actual time seeks can still exceed an understated declaration. */
internal object AdaptiveVodTimeline {
    private const val UNKNOWN_HORIZON_MS = 60_000L

    data class Scale(val maximumMs: Long, val approximate: Boolean = false)

    // Observed and preview times intentionally do not change the displayed endpoint.
    fun scale(reportedMs: Long, observedMs: Long, previewMs: Long = observedMs): Scale =
        Scale(reportedMs.takeIf { it > 0L } ?: UNKNOWN_HORIZON_MS)

    // Once playback passes declared EOF the bar remains full rather than stretching.
    fun fraction(timeMs: Long, scale: Scale): Float =
        (timeMs.coerceAtLeast(0L).toDouble() / scale.maximumMs.toDouble())
            .coerceIn(0.0, 1.0).toFloat()
}

/** The transport bar must remain visible following a seek until native time advances. */
internal class PostSeekPlaybackGate {
    var waiting: Boolean = false
        private set
    private var startedMs: Long = 0L
    private var previousMs: Long? = null
    private var previousAtMs: Long = 0L

    fun requested(nowMs: Long) {
        waiting = true
        startedMs = nowMs
        previousMs = null
        previousAtMs = 0L
    }

    /** Returns true only on playback confirmation after a seek. */
    fun observe(nowMs: Long, nativeMs: Long, playing: Boolean, buffering: Boolean): Boolean {
        if (!waiting) return false
        if (buffering || !playing || nativeMs < 0L) {
            previousMs = null
            return false
        }
        // Avoid clearing the wait on a pre-seek clock that is still advancing.
        if (nowMs - startedMs < 900L) return false
        val lastMs = previousMs
        val lastAt = previousAtMs
        previousMs = nativeMs
        previousAtMs = nowMs
        if (lastMs == null || nowMs - lastAt < 250L) return false
        val movement = nativeMs - lastMs
        if (movement < 150L || movement > (nowMs - lastAt) + 1_000L) return false
        waiting = false
        return true
    }
}
