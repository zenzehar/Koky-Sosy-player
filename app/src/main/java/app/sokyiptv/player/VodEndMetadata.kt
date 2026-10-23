/* GPL-3.0-or-later. Seek Solution 2: small, read-only container duration evidence. */
package app.sokyiptv.player

import java.nio.charset.StandardCharsets

/**
 * Reports a plausible media endpoint, not proof the video frames are seekable.
 * Only complete, finalized playlists and fully-contained MP4 moov atoms qualify.
 */
internal object VodEndMetadata {
    private const val MAX_DURATION_MS = 24L * 60L * 60L * 1_000L

    fun hlsVodDurationMillis(payload: ByteArray): Long? {
        if (payload.size !in 20..131_072) return null
        val text = payload.toString(StandardCharsets.UTF_8)
        if (!text.startsWith("#EXTM3U") || text.contains("#EXT-X-STREAM-INF") ||
            text.contains("#EXT-X-I-FRAME-STREAM-INF")) return null
        var total = 0.0
        var segments = 0
        var awaitingSegment = false
        var ended = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.length > 2048) return null
            when {
                line.startsWith("#EXTINF:") -> {
                    if (awaitingSegment) return null
                    val seconds = line.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull()
                        ?: return null
                    if (!seconds.isFinite() || seconds <= 0.0 || seconds > 3_600.0) return null
                    total += seconds
                    if (total > MAX_DURATION_MS / 1_000.0) return null
                    awaitingSegment = true
                }
                line == "#EXT-X-ENDLIST" -> ended = true
                line.isNotEmpty() && !line.startsWith("#") -> {
                    if (!awaitingSegment || ended) return null
                    segments++
                    if (segments > 10_000) return null
                    awaitingSegment = false
                }
            }
        }
        if (!ended || awaitingSegment || segments == 0) return null
        return (total * 1_000.0).toLong().takeIf { it in 60_000L..MAX_DURATION_MS }
    }

    /** Master HLS playlists need one selected same-origin media playlist, not segment downloads. */
    fun firstMasterMediaUri(payload: ByteArray): String? {
        if (payload.size !in 20..131_072) return null
        val lines = payload.toString(StandardCharsets.UTF_8).lineSequence()
            .map { it.trim() }.toList()
        if (lines.firstOrNull() != "#EXTM3U") return null
        val index = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF:") }
        if (index < 0) return null
        return lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?.takeIf { it.length <= 2_048 && !it.contains('\\u0000') }
    }

    fun mp4MovieDurationMillis(bytes: ByteArray): Long? {
        if (bytes.size < 36) return null
        // A tail range may begin in the middle of mdat. Search for a complete
        // moov box; never infer movie length from file bytes/bitrate.
        for (i in 4 until bytes.size - 16) {
            if (bytes[i] != 'm'.code.toByte() || bytes[i + 1] != 'o'.code.toByte() ||
                bytes[i + 2] != 'o'.code.toByte() || bytes[i + 3] != 'v'.code.toByte()) continue
            val start = i - 4
            val initialSize = u32(bytes, start)
            val headerSize: Int
            val boxSize: Long
            if (initialSize == 1L) {
                if (i + 12 > bytes.size) continue
                boxSize = u64(bytes, i + 4)
                headerSize = 16
            } else {
                boxSize = initialSize
                headerSize = 8
            }
            if (boxSize < headerSize + 28L || boxSize > bytes.size - start) continue
            val end = start + boxSize.toInt()
            var p = start + headerSize
            while (p + 8 <= end) {
                val partSize = u32(bytes, p)
                if (partSize < 8 || partSize > end - p) break
                if (bytes[p + 4] == 'm'.code.toByte() &&
                    bytes[p + 5] == 'v'.code.toByte() &&
                    bytes[p + 6] == 'h'.code.toByte() &&
                    bytes[p + 7] == 'd'.code.toByte()) {
                    val version = bytes[p + 8].toInt() and 0xff
                    val scaleOffset = if (version == 1) 28 else 20
                    val endOfHeader = if (version == 1) 40 else 28
                    if (version !in 0..1 || p + endOfHeader > p + partSize ||
                        p + endOfHeader > end) break
                    val scale = u32(bytes, p + scaleOffset)
                    val units = if (version == 1) u64(bytes, p + 32) else u32(bytes, p + 24)
                    if (scale == 0L || units <= 0L || units == -1L) break
                    val length = (units.toDouble() * 1_000.0 / scale).toLong()
                    return length.takeIf { it in 60_000L..MAX_DURATION_MS }
                }
                p += partSize.toInt()
            }
        }
        return null
    }

    private fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 255) shl 24) or
            ((b[p + 1].toLong() and 255) shl 16) or
            ((b[p + 2].toLong() and 255) shl 8) or (b[p + 3].toLong() and 255)

    private fun u64(b: ByteArray, p: Int): Long {
        val high = u32(b, p)
        val low = u32(b, p + 4)
        if (high > Int.MAX_VALUE) return -1L
        return high * 4_294_967_296L + low
    }
}
