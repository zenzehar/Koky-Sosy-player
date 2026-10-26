/* GPL-3.0-or-later: Continue Watching labels, from the SAME saved position used by Resume. */
package app.sokyiptv.ui.vod

/**
 * IPTV servers often return missing or incorrect duration metadata. Never show a
 * fabricated 0% / 0m for an otherwise valid watch position. Only show an actual
 * percentage and progress fill when the stored total is credible.
 */
internal object ResumeCardTime {
    fun format(positionMs: Long, durationMs: Long): String {
        val elapsed = clock(positionMs.coerceAtLeast(0L))
        val fraction = fraction(positionMs, durationMs)
        return if (fraction == null) {
            "Resume at $elapsed"
        } else {
            "$elapsed / ${clock(durationMs)} · ${(fraction * 100f).toInt()}%"
        }
    }

    fun fraction(positionMs: Long, durationMs: Long): Float? {
        if (positionMs < 0L || durationMs <= positionMs || durationMs <= 0L) return null
        return (positionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    private fun clock(ms: Long): String {
        val secs = ms / 1_000L
        val hours = secs / 3_600L
        val minutes = (secs / 60L) % 60L
        val seconds = secs % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(java.util.Locale.US, hours, minutes, seconds)
        } else {
            "%d:%02d".format(java.util.Locale.US, minutes, seconds)
        }
    }
}
