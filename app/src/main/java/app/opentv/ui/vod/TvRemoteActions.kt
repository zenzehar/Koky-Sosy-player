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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

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
    // Do not open the menu on KeyDown repeats: a subsequent KeyUp can land on
    // the newly focused menu and accidentally invoke its first item.
    // Dispatch the long/short action only after the original key is released.
    return this.onPreviewKeyEvent { event ->
        when (event.key) {
            Key.Menu -> {
                if (event.type == KeyEventType.KeyUp) latestLong()
                true
            }
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (downAt == 0L) downAt = SystemClock.elapsedRealtime()
                        true
                    }
                    KeyEventType.KeyUp -> {
                        val start = downAt
                        downAt = 0L
                        if (start != 0L) {
                            if (SystemClock.elapsedRealtime() - start >= 600L) latestLong()
                            else latestClick()
                        }
                        true
                    }
                    else -> false
                }
            }
            else -> false
        }
    }.combinedClickable(onClick = { latestClick() }, onLongClick = { latestLong() })
}
