/*
 * Koky & Sosy Player: TV-first native VLC renderer and transport controls.
 * Derived from GPL-3.0-or-later OpenTV; no VLC views may be attached twice.
 */
package app.opentv.ui.vod

import android.net.Uri
import android.content.Intent
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
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
import app.opentv.player.SeekDirection
import app.opentv.player.TvSeekPolicy
import app.opentv.player.VlcPlaybackHealth
import app.opentv.diagnostics.PlaybackDiagnostics
import app.opentv.ui.diagnostics.PlaybackDiagnosticsDialog
import kotlin.math.max
import kotlin.math.min

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
    onEnded: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val libvlc = remember(streamUrl) {
        LibVLC(context.applicationContext, arrayListOf("--network-caching=1400", "--no-video-title-show"))
    }
    val player = remember(streamUrl) { MediaPlayer(libvlc) }
    // Passive alpha diagnostics only: do not affect stable v0.1.9 playback state machine.
    val diagHealth = remember(streamUrl) { VlcPlaybackHealth() }
    var diagnosticText by remember(streamUrl) { mutableStateOf<String?>(null) }
    var diagnosticsState by remember(streamUrl) { mutableStateOf(false) }
    var ready by remember(streamUrl) { mutableStateOf(false) }
    var failed by remember(streamUrl) { mutableStateOf(false) }
    var ended by remember(streamUrl) { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var previewMs by remember { mutableLongStateOf(0L) }
    var seekOriginMs by remember { mutableLongStateOf(0L) }
    var seekHoldStartedAtMs by remember { mutableLongStateOf(0L) }
    var seekHeldDirection by remember { mutableStateOf<SeekDirection?>(null) }
    var timelineFocused by remember { mutableStateOf(false) }
    var initialSeekPending by remember { mutableStateOf(initialPositionMs > 0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    val rootFocus = remember { FocusRequester() }
    val pauseFocus = remember { FocusRequester() }
    val seekFocus = remember { FocusRequester() }

    fun showControls() { controlsVisible = true; interaction++ }
    fun showReport() {
        PlaybackDiagnostics.event(context, "REPORT", persistNow = true)
        diagnosticText = PlaybackDiagnostics.capture(context)
        diagnosticsState = true
        showControls()
    }
    fun openExternalVlc() {
        PlaybackDiagnostics.event(context, "VLC_EXTERNAL", persistNow = true)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(streamUrl), "video/*")
            setPackage("org.videolan.vlc")
        }
        // A provider may limit simultaneous connections; stop our decoder before handoff.
        runCatching {
            onProgress(positionMs, durationMs)
            player.stop()
            context.startActivity(intent)
            onBack()
        }.onFailure {
            // Do not show or log raw exception messages containing provider URLs.
            showControls()
        }
    }
    fun seekTo(target: Long) {
        if (!player.isSeekable) return
        val capped = target.coerceIn(0L, durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)
        player.setTime(capped)
        PlaybackDiagnostics.event(context, "VLC_SEEK", (capped / 1000).toInt())
        positionMs = capped
        showControls()
    }

    DisposableEffect(player) {
        PlaybackDiagnostics.begin("VLC", "RESTORED_V019")
        PlaybackDiagnostics.event(context, "VLC_START", persistNow = true)
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> scope.launch {
                    diagHealth.onPlaying(android.os.SystemClock.elapsedRealtime())
                    PlaybackDiagnostics.event(context, "VLC_READY", persistNow = true)
                    ready = true; failed = false; paused = false
                }
                MediaPlayer.Event.Buffering -> {
                    val percentage = event.getBuffering()
                    scope.launch {
                        diagHealth.onBuffering(
                            percentage, android.os.SystemClock.elapsedRealtime(),
                        )
                    }
                }
                MediaPlayer.Event.EncounteredError -> scope.launch {
                    PlaybackDiagnostics.event(context, "VLC_ERROR", persistNow = true)
                    failed = true; ready = false; showControls()
                }
                MediaPlayer.Event.EndReached -> scope.launch {
                    if (!ended) {
                        PlaybackDiagnostics.event(context, "VLC_END", persistNow = true)
                        ended = true
                        onProgress(durationMs, durationMs)
                        onEnded()
                    }
                    showControls()
                }
            }
        }
        onDispose {
            PlaybackDiagnostics.event(context, "VLC_EXIT", persistNow = true)
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
        var previousHealth = VlcPlaybackHealth.State.STARTING
        while (isActive) {
            runCatching {
                val length = player.length
                if (length > 0L) durationMs = length
                if (!scrubbing) positionMs = player.time.coerceAtLeast(0L)
                val tick = android.os.SystemClock.elapsedRealtime()
                val health = diagHealth.sample(tick, positionMs, player.isPlaying, paused)
                PlaybackDiagnostics.sample(positionMs, durationMs, health.interruptions)
                if (health.state != previousHealth) {
                    if (health.state == VlcPlaybackHealth.State.BUFFERING) {
                        PlaybackDiagnostics.event(context, "VLC_BUFFER",
                            health.bufferPercent, persistNow = true)
                    } else if (health.state == VlcPlaybackHealth.State.STALLED) {
                        PlaybackDiagnostics.event(context, "VLC_STALL", persistNow = true)
                    }
                    previousHealth = health.state
                }
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

    // Drive long-press preview from monotonic time, regardless of remote repeat rate.
    LaunchedEffect(seekHeldDirection, seekHoldStartedAtMs, durationMs) {
        val direction = seekHeldDirection ?: return@LaunchedEffect
        if (durationMs <= 0L) return@LaunchedEffect
        while (isActive && seekHeldDirection == direction) {
            val heldMs = (android.os.SystemClock.uptimeMillis() - seekHoldStartedAtMs).coerceAtLeast(0L)
            if (heldMs >= TvSeekPolicy.HOLD_THRESHOLD_MS) {
                val accelerated = TvSeekPolicy.heldPreview(seekOriginMs, heldMs, direction, durationMs)
                val firstTap = TvSeekPolicy.tap(seekOriginMs, durationMs, direction)
                previewMs = if (direction == SeekDirection.FORWARD) max(firstTap, accelerated)
                    else min(firstTap, accelerated)
                scrubbing = true
            }
            delay(75)
        }
    }

    LaunchedEffect(controlsVisible, interaction, ready, paused, scrubbing, failed, ended) {
        if (controlsVisible && ready && !paused && !scrubbing && !failed && !ended) {
            delay(5_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(controlsVisible, ready) {
        delay(55)
        runCatching {
            if (controlsVisible && ready) seekFocus.requestFocus()
            else if (controlsVisible) pauseFocus.requestFocus()
            else rootFocus.requestFocus()
        }
    }

    BackHandler {
        when {
            scrubbing -> {
                // Back cancels a preview, it does not exit the playing episode.
                scrubbing = false
                seekHeldDirection = null
                previewMs = positionMs
                showControls()
            }
            controlsVisible && ready && !failed -> controlsVisible = false
            else -> onBack()
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (controlsVisible) {
                    // Always allow Up to select timeline, and Down to return to transport.
                    // Do not rely solely on spatial focus traversal between Compose rows.
                    when (event.key) {
                        Key.DirectionUp -> {
                            if (!timelineFocused) { seekFocus.requestFocus(); showControls(); true }
                            else false
                        }
                        Key.DirectionDown -> {
                            if (timelineFocused) { pauseFocus.requestFocus(); showControls(); true }
                            else false
                        }
                        else -> {
                            if (event.key != Key.Back && event.key != Key.Escape) interaction++
                            false
                        }
                    }
                } else when (event.key) {
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
                TvVlcButton("Open in VLC app", onClick = ::openExternalVlc)
                TvVlcButton("Diagnostics QR", onClick = ::showReport)
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
                Text(
                    if (timelineFocused) "SEEK  •  Left/Right ±10s  •  Hold to accelerate  •  Down for buttons"
                    else "Press Up to focus the timeline",
                    color = if (timelineFocused) Color.White else Color.LightGray,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(clock(if (scrubbing) previewMs else positionMs), color = Color.White)
                    Box(
                        modifier = Modifier.weight(1f).padding(horizontal = 14.dp).height(48.dp)
                            .focusRequester(seekFocus)
                            .onFocusChanged { timelineFocused = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                val direction = when (event.key) {
                                    Key.DirectionLeft -> SeekDirection.BACKWARD
                                    Key.DirectionRight -> SeekDirection.FORWARD
                                    else -> null
                                }
                                if (direction != null) {
                                    if (event.type == KeyEventType.KeyDown) {
                                        if (player.isSeekable && durationMs > 0L) {
                                            if (seekHeldDirection != direction) {
                                                seekOriginMs = positionMs
                                                seekHoldStartedAtMs = android.os.SystemClock.uptimeMillis()
                                                seekHeldDirection = direction
                                                previewMs = TvSeekPolicy.tap(seekOriginMs, durationMs, direction)
                                            } else {
                                                val heldMs = (
                                                    android.os.SystemClock.uptimeMillis() - seekHoldStartedAtMs
                                                ).coerceAtLeast(0L)
                                                val accelerated = TvSeekPolicy.heldPreview(
                                                    seekOriginMs, heldMs, direction, durationMs,
                                                )
                                                val firstTap = TvSeekPolicy.tap(seekOriginMs, durationMs, direction)
                                                previewMs = if (direction == SeekDirection.FORWARD) {
                                                    max(firstTap, accelerated)
                                                } else {
                                                    min(firstTap, accelerated)
                                                }
                                            }
                                            scrubbing = true
                                            showControls()
                                        }
                                        true
                                    } else if (event.type == KeyEventType.KeyUp) {
                                        if (scrubbing) {
                                            seekTo(previewMs)
                                            scrubbing = false
                                        }
                                        seekHeldDirection = null
                                        true
                                    } else false
                                } else when {
                                    event.type == KeyEventType.KeyDown &&
                                        event.key == Key.DirectionDown -> {
                                        if (scrubbing) {
                                            seekTo(previewMs)
                                            scrubbing = false
                                            seekHeldDirection = null
                                        }
                                        pauseFocus.requestFocus()
                                        showControls()
                                        true
                                    }
                                    event.type == KeyEventType.KeyDown &&
                                        (event.key == Key.DirectionCenter || event.key == Key.Enter) -> {
                                        if (scrubbing) {
                                            seekTo(previewMs)
                                            scrubbing = false
                                            seekHeldDirection = null
                                        } else showControls()
                                        true
                                    }
                                    else -> false
                                }
                            }
                            .border(
                                width = if (timelineFocused) 3.dp else 0.dp,
                                color = Color.White,
                                shape = RoundedCornerShape(12.dp),
                            )
                            .semantics {
                                contentDescription = "Seek timeline. Left and right seek by ten seconds; hold to accelerate. Down moves to playback buttons."
                            }
                            .focusable(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(Modifier.fillMaxWidth().height(18.dp).padding(horizontal = 8.dp)) {
                            val centerY = size.height / 2f
                            val startX = 7.dp.toPx()
                            val endX = size.width - startX
                            drawLine(
                                color = Color.White.copy(alpha = 0.32f),
                                start = androidx.compose.ui.geometry.Offset(startX, centerY),
                                end = androidx.compose.ui.geometry.Offset(endX, centerY),
                                strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round,
                            )
                            val fraction = if (durationMs > 0L) {
                                (if (scrubbing) previewMs else positionMs)
                                    .coerceIn(0L, durationMs).toFloat() / durationMs.toFloat()
                            } else 0f
                            val playheadX = startX + (endX - startX) * fraction
                            drawLine(
                                color = if (timelineFocused) Color.White else Color(0xFFBBC6FF),
                                start = androidx.compose.ui.geometry.Offset(startX, centerY),
                                end = androidx.compose.ui.geometry.Offset(playheadX, centerY),
                                strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round,
                            )
                            drawCircle(
                                color = if (timelineFocused) Color.White else Color(0xFFBCC5FF),
                                radius = if (timelineFocused) 8.dp.toPx() else 5.dp.toPx(),
                                center = androidx.compose.ui.geometry.Offset(playheadX, centerY),
                            )
                        }
                    }
                    Text(
                        if (durationMs > 0L) clock(durationMs) else "--:--",
                        color = Color.White,
                    )
                }
                Row(
                    modifier = Modifier.onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            event.key == Key.DirectionUp) {
                            seekFocus.requestFocus()
                            showControls()
                            true
                        } else false
                    },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvVlcButton("−10s", enabled = player.isSeekable) { seekTo(positionMs - 10_000L) }
                    TvVlcButton(if (paused) "Play" else "Pause", modifier = Modifier.focusRequester(pauseFocus)) {
                        if (paused) player.play() else player.pause()
                        paused = !paused
                        showControls()
                    }
                    TvVlcButton("+10s", enabled = player.isSeekable) { seekTo(positionMs + 10_000L) }
                    TvVlcButton("Back", onClick = onBack)
                }
                // Extra actions keep the proven v0.1.9 seek/play row unchanged.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvVlcButton("Try Media3", onClick = onTryMedia3)
                    TvVlcButton("Open in VLC app", onClick = ::openExternalVlc)
                    TvVlcButton("Diagnostics QR", onClick = ::showReport)
                }
                if (ended) Text("Playback finished", color = Color.White)
            }
        }
    }
    if (diagnosticsState) {
        PlaybackDiagnosticsDialog(
            report = diagnosticText.orEmpty(),
            onDismiss = { diagnosticsState = false },
        )
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
