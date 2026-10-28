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
    val currentOffered by rememberUpdatedState(offered)
    val currentPlaying by rememberUpdatedState(activelyPlaying)
    // A SINGLE coroutine survives buffering/playing transitions. Previous
    // LaunchedEffect keys restarted at every health/clock transition; on TV
    // those interruptions could prevent the timeout callback from firing.
    LaunchedEffect(mediaKey, kind) {
        var fired = false
        var last = SystemClock.elapsedRealtime()
        while (isActive && !fired) {
            delay(80L)
            val now = SystemClock.elapsedRealtime()
            val delta = (now - last).coerceIn(0L, 250L)
            last = now
            if (!currentOffered || !currentPlaying) continue
            elapsed = SkipCountdownPolicy.advance(elapsed, delta, true)
            if (elapsed >= SkipCountdownPolicy.LENGTH_MS && currentOffered) {
                fired = true
                currentTimeout()
            }
        }
    }
    return elapsed
}
