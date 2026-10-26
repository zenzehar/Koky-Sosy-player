/* GPL-3.0-or-later. Safe, episode-specific skip cues. */
package app.sokyiptv.player

data class SkipCues(
    val introStartMs: Long = -1L,
    val introEndMs: Long = -1L,
    val creditsStartMs: Long = -1L,
) {
    fun hasIntro() = introStartMs >= 0L && introEndMs - introStartMs >= 8_000L
    fun hasCredits() = creditsStartMs >= 0L
}
data class VideoChapter(val startMs: Long, val durationMs: Long, val title: String)

/** No cross-episode timestamp guessing: IPTV openings may be shortened or absent. */
object SkipCuePolicy {
    private val opening = Regex("(?i)(?:intro|opening[\\s_-]*(?:credits|theme|sequence)|^op$)")
    private val credits = Regex("(?i)(?:end[\\s_-]*credits|closing[\\s_-]*credits|outro|credits[\\s_-]*roll|ending[\\s_-]*theme)")
    fun fromChapters(chapters: List<VideoChapter>, durationMs: Long): SkipCues {
        if (durationMs < 60_000L) return SkipCues()
        val list = chapters.filter { it.startMs in 0 until durationMs }.sortedBy { it.startMs }
        var introStart = -1L
        var introEnd = -1L
        var creditsStart = -1L
        list.forEachIndexed { index, chapter ->
            val next = list.getOrNull(index + 1)?.startMs ?: durationMs
            val end = if (chapter.durationMs > 0L) {
                chapter.startMs + chapter.durationMs.coerceAtMost(durationMs - chapter.startMs)
            } else next
            val safeEnd = minOf(end, next, durationMs)
            if (introStart < 0L && opening.containsMatchIn(chapter.title) &&
                chapter.startMs <= minOf(600_000L, durationMs / 4) &&
                safeEnd - chapter.startMs in 8_000L..240_000L) {
                introStart = chapter.startMs
                introEnd = safeEnd
            }
            if (creditsStart < 0L && credits.containsMatchIn(chapter.title) &&
                chapter.startMs >= durationMs * 65 / 100 &&
                durationMs - chapter.startMs in 8_000L..1_200_000L) {
                creditsStart = chapter.startMs
            }
        }
        return SkipCues(introStart, introEnd, creditsStart)
    }
    fun showIntro(pos: Long, cues: SkipCues): Boolean =
        cues.hasIntro() && pos >= cues.introStartMs && pos < cues.introEndMs - 3_000L
    fun showCredits(pos: Long, total: Long, cues: SkipCues): Boolean =
        cues.hasCredits() && total > 0L && cues.creditsStartMs < total - 5_000L &&
            pos >= cues.creditsStartMs && pos < total
}
