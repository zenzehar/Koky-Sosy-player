package app.sokyiptv.ui.vod

/**
 * True whole-video five-second timeline. No fixed frame-count cap:
 * RAM and on-disk storage have separate policies.
 *
 * Progressive broad-to-fine passes mean that early cache work spreads over
 * the whole film before filling the gaps. The current playback position is
 * deliberately NOT an input to this plan.
 */
internal object FullVideoThumbnailPlan {
    const val SPACING_MS = 5_000L

    fun supportedSource(url: String, sdk: Int): Boolean {
        if (sdk < 27 || !(url.startsWith("https://", true) ||
                    url.startsWith("http://", true))) return false
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        if (path.endsWith(".m3u8") || path.endsWith(".mpd") ||
            path.contains("/live/")) return false
        if (listOf(".mp4", ".m4v", ".mov", ".mkv", ".webm", ".ts", ".mpeg", ".mpg")
                .any { path.endsWith(it) }) return true
        return (path.contains("/movie/") || path.contains("/series/") ||
            path.contains("/vod/")) &&
            path.substringAfterLast('/').let { it.isNotEmpty() && it.all { ch -> ch.isDigit() } }
    }

    fun nearestBucket(timeMs: Long, durationMs: Long): Long {
        if (durationMs < SPACING_MS + 2_000L) return -1L
        val last = (durationMs - 2_000L) / SPACING_MS * SPACING_MS
        return ((timeMs + SPACING_MS / 2L) / SPACING_MS * SPACING_MS)
            .coerceIn(SPACING_MS, last)
    }

    fun orderedTimeline(durationMs: Long): List<Long> {
        if (durationMs < 60_000L) return emptyList()
        val count = ((durationMs - 2_000L) / SPACING_MS).toInt()
        if (count <= 0) return emptyList()
        val list = LinkedHashSet<Long>(count)
        // First ~24 samples spanning the *entire* video, never around playhead.
        for (i in 1..24) {
            val slot = (count.toLong() * i / 25L).toInt().coerceIn(1, count)
            list.add(slot * SPACING_MS)
        }
        // Coarse-to-fine, with no 96, 192 or 2880-thumbnail total cap.
        for (stride in listOf(64, 32, 16, 8, 4, 2, 1)) {
            var index = stride
            while (index <= count) {
                list.add(index.toLong() * SPACING_MS)
                index += stride
            }
        }
        return list.toList()
    }
}
