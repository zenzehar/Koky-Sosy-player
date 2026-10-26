/* GPL-3.0-or-later: bounded, metadata-only ISO-BMFF and HLS VOD duration readers. */
package app.sokyiptv.data.remote

import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

/**
 * Inspect a small HTTP byte range of an MP4, not its video stream or decoder.
 * An exact movie-header mvhd duration can be present in the first or last few
 * KiB; when it isn't available, do not fabricate a running time.
 */
internal object BoundedVODDurationParser {
    private fun unsigned32(b: ByteArray, i: Int): Long {
        if (i < 0 || i > b.size - 4) return -1L
        return ((b[i].toLong() and 255L) shl 24) or
            ((b[i + 1].toLong() and 255L) shl 16) or
            ((b[i + 2].toLong() and 255L) shl 8) or (b[i + 3].toLong() and 255L)
    }

    private fun unsigned64(b: ByteArray, i: Int): Long {
        val high = unsigned32(b, i)
        val low = unsigned32(b, i + 4)
        if (high < 0 || low < 0 || high >= 0x8000_0000L) return -1L
        return (high shl 32) or low
    }

    private fun fourCC(b: ByteArray, i: Int, expected: String): Boolean =
        i >= 0 && i <= b.size - 4 &&
            (0..3).all { (b[i + it].toInt() and 255) == expected[it].code }

    fun mp4Seconds(bytes: ByteArray): Int? {
        if (bytes.size < 40) return null
        // A suffix byte-range may start inside an mdat payload. Find a complete
        // moov header, then read mvhd; never trust arbitrary unanchored "mvhd".
        for (start in 0..bytes.size - 16) {
            if (!fourCC(bytes, start + 4, "moov")) continue
            var header = 8
            var size = unsigned32(bytes, start)
            if (size == 1L) {
                size = unsigned64(bytes, start + 8)
                header = 16
            }
            if (size <= header || size > 512L * 1024 * 1024) continue
            // The moov atom may extend outside the slice; mvhd is normally near
            // its beginning. Only examine the first 64KiB of moov for children.
            val stop = minOf(bytes.size - 8, start + 65_536)
            var cursor = start + header
            while (cursor <= stop) {
                var length = unsigned32(bytes, cursor)
                var atomHeader = 8
                if (length == 1L) {
                    length = unsigned64(bytes, cursor + 8)
                    atomHeader = 16
                }
                if (length < atomHeader || length > Int.MAX_VALUE) break
                if (fourCC(bytes, cursor + 4, "mvhd")) {
                    val payload = cursor + atomHeader
                    val version = bytes.getOrNull(payload)?.toInt()?.and(255) ?: break
                    val scaleAt = if (version == 1) payload + 20 else payload + 12
                    val durationAt = if (version == 1) payload + 24 else payload + 16
                    val scale = unsigned32(bytes, scaleAt)
                    val duration = when (version) {
                        0 -> unsigned32(bytes, durationAt)
                        1 -> unsigned64(bytes, durationAt)
                        else -> -1L
                    }
                    // Reject all-ones "unknown" movie duration and nonsensical data.
                    if (scale in 1..1_000_000_000L && duration > 0 &&
                        !(version == 0 && duration == 0xffff_ffffL)) {
                        val seconds = duration.toDouble() / scale
                        if (seconds in 120.0..43_200.0) return seconds.roundToInt()
                    }
                    break
                }
                cursor += length.toInt()
            }
        }
        return null
    }

    /** Parse only a complete VOD media playlist. A live/master manifest has no
     * fixed duration; EXT-X-ENDLIST and EXTINF samples are mandatory. */
    fun hlsVodSeconds(bytes: ByteArray): Int? {
        if (bytes.size > 262_144) return null
        val text = bytes.toString(StandardCharsets.UTF_8)
        if (!text.trimStart().startsWith("#EXTM3U") ||
            !text.contains("#EXT-X-ENDLIST") ||
            text.contains("#EXT-X-STREAM-INF")) return null
        var total = 0.0
        var count = 0
        for (line in text.lineSequence()) {
            if (!line.startsWith("#EXTINF:")) continue
            val value = line.substringAfter(':').substringBefore(',').trim()
                .toDoubleOrNull() ?: return null
            if (!value.isFinite() || value <= 0.0 || value > 7200.0) return null
            total += value
            count++
            if (total > 43_200.0 || count > 20_000) return null
        }
        return if (count > 0 && total in 120.0..43_200.0) total.roundToInt() else null
    }
}
