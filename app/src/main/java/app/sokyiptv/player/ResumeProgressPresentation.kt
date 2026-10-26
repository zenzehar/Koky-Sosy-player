/* GPL-3.0-or-later: truthful Continue Watching card progress. */
package app.sokyiptv.player

/**
 * The playback position is always local Room state; catalogue runtimes are used
 * ONLY for display. An unknown duration must never be shown as "0m" or "0%".
 *
 * IPTV panels and native demuxers sometimes understate duration. The same 93%
 * safety margin as WatchProgressCapture avoids presenting bogus completion
 * percentages near an unreliable reported endpoint.
 */
internal object ResumeProgressPresentation {
    data class Summary(val durationMs: Long, val fraction: Float) {
        val isKnown: Boolean get() = durationMs > 0L

        fun label(positionMs: Long): String {
            val elapsed = positionMs.coerceAtLeast(0L)
            if (!isKnown) return if (elapsed >= 60_000L) {
                "${elapsed / 60_000L}m watched"
            } else {
                "${elapsed / 1_000L}s watched"
            }
            val percent = (fraction * 100f).toInt().coerceIn(0, 100)
            return "${percent}% · ${elapsed / 60_000L}m / ${durationMs / 60_000L}m"
        }
    }

    fun resolve(
        positionMs: Long,
        savedDurationMs: Long,
        catalogueDurationSeconds: Int?,
    ): Summary {
        val elapsed = positionMs.coerceAtLeast(0L)
        // Catalogue durations come from local Movie/Episode records, not a new
        // provider request. This function NEVER modifies saved positions.
        val fromCatalogue = catalogueDurationSeconds
            ?.takeIf { it in 60..86_400 }
            ?.toLong()?.times(1_000L) ?: 0L
        val duration = listOf(savedDurationMs, fromCatalogue).firstOrNull { candidate ->
            candidate in 60_000L..86_400_000L &&
                elapsed < candidate * 93L / 100L
        } ?: 0L
        return if (duration <= 0L) Summary(0L, 0f) else
            Summary(duration, (elapsed.toFloat() / duration.toFloat()).coerceIn(0f, 1f))
    }
}
