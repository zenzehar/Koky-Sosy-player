/* GPL-3.0-or-later. Local visual fingerprint sequence matching, no network, no chapters. */
package app.sokyiptv.player

import kotlin.math.abs

data class SceneSample(val atMs: Long, val hash: Long, val creditsLike: Boolean)
data class EpisodeScenes(val episodeId: String, val totalMs: Long, val samples: List<SceneSample>)
data class RepeatedClip(val startMs: Long, val endMs: Long, val reference: List<SceneSample>)
data class DetectedSkip(val introEndMs: Long = -1L, val credits: Boolean = false)

/**
 * Frames from independent episode decoders cannot be sampled at precisely equal
 * points in time. Comparing exact sample timestamps or exact dHashes (as v61/v62
 * did) discards genuine opening sequences. This algorithm votes on relative
 * temporal offsets and then verifies a moving, monotonically ordered sequence.
 *
 * It makes NO claim to identify the end of an opening from a single episode.
 * Two independent previous recordings are needed to infer a repeatable boundary.
 */
object PassiveSkipDetector {
    private const val EARLY_MS = 480_000L
    private const val MAX_CLIP_MS = 240_000L
    private const val MAX_ALIGNMENT_MS = 180_000L
    private const val OFFSET_BIN_MS = 5_000L
    private const val MAX_TIME_JITTER_MS = 5_000L
    private const val MAX_FRAME_DISTANCE = 19
    private const val MAX_GAP_MS = 15_000L
    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    private data class PairHit(val a: SceneSample, val b: SceneSample, val distance: Int) {
        val offset: Long get() = b.atMs - a.atMs
    }

    fun template(history: List<EpisodeScenes>, credits: Boolean = false): RepeatedClip? {
        val valid = history.filter { episode ->
            episode.samples.count { allowed(it, episode.totalMs, credits) } >= 8
        }.takeLast(8)
        if (valid.size < 2) return null
        var best: RepeatedClip? = null
        var bestScore = -1L
        for (i in valid.indices) for (j in (i + 1) until valid.size) {
            val a = valid[i]
            val b = valid[j]
            val left = a.samples.filter { allowed(it, a.totalMs, credits) }
                .sortedBy { it.atMs }
            val right = b.samples.filter { allowed(it, b.totalMs, credits) }
                .sortedBy { it.atMs }
            // Candidate offsets need a rough 5s bin only for *discovery*.
            // Verification below permits sampling jitter across bin boundaries.
            val votes = HashMap<Long, Int>()
            for (x in left) for (y in right) {
                val from = if (credits) a.totalMs - x.atMs else x.atMs
                val to = if (credits) b.totalMs - y.atMs else y.atMs
                val delta = to - from
                if (abs(delta) > MAX_ALIGNMENT_MS ||
                    distance(x.hash, y.hash) > MAX_FRAME_DISTANCE) continue
                val bucket = Math.floorDiv(delta, OFFSET_BIN_MS)
                votes[bucket] = (votes[bucket] ?: 0) + 1
            }
            // Check the strongest candidate offsets, not just the single maximum.
            val buckets = votes.entries.sortedByDescending { it.value }.take(12)
            for (bucket in buckets) {
                val delta = bucket.key * OFFSET_BIN_MS + OFFSET_BIN_MS / 2L
                val aligned = mutableListOf<PairHit>()
                // Greedily match each reference frame to at most one frame from
                // the other episode, moving time forward on BOTH timelines.
                var lastLeftAt = -1L
                for (rightFrame in right) {
                    val expectedLeftTime = if (credits)
                        a.totalMs - (b.totalMs - rightFrame.atMs - delta)
                        else rightFrame.atMs - delta
                    val candidate = left.asSequence()
                        .filter {
                            it.atMs > lastLeftAt &&
                                abs(it.atMs - expectedLeftTime) <= MAX_TIME_JITTER_MS
                        }
                        .map { PairHit(it, rightFrame, distance(it.hash, rightFrame.hash)) }
                        .filter { it.distance <= MAX_FRAME_DISTANCE }
                        .minByOrNull { it.distance * 200 + abs(it.a.atMs - expectedLeftTime) / 100 }
                    if (candidate != null) {
                        aligned.add(candidate)
                        lastLeftAt = candidate.a.atMs
                    }
                }
                // The strongest temporal run is the proposed common sequence.
                var run = mutableListOf<PairHit>()
                fun considerRun() {
                    if (run.size < 7) return
                    val span = run.last().b.atMs - run.first().b.atMs
                    if (span < 18_000L || span > MAX_CLIP_MS) return
                    if (run.map { it.b.hash }.distinct().size < 6 ||
                        run.map { it.a.hash }.distinct().size < 6) return
                    val start = run.first().b.atMs
                    val end = run.last().b.atMs
                    val reference = right.filter { it.atMs in start..end }
                    val score = span + run.size * 1_500L
                    if (score > bestScore) {
                        bestScore = score
                        best = RepeatedClip(start, end, reference)
                    }
                }
                for (hit in aligned) {
                    val previous = run.lastOrNull()
                    if (previous != null &&
                        (hit.b.atMs - previous.b.atMs > MAX_GAP_MS ||
                            hit.a.atMs - previous.a.atMs > MAX_GAP_MS)) {
                        considerRun()
                        run = mutableListOf()
                    }
                    run.add(hit)
                }
                considerRun()
            }
        }
        return best
    }

