/* GPL-3.0-or-later: bounded MPEG-TS duration estimate from PCR samples. */
package app.sokyiptv.data.remote

import kotlin.math.roundToInt

/**
 * Not an exact decoder runtime. Estimate ONLY if the server supplies real byte
 * ranges at both ends of one finite TS file, with the SAME PCR PID at both ends.
 * Works on bounded <=256 KiB samples. Never plays or downloads the stream.
 */
internal object TransportStreamDurationReader {
    private const val PACKET = 188
    private const val WRAP = 1L shl 33
    private data class Pcr(val pid: Int, val base: Long)

    private fun readPcr(b: ByteArray, i: Int): Pcr? {
        if (i + PACKET > b.size || (b[i].toInt() and 255) != 0x47) return null
        val control = (b[i + 3].toInt() ushr 4) and 3
        if (control != 2 && control != 3) return null
        if ((b[i + 5].toInt() and 0x10) == 0 ||
            (b[i + 4].toInt() and 255) < 7) return null
        val pid = ((b[i + 1].toInt() and 31) shl 8) or (b[i + 2].toInt() and 255)
        val a = (b[i + 6].toLong() and 255)
        val c = (b[i + 7].toLong() and 255)
        val d = (b[i + 8].toLong() and 255)
        val e = (b[i + 9].toLong() and 255)
        val f = (b[i + 10].toLong() and 255)
        val base = (a shl 25) or (c shl 17) or (d shl 9) or (e shl 1) or (f ushr 7)
        return Pcr(pid, base)
    }

    private fun samples(bytes: ByteArray): List<Pcr> {
        if (bytes.size < 5 * PACKET || bytes.size > 262_144) return emptyList()
        var offset = -1
        for (i in 0..minOf(PACKET - 1, bytes.size - 4 * PACKET - 1)) {
            if ((0..3).all { (bytes[i + it * PACKET].toInt() and 255) == 0x47 }) {
                offset = i; break
            }
        }
        if (offset < 0) return emptyList()
        val results = ArrayList<Pcr>()
        var i = offset
        while (i + PACKET <= bytes.size) {
            readPcr(bytes, i)?.let(results::add)
            i += PACKET
        }
        return results
    }

    fun approximateSeconds(head: ByteArray, tail: ByteArray): Int? {
        val first = samples(head)
        val last = samples(tail)
        if (first.size < 2 || last.size < 2) return null
        val pid = first.groupingBy { it.pid }.eachCount().maxByOrNull { it.value }?.key
            ?: return null
        val from = first.firstOrNull { it.pid == pid } ?: return null
        val to = last.lastOrNull { it.pid == pid } ?: return null
        // Require repeated samples on the same PCR clock to prevent accidental
        // PID selection in multiplexed or scrambled transport streams.
        if (first.count { it.pid == pid } < 2 || last.count { it.pid == pid } < 2)
            return null
        val diff = (to.base - from.base + WRAP) % WRAP
        val seconds = diff / 90_000.0
        if (!seconds.isFinite() || seconds !in 120.0..43_200.0) return null
        return seconds.roundToInt()
    }
}
