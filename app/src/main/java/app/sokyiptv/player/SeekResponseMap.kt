/* GPL-3.0-or-later. Learned stream-specific time seek response, never percentage based. */
package app.sokyiptv.player

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Two verified, monotonic seek outcomes are needed before predicting native
 * command timestamps. Bounded interpolation and short extrapolation only.
 * Learning occurs from the native clock after playback has settled.
 */
internal class SeekResponseMap {
    private data class Anchor(val commandMs: Long, val landedMs: Long)
    private val anchors = ArrayList<Anchor>()

    fun record(commandMs: Long, landedMs: Long) {
        if (commandMs < 5_000L || landedMs < 5_000L ||
            commandMs > MAX_TIME || landedMs > MAX_TIME) return
        anchors.removeAll { abs(it.commandMs - commandMs) < 3_000L }
        anchors.add(Anchor(commandMs, landedMs))
        if (anchors.size > 12) anchors.removeAt(0)
    }

    fun commandFor(targetMs: Long): Long {
        if (targetMs <= 0L || anchors.size < 2) return targetMs.coerceAtLeast(0L)
        val sorted = anchors.sortedBy { it.landedMs }
        // An exact previously observed landing has a known native command;
        // it must not fall through the interpolation case to a bad identity seek.
        val exact = sorted.minByOrNull { abs(it.landedMs - targetMs) }
        if (exact != null && abs(exact.landedMs - targetMs) <= 1_000L) {
            return exact.commandMs
        }
        val before = sorted.lastOrNull { it.landedMs <= targetMs }
        val after = sorted.firstOrNull { it.landedMs >= targetMs }
        val a: Anchor
        val b: Anchor
        when {
            before != null && after != null && before !== after -> { a = before; b = after }
            targetMs < sorted.first().landedMs &&
                sorted.first().landedMs - targetMs <= 90_000L -> {
                a = sorted[0]; b = sorted[1]
            }
            targetMs > sorted.last().landedMs &&
                targetMs - sorted.last().landedMs <= 90_000L -> {
                a = sorted[sorted.lastIndex - 1]; b = sorted.last()
            }
            else -> return targetMs
        }
        val dx = (b.commandMs - a.commandMs).toDouble()
        val dy = (b.landedMs - a.landedMs).toDouble()
        if (dx < 10_000.0 || dy < 10_000.0) return targetMs
        val slope = dy / dx
        if (!slope.isFinite() || slope !in 0.4..2.5) return targetMs
        val predicted = a.commandMs + (targetMs - a.landedMs) / slope
        if (!predicted.isFinite() || predicted < 0.0 || predicted > MAX_TIME) return targetMs
        val result = predicted.roundToLong()
        if (abs(result - targetMs) > maxOf(120_000L, targetMs / 2L)) return targetMs
        return result
    }

    companion object { private const val MAX_TIME = 24L * 60L * 60L * 1_000L }
}
