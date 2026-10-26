package app.sokyiptv.ui.vod

import kotlin.math.abs

/**
 * Pure, TV-independent rules for scrubbing thumbnails and time display.
 * Only a decoded frame recorded within 2.6 seconds of the selected instant
 * may be shown as its visual preview. Every unknown instant stays image-free.
 */
internal object NetflixSeekPreviewPolicy {
    fun frameMatches(sampleMs: Long, targetMs: Long): Boolean =
        sampleMs >= 0L && targetMs >= 0L && abs(sampleMs - targetMs) <= 2_600L

    fun clampPreview(positionMs: Long, durationMs: Long): Long =
        if (durationMs <= 0L) positionMs.coerceAtLeast(0L)
        else positionMs.coerceIn(0L, durationMs)

    fun remainingMs(positionMs: Long, durationMs: Long): Long =
        (durationMs - clampPreview(positionMs, durationMs)).coerceAtLeast(0L)
}
