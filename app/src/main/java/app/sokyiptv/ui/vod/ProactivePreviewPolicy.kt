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
        val priority = listOf(
            fromMs + 30_000L, fromMs + 60_000L, fromMs + 120_000L,
            durationMs / 4, durationMs / 2, durationMs * 3 / 4,
            durationMs / 10, durationMs * 9 / 10,
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

    /** A small group around the selected scrub point, avoiding full-video scans. */
    fun requestedFrames(atMs: Long, durationMs: Long): List<Long> {
        if (durationMs < 60_000L || atMs < 0L) return emptyList()
        val last = (durationMs - 2_000L).coerceAtLeast(0L)
        return listOf(atMs, atMs + 25_000L, atMs - 25_000L)
            .map { it.coerceIn(2_000L, last) }.distinct()
    }

    /** A sparse storyboard is explicitly an approximate scene, not an exact seek frame. */
    fun maxApproximateDistanceMs(durationMs: Long): Long =
        (durationMs / 48L + 5_000L).coerceIn(12_000L, 180_000L)
}
