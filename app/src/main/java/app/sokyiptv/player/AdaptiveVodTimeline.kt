/* GPL-3.0-or-later: adaptive display horizon; does not assert actual media duration. */
package app.sokyiptv.player

/** Declared duration can be a defective container/index estimate on IPTV VOD. */
internal object AdaptiveVodTimeline {
    private const val MIN_HORIZON_MS = 60_000L
    private const val MIN_HEADROOM_MS = 10 * 60_000L
    private const val MAX_HORIZON_MS = 48 * 60 * 60_000L

    data class Scale(val maximumMs: Long, val approximate: Boolean)

    fun scale(reportedMs: Long, observedMs: Long, previewMs: Long = observedMs): Scale {
        val declared = reportedMs.coerceIn(0L, MAX_HORIZON_MS)
        val observed = observedMs.coerceIn(0L, MAX_HORIZON_MS)
        val preview = previewMs.coerceIn(0L, MAX_HORIZON_MS)
        val current = maxOf(observed, preview)
        val nearDeclaredEnd = declared <= 0L || current >= (declared * 85L / 100L)
        if (!nearDeclaredEnd) return Scale(declared.coerceAtLeast(MIN_HORIZON_MS), false)
        val headroom = maxOf(MIN_HEADROOM_MS, declared / 5L)
        val horizon = maxOf(
            declared,
            (declared + headroom).coerceAtMost(MAX_HORIZON_MS),
            (current + headroom).coerceAtMost(MAX_HORIZON_MS),
            MIN_HORIZON_MS,
        )
        // The displayed scale is an editing/seek horizon, not a discovered end.
        return Scale(horizon, true)
    }

    fun fraction(timeMs: Long, scale: Scale): Float =
        (timeMs.coerceIn(0L, scale.maximumMs).toDouble() /
            scale.maximumMs.coerceAtLeast(1L).toDouble()).toFloat()
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
