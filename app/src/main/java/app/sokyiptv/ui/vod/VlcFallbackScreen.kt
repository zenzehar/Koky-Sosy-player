/*
 * Soky IPTV Pro: TV-first native VLC renderer and transport controls.
 * Derived from GPL-3.0-or-later SokyIPTV; no VLC views may be attached twice.
 */
package app.sokyiptv.ui.vod

import android.net.Uri
import android.content.Intent
import android.content.Context
import android.os.PowerManager
import app.sokyiptv.isRunningOnTelevision
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import app.sokyiptv.core.AppSettings
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
import app.sokyiptv.player.SeekDirection
import app.sokyiptv.player.TvSeekPolicy
import app.sokyiptv.player.TvHardwarePlaybackKeys
import app.sokyiptv.player.VlcSeekClock
import app.sokyiptv.player.VlcNativeSeekCorrection
import app.sokyiptv.player.VlcPlaybackHealth
import app.sokyiptv.diagnostics.PlaybackDiagnostics
import app.sokyiptv.ui.diagnostics.PlaybackDiagnosticsDialog
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
    showEpisodeControls: Boolean = false,
    previousEpisodeAvailable: Boolean = false,
    nextEpisodeAvailable: Boolean = false,
    onPreviousEpisode: () -> Unit = {},
    onNextEpisodeClick: () -> Unit = {},
    // Keep the newer screensaver lifecycle hook even though the VLC engine is v0.1.9.
    suspendPlayback: Boolean = false,
) {
    val context = LocalContext.current
    val preferences = remember { AppSettings.get(context) }
    val autoplay by preferences.seriesAutoplay.collectAsState()
    val scope = rememberCoroutineScope()
    val libvlc = remember(streamUrl) {
        LibVLC(context.applicationContext, arrayListOf("--network-caching=1400", "--no-video-title-show"))
    }
    val player = remember(streamUrl) { MediaPlayer(libvlc) }
    // Passive alpha diagnostics only: do not affect stable v0.1.9 playback state machine.
    val diagHealth = remember(streamUrl) { VlcPlaybackHealth() }
    var healthSnapshot by remember(streamUrl) {
        mutableStateOf(VlcPlaybackHealth.Snapshot(VlcPlaybackHealth.State.STARTING, 0, 0))
    }
    var resumeNeedsVideoRebind by remember(streamUrl) { mutableStateOf(false) }
    var videoLayout by remember(streamUrl) { mutableStateOf<VLCVideoLayout?>(null) }
    var diagnosticText by remember(streamUrl) { mutableStateOf<String?>(null) }
    var diagnosticsState by remember(streamUrl) { mutableStateOf(false) }
    var ready by remember(streamUrl) { mutableStateOf(false) }
    var failed by remember(streamUrl) { mutableStateOf(false) }
    var ended by remember(streamUrl) { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    // LibVLC may report the pre-seek clock for several polling cycles. Keep
    // repeated +/-10 presses anchored to the latest requested target until
    // the native clock catches up or a short bounded timeout expires.
    val seekClock = remember(player) { VlcSeekClock() }
    val nativeSeekCorrection = remember(player) { VlcNativeSeekCorrection() }
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
    val autoplayFocus = remember { FocusRequester() }
    var autoplayFocused by remember { mutableStateOf(false) }
    var focusLevel by remember { mutableStateOf(CinematicFocusLevel.CONTROLS) }
    // Only Left/Right while transport controls are hidden selects seekbar.
    // When visible, native Compose focus navigation uses the visible button order.
    var focusSeekOnReveal by remember { mutableStateOf(false) }
    val seekFocus = remember { FocusRequester() }

    fun showControls() { controlsVisible = true; interaction++ }
    fun togglePlayback() {
        if (paused) player.play() else player.pause()
        paused = !paused
        showControls()
    }

    fun setHardwarePlayback(shouldPlay: Boolean) {
        // Dedicated Play/Pause keys are idempotent; a repeat never toggles twice.
        if (shouldPlay && paused) {
            player.play()
            paused = false
        } else if (!shouldPlay && !paused) {
            player.pause()
            paused = true
        }
        showControls()
    }
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
        // Native absolute milliseconds, never a percentage of an unreliable runtime.
        val capped = target.coerceAtLeast(0L)
        // LibVLC fast seek may resume at a neighbouring keyframe, causing
        // +10 to land earlier and -10 to land later than requested. Always
        // request precise decoded-time seeking for transport and timeline taps.
        // Retain the bounded logical-clock guard for repeated fast presses.
        val now = android.os.SystemClock.elapsedRealtime()
        nativeSeekCorrection.requested(capped, player.time, now)
        player.setTime(capped, false)
        PlaybackDiagnostics.event(context, "VLC_SEEK", (capped / 1000).toInt())
        positionMs = seekClock.requested(capped, now)
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
            nativeSeekCorrection.clear()
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
        var lastSavedAtMs = 0L
        var previousHealth = VlcPlaybackHealth.State.STARTING
        while (isActive) {
            runCatching {
                val length = player.length
                if (length > 0L) durationMs = length
                val tick = android.os.SystemClock.elapsedRealtime()
                val nativeTime = player.time
                if (!scrubbing) positionMs = seekClock.reconcile(nativeTime, tick)
                val health = diagHealth.sample(tick, nativeTime.coerceAtLeast(0L), player.isPlaying, paused)
                if (!scrubbing) {
                    when (val correction = nativeSeekCorrection.observe(
                        nativeTime, tick, ready && player.isPlaying && !paused,
                        diagHealth.networkBuffering,
                    )) {
                        is VlcNativeSeekCorrection.Decision.RetryAt -> {
                            // One bounded native-time correction. Do not setPosition(%).
                            player.setTime(correction.commandTimeMs, false)
                            positionMs = seekClock.requested(correction.intendedMs, tick)
                            PlaybackDiagnostics.event(
                                context, "VLC_SEEK_ADJUST",
                                ((correction.landedMs - correction.intendedMs) / 1_000L)
                                    .coerceIn(-999_999L, 999_999L).toInt(),
                                persistNow = true,
                            )
                        }
                        is VlcNativeSeekCorrection.Decision.Unreliable -> {
                            // Don't loop indefinitely when the server's timestamps
                            // cannot be reconciled. Native clock remains authoritative.
                            PlaybackDiagnostics.event(
                                context, "VLC_SEEK_MISS",
                                ((correction.landedMs - correction.intendedMs) / 1_000L)
                                    .coerceIn(-999_999L, 999_999L).toInt(),
                                persistNow = true,
                            )
                        }
                        null -> Unit
                    }
                }
                healthSnapshot = health
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
                if (ready && initialSeekPending && player.isSeekable) {
                    val initialTarget = initialPositionMs.coerceAtLeast(0L)
                    nativeSeekCorrection.requested(initialTarget, player.time, tick)
                    player.setTime(initialTarget, false)
                    positionMs = seekClock.requested(initialTarget, tick)
                    initialSeekPending = false
                }
                if (ready && positionMs > 5_000L && tick - lastSavedAtMs >= 10_000L) {
                    // Wall-clock cadence, not playback-position delta. Rewinding from
                    // 60m to 20m must not disable saves until the viewer reaches 60m!
                    // An unknown total duration must also never disable persistence.
                    lastSavedAtMs = tick
                    onProgress(positionMs, durationMs)
                }
            }
            delay(500)
        }
    }

    // Some television firmwares ignore KEEP_SCREEN_ON on a SurfaceView while video is
    // decoding. Use a TV-only screen wake lock as a secondary safeguard, releasing it
    // immediately when playback pauses, ends, or this composable leaves composition.
    @Suppress("DEPRECATION")
    DisposableEffect(ready, paused, ended, suspendPlayback) {
        val wakeLock = if (ready && !paused && !ended && !suspendPlayback &&
            isRunningOnTelevision(context)) {
            runCatching {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                    "SokyIPTVPro:VodPlayback").apply { acquire() }
            }.getOrNull()
        } else null
        onDispose { runCatching { if (wakeLock?.isHeld == true) wakeLock.release() } }
    }

    // Retain the modern screensaver pause hook without replacing v0.1.9 playback internals.
    // Do not auto-resume: the viewer should explicitly unpause after waking.
    LaunchedEffect(suspendPlayback) {
        if (suspendPlayback) {
            // A TV dream can destroy the LibVLC SurfaceView while keeping audio alive.
            resumeNeedsVideoRebind = true
            runCatching { player.pause() }
            paused = true
            controlsVisible = true
            PlaybackDiagnostics.event(context, "VLC_DREAM_PAUSED", persistNow = true)
        } else if (resumeNeedsVideoRebind) {
            delay(250) // give Android a chance to recreate the surface
            val layout = videoLayout
            if (layout != null) {
                runCatching {
                    player.detachViews()
                    player.attachViews(layout, null, true, false)
                }.onSuccess {
                    PlaybackDiagnostics.event(context, "VLC_VIDEO_REBOUND", persistNow = true)
                    resumeNeedsVideoRebind = false
                }.onFailure {
                    PlaybackDiagnostics.event(context, "VLC_VIDEO_REBIND_FAILED", persistNow = true)
                }
            }
            // Keep paused after waking. User explicitly presses Play to resume.
        }
    }

    // Drive long-press preview from monotonic time, regardless of remote repeat rate.
    LaunchedEffect(seekHeldDirection, seekHoldStartedAtMs, durationMs) {
        val direction = seekHeldDirection ?: return@LaunchedEffect
        while (isActive && seekHeldDirection == direction) {
            val heldMs = (android.os.SystemClock.uptimeMillis() - seekHoldStartedAtMs).coerceAtLeast(0L)
            if (heldMs >= TvSeekPolicy.HOLD_THRESHOLD_MS) {
                val accelerated = TvSeekPolicy.heldPreview(seekOriginMs, heldMs, direction, null)
                val firstTap = TvSeekPolicy.tap(seekOriginMs, null, direction)
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
            if (controlsVisible && ready) {
                if (focusSeekOnReveal) {
                    seekFocus.requestFocus()
                    focusLevel = CinematicFocusLevel.SEEK
                    focusSeekOnReveal = false
                } else {
                    pauseFocus.requestFocus()
                    focusLevel = CinematicFocusLevel.CONTROLS
                }
            } else if (controlsVisible) {
                if (focusSeekOnReveal) {
                    seekFocus.requestFocus()
                    focusLevel = CinematicFocusLevel.SEEK
                    focusSeekOnReveal = false
                } else {
                    pauseFocus.requestFocus()
                    focusLevel = CinematicFocusLevel.CONTROLS
                }
            } else {
                rootFocus.requestFocus()
            }
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
                val hardwareKeyCode = event.nativeKeyEvent.keyCode
                if (TvHardwarePlaybackKeys.isTransportKey(hardwareKeyCode)) {
                    if (event.type == KeyEventType.KeyDown) {
                        when (TvHardwarePlaybackKeys.actionForInitialPress(
                            hardwareKeyCode, event.nativeKeyEvent.repeatCount,
                        )) {
                            TvHardwarePlaybackKeys.Action.TOGGLE -> togglePlayback()
                            TvHardwarePlaybackKeys.Action.PLAY -> setHardwarePlayback(true)
                            TvHardwarePlaybackKeys.Action.PAUSE -> setHardwarePlayback(false)
                            null -> Unit
                        }
                    }
                    // Consume the matching KeyUp as well to prevent duplicate actions.
                    true
                } else if (event.type != KeyEventType.KeyDown) false
                else if (controlsVisible) {
                    // Exactly 3 deterministic ordered levels, one press per move.
                    // Never rely on stale Compose focus-change callbacks to choose
                    // the next row. Native seek left/right/hold handlers remain intact.
                    when (event.key) {
                        Key.DirectionLeft, Key.DirectionRight -> {
                            // Never steal focus from visible transport buttons.
                            // If seekbar is focused, its own handler handles seeking.
                            interaction++
                            false
                        }
                        Key.DirectionUp, Key.DirectionDown -> {
                            val next = moveCinematicFocus(
                                focusLevel, showEpisodeControls,
                                up = event.key == Key.DirectionUp,
                            )
                            if (next != focusLevel) {
                                focusLevel = next
                                when (next) {
                                    CinematicFocusLevel.AUTOPLAY -> autoplayFocus.requestFocus()
                                    CinematicFocusLevel.CONTROLS -> pauseFocus.requestFocus()
                                    CinematicFocusLevel.SEEK -> seekFocus.requestFocus()
                                }
                                showControls()
                                true
                            } else false
                        }
                        else -> {
                            if (event.key != Key.Back && event.key != Key.Escape) interaction++
                            false
                        }
                    }
                } else when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        focusSeekOnReveal = true
                        focusLevel = CinematicFocusLevel.SEEK
                        showControls()
                        if (player.isSeekable) {
                            val direction = if (event.key == Key.DirectionRight)
                                SeekDirection.FORWARD else SeekDirection.BACKWARD
                            seekTo(TvSeekPolicy.tap(positionMs, null, direction))
                        }
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        if (event.nativeKeyEvent.repeatCount == 0) togglePlayback()
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown -> { showControls(); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                VLCVideoLayout(ctx).apply {
                    videoLayout = this
                    keepScreenOn = true
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
        // Diagnose stutter without claiming a decoder-clock stall is network buffering.
        if (ready && !failed && !ended && !paused &&
            healthSnapshot.state in setOf(
                VlcPlaybackHealth.State.BUFFERING,
                VlcPlaybackHealth.State.STALLED,
            )
        ) {
            Column(
                modifier = Modifier.align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.60f), RoundedCornerShape(15.dp))
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(color = CinemaBlue)
                Text(
                    if (healthSnapshot.state == VlcPlaybackHealth.State.BUFFERING)
                        "Buffering ${healthSnapshot.bufferPercent}%"
                    else "Playback stalled (not VLC buffering)",
                    color = Color.White,
                )
            }
        }
        if (controlsVisible) {
            Box(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                        ),
                    ),
            ) {
                CinematicScrim()
                CinematicTitle(
                    rawTitle = title,
                    isEpisode = showEpisodeControls,
                    elapsedText = clock(if (scrubbing) previewMs else positionMs),
                    durationText = if (durationMs > 0L) clock(durationMs) else "--:--",
                    modifier = Modifier.align(Alignment.TopStart)
                        .padding(start = 46.dp, top = 44.dp),
                )
                if (showEpisodeControls) {
                    CinematicAutoplay(
                        enabled = autoplay,
                        onChange = { preferences.setSeriesAutoplay(it); showControls() },
                        focusRequester = autoplayFocus,
                        onFocusChanged = {
                            autoplayFocused = it
                            if (it) focusLevel = CinematicFocusLevel.AUTOPLAY
                        },
                        modifier = Modifier.align(Alignment.TopEnd)
                            .padding(end = 40.dp, top = 42.dp),
                    )
                }
                // The complete approved v0.1.11.0 seek-preview and hold logic is embedded
                // unchanged in this timeline. Only its placement and paint have changed.
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 36.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                        Text(clock(if (scrubbing) previewMs else positionMs), color = Color.White)
                        Box(
                            modifier = Modifier.weight(1f).padding(horizontal = 14.dp).height(48.dp)
                                .focusRequester(seekFocus)
                                .onFocusChanged {
                                    timelineFocused = it.isFocused
                                    if (it.isFocused) focusLevel = CinematicFocusLevel.SEEK
                                }
                                .onPreviewKeyEvent { event ->
                                    val direction = when (event.key) {
                                        Key.DirectionLeft -> SeekDirection.BACKWARD
                                        Key.DirectionRight -> SeekDirection.FORWARD
                                        else -> null
                                    }
                                    if (direction != null) {
                                        if (event.type == KeyEventType.KeyDown) {
                                            if (player.isSeekable) {
                                                if (seekHeldDirection != direction) {
                                                    seekOriginMs = positionMs
                                                    seekHoldStartedAtMs = android.os.SystemClock.uptimeMillis()
                                                    seekHeldDirection = direction
                                                    previewMs = TvSeekPolicy.tap(seekOriginMs, null, direction)
                                                } else {
                                                    val heldMs = (
                                                        android.os.SystemClock.uptimeMillis() - seekHoldStartedAtMs
                                                    ).coerceAtLeast(0L)
                                                    val accelerated = TvSeekPolicy.heldPreview(
                                                        seekOriginMs, heldMs, direction, null,
                                                    )
                                                    val firstTap = TvSeekPolicy.tap(seekOriginMs, null, direction)
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
                                            } else if (event.nativeKeyEvent.repeatCount == 0) {
                                                togglePlayback()
                                            }
                                            true
                                        }
                                        else -> false
                                    }
                                }
                                .border(
                                    width = if (timelineFocused) 1.dp else 0.dp,
                                    color = CinemaBlue,
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
                                    color = CinemaTrack,
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
                                    color = CinemaBlue,
                                    start = androidx.compose.ui.geometry.Offset(startX, centerY),
                                    end = androidx.compose.ui.geometry.Offset(playheadX, centerY),
                                    strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round,
                                )
                                drawCircle(
                                    color = Color.White,
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
                CinematicTransport(
                    isEpisode = showEpisodeControls,
                    paused = paused,
                    previousEnabled = previousEpisodeAvailable,
                    nextEnabled = nextEpisodeAvailable,
                    onPrevious = onPreviousEpisode,
                    onRewind = { seekTo(TvSeekPolicy.tap(positionMs, null, SeekDirection.BACKWARD)) },
                    onTogglePause = ::togglePlayback,
                    onForward = { seekTo(TvSeekPolicy.tap(positionMs, null, SeekDirection.FORWARD)) },
                    onNext = onNextEpisodeClick,
                    pauseRequester = pauseFocus,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = 74.dp)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown &&
                                event.key == Key.DirectionUp) {
                                if (showEpisodeControls) autoplayFocus.requestFocus()
                                showControls()
                                showEpisodeControls
                            } else if (event.type == KeyEventType.KeyDown &&
                                event.key == Key.DirectionDown) {
                                seekFocus.requestFocus()
                                showControls()
                                true
                            } else false
                        },
                )
                if (ended) {
                    Text(
                        "Playback finished",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
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
