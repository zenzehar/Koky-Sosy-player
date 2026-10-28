package app.sokyiptv.ui.vod

import kotlin.math.abs

/**
 * Netflix-like trickplay scheduling, not a claim to Netflix's private code.
 *
 * A commercial storyboard service can deliver sprites with the stream; most
 * Xtream IPTV providers only deliver media. This plan emulates that experience
 * by building a full-film, progressive contact sheet from one separate,
 * low-priority frame extractor, keeping the player decoder untouched.
 */
internal object NetflixPreviewPolicy {
    const val BUCKET_MS = 5_000L

    fun bucket(timeMs: Long, durationMs: Long): Long =
        FullVideoThumbnailPlan.nearestBucket(timeMs, durationMs)

    /**
     * Key scene coverage first, then 60s/20s/10s and finally 5s intervals.
     * No playback-position dependency; avoid filling only the opening minutes.
     */
    fun progressive(durationMs: Long): List<Long> {
        if (durationMs < 60_000L) return emptyList()
        val last = (durationMs - 2_000L) / BUCKET_MS
        if (last < 1L) return emptyList()
        val covered = LinkedHashSet<Long>()
        fun add(time: Long) {
            val point = bucket(time, durationMs)
            if (point >= BUCKET_MS) covered.add(point)
        }
        for (i in 1..24) add(durationMs * i / 25L)
        // Uniform whole-duration passes: distant scenes appear before local
        // detail. Stage limits are measured in seconds, not image counts.
        for (interval in listOf(60_000L, 20_000L, 10_000L, 5_000L)) {
            var time = interval
            while (time <= last * BUCKET_MS) {
                add(time)
                time += interval
            }
        }
        return covered.toList()
    }

    /** The current cursor and its neighbours outrank speculative coverage. */
    fun aroundCursor(timeMs: Long, durationMs: Long): List<Long> {
        if (timeMs < 0L || durationMs < 60_000L) return emptyList()
        val wanted = bucket(timeMs, durationMs)
        return listOf(0L, 5_000L, -5_000L, 10_000L, -10_000L, 20_000L, -20_000L)
            .map { bucket(wanted + it, durationMs) }
            .filter { it >= BUCKET_MS }
            .distinct()
    }

    /** During rapid scrub preview the newest position, not all skipped steps. */
    fun shouldPrioritize(request: Long, lastHandled: Long, durationMs: Long): Boolean =
        request >= 0L && durationMs >= 60_000L &&
            abs(bucket(request, durationMs) - lastHandled) >= BUCKET_MS

    /** The fast seek overlay can select fewer images but does not alter seeks. */
    fun displayGranularity(heldMs: Long): Long = when {
        heldMs >= 2_500L -> 20_000L
        heldMs >= 900L -> 10_000L
        else -> 5_000L
    }
}
