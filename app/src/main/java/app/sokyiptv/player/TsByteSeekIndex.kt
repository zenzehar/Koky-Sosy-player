/* GPL-3.0-or-later. Seek Solution 4: indexed byte positioning without VLC's defective PCR timeline. */
package app.sokyiptv.player

import kotlin.math.abs

/**
 * Sparse, verified video-PTS -> HTTP byte-offset landmarks.
 * No decoder, no full-file scan. Use only with VLC TS byte-position seek option.
 * A consistently monotonic video PTS clock is necessary, not sufficient, for
 * accurate frame seeking. Malformed/discontinuous samples reject the index.
 */
internal class TsByteSeekIndex private constructor(
    private val landmarks: List<Landmark>,
    val estimatedDurationMs: Long,
    private val fileBytes: Long,
) {
    data class Landmark(val byteOffset: Long, val elapsedMs: Long)

    fun byteFractionFor(timeMs: Long): Float {
        val desired = timeMs.coerceIn(0L, estimatedDurationMs)
        val next = landmarks.firstOrNull { it.elapsedMs >= desired }
        val left: Landmark
        val right: Landmark
        when {
            next == null -> {
                left = landmarks[landmarks.lastIndex - 1]
                right = landmarks.last()
            }
            next === landmarks.first() -> {
                left = landmarks[0]
                right = landmarks[1]
            }
            else -> {
                val i = landmarks.indexOf(next)
                left = landmarks[i - 1]
                right = landmarks[i]
            }
        }
        val span = (right.elapsedMs - left.elapsedMs).coerceAtLeast(1L)
        val weight = (desired - left.elapsedMs).toDouble() / span.toDouble()
        val offset = left.byteOffset + (right.byteOffset - left.byteOffset) * weight
        // Avoid seeking exactly to EOF; it would trigger EndReached without frames.
        return (offset / fileBytes.toDouble()).coerceIn(0.0, 0.992).toFloat()
    }

    fun isSignificantlyLongerThan(declaredMs: Long): Boolean =
        declaredMs <= 0L || estimatedDurationMs >
            maxOf(declaredMs + 90_000L, declaredMs * 112L / 100L)

    data class Probe(val offset: Long, val totalBytes: Long, val clock: TransportStreamDuration.VideoClock)
    companion object {
        private const val WRAP90K = 1L shl 33
        fun fromProbes(probes: List<Probe>): TsByteSeekIndex? {
            if (probes.size < 6) return null
            val items = probes.sortedBy { it.offset }
            val total = items.first().totalBytes
            if (total < 1024 * 1024 || items.any { it.totalBytes != total }) return null
            if (items.first().offset > 32_768L || items.last().offset < total * 92L / 100L)
                return null
            val videoPid = items.first().clock.pid
            if (items.any { it.clock.pid != videoPid }) return null
            val startPts = items.first().clock.pts90k
            val nodes = ArrayList<Landmark>()
            for (p in items) {
                val elapsed90k = (p.clock.pts90k - startPts + WRAP90K) % WRAP90K
                val elapsed = elapsed90k / 90L
                if (elapsed > 24L * 3600 * 1000 || p.offset < 0 || p.offset >= total) return null
                if (nodes.isNotEmpty()) {
                    val older = nodes.last()
                    if (p.offset <= older.byteOffset || elapsed <= older.elapsedMs) return null
                }
                nodes.add(Landmark(p.offset, elapsed))
            }
            val duration = nodes.last().elapsedMs
            if (duration !in 60_000L..(24L * 3600 * 1000)) return null
            // Reject discontinuities and badly nonlinear timestamp/fraction drift.
            // A VBR video can vary considerably, so allow 8x bitrate differences,
            // but never trust a jumping clock or fake endpoint.
            val overallRate = duration.toDouble() / (nodes.last().byteOffset - nodes.first().byteOffset)
            for (i in 1 until nodes.size) {
                val segmentRate = (nodes[i].elapsedMs - nodes[i - 1].elapsedMs).toDouble() /
                    (nodes[i].byteOffset - nodes[i - 1].byteOffset).toDouble()
                val relative = segmentRate / overallRate
                if (relative < 0.125 || relative > 8.0) return null
            }
            return TsByteSeekIndex(nodes, duration, total)
        }
    }
}
