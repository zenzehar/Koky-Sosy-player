/*
 * Koky & Sosy Player: TV-first native VLC renderer and transport controls.
 * Derived from GPL-3.0-or-later OpenTV; no VLC views may be attached twice.
 */
package app.opentv.ui.vod

import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/** Native VLC is the first VOD choice. Media3 remains an explicitly bounded alternative. */
@Composable
fun VlcFallbackScreen(
    streamUrl: String,
    title: String,
    userAgent: String,
    initialPositionMs: Long,
    onBack: () -> Unit,
    onTryMedia3: () -> Unit = {},
    onProgress: (Long, Long) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val libvlc = remember(streamUrl) {
        LibVLC(context.applicationContext, arrayListOf("--network-caching=1400", "--no-video-title-show"))
    }
    val player = remember(streamUrl) { MediaPlayer(libvlc) }
    var ready by remember(streamUrl) { mutableStateOf(false) }
    var failed by remember(streamUrl) { mutableStateOf(false) }
    var ended by remember(streamUrl) { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var previewMs by remember { mutableLongStateOf(0L) }
    var initialSeekPending by remember { mutableStateOf(initialPositionMs > 0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    val rootFocus = remember { FocusRequester() }
    val pauseFocus = remember { FocusRequester() }
    val seekFocus = remember { FocusRequester() }

    fun showControls() { controlsVisible = true; interaction++ }
    fun seekTo(target: Long) {
        if (!player.isSeekable) return
        val capped = target.coerceIn(0L, durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)
        player.setTime(capped)
        positionMs = capped
        showControls()
    }

    DisposableEffect(player) {
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> scope.launch { ready = true; failed = false; paused = false }
                MediaPlayer.Event.EncounteredError -> scope.launch { failed = true; ready = false; showControls() }
                MediaPlayer.Event.EndReached -> scope.launch { ended = true; showControls() }
            }
        }
        onDispose {
            onProgress(positionMs, durationMs)
            player.setEventListener(null)
            runCatching { player.stop() }
            // This is the ONLY detach; factory runs only once per AndroidView instance.
            runCatching { player.detachViews() }
            runCatching { player.release() }
            runCatching { libvlc.release() }
        }
    }

    LaunchedEffect(player) {
        var lastSaved = 0L
        while (isActive) {
            runCatching {
                val length = player.length
                if (length > 0L) durationMs = length
                if (!scrubbing) positionMs = player.time.coerceAtLeast(0L)
                if (ready && initialSeekPending && player.isSeekable && durationMs > 0L) {
                    player.setTime(initialPositionMs.coerceIn(0L, durationMs))
                    initialSeekPending = false
                }
                if (ready && positionMs - lastSaved >= 10_000L && durationMs > 0L) {
                    lastSaved = positionMs
                    onProgress(positionMs, durationMs)
                }
            }
            delay(500)
        }
    }

    LaunchedEffect(controlsVisible, interaction, ready, paused, scrubbing, failed, ended) {
        if (controlsVisible && ready && !paused && !scrubbing && !failed && !ended) {
            delay(5_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(controlsVisible) {
        delay(55)
        runCatching {
            if (controlsVisible) pauseFocus.requestFocus() else rootFocus.requestFocus()
        }
    }

    BackHandler {
        if (controlsVisible && ready && !failed) controlsVisible = false else onBack()
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (controlsVisible) {
                    // Every remote interaction resets the idle-hiding timer.
                    if (event.key != Key.Back && event.key != Key.Escape) interaction++
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.DirectionLeft -> { seekTo(positionMs - 10_000L); true }
                    Key.DirectionRight -> { seekTo(positionMs + 10_000L); true }
                    Key.DirectionUp, Key.DirectionDown, Key.Enter, Key.DirectionCenter -> {
                        showControls(); true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                VLCVideoLayout(ctx).apply {
                    isFocusable = false
                    isFocusableInTouchMode = false
                    // VLCVideoLayout automatically resizes the native video surface with the TV.
                    player.attachViews(this, null, true, false)
                    player.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
                    val media = Media(libvlc, Uri.parse(streamUrl))
                    media.setHWDecoderEnabled(true, false)
                    media.addOption(":http-user-agent=$userAgent")
                    player.media = media
                    media.release()
                    player.play()
                }
            },
        )
        if (!ready && !failed && !ended) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
        if (failed) {
            Column(
                Modifier.align(Alignment.Center).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("VLC could not decode or open this stream.", color = Color.White, textAlign = TextAlign.Center)
                TvVlcButton("Retry VLC") { failed = false; ended = false; player.play() }
                TvVlcButton("Try Media3 instead", onClick = onTryMedia3)
                TvVlcButton("Back to title", onClick = onBack)
            }
        }
        if (controlsVisible) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.78f))
                    .padding(horizontal = 26.dp, vertical = 14.dp),
            ) {
                Text(title, color = Color.White, maxLines = 1, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(clock(if (scrubbing) previewMs else positionMs), color = Color.White)
                    if (durationMs > 0L) {
                        Slider(
                            value = if (scrubbing) previewMs.toFloat() else positionMs.coerceIn(0L, durationMs).toFloat(),
                            valueRange = 0f..durationMs.toFloat(),
                            onValueChange = { scrubbing = true; previewMs = it.toLong(); interaction++ },
                            onValueChangeFinished = { seekTo(previewMs); scrubbing = false },
                            enabled = player.isSeekable,
                            modifier = Modifier.weight(1f).focusRequester(seekFocus),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                        Text("Duration unavailable", color = Color.LightGray)
                    }
                    Text(clock(durationMs), color = Color.White)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvVlcButton("−10s", enabled = player.isSeekable) { seekTo(positionMs - 10_000L) }
                    TvVlcButton(if (paused) "Play" else "Pause", modifier = Modifier.focusRequester(pauseFocus)) {
                        if (paused) player.play() else player.pause()
                        paused = !paused
                        showControls()
                    }
                    TvVlcButton("+10s", enabled = player.isSeekable) { seekTo(positionMs + 10_000L) }
                    TvVlcButton("Back", onClick = onBack)
                }
                if (ended) Text("Playback finished", color = Color.White)
            }
        }
    }
}

@Composable
private fun TvVlcButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier
            .background(if (focused) Color(0xFF5468D4) else Color(0xFF263258), shape)
            .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Color.LightGray, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Color.White else Color.Gray)
    }
}

private fun clock(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0L) / 1000)
    return if (seconds >= 3600L) "%d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
