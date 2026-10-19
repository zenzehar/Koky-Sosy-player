/* GPL-3.0-or-later: pure VLC buffering diagnostics without native-player ownership. */
package app.sokyiptv.player

/**
 * LibVLC network-buffer events and monotonic clock health, with no player control commands.
 * A buffering event describes input starvation; a stalled time without buffering suggests
 * decoder/render or another source problem. Neither alone proves why frames stopped.
 */
class VlcPlaybackHealth {
    enum class State { STARTING, PLAYING, BUFFERING, STALLED, PAUSED }

    data class Snapshot(val state: State, val interruptions: Int, val bufferPercent: Int)

    private var started = false
    private var isBuffering = false
    private var bufferingSinceMs = 0L
    private var percent = 0
    private var interruptions = 0
    private var lastNativePositionMs: Long? = null
    private var lastAdvancedAtMs = 0L

    val networkBuffering: Boolean get() = isBuffering

    fun onBuffering(bufferPercentage: Float, nowMillis: Long) {
        percent = bufferPercentage.toInt().coerceIn(0, 100)
        if (percent < 99) {
            if (!isBuffering) {
                isBuffering = true
                bufferingSinceMs = nowMillis
                if (started) interruptions++
            }
        } else {
            isBuffering = false
        }
    }

    fun onPlaying(nowMillis: Long) {
        started = true
        isBuffering = false
        lastAdvancedAtMs = nowMillis
    }

    fun sample(nowMillis: Long, positionMs: Long, nativePlaying: Boolean, paused: Boolean): Snapshot {
        val lastPosition = lastNativePositionMs
        if (lastPosition == null || positionMs > lastPosition + 150L || positionMs < lastPosition - 1000L) {
            lastNativePositionMs = positionMs
            lastAdvancedAtMs = nowMillis
        }
        val state = when {
            paused -> State.PAUSED
            !started -> State.STARTING
            isBuffering && nowMillis - bufferingSinceMs >= 750L -> State.BUFFERING
            isBuffering -> State.PLAYING // Ignore momentary event bursts to avoid UI flicker.
            nativePlaying && nowMillis - lastAdvancedAtMs >= 7_000L -> State.STALLED
            else -> State.PLAYING
        }
        return Snapshot(state, interruptions, percent)
    }
}
