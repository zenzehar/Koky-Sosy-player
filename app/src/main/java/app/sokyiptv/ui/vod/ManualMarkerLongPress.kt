package app.sokyiptv.ui.vod

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type

/**
 * Opens the menu ONLY after the physical OK release. Never insert a new Dialog
 * mid-press: that previously delivered OK-up to the new focused menu, selecting
 * or dismissing the first option before users could navigate.
 *
 * An ordinary short OK is forwarded unchanged to the existing playback button.
 * Repeated down events on remotes that repeat OK are consumed so they cannot
 * retrigger a click. This state survives player recompositions.
 */
internal class MarkerHoldGate(private val thresholdMs: Long = 700L) {
    private var startedAt = -1L
    private var activeCode = -1

    enum class Result { PASS, CONSUME, OPEN }

    fun handle(code: Int, down: Boolean, up: Boolean, repeat: Int,
        eventTimeMs: Long, enabled: Boolean): Result {
        if (!down && !up) return Result.PASS
        if (down && repeat == 0) {
            if (enabled) {
                activeCode = code
                startedAt = eventTimeMs
            } else {
                activeCode = -1
                startedAt = -1L
            }
            return Result.PASS
        }
        if (activeCode != code || startedAt < 0L) return Result.PASS
        if (down) return Result.CONSUME
        val elapsed = (eventTimeMs - startedAt).coerceAtLeast(0L)
        activeCode = -1
        startedAt = -1L
        return if (elapsed >= thresholdMs && enabled) Result.OPEN else Result.PASS
    }

    fun cancel() {
        startedAt = -1L
        activeCode = -1
    }
}

@Composable
internal fun rememberManualMarkerLongPress(
    enabled: Boolean,
    onLongPress: () -> Unit,
): (KeyEvent) -> Boolean {
    val active by rememberUpdatedState(enabled)
    val invoke by rememberUpdatedState(onLongPress)
    val gate = remember { MarkerHoldGate() }
    return remember(gate) {
        { event: KeyEvent ->
            val native = event.nativeKeyEvent
            val keyCode = native.keyCode
            val ok = keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER ||
                keyCode == android.view.KeyEvent.KEYCODE_BUTTON_A
            if (!ok) {
                if (event.type == KeyEventType.KeyDown) gate.cancel()
                false
            } else when (gate.handle(
                code = keyCode,
                down = event.type == KeyEventType.KeyDown,
                up = event.type == KeyEventType.KeyUp,
                repeat = native.repeatCount,
                eventTimeMs = native.eventTime,
                enabled = active,
            )) {
                MarkerHoldGate.Result.PASS -> false
                MarkerHoldGate.Result.CONSUME -> true
                MarkerHoldGate.Result.OPEN -> {
                    // Trigger only after consuming the released key.
                    invoke()
                    true
                }
            }
        }
    }
}
