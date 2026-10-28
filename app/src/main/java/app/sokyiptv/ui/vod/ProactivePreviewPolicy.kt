package app.sokyiptv.ui.vod

import kotlin.math.abs

/**
 * Conservative eligibility: Android's metadata retriever is unreliable for
 * live/HLS/transport streams. Never probe an unknown IPTV URL or local recording.
 */
internal object ProactivePreviewPolicy {
    fun eligible(url: String, sdk: Int): Boolean {
        if (sdk < 27 || !(url.startsWith("https://", true) || url.startsWith("http://", true))) return false
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        // HLS manifests and live streams are not independently seekable VOD.
        if (path.endsWith(".m3u8") || path.endsWith(".mpd") || path.contains("/live/")) return false
        if (listOf(".mp4", ".m4v", ".mov", ".mkv", ".webm", ".ts", ".mpeg", ".mpg")
                .any { path.endsWith(it) }) return true
        // Recognise numeric Xtream-style extensionless movie/series VOD paths.
        // Never probe arbitrary extensionless or local URLs.
        val tail = path.substringAfterLast('/')
        return (path.contains("/series/") || path.contains("/movie/") || path.contains("/vod/")) &&
            tail.isNotEmpty() && tail.all { it.isDigit() }
    }

    /** At most 24 previews, spreading the first few across likely seeking destinations. */
    fun storyboard(durationMs: Long, fromMs: Long): List<Long> {
        if (durationMs < 60_000L) return emptyList()
        val last = (durationMs - 2_000L).coerceAtLeast(0L)
        // Cover the entire episode before filling in local details.
        val priority = listOf(
            durationMs / 2, durationMs / 4, durationMs * 3 / 4,
            durationMs / 10, durationMs * 9 / 10,
            fromMs + 30_000L, fromMs + 90_000L, fromMs - 30_000L,
        )
        val evenlySpaced = (1..24).map { i -> durationMs * i / 25L }
        val result = ArrayList<Long>(24)
        for (raw in priority + evenlySpaced) {
            val target = raw.coerceIn(0L, last)
            if (target < 2_000L || result.any { abs(it - target) < 5_000L }) continue
            result.add(target)
            if (result.size == 24) break
        }
        return result
    }

    /**
     * Progressive, evenly distributed contact sheet: the existing coarse
     * 24-frame overview is made first, then 48- and 96-slot midpoint passes.
     * On a supported VOD, rapid scrubbing uses these already-decoded frames,
     * rather than waiting for the primary video to seek or buffer.
     *
     * NOT literally every video frame: that would require tens of thousands
     * of bitmaps and is unsuitable for low-memory Android TV hardware.
     */
    fun detailedStoryboard(durationMs: Long, fromMs: Long): List<Long> {
        val initial = storyboard(durationMs, fromMs)
        if (initial.isEmpty()) return emptyList()
        val results = ArrayList<Long>(96)
        results.addAll(initial)
        val minimumSpacingMs = (durationMs / 250L).coerceAtLeast(250L)
        for (slots in listOf(48, 96, 192)) {
            for (i in 1 until slots step 2) {
                val time = (durationMs * i / slots).coerceIn(2_000L, durationMs - 2_000L)
                if (results.any { abs(it - time) < minimumSpacingMs }) continue
                results.add(time)
                if (results.size == 96) return results
            }
        }
        // Fill tiny gaps as needed without duplicating keyframe requests.
        for (i in 1..192) {
            val time = (durationMs * i / 193L).coerceIn(2_000L, durationMs - 2_000L)
            if (results.any { abs(it - time) < minimumSpacingMs }) continue
            results.add(time)
            if (results.size == 96) break
        }
        return results
    }

    /**
     * First prepare the 96 distributed overview samples, then gradually fill
     * the full movie at 10-second intervals. For videos over four hours the
     * spacing increases proportionally to avoid unbounded network extraction.
     * Returned timestamps are aligned to the disk-cache 10-second buckets.
     */
    fun tenSecondStoryboard(durationMs: Long, fromMs: Long): List<Long> {
        if (durationMs < 60_000L) return emptyList()
        val step = ((durationMs / 1_440L + 9_999L) / 10_000L)
            .coerceAtLeast(1L) * 10_000L
        val maxPoint = durationMs - 2_000L
        val frames = LinkedHashSet<Long>()
        fun add(raw: Long) {
            val aligned = ((raw + step / 2L) / step) * step
            if (aligned in 2_000L..maxPoint) frames.add(aligned)
        }
        detailedStoryboard(durationMs, fromMs).forEach(::add)
        // Coarse-to-fine ordering gives seekbar-wide coverage early instead of
        // fully caching the start before any distant previews exist.
        for (stride in listOf(12L, 6L, 3L, 1L)) {
            var i = stride
            while (i * step <= maxPoint && frames.size < 1_440) {
                frames.add(i * step)
                i += stride
            }
        }
        return frames.toList()
    }

    /**
     * v77: Twice the v76 density: five-second samples for up to four hours,
     * then the shortest multiple of five seconds that fits 2880 frames.
     *
     * Stages cover the entire film *before* filling local gaps. All output
     * locations are grid-aligned so disk cache hits are exact and reusable.
     * This plan is independent of resume/playback position.
     */
    fun fiveSecondStoryboard(durationMs: Long, fromMs: Long = 0L): List<Long> {
        if (durationMs < 60_000L) return emptyList()
        val maxTime = durationMs - 2_000L
        val step = (((durationMs + 2_879L) / 2_880L + 4_999L) / 5_000L)
            .coerceAtLeast(1L) * 5_000L
        val totalSlots = (maxTime / step).coerceAtMost(2_880L).toInt()
        if (totalSlots <= 0) return emptyList()

        val result = LinkedHashSet<Long>(totalSlots)
        fun add(requested: Long) {
            val target = ((requested + step / 2L) / step) * step
            if (target in step..maxTime) result.add(target)
        }
        // The first 24 scenes deliberately span the entire movie, not its
        // start or the current playback position.
        storyboard(durationMs, 0L).forEach(::add)
        // Fill a whole-video contact sheet in progressively finer passes.
        for (slots in listOf(48, 96, 192, 384, 768, 1536)) {
            for (i in 1 until slots) add(durationMs * i / slots)
            if (result.size >= totalSlots) break
        }
        // Complete every remaining 5-second bucket without linear playhead scanning.
        for (i in 1..totalSlots) result.add(i.toLong() * step)
        return result.toList()
    }

    /** A small group around the selected scrub point, avoiding full-video scans. */
    fun requestedFrames(atMs: Long, durationMs: Long): List<Long> {
        if (durationMs < 60_000L || atMs < 0L) return emptyList()
        val last = (durationMs - 2_000L).coerceAtLeast(0L)
        return listOf(atMs, atMs + 25_000L, atMs - 25_000L)
            .map { it.coerceIn(2_000L, last) }.distinct()
    }

    /** A sparse storyboard is explicitly an approximate scene, not an exact seek frame. */
    fun maxApproximateDistanceMs(durationMs: Long): Long =
        (durationMs / 14L + 8_000L).coerceIn(40_000L, 240_000L)
}
