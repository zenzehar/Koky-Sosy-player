/* GPL-3.0-or-later: elapsed-time playback progress capture. */
package app.sokyiptv.player

import app.sokyiptv.data.model.PlaybackPosition

/**
 * Persist elapsed playback even when an IPTV server/player cannot determine
 * the full stream duration. Unknown or contradictory durations are saved as 0,
 * so an arbitrary duration cannot incorrectly mark a title finished at 95%.
 * A card may still display elapsed minutes and offer Resume with 0% unknown.
 */
internal object WatchProgressCapture {
    private const val MIN_ELAPSED_MS = 5_000L

    fun snapshot(
        profileId: Long,
        mediaKey: String,
        elapsedMs: Long,
        reportedDurationMs: Long,
        updatedAtMs: Long,
    ): PlaybackPosition? {
        if (profileId <= 0L || mediaKey.isBlank() ||
            elapsedMs <= MIN_ELAPSED_MS || updatedAtMs <= 0L) return null

        // Do not invent a movie length from elapsed time: completed status
        // and progress percentages require credible media duration metadata.
        val usableDuration = reportedDurationMs.takeIf {
            it > 0L && elapsedMs <= it + 2_000L
        } ?: 0L

        return PlaybackPosition(
            profileId = profileId,
            mediaKey = mediaKey,
            positionMillis = elapsedMs,
            durationMillis = usableDuration,
            updatedAtMillis = updatedAtMs,
        )
    }
}
