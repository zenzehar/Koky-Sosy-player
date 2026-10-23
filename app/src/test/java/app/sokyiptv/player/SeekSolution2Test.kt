package app.sokyiptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VodEndMetadataTest {
    @Test fun finalizedHlsVodUsesSumOfActualSegmentDurations() {
        val playlist = """#EXTM3U
#EXT-X-PLAYLIST-TYPE:VOD
#EXT-X-TARGETDURATION:10
#EXTINF:10.5,
s1.ts
#EXTINF:10.5,
s2.ts
#EXTINF:40.0,
s3.ts
#EXT-X-ENDLIST
""".toByteArray()
        assertEquals(61_000L, VodEndMetadata.hlsVodDurationMillis(playlist))
    }

    @Test fun neverTreatLiveOrMasterPlaylistAsKnownDuration() {
        val live = """#EXTM3U
#EXTINF:100,
1.ts
""".toByteArray()
        val master = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=2000000
video.m3u8
#EXT-X-ENDLIST
""".toByteArray()
        assertNull(VodEndMetadata.hlsVodDurationMillis(live))
        assertNull(VodEndMetadata.hlsVodDurationMillis(master))
    }

    private fun mp4Header(seconds: Int): ByteArray {
        val mvhd = ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN)
        mvhd.putInt(28).put("mvhd".toByteArray()).putInt(0)
            .putInt(0).putInt(0).putInt(1000).putInt(seconds * 1000)
        val moov = ByteBuffer.allocate(8 + 28).order(ByteOrder.BIG_ENDIAN)
        moov.putInt(36).put("moov".toByteArray()).put(mvhd.array())
        return moov.array()
    }
    @Test fun acceptsCompleteMp4MoovAtHeadOrTail() {
        val moov = mp4Header(5400)
        assertEquals(5_400_000L, VodEndMetadata.mp4MovieDurationMillis(moov))
        assertEquals(5_400_000L,
            VodEndMetadata.mp4MovieDurationMillis(ByteArray(50) + moov))
    }
    @Test fun truncatedMp4MoovNeverInventsDuration() {
        val moov = mp4Header(5400)
        assertNull(VodEndMetadata.mp4MovieDurationMillis(moov.copyOf(24)))
    }
    @Test fun restrictsProbeToKnownStaticFormats() {
        assertEquals(VodContainerDurationProbe.Type.HLS_VOD,
            VodContainerDurationProbe.typeOf("https://iptv.example/vod/1/master.m3u8?token=abc"))
        assertEquals(VodContainerDurationProbe.Type.MP4,
            VodContainerDurationProbe.typeOf("http://iptv.example/movie/video.mp4"))
        assertNull(VodContainerDurationProbe.typeOf("http://iptv.example/movie/video.mkv"))
        assertNull(VodContainerDurationProbe.typeOf("file:///movie.mp4"))
    }
}

class SeekResponseMapTest {
    @Test fun learnsStableProportionalTimeWarpAndInvertsNativeRequest() {
        val map = SeekResponseMap()
        map.record(600_000L, 900_000L)
        map.record(1_200_000L, 1_800_000L)
        assertEquals(900_000L, map.commandFor(1_350_000L))
    }
    @Test fun exactKnownLandingReusesMeasuredNativeCommand() {
        val map = SeekResponseMap()
        map.record(600_000L, 900_000L)
        map.record(1_200_000L, 1_800_000L)
        assertEquals(600_000L, map.commandFor(900_000L))
        assertEquals(1_200_000L, map.commandFor(1_800_000L))
    }

    @Test fun rejectsSparseExtrapolationFarBeyondKnownAnchors() {
        val map = SeekResponseMap()
        map.record(600_000L, 900_000L)
        map.record(1_200_000L, 1_800_000L)
        assertEquals(3_000_000L, map.commandFor(3_000_000L))
    }
    @Test fun ignoresSingleObservationAndContradictorySlope() {
        val map = SeekResponseMap()
        map.record(600_000L, 900_000L)
        assertEquals(700_000L, map.commandFor(700_000L))
        map.record(1_200_000L, 880_000L)
        assertEquals(895_000L, map.commandFor(895_000L))
    }
    @Test fun learnedCalibrationIsNotAllowedToMakeWildJumps() {
        val map = SeekResponseMap()
        map.record(500_000L, 200_000L)
        map.record(700_000L, 400_000L)
        assertTrue(map.commandFor(1_000_000L) >= 0L)
    }
}
