package app.sokyiptv.data.remote

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BoundedVODDurationParserTest {
    private fun put32(b: ByteArray, at: Int, value: Long) {
        b[at] = (value shr 24).toByte()
        b[at + 1] = (value shr 16).toByte()
        b[at + 2] = (value shr 8).toByte()
        b[at + 3] = value.toByte()
    }

    private fun put4(b: ByteArray, at: Int, name: String) {
        for (i in 0..3) b[at + i] = name[i].code.toByte()
    }

    private fun movieHeader(seconds: Int, scale: Long = 1000L): ByteArray {
        val b = ByteArray(8 + 32)
        put32(b, 0, b.size.toLong()); put4(b, 4, "moov")
        put32(b, 8, 32); put4(b, 12, "mvhd")
        b[16] = 0  // version 0
        put32(b, 16 + 12, scale)
        put32(b, 16 + 16, seconds.toLong() * scale)
        return b
    }

    @Test fun parsesExactIsoBmffHeaderWithoutStartingAPlayer() {
        assertThat(BoundedVODDurationParser.mp4Seconds(movieHeader(2886))).isEqualTo(2886)
    }

    @Test fun findsMoovBoxInTailRange() {
        val suffix = ByteArray(77) + movieHeader(2886)
        assertThat(BoundedVODDurationParser.mp4Seconds(suffix)).isEqualTo(2886)
    }

    @Test fun rejectsFakeOrUnanchoredBoxAndImpossibleDuration() {
        val invalid = movieHeader(2886)
        put4(invalid, 4, "mdat")
        assertThat(BoundedVODDurationParser.mp4Seconds(invalid)).isNull()
        assertThat(BoundedVODDurationParser.mp4Seconds(movieHeader(50))).isNull()
        assertThat(BoundedVODDurationParser.mp4Seconds(ByteArray(16))).isNull()
    }

    @Test fun onlyCompleteVodHlsCanReportRuntime() {
        val valid = """#EXTM3U
#EXT-X-TARGETDURATION:6
#EXTINF:5.9,
a.ts
#EXTINF:8.1,
b.ts
#EXTINF:120.0,
c.ts
#EXT-X-ENDLIST
""".toByteArray()
        assertThat(BoundedVODDurationParser.hlsVodSeconds(valid)).isEqualTo(134)
        assertThat(BoundedVODDurationParser.hlsVodSeconds(
            "#EXTM3U\n#EXTINF:100.0,\na.ts\n".toByteArray(),
        )).isNull()
        assertThat(BoundedVODDurationParser.hlsVodSeconds(
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=4000000\nvariant.m3u8\n#EXT-X-ENDLIST"
                .toByteArray(),
        )).isNull()
    }
}
