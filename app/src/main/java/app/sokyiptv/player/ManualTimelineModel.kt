package app.sokyiptv.player

/** Two explicit marks: intro ends at a point, credits start at a point. */
enum class MarkerRegion { INTRO, CREDITS }
data class TimelineRegion(val kind: MarkerRegion, val startFraction: Float, val endFraction: Float)
data class TimelineBoundary(val kind: MarkerRegion, val positionFraction: Float)

object ManualTimelineModel {
    /**
     * The intro visually occupies 0 -> its marked END; credits visually occupy
     * their marked START -> current episode's duration. Only two user marks exist.
     * Clamp to each episode's real duration; never extrapolate past the seekbar.
     */
    fun regions(marks: ManualSeriesSkips, totalMs: Long): List<TimelineRegion> {
        if (totalMs <= 0L) return emptyList()
        val out = mutableListOf<TimelineRegion>()
        if (marks.introValid()) {
            val end = fraction(marks.introEndMs, totalMs)
            if (end > 0f) out.add(TimelineRegion(MarkerRegion.INTRO, 0f, end))
        }
        if (marks.creditsValid() && marks.creditsStartMs < totalMs) {
            out.add(TimelineRegion(
                MarkerRegion.CREDITS, fraction(marks.creditsStartMs, totalMs), 1f
            ))
        }
        return out
    }
    fun boundaries(marks: ManualSeriesSkips, totalMs: Long): List<TimelineBoundary> {
        if (totalMs <= 0L) return emptyList()
        val out = mutableListOf<TimelineBoundary>()
        if (marks.introValid() && marks.introEndMs <= totalMs) {
            out.add(TimelineBoundary(MarkerRegion.INTRO, fraction(marks.introEndMs, totalMs)))
        }
        if (marks.creditsValid() && marks.creditsStartMs <= totalMs) {
            out.add(TimelineBoundary(MarkerRegion.CREDITS, fraction(marks.creditsStartMs, totalMs)))
        }
        return out
    }
    private fun fraction(ms: Long, totalMs: Long) =
        (ms.toDouble() / totalMs).toFloat().coerceIn(0f, 1f)
}
