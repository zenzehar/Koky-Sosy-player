package app.sokyiptv.core

import android.os.SystemClock
import android.view.Choreographer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Optional diagnostic only: dispatch-to-NEXT-frame (vsync callback) latency,
 * not a claim to measure actual visual completion or physical remote latency.
 * It runs no callbacks or allocations when disabled.
 */
object TvInputLatencyMonitor {
    data class Snapshot(
        val samples: Int = 0,
        val averageMs: Long = 0L,
        val p95Ms: Long = 0L,
        val latestMs: Long = 0L,
        val slowFrames: Int = 0,
    )
    @Volatile var enabled = false
    private val recent = ArrayDeque<Long>()
    private var total = 0
    private var lastPublishedAt = 0L
    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    /** Main-thread key dispatch only. */
    fun recordDirectionOrOk() {
        if (!enabled) return
        val startedMs = SystemClock.uptimeMillis()
        Choreographer.getInstance().postFrameCallback {
            if (!enabled) return@postFrameCallback
            val elapsed = (SystemClock.uptimeMillis() - startedMs).coerceAtLeast(0L)
            recent.addLast(elapsed)
            if (recent.size > 48) recent.removeFirst()
            total++
            if (total % 4 != 0 && startedMs - lastPublishedAt < 450L) return@postFrameCallback
            lastPublishedAt = SystemClock.uptimeMillis()
            val sorted = recent.sorted()
            _snapshot.value = Snapshot(
                samples = total,
                averageMs = recent.average().toLong(),
                p95Ms = sorted[((sorted.size - 1) * 0.95).toInt()],
                latestMs = elapsed,
                slowFrames = recent.count { it >= 50L },
            )
        }
    }
}