    private fun allowed(sample: SceneSample, totalMs: Long, credits: Boolean): Boolean {
        if (!credits) return sample.atMs >= 0L && sample.atMs <= EARLY_MS
        return totalMs > 0L && sample.atMs >= totalMs * 75L / 100L &&
            (totalMs - sample.atMs) in 0L..900_000L
    }

    /** Estimate the current episode's end of its common opening from live evidence. */
    fun detectIntro(
        current: List<SceneSample>, template: RepeatedClip?,
        nowMs: Long, totalMs: Long,
    ): Long {
        val clip = template ?: return -1L
        if (nowMs > EARLY_MS || clip.reference.size < 7) return -1L
        val recent = current.filter { it.atMs <= EARLY_MS }.takeLast(22)
        if (recent.size < 4) return -1L
        val anchors = mutableMapOf<Long, MutableList<PairHit>>()
        for (frame in recent) for (ref in clip.reference) {
            val d = distance(frame.hash, ref.hash)
            if (d > MAX_FRAME_DISTANCE) continue
            val delta = frame.atMs - ref.atMs
            val bin = Math.floorDiv(delta, OFFSET_BIN_MS)
            anchors.getOrPut(bin) { mutableListOf() }.add(PairHit(ref, frame, d))
        }
        // Allow adjacent buckets: unrelated similar frames have no sustained
        // consistent offset over multiple DIFFERENT video frames.
        val orderedBuckets = anchors.keys.sortedByDescending { anchors[it]?.size ?: 0 }.take(10)
        for (bin in orderedBuckets) {
            val matches = ((anchors[bin - 1] ?: emptyList()) +
                (anchors[bin] ?: emptyList()) + (anchors[bin + 1] ?: emptyList()))
                .sortedWith(compareBy<PairHit> { it.b.atMs }.thenBy { it.distance })
            val sequence = mutableListOf<PairHit>()
            var lastRef = -1L
            var lastOwn = -1L
            val centre = bin * OFFSET_BIN_MS
            for (pair in matches) {
                if (abs((pair.b.atMs - pair.a.atMs) - centre) > 7_500L ||
                    pair.a.atMs <= lastRef || pair.b.atMs <= lastOwn) continue
                sequence.add(pair)
                lastRef = pair.a.atMs
                lastOwn = pair.b.atMs
            }
            if (sequence.size < 4 ||
                sequence.last().b.atMs - sequence.first().b.atMs < 7_000L ||
                sequence.map { it.a.hash }.distinct().size < 4) continue
            val currentOffset = sequence.map { it.b.atMs - it.a.atMs }
                .sorted()[sequence.size / 2]
            val target = clip.endMs + currentOffset
            // Never seek without a believable end, even when an opening looks familiar.
            if (target >= nowMs + 8_000L && target <= nowMs + MAX_CLIP_MS &&
                (totalMs <= 0L || target < totalMs - 30_000L)) return target
        }
        return -1L
    }

    fun detectRepeatedCredits(
        current: List<SceneSample>, clip: RepeatedClip?, nowMs: Long, totalMs: Long,
    ): Boolean {
        if (clip == null || totalMs <= 0L || nowMs < totalMs * 75L / 100L) return false
        val recent = current.filter { it.atMs >= totalMs * 75L / 100L }.takeLast(12)
        if (recent.size < 5) return false
        val hits = recent.count { own ->
            clip.reference.any { ref -> distance(own.hash, ref.hash) <= MAX_FRAME_DISTANCE }
        }
        return hits >= 5 && recent.map { it.hash }.distinct().size >= 4
    }

    fun detectVisualCredits(samples: List<SceneSample>, nowMs: Long, totalMs: Long): Boolean {
        if (totalMs <= 0L || nowMs < totalMs * 80L / 100L ||
            totalMs - nowMs > 900_000L) return false
        val recent = samples.filter { it.atMs >= nowMs - 23_000L }.takeLast(7)
        return recent.size >= 5 && recent.count { it.creditsLike } >= 4 &&
            recent.last().atMs - recent.first().atMs <= 23_000L
    }
}
