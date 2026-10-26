/* GPL-3.0-or-later. Optional Netflix-like TV controls; existing player remains intact. */
package app.sokyiptv.ui.vod

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sokyiptv.player.SeekDirection
import app.sokyiptv.player.TvSeekPolicy
import kotlin.math.max
import kotlin.math.min

private val red = Color(0xFFE50914)
private val white = Color(0xFFF5F5F5)

/** No placeholder casting buttons: only controls with real actions are focusable. */
@Composable
internal fun NetflixLikePlayerChrome(
    title: String, isEpisode: Boolean, positionMs: Long, durationMs: Long,
    paused: Boolean, nextAvailable: Boolean, playFocus: FocusRequester,
    onBack: () -> Unit, onToggle: () -> Unit, onSeek: (Long) -> Unit,
    onEpisodes: () -> Unit, onTracks: () -> Unit, onNext: () -> Unit,

) {
    val close = remember { FocusRequester() }
    val rewind = remember { FocusRequester() }
    val forward = remember { FocusRequester() }
    val timeline = remember { FocusRequester() }
    val episodes = remember { FocusRequester() }
    val tracks = remember { FocusRequester() }
    val next = remember { FocusRequester() }
    var scrubbing by remember { mutableStateOf(false) }
    var preview by remember { mutableLongStateOf(0L) }
    var heldAt by remember { mutableLongStateOf(0L) }
    var startAt by remember { mutableLongStateOf(0L) }
    val seekDisplay = if (scrubbing) preview else positionMs
    Box(Modifier.fillMaxSize().background(
        Brush.verticalGradient(0f to Color.Black.copy(alpha = .57f),
            .26f to Color.Transparent, .62f to Color.Transparent,
            1f to Color.Black.copy(alpha = .94f))
    )) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontSize = 20.sp, color = white,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(.62f)
                .padding(top = 38.dp))
        NIcon(Icons.Default.Close, "Close", Modifier.align(Alignment.TopEnd)
            .padding(end = 35.dp, top = 25.dp).focusRequester(close)
            .focusProperties { down = playFocus; left = playFocus }, 40, onBack)
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(115.dp)) {
            NIcon(Icons.Default.Replay10, "Rewind 10 seconds",
                Modifier.focusRequester(rewind).focusProperties {
                    right = playFocus; up = close; down = timeline },
                66, { onSeek(TvSeekPolicy.tap(positionMs, durationMs, SeekDirection.BACKWARD)) })
            NIcon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                if (paused) "Play" else "Pause",
                Modifier.focusRequester(playFocus).focusProperties {
                    left = rewind; right = forward; up = close; down = timeline
                }, 82, onToggle)
            NIcon(Icons.Default.Forward10, "Forward 10 seconds",
                Modifier.focusRequester(forward).focusProperties {
                    left = playFocus; up = close; down = timeline },
                66, { onSeek(TvSeekPolicy.tap(positionMs, durationMs, SeekDirection.FORWARD)) })
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .padding(start = 52.dp, end = 43.dp, bottom = 95.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(40.dp)
                .focusRequester(timeline)
                .focusProperties {
                    up = playFocus
                    down = if (isEpisode) episodes else tracks
                }
                .onPreviewKeyEvent { e ->
                    val dir = when (e.key) {
                        Key.DirectionRight -> SeekDirection.FORWARD
                        Key.DirectionLeft -> SeekDirection.BACKWARD
                        else -> null
                    }
                    if (dir == null) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyDown && durationMs > 0L) {
                        if (!scrubbing) {
                            startAt = positionMs
                            heldAt = android.os.SystemClock.uptimeMillis()
                            preview = TvSeekPolicy.tap(positionMs, durationMs, dir)
                            scrubbing = true
                        } else {
                            val elapsed = android.os.SystemClock.uptimeMillis() - heldAt
                            if (elapsed >= TvSeekPolicy.HOLD_THRESHOLD_MS) {
                                val value = TvSeekPolicy.heldPreview(startAt, elapsed, dir, durationMs)
                                preview = if (dir == SeekDirection.FORWARD) max(preview, value)
                                    else min(preview, value)
                            }
                        }
                    } else if (e.type == KeyEventType.KeyUp && scrubbing) {
                        onSeek(preview)
                        scrubbing = false
                    }
                    true
                }.semantics {
                    contentDescription = "Timeline. Left/right seek 10 seconds; hold to accelerate."
                }.focusable(), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxWidth().height(22.dp)) {
                    val a = 9.dp.toPx()
                    val b = size.width - a
                    val y = size.height / 2f
                    drawLine(Color(0xFF606060), Offset(a, y), Offset(b, y),
                        3.dp.toPx(), cap = StrokeCap.Round)
                    val fraction = if (durationMs > 0L)
                        (seekDisplay.toDouble() / durationMs).toFloat().coerceIn(0f, 1f)
                        else 0f
                    val x = a + (b - a) * fraction
                    drawLine(red, Offset(a, y), Offset(x, y), 4.dp.toPx(),
                        cap = StrokeCap.Round)
                    drawCircle(red, 10.dp.toPx(), Offset(x, y))
                }
            }
            Spacer(Modifier.width(15.dp))
            Text(if (durationMs > 0L) nClock(durationMs) else "--:--",
                color = white, fontSize = 17.sp)
        }
        Text(nClock(seekDisplay), color = white, fontSize = 14.sp,
            modifier = Modifier.align(Alignment.BottomStart)
                .padding(start = 52.dp, bottom = 137.dp))
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 27.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (isEpisode) NText("Episodes", Modifier.focusRequester(episodes)
                .focusProperties { up = timeline; right = tracks }, onEpisodes)
            NText("Audio & Subtitles", Modifier.focusRequester(tracks)
                .focusProperties {
                    up = timeline
                    if (isEpisode) left = episodes
                    right = if (isEpisode && nextAvailable) next else tracks
                }, onTracks)
            if (isEpisode && nextAvailable)
                NText("Next Episode", Modifier.focusRequester(next)
                    .focusProperties { left = tracks; up = timeline }, onNext)

        }

    }
}

