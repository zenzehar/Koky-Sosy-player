package app.sokyiptv.player

/** Normalized regions on the timeline; no inferred or automatic skip locations. */
enum class MarkerRegion { INTRO, CREDITS }
data class TimelineRegion(val kind: MarkerRegion, val startFraction: Float, val endFraction: Float)
data class TimelineBoundary(val kind: MarkerRegion, val positionFraction: Float)

/**
 * Map saved episode-relative milliseconds to ANY episode's actual seek duration.
 * Markers are shared by all episodes under the same provider/series key.
 * Incomplete ranges render their endpoints as ticks, but never a skip region.
 */
object ManualTimelineModel {
    fun regions(marks: ManualSeriesSkips, totalMs: Long): List<TimelineRegion> {
        if (totalMs <= 0L) return emptyList()
        val result = mutableListOf<TimelineRegion>()
        if (marks.introValid()) region(MarkerRegion.INTRO,
            marks.introStartMs, marks.introEndMs, totalMs)?.let(result::add)
        if (marks.creditsValid()) region(MarkerRegion.CREDITS,
            marks.creditsStartMs, marks.creditsEndMs, totalMs)?.let(result::add)
        return result
    }
    fun boundaries(marks: ManualSeriesSkips, totalMs: Long): List<TimelineBoundary> {
        if (totalMs <= 0L) return emptyList()
        val result = mutableListOf<TimelineBoundary>()
        fun add(kind: MarkerRegion, value: Long) {
            if (value >= 0L && value <= totalMs) result.add(
                TimelineBoundary(kind, value.toDouble().div(totalMs).toFloat().coerceIn(0f, 1f))
            )
        }
        add(MarkerRegion.INTRO, marks.introStartMs)
        add(MarkerRegion.INTRO, marks.introEndMs)
        add(MarkerRegion.CREDITS, marks.creditsStartMs)
        add(MarkerRegion.CREDITS, marks.creditsEndMs)
        return result
    }
    private fun region(kind: MarkerRegion, start: Long, end: Long,
        total: Long): TimelineRegion? {
        if (start >= total || end <= 0L) return null
        val a = start.toDouble().div(total).toFloat().coerceIn(0f, 1f)
        val b = end.toDouble().div(total).toFloat().coerceIn(0f, 1f)
        return if (b > a) TimelineRegion(kind, a, b) else null
    }
}
