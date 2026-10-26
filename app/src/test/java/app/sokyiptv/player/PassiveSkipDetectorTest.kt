package app.sokyiptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassiveSkipDetectorTest {
    private fun hash(seed: Long): Long =
        java.util.Random(seed).nextLong()

    @Test fun identicalOpeningWithOffsetLearnsDynamicEnd() {
        val a = (0..22).map { n -> SceneSample(42_000L + 3_000L * n, hash(n.toLong()), false) }
        val b = (0..22).map { n -> SceneSample(63_000L + 3_000L * n, hash(n.toLong()), false) }
        val clip = PassiveSkipDetector.template(listOf(
            EpisodeScenes("ep1", 2_200_000, a),
            EpisodeScenes("ep2", 2_400_000, b)))
        assertTrue(clip != null)
        assertEquals(63_000L, clip!!.startMs)
        assertEquals(129_000L, clip.endMs)
        val third = (0..4).map { n ->
            SceneSample(27_000L + n * 3_000L, hash(n.toLong()), false)
        }
        val target = PassiveSkipDetector.detectIntro(third, clip,
            nowMs = third.last().atMs, totalMs = 2_300_000)
        assertTrue("Skip prediction must be based on matched frames, not fixed offset", target > 70_000L)
    }

    @Test fun noReferenceOrShortenedIntroCannotInventAJump() {
        assertEquals(-1L, PassiveSkipDetector.detectIntro(
            listOf(SceneSample(25_000, hash(1), false)), null, 25_000, 2_200_000))
        assertEquals(null, PassiveSkipDetector.template(emptyList()))
        assertEquals(null, PassiveSkipDetector.template(listOf(
            EpisodeScenes("one", 100_000, emptyList()))))
    }

    @Test fun movieCreditsRequireFourOfFiveNearEndVisualSamples() {
        val samples = (0 until 5).map {
            SceneSample(2_610_000L + it * 3_000L, hash((it + 5).toLong()), true)
        }
        assertTrue(PassiveSkipDetector.detectVisualCredits(samples, 2_622_000, 3_000_000))
        assertFalse(PassiveSkipDetector.detectVisualCredits(samples, 900_000, 3_000_000))
        assertFalse(PassiveSkipDetector.detectVisualCredits(
            samples.map { it.copy(creditsLike = false) }, 2_622_000, 3_000_000))
    }

    @Test fun unrelatedVisualSequencesDoNotAutoSkip() {
        val a = (0..25).map { n -> SceneSample(n * 3_000L, hash(n.toLong()), false) }
        val b = (0..25).map { n -> SceneSample(n * 3_000L, hash(n.toLong() + 1_000), false) }
        assertEquals(null, PassiveSkipDetector.template(listOf(
            EpisodeScenes("one", 2_500_000, a),
            EpisodeScenes("two", 2_600_000, b))))
    }

    @Test fun creditsAreNotPredictedInMiddleOfAnEpisode() {
        val sample = (0..10).map {
            SceneSample(200_000L + it * 3_000L, hash(it.toLong()), true)
        }
        assertFalse(PassiveSkipDetector.detectVisualCredits(sample, 240_000, 3_000_000))
    }
}
