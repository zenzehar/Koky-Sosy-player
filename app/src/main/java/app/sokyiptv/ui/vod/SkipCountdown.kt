package app.sokyiptv.ui.vod

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Ten seconds of ACTIVE playback; pause, buffering and frozen video pause the fill. */
internal object SkipCountdownPolicy {
    const val LENGTH_MS = 10_000L
    fun advance(elapsed: Long, delta: Long, playing: Boolean): Long =
        (elapsed.coerceIn(0L, LENGTH_MS) +
            if (playing) delta.coerceAtLeast(0L) else 0L).coerceAtMost(LENGTH_MS)
    fun fraction(elapsed: Long): Float =
        elapsed.coerceIn(0L, LENGTH_MS).toFloat() / LENGTH_MS
    fun remainingSeconds(elapsed: Long): Int =
        ((LENGTH_MS - elapsed.coerceIn(0L, LENGTH_MS) + 999L) / 1_000L).toInt()
}

/**
 * Stored separately for each episode and INTRO/CREDITS; never inherited by the
 * next episode.  The caller owns dismissal and exposes Watch intro/credits.
 */
@Composable
internal fun rememberSkipCountdown(
    mediaKey: String,
    kind: String,
    offered: Boolean,
    activelyPlaying: Boolean,
    onTimeout: () -> Unit,
): Long {
    var elapsed by remember(mediaKey, kind) { mutableLongStateOf(0L) }
    val currentTimeout by rememberUpdatedState(onTimeout)
    LaunchedEffect(mediaKey, kind, offered, activelyPlaying) {
        if (!offered || !activelyPlaying || elapsed >= SkipCountdownPolicy.LENGTH_MS)
            return@LaunchedEffect
        var last = SystemClock.elapsedRealtime()
        while (isActive && elapsed < SkipCountdownPolicy.LENGTH_MS) {
            delay(80L)
            val now = SystemClock.elapsedRealtime()
            elapsed = SkipCountdownPolicy.advance(elapsed, now - last, playing = true)
            last = now
            if (elapsed >= SkipCountdownPolicy.LENGTH_MS) {
                currentTimeout()
                break
            }
        }
    }
    return elapsed
}
