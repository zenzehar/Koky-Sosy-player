package app.sokyiptv.data.remote

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

class AdditionalDurationReadersTest {
    private fun packet(pcr: Long): ByteArray {
        val b = ByteArray(188)
        b[0] = 0x47
        b[1] = 0x01; b[2] = 0x00  // PCR PID 256
        b[3] = 0x20
        b[4] = 7; b[5] = 0x10
        b[6] = (pcr ushr 25).toByte()
        b[7] = (pcr ushr 17).toByte()
        b[8] = (pcr ushr 9).toByte()
        b[9] = (pcr ushr 1).toByte()
        b[10] = ((pcr and 1L) shl 7).toByte()
        return b
    }

    @Test fun tsDurationDerivesFromTwoBoundedPcrRangeSamples() {
        val h = (0..7).map { packet(90_000L + it * 9_000L) }.reduce(ByteArray::plus)
        val t = (0..7).map { packet(90_000L + 2_886L * 90_000L - 63_000L + it * 9_000L) }
            .reduce(ByteArray::plus)
        assertThat(TransportStreamDurationReader.approximateSeconds(h, t)).isEqualTo(2886)
        assertThat(TransportStreamDurationReader.approximateSeconds(ByteArray(7), t)).isNull()
    }

    @Test fun mkvInfoHandlesUnrelatedFieldsBeforeDuration() {
        val box = java.io.ByteArrayOutputStream()
        box.write(byteArrayOf(0x4D, 0x80.toByte(), 0x81.toByte(), 0x41)) // MuxingApp
        box.write(byteArrayOf(0x2A, 0xD7.toByte(), 0xB1.toByte(), 0x83.toByte(),
            0x0F, 0x42, 0x40)) // TimestampScale=1,000,000ns
        box.write(byteArrayOf(0x44, 0x89.toByte(), 0x88.toByte()))
        box.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(2_886_000.0).array())
        val children = box.toByteArray()
        val mkv = byteArrayOf(0x15, 0x49, 0xA9.toByte(), 0x66, (0x80 or children.size).toByte()) +
            children
        assertThat(MatroskaDurationReader.seconds(mkv)).isEqualTo(2886)
        assertThat(MatroskaDurationReader.seconds(ByteArray(24))).isNull()
    }
}
