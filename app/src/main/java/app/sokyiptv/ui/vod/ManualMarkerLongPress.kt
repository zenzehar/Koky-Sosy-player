package app.sokyiptv.ui.vod

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The normal OK key is untouched unless held while paused for >=700 ms.
 * Uses elapsed hold time, not KEYCODE repetition (some TV remotes never repeat OK).
 * This is an independent handler on the ROOT preview phase so it also works when
 * focus currently belongs to a seekbar or transport control.
 */
@Composable
internal fun rememberManualMarkerLongPress(
    enabled: Boolean,
    onLongPress: () -> Unit,
): (KeyEvent) -> Boolean {
    val enabledNow by rememberUpdatedState(enabled)
    val triggerNow by rememberUpdatedState(onLongPress)
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var armed by remember { mutableStateOf(false) }
    var triggered by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { job?.cancel() }
    }
    return remember(scope) {
        { e: KeyEvent ->
            val code = e.nativeKeyEvent.keyCode
            val ok = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                code == android.view.KeyEvent.KEYCODE_ENTER ||
                code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER ||
                code == android.view.KeyEvent.KEYCODE_BUTTON_A
            if (!ok) false
            else if (e.type == KeyEventType.KeyDown) {
                if (enabledNow && e.nativeKeyEvent.repeatCount == 0) {
                    job?.cancel()
                    armed = true
                    triggered = false
                    job = scope.launch {
                        delay(700L)
                        if (armed && enabledNow) {
                            triggered = true
                            triggerNow()
                        }
                    }
                }
                // A held OK must not also press an obscured transport button.
                triggered
            } else if (e.type == KeyEventType.KeyUp) {
                job?.cancel()
                armed = false
                val consumed = triggered
                triggered = false
                consumed
            } else false
        }
    }
}
