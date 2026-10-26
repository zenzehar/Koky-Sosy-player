/* GPL-3.0-or-later: bounded EBML metadata reader, never opens a decoder. */
package app.sokyiptv.data.remote

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Matroska Duration float (Info/Duration) scaled by TimestampScale nanoseconds.
 * Reads only short, already-downloaded head bytes; no playback or file scanning.
 */
internal object MatroskaDurationReader {
    private fun hasId(data: ByteArray, at: Int, id: IntArray): Boolean =
        at >= 0 && at + id.size <= data.size &&
            id.indices.all { (data[at + it].toInt() and 255) == id[it] }

    private fun sizeAt(data: ByteArray, start: Int): Pair<Long, Int>? {
        if (start >= data.size) return null
        val b = data[start].toInt() and 255
        if (b == 0) return null
        var length = 1
        var mask = 0x80
        while (length <= 8 && b and mask == 0) { length++; mask = mask shr 1 }
        if (length > 8 || start + length > data.size) return null
        var value = (b and (mask - 1)).toLong()
        for (i in 1 until length)
            value = (value shl 8) or (data[start + i].toLong() and 255L)
        if (value == (1L shl (length * 7)) - 1L) return null // unknown size
        return value to length
    }

    private val INFO = intArrayOf(0x15, 0x49, 0xA9, 0x66)
    private val TIMESTAMP_SCALE = intArrayOf(0x2A, 0xD7, 0xB1)
    private val DURATION = intArrayOf(0x44, 0x89)

    fun seconds(bytes: ByteArray): Int? {
        if (bytes.size < 24 || bytes.size > 262_144) return null
        for (start in 0..bytes.size - 10) {
            if (!hasId(bytes, start, INFO)) continue
            val (size, szlen) = sizeAt(bytes, start + 4) ?: continue
            if (size <= 0 || size > 2_000_000L) continue
            val end = minOf(bytes.size.toLong(), start + 4L + szlen + size).toInt()
            var cursor = start + 4 + szlen
            var scale = 1_000_000L
            var duration: Double? = null
            while (cursor < end - 3) {
                val len = when {
                    hasId(bytes, cursor, TIMESTAMP_SCALE) -> 3
                    hasId(bytes, cursor, DURATION) -> 2
                    else -> break
                }
                val (payloadLen, encodedLen) = sizeAt(bytes, cursor + len) ?: break
                val payload = cursor + len + encodedLen
                if (payloadLen < 1L || payloadLen > 8L || payload + payloadLen > end) break
                if (len == 3) {
                    var n = 0L
                    for (i in 0 until payloadLen.toInt())
                        n = (n shl 8) or (bytes[payload + i].toLong() and 255L)
                    if (n in 1..1_000_000_000) scale = n
                } else if (payloadLen == 4L || payloadLen == 8L) {
                    val bb = ByteBuffer.wrap(bytes, payload, payloadLen.toInt())
                        .order(ByteOrder.BIG_ENDIAN)
                    duration = if (payloadLen == 4L) bb.float.toDouble() else bb.double
                }
                cursor = payload + payloadLen.toInt()
            }
            val seconds = duration?.times(scale.toDouble() / 1_000_000_000.0)
            if (seconds != null && seconds.isFinite() && seconds in 120.0..43_200.0)
                return seconds.roundToInt()
        }
        return null
    }
}
