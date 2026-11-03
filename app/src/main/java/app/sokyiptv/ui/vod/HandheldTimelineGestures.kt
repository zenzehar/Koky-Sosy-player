/*
 * GPL-3.0-or-later. Handheld-only timeline tap/drag seeking.
 * Does not install any pointer handler on Android TV or Fire TV.
 */
package app.sokyiptv.ui.vod

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.consume
import androidx.compose.ui.input.pointer.pointerInput
import app.sokyiptv.ui.isHandheldTouch

internal object HandheldSeekPosition {
    fun at(x: Float, width: Int, durationMs: Long): Long {
        if (durationMs <= 0L || width <= 0) return 0L
        return (x.coerceIn(0f, width.toFloat()).toDouble() /
            width.toDouble() * durationMs.toDouble()).toLong()
            .coerceIn(0L, durationMs)
    }
}

/**
 * A tap seeks once. A drag previews time as the finger moves, then submits
 * exactly one seek on release — never reloads the stream on each move.
 * D-pad/keyboard seek handlers remain unchanged and the TV receives no
 * new pointer nodes whatsoever.
 */
@Composable
internal fun Modifier.handheldTimelineGestures(
    durationMs: Long,
    onSeek: (Long) -> Unit,
    onPreview: (Long) -> Unit = {},
    onSeekingChanged: (Boolean) -> Unit = {},
): Modifier {
    if (!isHandheldTouch() || durationMs <= 0L) return this
    val submit by rememberUpdatedState(onSeek)
    val preview by rememberUpdatedState(onPreview)
    val selecting by rememberUpdatedState(onSeekingChanged)

    return this
        .pointerInput(durationMs) {
            detectTapGestures(onTap = { p ->
                submit(HandheldSeekPosition.at(p.x, size.width, durationMs))
            })
        }
        .pointerInput(durationMs) {
            var last: Long? = null
            detectDragGestures(
                onDragStart = { p ->
                    last = HandheldSeekPosition.at(p.x, size.width, durationMs)
                    selecting(true)
                    preview(last!!)
                },
                onDragEnd = {
                    val target = last
                    last = null
                    selecting(false)
                    if (target != null) submit(target)
                },
                onDragCancel = {
                    last = null
                    selecting(false)
                },
                onDrag = { change, _ ->
                    last = HandheldSeekPosition.at(change.position.x, size.width, durationMs)
                    preview(last!!)
                    change.consume()
                },
            )
        }
}
