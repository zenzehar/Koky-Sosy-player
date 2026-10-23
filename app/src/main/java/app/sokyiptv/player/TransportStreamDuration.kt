/* GPL-3.0-or-later. Pure MPEG-TS timestamp inspection, no native player or network ownership. */
package app.sokyiptv.player

/**
 * Extracts PTS from a short prefix and a short suffix of a byte-seekable MPEG-TS file.
 * Never uses byte count / bitrate to invent the duration. Returns null when the
 * packets cannot be confidently matched (e.g. HLS/live stream, discontinuity).
 */
internal object TransportStreamDuration {
    private const val PACKET = 188
    private const val PTS_WRAP = 1L shl 33
    private const val MAX_DURATION_MS = 24L * 60 * 60 * 1_000L

    private data class Stamp(val pid: Int, val ticks: Long, val isVideo: Boolean)

    fun estimateMillis(prefix: ByteArray, suffix: ByteArray): Long? {
        val starts = timestamps(prefix)
        val ends = timestamps(suffix)
        if (starts.isEmpty() || ends.isEmpty()) return null
        // Prefer matching video PIDs; don't combine timestamps from different
        // audio/video clock domains or different track IDs.
        val match = ends.asReversed().firstOrNull { end ->
            end.isVideo && starts.any { it.pid == end.pid && it.isVideo }
        } ?: ends.asReversed().firstOrNull { end ->
            starts.any { it.pid == end.pid && it.isVideo == end.isVideo }
        } ?: return null
        val first = starts.firstOrNull { it.pid == match.pid && it.isVideo == match.isVideo }
            ?: return null
        val ticks = (match.ticks - first.ticks + PTS_WRAP) % PTS_WRAP
        val millis = ticks / 90L
        // Reject implausible stream clocks and all ambiguous/partial samples.
        return millis.takeIf { it in 60_000L..MAX_DURATION_MS }
    }

    private fun timestamps(data: ByteArray): List<Stamp> {
        if (data.size < PACKET * 4) return emptyList()
        val aligned = (0 until PACKET).firstOrNull { offset ->
            offset + PACKET * 3 < data.size &&
                (0..3).all { (data[offset + it * PACKET].toInt() and 0xff) == 0x47 }
        } ?: return emptyList()
        val out = ArrayList<Stamp>()
        var i = aligned
        while (i + PACKET <= data.size) {
            if ((data[i].toInt() and 0xff) != 0x47) break
            val b1 = data[i + 1].toInt() and 0xff
            val b2 = data[i + 2].toInt() and 0xff
            val b3 = data[i + 3].toInt() and 0xff
            val pid = ((b1 and 0x1f) shl 8) or b2
            val payloadStart = (b1 and 0x40) != 0
            val afc = (b3 ushr 4) and 3
            if (pid != 0x1fff && payloadStart && (afc == 1 || afc == 3)) {
                var p = i + 4
                if (afc == 3) p += 1 + (data[p].toInt() and 0xff)
                // PES starts at payload boundary. Flags and marker bits prevent
                // false timestamps in encrypted, malformed or unrelated data.
                if (p + 14 <= i + PACKET && p + 14 <= data.size &&
                    data[p].toInt() == 0 && data[p + 1].toInt() == 0 &&
                    (data[p + 2].toInt() and 0xff) == 1) {
                    val streamId = data[p + 3].toInt() and 0xff
                    val ptsFlag = (data[p + 7].toInt() and 0xff) and 0xc0
                    val ptsHead = data[p + 9].toInt() and 0xff
                    if ((streamId in 0xc0..0xdf || streamId in 0xe0..0xef) &&
                        ptsFlag in listOf(0x80, 0xc0) && (data[p + 8].toInt() and 0xff) >= 5 &&
                        (ptsHead and 0x01) == 1 &&
                        (data[p + 11].toInt() and 0x01) == 1 &&
                        (data[p + 13].toInt() and 0x01) == 1) {
                        val q1 = data[p + 10].toInt() and 0xff
                        val q2 = data[p + 11].toInt() and 0xff
                        val q3 = data[p + 12].toInt() and 0xff
                        val q4 = data[p + 13].toInt() and 0xff
                        val pts = ((ptsHead.toLong() and 0x0e) shl 29) or
                            (q1.toLong() shl 22) or ((q2.toLong() and 0xfe) shl 14) or
                            (q3.toLong() shl 7) or ((q4.toLong() and 0xfe) ushr 1)
                        out.add(Stamp(pid, pts, streamId in 0xe0..0xef))
                    }
                }
            }
            i += PACKET
        }
        return out
    }
}
