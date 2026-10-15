/*
 * Koky & Sosy Player — remote-safe click and long-press.
 * GPL-3.0-or-later.
 */
package app.opentv.ui.vod

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent

/**
 * Native TV key holds are not consistently mapped to Compose touch long-click on Fire TV.
 * Intercept Select/Enter key-down repeats and measure hold duration on key-up. Clicking the
 * surface with a mouse/touch still uses standard accessibility-aware combinedClickable.
 * A visible options control should also be offered when screen space permits.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.tvRemoteActions(onClick: () -> Unit, onLongPress: () -> Unit): Modifier {
    val latestClick by rememberUpdatedState(onClick)
    val latestLong by rememberUpdatedState(onLongPress)
    var downAt by remember { mutableLongStateOf(0L) }
    var longTriggered by remember { mutableStateOf(false) }
    return this.onPreviewKeyEvent { event ->
        when (event.key) {
            Key.Menu -> {
                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) latestLong()
                true
            }
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        val now = SystemClock.elapsedRealtime()
                        if (downAt == 0L) { downAt = now; longTriggered = false }
                        if (!longTriggered && (event.nativeKeyEvent.repeatCount >= 2 || now - downAt >= 600L)) {
                            longTriggered = true
                            latestLong()
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        val elapsed = SystemClock.elapsedRealtime() - downAt
                        if (downAt != 0L && !longTriggered) {
                            if (elapsed >= 600L) latestLong() else latestClick()
                        }
                        downAt = 0L
                        longTriggered = false
                        true
                    }
                    else -> false
                }
            }
            else -> false
        }
    }.combinedClickable(onClick = { latestClick() }, onLongClick = { latestLong() })
}
