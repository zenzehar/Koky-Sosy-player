/* GPL-3.0-or-later. Seek Solution 3: a truthful, local time ruler near unreliable EOF. */
package app.sokyiptv.player

/**
 * A fixed-width ten-minute seek ruler near an untrustworthy provider end.
 * It is a time *window*, not a duration claim. It moves only when the
 * playhead leaves the central 80%, so playback does not jitter the scale.
 * Never modifies the native seek command or the decoder.
 */
internal class NearEndSeekWindow {
    data class Range(
        val fromMs: Long,
        val toMs: Long,
        val zoomed: Boolean,
    ) {
        fun fraction(positionMs: Long): Float =
            ((positionMs.coerceIn(fromMs, toMs) - fromMs).toDouble() /
                (toMs - fromMs).coerceAtLeast(1L).toDouble()).toFloat()
    }

    private var fromMs = 0L
    private var wasZoomed = false

    fun window(reportedMs: Long, actualMs: Long, previewMs: Long = actualMs): Range {
        val reported = reportedMs.coerceAtLeast(0L)
        val actual = actualMs.coerceAtLeast(0L)
        val preview = previewMs.coerceAtLeast(0L)
        val nearEnd = if (reported > 0L) {
            actual >= reported * 85L / 100L || preview >= reported * 85L / 100L
        } else {
            actual >= 5L * 60_000L || preview >= 5L * 60_000L
        }
        // Keep near-end mode through moderate rewind, but return to the
        // original whole-file timeline well before the boundary.
        val zoom = nearEnd || (wasZoomed &&
            (reported == 0L || actual >= reported * 70L / 100L))
        if (!zoom) {
            wasZoomed = false
            fromMs = 0L
            return Range(0L, maxOf(reported, actual, preview, 60_000L), false)
        }
        val cursor = if (preview != actual) preview else actual
        if (!wasZoomed ||
            cursor < fromMs + EDGE_MS ||
            cursor > fromMs + WIDTH_MS - EDGE_MS) {
            fromMs = (cursor - WIDTH_MS / 2L).coerceAtLeast(0L)
        }
        wasZoomed = true
        return Range(fromMs, fromMs + WIDTH_MS, true)
    }

    companion object {
        private const val WIDTH_MS = 10L * 60_000L
        private const val EDGE_MS = 60_000L
    }
}
