/* GPL-3.0-or-later. Fully passive, per-series VIDEO fingerprint matching.
 * No stream requests, extra decoders, chapters or manually supplied skip times.
 */
package app.sokyiptv.player

/** Captured from the video surface already playing on the device. */
data class SceneSample(val atMs: Long, val hash: Long, val creditsLike: Boolean)

data class EpisodeScenes(val episodeId: String, val totalMs: Long, val samples: List<SceneSample>)

data class RepeatedClip(val startMs: Long, val endMs: Long, val reference: List<SceneSample>)

data class DetectedSkip(val introEndMs: Long = -1L, val credits: Boolean = false)

/**
 * Recognition requires independent examples. A first-ever episode or a movie has
 * no trusted reference for its opening; we must not invent one. Generic visual
 * credits recognition is conservatively limited to the end of a film.
 */
object PassiveSkipDetector {
    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
    private const val STEP_MS = 3_000L
    private const val EARLY_LIMIT = 480_000L
    private const val MAX_CLIP_MS = 240_000L

    fun template(history: List<EpisodeScenes>, credits: Boolean = false): RepeatedClip? {
        val valid = history.filter { it.samples.size >= 10 }.takeLast(8)
        var best: RepeatedClip? = null
        var longest = 0L
        for (i in valid.indices) for (j in i + 1 until valid.size) {
            val a = valid[i]
            val b = valid[j]
            val left = a.samples.filter { allowed(it, a.totalMs, credits) }
            val right = b.samples.filter { allowed(it, b.totalMs, credits) }
            if (left.isEmpty() || right.isEmpty()) continue
            // Candidate matches binned by temporal offset. Different episodes
            // may truncate the opening so equality of raw timestamps is unsafe.
            val aligned = mutableMapOf<Int, MutableList<Pair<SceneSample, SceneSample>>>()
            left.forEach { one ->
                right.forEach { two ->
                    val relativeA = if (credits) a.totalMs - one.atMs else one.atMs
                    val relativeB = if (credits) b.totalMs - two.atMs else two.atMs
                    val offset = relativeB - relativeA
                    if (kotlin.math.abs(offset) <= 180_000L &&
                        distance(one.hash, two.hash) <= 10) {
                        val bucket = (offset / 9_000L).toInt()
                        aligned.getOrPut(bucket) { mutableListOf() }.add(one to two)
                    }
                }
            }
            for (pairs in aligned.values) {
                val sorted = pairs.sortedBy { it.second.atMs }
                var run = mutableListOf<Pair<SceneSample, SceneSample>>()
                fun offerRun() {
                    val distinct = run.distinctBy { it.second.atMs }
                    if (distinct.size < 8) return
                    val span = distinct.last().second.atMs - distinct.first().second.atMs
                    if (span < 24_000L || span > MAX_CLIP_MS) return
                    val from = distinct.first().second.atMs
                    val until = distinct.last().second.atMs
                    val reference = right.filter { it.atMs in from..until }
                    // Ignore dark/near-identical static cards and isolated matches;
                    // a template needs movement and multiple distinct signatures.
                    if (reference.map { it.hash }.distinct().size < 7) return
                    if (span > longest) {
                        longest = span
                        best = RepeatedClip(from, until, reference)
                    }
                }
                for (pair in sorted) {
                    val previous = run.lastOrNull()?.second?.atMs
                    if (previous != null && pair.second.atMs - previous > 15_000L) {
                        offerRun()
                        run = mutableListOf()
                    }
                    if (run.none { it.second.atMs == pair.second.atMs }) run.add(pair)
                }
                offerRun()
            }
        }
        return best
    }

    private fun allowed(sample: SceneSample, totalMs: Long, credits: Boolean): Boolean {
        if (credits) return totalMs > 0L && sample.atMs > totalMs * 75 / 100 &&
            totalMs - sample.atMs <= 900_000L
        return sample.atMs <= EARLY_LIMIT
    }

    fun detectIntro(
        current: List<SceneSample>, template: RepeatedClip?, nowMs: Long, totalMs: Long,
    ): Long {
        val clip = template ?: return -1L
        if (nowMs > EARLY_LIMIT || current.size < 3) return -1L
        val recent = current.takeLast(12).filter { it.atMs <= EARLY_LIMIT }
        val anchors = mutableListOf<Pair<SceneSample, SceneSample>>()
        recent.forEach { own ->
            clip.reference.forEach { ref ->
                if (distance(own.hash, ref.hash) <= 10) anchors.add(own to ref)
            }
        }
        if (anchors.size < 3) return -1L
        val offsets = anchors.groupBy { ((it.first.atMs - it.second.atMs) / 9_000L).toInt() }
        val match = offsets.values.maxByOrNull { it.map { p -> p.first.atMs }.distinct().size }
            ?.distinctBy { it.first.atMs }?.sortedBy { it.first.atMs } ?: return -1L
        if (match.size < 3 || match.last().first.atMs - match.first().first.atMs < 6_000L) return -1L
        val lastMatch = match.last()
        val target = nowMs + (clip.endMs - lastMatch.second.atMs)
        return if (target > nowMs + 8_000L && target <= nowMs + MAX_CLIP_MS &&
            (totalMs <= 0L || target < totalMs - 30_000L)) target else -1L
    }

    fun detectRepeatedCredits(
        current: List<SceneSample>, clip: RepeatedClip?, nowMs: Long, totalMs: Long,
    ): Boolean {
        if (clip == null || totalMs <= 0L || nowMs < totalMs * 75 / 100) return false
        val recent = current.takeLast(9)
        val hits = recent.count { own ->
            clip.reference.any { ref -> distance(own.hash, ref.hash) <= 10 }
        }
        return hits >= 4 && recent.size >= 4
    }

    /** Generic fallback for movie credits over a mostly-dark scrolling text card.
     * Conservative by design; credits over live-action footage may not be recognised.
     */
    fun detectVisualCredits(
        samples: List<SceneSample>, nowMs: Long, totalMs: Long,
    ): Boolean {
        if (totalMs <= 0 || nowMs < totalMs * 80 / 100 ||
            totalMs - nowMs > 900_000L) return false
        val recent = samples.takeLast(5)
        return recent.size >= 5 && recent.count { it.creditsLike } >= 4 &&
            recent.last().atMs - recent.first().atMs <= 24_000L
    }
}
