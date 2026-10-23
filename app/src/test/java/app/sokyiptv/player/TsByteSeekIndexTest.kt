package app.sokyiptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TsByteSeekIndexTest {
    private val total = 1_000_000_000L
    private val percentages = listOf(0L, 12L, 28L, 45L, 62L, 78L, 89L, 97L, 99L)

    private fun probes(
        movieMs: Long = 7_200_000L,
        pid: Int = 0x101,
    ): List<TsByteSeekIndex.Probe> =
        percentages.map {
            val elapsed = movieMs * it / 100L
            TsByteSeekIndex.Probe(
                offset = total * it / 100L,
                totalBytes = total,
                clock = TransportStreamDuration.VideoClock(pid, elapsed * 90L),
            )
        }

    @Test fun mapsActualVideoTimeToCorrectBytePositionWithoutDeclaredDuration() {
        val index = TsByteSeekIndex.fromProbes(probes())!!
        assertEquals(7_128_000L, index.estimatedDurationMs)
        assertTrue(index.isSignificantlyLongerThan(3_600_000L))
        assertEquals(0.5f, index.byteFractionFor(3_600_000L), 0.02f)
        assertTrue(index.byteFractionFor(6_900_000L) > 0.9f)
        assertTrue(index.byteFractionFor(7_150_000L) <= 0.9995f)
    }

    @Test fun monotonicButVariableBitrateIndexInterpolatesLocally() {
        val landmarks = percentages.mapIndexed { i, offset ->
            val elapsedMs = listOf(0L, 480_000L, 1_400_000L, 2_300_000L,
                3_100_000L, 4_000_000L, 5_100_000L, 6_100_000L, 6_200_000L)[i]
            TsByteSeekIndex.Probe(
                total * offset / 100L, total,
                TransportStreamDuration.VideoClock(0x101, elapsedMs * 90L),
            )
        }
        val index = TsByteSeekIndex.fromProbes(landmarks)!!
        assertTrue(index.byteFractionFor(5_000_000L) in 0.78f..0.89f)
    }

    @Test fun rejectsDiscontinuousPtsOrChangedFileOrDifferentVideoPid() {
        val reset = probes().toMutableList()
        reset[5] = reset[5].copy(
            clock = TransportStreamDuration.VideoClock(0x101, 100_000L * 90L))
        assertNull(TsByteSeekIndex.fromProbes(reset))
        val changedFile = probes().toMutableList()
        changedFile[6] = changedFile[6].copy(totalBytes = total + 1024L)
        assertNull(TsByteSeekIndex.fromProbes(changedFile))
        val switchedPid = probes().toMutableList()
        switchedPid[7] = switchedPid[7].copy(
            clock = TransportStreamDuration.VideoClock(0x102, 7_000_000L * 90L))
        assertNull(TsByteSeekIndex.fromProbes(switchedPid))
    }

    @Test fun skipsIncompleteScanOrShortUnreliableStreams() {
        assertNull(TsByteSeekIndex.fromProbes(probes().take(4)))
        assertFalse(LowImpactTsDurationProbe.eligibleUrl("http://x.example/video.m3u8"))
    }
}
