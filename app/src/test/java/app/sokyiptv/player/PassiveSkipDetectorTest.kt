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

    @Test fun realWorldSamplingJitterAndCodecHashVarianceStillRecognizeIntro() {
        // Sampled screenshots of an identical fast-moving opening don't have
        // the same millisecond offsets or identical hashes on every device.
        val a = (0 until 36).map { n ->
            SceneSample(38_000L + n * 2_500L, hash(n.toLong()), false)
        }
        val b = (0 until 36).map { n ->
            val jitter = (n % 3 - 1) * 900L
            SceneSample(56_000L + n * 2_500L + jitter,
                hash(n.toLong()) xor ((1L shl 13) - 1L), false)
        }
        val common = PassiveSkipDetector.template(listOf(
            EpisodeScenes("one", 2_000_000, a),
            EpisodeScenes("two", 2_300_000, b)))
        assertTrue("A 90-second visual opening must be recognized despite frame jitter",
            common != null && common.endMs - common.startMs >= 70_000L)
        val third = (0 until 12).map { n ->
            SceneSample(61_000L + n * 2_500L + ((n % 3) - 1) * 800L,
                hash(n.toLong()) xor ((1L shl 9) - 1L), false)
        }
        val jump = PassiveSkipDetector.detectIntro(third, common,
            third.last().atMs, 2_400_000L)
        assertTrue("A matching third episode must offer a meaningful skip", jump > 125_000L)
    }

    @Test fun repeatedOutroWithDifferentEpisodeLengthsTriggers() {
        val common = (0 until 28).map { n -> hash(n.toLong() + 200L) }
        val first = common.mapIndexed { n, h ->
            SceneSample(2_430_000L + n * 2_500L, h, false)
        }
        val second = common.mapIndexed { n, h ->
            SceneSample(2_710_000L + n * 2_500L + (n % 2) * 650L,
                h xor ((1L shl 8) - 1L), false)
        }
        val clip = PassiveSkipDetector.template(listOf(
            EpisodeScenes("one", 2_520_000L, first),
            EpisodeScenes("two", 2_800_000L, second)), credits = true)
        assertTrue("Same credits at different absolute offsets must align", clip != null)
        val current = common.take(10).mapIndexed { n, h ->
            SceneSample(2_608_000L + n * 2_500L, h xor ((1L shl 11) - 1L), false)
        }
        assertTrue(PassiveSkipDetector.detectRepeatedCredits(
            current, clip, current.last().atMs, 2_700_000L))
    }

    @Test fun creditsCanBeDetectedWithoutTwoOtherEpisodes() {
        val samples = (0 until 6).map {
            SceneSample(2_610_000L + it * 3_000L, hash((it + 44).toLong()), true)
        }
        assertTrue(PassiveSkipDetector.detectVisualCredits(samples,
            samples.last().atMs, 3_000_000L))
    }

    @Test fun creditsAreNotPredictedInMiddleOfAnEpisode() {
        val sample = (0..10).map {
            SceneSample(200_000L + it * 3_000L, hash(it.toLong()), true)
        }
        assertFalse(PassiveSkipDetector.detectVisualCredits(sample, 240_000, 3_000_000))
    }
}
