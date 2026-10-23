package app.sokyiptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportStreamDurationTest {
    private fun packet(pid: Int, pts: Long): ByteArray = ByteArray(188) { 0xff.toByte() }.also { b ->
        b[0] = 0x47
        b[1] = (0x40 or (pid ushr 8 and 0x1f)).toByte()
        b[2] = (pid and 0xff).toByte()
        b[3] = 0x10
        b[4] = 0
        b[5] = 0
        b[6] = 1
        b[7] = 0xe0.toByte()
        b[8] = 0
        b[9] = 0
        b[10] = 0x80.toByte()
        b[11] = 0x80.toByte()
        b[12] = 5
        b[13] = (0x20 or (((pts ushr 30).toInt() and 7) shl 1) or 1).toByte()
        b[14] = (pts ushr 22).toByte()
        b[15] = (((pts ushr 15).toInt() and 0x7f) shl 1 or 1).toByte()
        b[16] = (pts ushr 7).toByte()
        b[17] = (((pts and 0x7fL).toInt() shl 1) or 1).toByte()
    }
    private fun sample(fromMs: Long, toMs: Long, pid: Int = 0x101): ByteArray =
        (0..7).fold(ByteArray(0)) { acc, n ->
            val time = fromMs + (toMs - fromMs) * n / 7
            acc + packet(pid, time * 90L)
        }

    @Test fun estimatesEndDurationUsingOnlyShortTransportStreamHeadAndTail() {
        val head = sample(1_000L, 3_000L)
        val tail = sample(5_399_000L, 5_401_000L)
        assertEquals(5_400_000L, TransportStreamDuration.estimateMillis(head, tail))
    }

    @Test fun supportsNonPacketAlignedSuffixData() {
        val head = sample(1_000L, 2_000L)
        val tail = ByteArray(47) + sample(3_600_000L, 3_602_000L)
        assertEquals(3_601_000L, TransportStreamDuration.estimateMillis(head, tail))
    }

    @Test fun neverCombinesTimestampsFromDifferentStreamPid() {
        assertNull(TransportStreamDuration.estimateMillis(
            sample(1_000L, 2_000L, 0x101),
            sample(5_000_000L, 5_001_000L, 0x102),
        ))
    }

    @Test fun rejectsNotTransportStreamOrShortFile() {
        assertNull(TransportStreamDuration.estimateMillis(ByteArray(1000), ByteArray(1000)))
        assertNull(TransportStreamDuration.estimateMillis(ByteArray(20), ByteArray(20)))
    }

    @Test fun acceptsOnlyProgressiveHttpTsFilesForProbing() {
        assertTrue(LowImpactTsDurationProbe.eligibleUrl("https://provider.example/vod/101/file.ts?token=x"))
        assertFalse(LowImpactTsDurationProbe.eligibleUrl("https://provider.example/vod/101/file.mkv"))
        assertFalse(LowImpactTsDurationProbe.eligibleUrl("https://provider.example/playlist.m3u8"))
        assertFalse(LowImpactTsDurationProbe.eligibleUrl("file:///sdcard/movie.ts"))
    }
}
