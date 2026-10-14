/*
 * Koky & Sosy Player — native VLC VOD fallback for Media3 unsupported containers.
 * This GPL-3.0-or-later implementation owns exactly one LibVLC native surface.
 */
package app.opentv.ui.vod

import android.net.Uri
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

@Composable
fun VlcFallbackScreen(
    streamUrl: String,
    title: String,
    userAgent: String,
    initialPositionMs: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val libvlc = remember { LibVLC(context.applicationContext, arrayListOf("--network-caching=1800", "--no-video-title-show")) }
    val player = remember { MediaPlayer(libvlc) }
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var previewMs by remember { mutableLongStateOf(0L) }
    var initialSeekPending by remember { mutableStateOf(initialPositionMs > 0L) }

    DisposableEffect(player) {
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> scope.launch { ready = true; failed = false }
                MediaPlayer.Event.EncounteredError -> scope.launch { failed = true; ready = false }
                MediaPlayer.Event.EndReached -> scope.launch { ended = true }
            }
        }
        onDispose {
            player.setEventListener(null)
            runCatching { player.stop() }
            runCatching { player.vlcVout.detachViews() }
            runCatching { player.release() }
            runCatching { libvlc.release() }
        }
    }

    LaunchedEffect(player) {
        while (isActive) {
            runCatching {
                val length = player.length
                if (length > 0L) durationMs = length
                if (!scrubbing) positionMs = player.time.coerceAtLeast(0L)
                if (ready && initialSeekPending && player.isSeekable && durationMs > 0L) {
                    player.setTime(initialPositionMs.coerceIn(0L, durationMs))
                    initialSeekPending = false
                }
            }
            delay(400L)
        }
    }

    fun seek(target: Long) {
        if (player.isSeekable) {
            val cap = durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
            val t = target.coerceIn(0L, cap)
            player.setTime(t)
            positionMs = t
        }
    }

    BackHandler { onBack() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).also { surface ->
                    player.vlcVout.setVideoView(surface)
                    player.vlcVout.attachViews()
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
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        if (failed) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Unable to play this stream with Media3 or VLC.", color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { failed = false; player.play() }) { Text("Retry VLC") }
                Button(onClick = onBack) { Text("Back to title") }
            }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.79f))
                .padding(20.dp),
        ) {
            Text(title, color = Color.White)
            Spacer(Modifier.height(8.dp))
            if (durationMs > 0L) {
                Slider(
                    value = if (scrubbing) previewMs.toFloat() else positionMs.toFloat(),
                    valueRange = 0f..durationMs.toFloat(),
                    onValueChange = { scrubbing = true; previewMs = it.toLong() },
                    onValueChangeFinished = { seek(previewMs); scrubbing = false },
                    enabled = player.isSeekable,
                )
            } else {
                Text("Seeking will be available if the stream provides a duration.", color = Color.LightGray)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { seek(positionMs - 10_000L) }, enabled = player.isSeekable) { Text("−10s") }
                Button(onClick = {
                    if (paused) player.play() else player.pause()
                    paused = !paused
                }) { Text(if (paused) "Play" else "Pause") }
                Button(onClick = { seek(positionMs + 10_000L) }, enabled = player.isSeekable) { Text("+10s") }
                Button(onClick = onBack) { Text("Back") }
            }
            if (ended) Text("Playback finished", color = Color.White)
        }
    }
}
