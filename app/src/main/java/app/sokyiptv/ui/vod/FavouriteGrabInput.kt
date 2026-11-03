package app.sokyiptv.ui.vod

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * While grabbed, arrows move the selected item in the *order*, not the focus.
 * OK drops it without opening or playing. Back is deliberately handled by the
 * owning screen so it can finish the reorder safely.
 * Avoid extra focus stops and avoid auto-scrolling jobs on every normal keypress.
 */
internal fun Modifier.grabFavouriteKeys(
    grabbed: Boolean,
    columns: Int,
    onStep: (Int) -> Unit,
    onDrop: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    if (!grabbed) return@onPreviewKeyEvent false
    val step = when (event.key) {
        Key.DirectionLeft -> -1
        Key.DirectionRight -> 1
        Key.DirectionUp -> -columns.coerceAtLeast(1)
        Key.DirectionDown -> columns.coerceAtLeast(1)
        else -> 0
    }
    val confirm = event.key == Key.DirectionCenter || event.key == Key.Enter
    if (step == 0 && !confirm) return@onPreviewKeyEvent false
    if (event.type == KeyEventType.KeyDown) {
        if (step != 0) onStep(step) else if (event.nativeKeyEvent.repeatCount == 0) onDrop()
    }
    true
}