@Composable
internal fun NetflixSkipPrompt(label: String, modifier: Modifier = Modifier,
    onClick: () -> Unit) {
    NText(label, modifier, onClick, emphasized = true)
}
@Composable
internal fun NetflixTrackOptions(
    audio: List<Pair<String, () -> Unit>>,
    subtitles: List<Pair<String, () -> Unit>>,
    onClose: () -> Unit,
) {
    Column(Modifier.background(Color(0xF4141414), RoundedCornerShape(12.dp))
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Audio & Subtitles", color = white, fontWeight = FontWeight.Bold)
        Text("Audio", color = white.copy(alpha = .7f))
        audio.forEach { (label, action) -> NText(label, onClick = action) }
        Text("Subtitles", color = white.copy(alpha = .7f))
        subtitles.forEach { (label, action) -> NText(label, onClick = action) }
        NText("Done", onClick = onClose)
    }
}

@Composable
private fun NIcon(icon: ImageVector, label: String, modifier: Modifier = Modifier,
    size: Int = 36, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(modifier.onFocusChanged { focused = it.isFocused }
        .border(if (focused) 2.dp else 0.dp, white, RoundedCornerShape(10.dp))
        .clickable(onClick = onClick).padding(6.dp), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = white, modifier = Modifier.size(size.dp))
    }
}
@Composable
private fun NText(label: String, modifier: Modifier = Modifier, onClick: () -> Unit,
    emphasized: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(5.dp)
    Box(modifier.onFocusChanged { focused = it.isFocused }
        .background(if (focused || emphasized) white else Color(0xAE242424), shape)
        .border(if (focused) 2.dp else 0.dp, white, shape)
        .clickable(onClick = onClick).padding(horizontal = 15.dp, vertical = 9.dp)) {
        Text(label, color = if (focused || emphasized) Color.Black else white,
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}
private fun nClock(ms: Long): String {
    val s = ms.coerceAtLeast(0L) / 1000L
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
}
