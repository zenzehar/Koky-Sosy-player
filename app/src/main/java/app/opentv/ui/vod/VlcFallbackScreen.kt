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
import kotlin.math.max
import kotlin.math.min
import java.util.concurrent.atomic.AtomicBoolean
import app.opentv.player.VlcSeekWatchdog
import app.opentv.player.VlcPlaybackHealth
import app.opentv.player.VlcPlaybackMode
import app.opentv.player.VlcPlaybackPolicy
import app.opentv.data.model.Source

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
    suspendPlayback: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appSettings = remember { app.opentv.core.AppSettings.get(context) }
    val bufferPreset = remember(streamUrl) { appSettings.playbackBufferPreset.value }
    val vlcMode = remember(streamUrl) { appSettings.vlcPlaybackMode.value }
    // The standalone VLC app plays this exact stream correctly. Default to libVLC's
    // native HTTP/decoder/cache behaviour instead of forcing the app's generic UA.
    // Retain the older tuned options as an explicit A/B setting, not an automatic reset.
    val libvlc = remember(streamUrl, bufferPreset, vlcMode) {
        LibVLC(
            context.applicationContext,
            VlcPlaybackPolicy.engineArguments(vlcMode, bufferPreset.vlcNetworkCacheMs),
        )
    }
    val player = remember(streamUrl, bufferPreset, vlcMode) { MediaPlayer(libvlc) }
    val released = remember(streamUrl) { AtomicBoolean(false) }
    val exitRequested = remember(streamUrl) { AtomicBoolean(false) }
    val seekWatchdog = remember(streamUrl) { VlcSeekWatchdog() }
    val playbackHealth = remember(streamUrl) { VlcPlaybackHealth() }
    var healthSnapshot by remember(streamUrl) {
        mutableStateOf(VlcPlaybackHealth.Snapshot(VlcPlaybackHealth.State.STARTING, 0, 0))
    }
    var recoveryMessage by remember(streamUrl) { mutableStateOf<String?>(null) }
    // Native seeks are serialized. A later D-pad command supersedes queued commands instead of
    // overwhelming a decoder with dozens of seeks while recovering from the first one.
    var queuedSeekTarget by remember(streamUrl) { mutableStateOf<Long?>(null) }
    var lastConfirmedPositionMs by remember(streamUrl) { mutableLongStateOf(0L) }
    var pendingSeekPreview by remember(streamUrl) { mutableStateOf(false) }
    var lastNativeSeekTime by remember(streamUrl) { mutableLongStateOf(0L) }
    fun exitOnce() {
        if (exitRequested.compareAndSet(false, true)) onBack()
    }
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
    fun seekTo(target: Long) {
        if (released.get() || failed || !ready ||
            !runCatching { player.isSeekable }.getOrDefault(false)) return
        val ceiling = durationMs.takeIf { it > 1_000L }?.minus(1_000L) ?: Long.MAX_VALUE
        val capped = target.coerceIn(0L, ceiling)
        previewMs = capped
        pendingSeekPreview = true
        val now = android.os.SystemClock.elapsedRealtime()
        if (!seekWatchdog.isIdle || now - lastNativeSeekTime < 450L) {
            // Keep only the latest target. The preview reacts instantly; native seeks do not pile up.
            queuedSeekTarget = capped
            showControls()
            return
        }
        queuedSeekTarget = null
        if (runCatching { player.setTime(capped) }.isFailure) {
            pendingSeekPreview = false
            recoveryMessage = "This stream could not seek to that position."
            showControls()
            return
        }
        lastNativeSeekTime = now
        // A successful seek can go backwards. Forget the old monotonic position so a later
        // confirmed lower timestamp can become the new correct resume point.
        lastConfirmedPositionMs = 0L
        if (!paused) seekWatchdog.begin(now) else pendingSeekPreview = false
        showControls()
    }

    fun openExternalVlc() {
        if (released.get() || exitRequested.get()) return
        // Package-restricted intent: only the VLC application receives the stream URL.
        // Stop the internal decoder/socket first to avoid two sessions on a one-stream account.
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(streamUrl), "video/*")
            setPackage("org.videolan.vlc")
        }
        try {
            if (durationMs > 0L && lastConfirmedPositionMs > 5_000L) {
                onProgress(lastConfirmedPositionMs, durationMs)
            }
            player.stop()
            context.startActivity(intent)
            exitOnce()
        } catch (e: android.content.ActivityNotFoundException) {
            recoveryMessage = "Standalone VLC is unavailable on this device."
            runCatching { player.play() }
            showControls()
        } catch (e: Exception) {
            // Do not log the exception: it can contain the credential-bearing stream URI.
            recoveryMessage = "Could not open VLC. Internal playback can be retried."
            showControls()
        }
    }

    DisposableEffect(player) {
        player.setEventListener { event ->
            if (!released.get()) when (event.type) {
                MediaPlayer.Event.Playing -> scope.launch {
                    if (!released.get()) {
                        playbackHealth.onPlaying(android.os.SystemClock.elapsedRealtime())
                        ready = true
                        failed = false
                        paused = false
                    }
                }
                MediaPlayer.Event.Buffering -> {
                    // LibVLC may emit native callbacks off the main/UI thread.
                    val percent = event.getBuffering()
                    scope.launch {
                        if (!released.get()) playbackHealth.onBuffering(
                            percent, android.os.SystemClock.elapsedRealtime(),
                        )
                    }
                }
                MediaPlayer.Event.EncounteredError -> scope.launch {
                    if (!released.get()) { failed = true; ready = false; showControls() }
                }
                MediaPlayer.Event.EndReached -> scope.launch {
                    if (!released.get()) {
                        if (!ended) {
                            ended = true
                            onProgress(durationMs, durationMs)
                            onEnded()
                        }
                        showControls()
                    }
                }
            }
        }
        onDispose {
            // The AndroidView can still own a native surface during composition disposal.
            // Its onRelease is the sole owner of stop/detach/release.
            runCatching { player.setEventListener(null) }
            // Never persist a speculative playhead from an unfinished/frozen native seek.
            if (!released.get()) onProgress(lastConfirmedPositionMs, durationMs)
        }
    }

    // Unlike pausing an Android view, pause() also stops the VLC audio decoder.
    LaunchedEffect(suspendPlayback) {
        if (suspendPlayback) {
            runCatching { player.pause() }
            paused = true
            controlsVisible = true
        }
    }

    LaunchedEffect(player) {
        var lastSaved = 0L
        while (isActive && !released.get()) {
            runCatching {
                val length = player.length
                if (length > 0L) durationMs = length
                val nativeTime = player.time.coerceAtLeast(0L)
                val tick = android.os.SystemClock.elapsedRealtime()
                healthSnapshot = playbackHealth.sample(
                    tick, nativeTime, player.isPlaying, paused,
                )
                if (!scrubbing) positionMs = nativeTime
                if (ready && initialSeekPending && player.isSeekable && durationMs > 0L) {
                    initialSeekPending = false
                    val target = initialPositionMs.coerceIn(0L, (durationMs - 1_000L).coerceAtLeast(0L))
                    if (runCatching { player.setTime(target) }.isSuccess) {
                        lastNativeSeekTime = android.os.SystemClock.elapsedRealtime()
                        if (!paused) seekWatchdog.begin(lastNativeSeekTime)
                        lastConfirmedPositionMs = 0L
                        previewMs = target
                        pendingSeekPreview = true
                    }
                }
                if (ready && !scrubbing && !ended) {
                    when (seekWatchdog.observe(
                        tick, nativeTime, !paused && !playbackHealth.networkBuffering,
                    )) {
                        VlcSeekWatchdog.Action.NUDGE -> {
                            // Never pause/play a decoder while network input is buffering.
                            // The old recovery actively amplified normal rebuffering.
                            recoveryMessage = "Seek is taking longer than expected. Buffering may recover."
                            showControls()
                        }
                        VlcSeekWatchdog.Action.RESTART -> {
                            // Let VLC/HTTP reconnect. Never automatically stop/restart the stream,
                            // especially while the provider is struggling to fill its cache.
                            queuedSeekTarget = null
                            pendingSeekPreview = false
                            recoveryMessage = "Seek not responding. Try Media3 or Retry VLC."
                            showControls()
                        }
                        else -> Unit
                    }
                    if (seekWatchdog.isIdle && pendingSeekPreview) {
                        pendingSeekPreview = false
                    }
                    if (seekWatchdog.isIdle && queuedSeekTarget != null) {
                        val queued = queuedSeekTarget
                        queuedSeekTarget = null
                        if (queued != null) seekTo(queued)
                    }
                }
                // Do not store unconfirmed seek previews in Continue Watching.
                // Native time is only useful once the playback watchdog has cleared.
                if (ready && !scrubbing && !paused && player.isPlaying &&
                    seekWatchdog.isIdle && queuedSeekTarget == null && !pendingSeekPreview &&
                    nativeTime >= lastConfirmedPositionMs && durationMs > 0L) {
                    lastConfirmedPositionMs = nativeTime
                    if (nativeTime - lastSaved >= 10_000L) {
                        lastSaved = nativeTime
                        onProgress(nativeTime, durationMs)
                    }
                }
            }.onFailure {
                if (!released.get()) android.util.Log.w("VlcFallbackScreen", "VLC polling issue", it)
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
            else -> exitOnce()
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { event ->
                val direction = when (event.key) {
                    Key.DirectionLeft -> SeekDirection.BACKWARD
                    Key.DirectionRight -> SeekDirection.FORWARD
                    else -> null
                }
                // Capture the matching release before focus is re-targeted by the controls.
                // One hold => one native seek, even when it started with controls hidden.
                if (event.type == KeyEventType.KeyUp && direction != null &&
                    seekHeldDirection == direction) {
                    if (scrubbing) seekTo(previewMs)
                    scrubbing = false
                    seekHeldDirection = null
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (controlsVisible) {
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
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (event.nativeKeyEvent.repeatCount == 0 && direction != null &&
                            ready && durationMs > 0L &&
                            runCatching { player.isSeekable }.getOrDefault(false)) {
                            seekOriginMs = if (pendingSeekPreview) previewMs else positionMs
                            seekHoldStartedAtMs = android.os.SystemClock.uptimeMillis()
                            seekHeldDirection = direction
                            previewMs = TvSeekPolicy.tap(seekOriginMs, durationMs, direction)
                            scrubbing = true
                        }
                        showControls()
                        true
                    }
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
                    try {
                        // Native = use LibVLC's built-in automatic decoder handling.
                        // Tuned = restore the older explicit decoder choice for A/B testing.
                        if (VlcPlaybackPolicy.overrideHardwareDecoder(vlcMode)) {
                            media.setHWDecoderEnabled(true, false)
                        }
                        VlcPlaybackPolicy.mediaArguments(
                            vlcMode, userAgent, Source.DEFAULT_USER_AGENT,
                        ).forEach(media::addOption)
                        // The network cache option is set only on the tuned LibVLC engine,
                        // avoiding duplicate per-media cache options overriding HW defaults.
                        player.media = media
                    } finally {
                        media.release()
                    }
                    player.play()
                }
            },
            onRelease = {
                // Release the player after Compose removes the AndroidView, not earlier.
                // Particularly important if Back is pressed during initial stream startup.
                if (released.compareAndSet(false, true)) {
                    seekWatchdog.reset()
                    runCatching { player.setEventListener(null) }
                    runCatching { player.stop() }
                    runCatching { player.detachViews() }
                    runCatching { player.release() }
                    runCatching { libvlc.release() }
                }
            },
        )
        if (!ready && !failed && !ended) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
        if (!failed && !ended && (
            healthSnapshot.state == VlcPlaybackHealth.State.BUFFERING ||
            healthSnapshot.state == VlcPlaybackHealth.State.STALLED
        )) {
            Text(
                text = when (healthSnapshot.state) {
                    VlcPlaybackHealth.State.BUFFERING ->
                        "Buffering ${healthSnapshot.bufferPercent}% · interruptions: ${healthSnapshot.interruptions}"
                    else -> "Playback stalled without VLC buffering · Try Media3 or Retry VLC"
                },
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                color = Color.White,
            )
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
                TvVlcButton("Back to title", onClick = ::exitOnce)
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
                    Text(clock(if (scrubbing || pendingSeekPreview) previewMs else positionMs), color = Color.White)
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
                                (if (scrubbing || pendingSeekPreview) previewMs else positionMs)
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
                    TvVlcButton("−10s", enabled = ready && player.isSeekable) {
                        seekTo((if (pendingSeekPreview) previewMs else positionMs) - 10_000L)
                    }
                    TvVlcButton(if (paused) "Play" else "Pause", modifier = Modifier.focusRequester(pauseFocus)) {
                        if (paused) player.play() else player.pause()
                        paused = !paused
                        showControls()
                    }
                    TvVlcButton("+10s", enabled = ready && player.isSeekable) {
                        seekTo((if (pendingSeekPreview) previewMs else positionMs) + 10_000L)
                    }
                    TvVlcButton("Back", onClick = ::exitOnce)
                }
                // Keep everyday controls on the primary row; the advanced fallbacks
                // occupy a separate row so they never clip on lower-resolution TVs.
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvVlcButton("Retry VLC") {
                        if (!released.get()) {
                            seekWatchdog.reset()
                            queuedSeekTarget = null
                            recoveryMessage = null
                            ready = false
                            player.stop()
                            player.play()
                        }
                    }
                    TvVlcButton("Try Media3", onClick = onTryMedia3)
                    TvVlcButton("Open in VLC app", onClick = ::openExternalVlc)
                }
                if (recoveryMessage != null) Text(recoveryMessage.orEmpty(), color = Color.White)
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
