/* GPL-3.0-or-later. Scope: one video, one immutable reference set. */
package app.sokyiptv.player

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every observation is from the CURRENT stream's already rendered picture.
 * Exact series identity comes from provider source + series ID, so unrelated
 * programmes or quality variants cannot accidentally share learned timings.
 */
class PassiveSkipTracker(
    context: Context,
    private val groupKey: String?,
    private val episodeKey: String,
) {
    private val memory = PassiveSkipMemory(context)
    // Cross-episode comparisons can be expensive for large series. Never block
    // Compose, LibVLC callbacks, screen navigation or hardware remote focus.
    @Volatile private var intro: RepeatedClip? = null
    @Volatile private var outro: RepeatedClip? = null
    @Volatile var referenceEpisodes: Int = 0
        private set
    val capturedFrames: Int get() = observations.size

    suspend fun prepare() {
        val key = groupKey ?: return
        val examples = withContext(Dispatchers.IO) {
            memory.history(key, episodeKey)
        }
        referenceEpisodes = examples.count { it.samples.isNotEmpty() }
        val pair = withContext(Dispatchers.Default) {
            PassiveSkipDetector.template(examples) to
                PassiveSkipDetector.template(examples, credits = true)
        }
        intro = pair.first
        outro = pair.second
    }
    // Keep beginning AND latest ending; a first-in first-out cap otherwise
    // stops gathering credits just before the episode finishes.
    private val observations = ArrayList<SceneSample>(350)
    private var lastAt = -1L
    private var highestDurationMs = 0L
    private var rememberedIntroEnd = -1L
    private var detectedCredits = false
    private var saved = false

    fun observe(frame: SceneSample, totalMs: Long): DetectedSkip {
        if (saved) return DetectedSkip(rememberedIntroEnd, detectedCredits)
        if (lastAt >= 0L && frame.atMs < lastAt - 5_000L) lastAt = -1L
        if (frame.atMs <= lastAt + 900L && lastAt >= 0L)
            return DetectedSkip(rememberedIntroEnd, detectedCredits)
        if (totalMs > 0L) highestDurationMs = totalMs
        lastAt = frame.atMs
        // Sampled at higher frequency for dynamic scene alignment, but persist
        // only bounded beginning and rolling-end data. No video bitmaps are stored.
        if (frame.atMs <= 480_000L) {
            val earlyCount = observations.count { it.atMs <= 480_000L }
            if (earlyCount < 210 && observations.none {
                    kotlin.math.abs(it.atMs - frame.atMs) < 1_200L
                }) observations.add(frame)
        } else if (totalMs > 0 && frame.atMs >= totalMs * 75 / 100) {
            val lateIndices = observations.indices.filter { observations[it].atMs > 480_000L }
            if (lateIndices.size >= 140) observations.removeAt(lateIndices.first())
            observations.add(frame)
        }
        val early = frame.atMs <= 480_000L
        if (early && groupKey != null && rememberedIntroEnd < 0L) {
            val target = PassiveSkipDetector.detectIntro(
                observations, intro, frame.atMs, highestDurationMs
            )
            if (target > frame.atMs) rememberedIntroEnd = target
        }
        if (highestDurationMs > 0 && frame.atMs >= highestDurationMs * 75 / 100) {
            detectedCredits = detectedCredits ||
                (groupKey != null && PassiveSkipDetector.detectRepeatedCredits(
                    observations, outro, frame.atMs, highestDurationMs)) ||
                PassiveSkipDetector.detectVisualCredits(
                    observations, frame.atMs, highestDurationMs)
        }
        return DetectedSkip(rememberedIntroEnd, detectedCredits)
    }

    fun finish() {
        if (saved) return
        saved = true
        if (groupKey != null) memory.persist(groupKey, episodeKey,
            highestDurationMs, observations)
    }
}
