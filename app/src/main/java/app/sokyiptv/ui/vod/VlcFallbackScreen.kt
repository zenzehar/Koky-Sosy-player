/*
 * Soky IPTV Pro: TV-first native VLC renderer and transport controls.
 * Derived from GPL-3.0-or-later SokyIPTV; no VLC views may be attached twice.
 */
package app.sokyiptv.ui.vod

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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import app.sokyiptv.player.AdaptiveVodTimeline
import app.sokyiptv.player.VlcPlaybackHealth
import app.sokyiptv.player.VlcNativeSeekCorrection
import app.sokyiptv.player.ManualSeriesSkips
import app.sokyiptv.diagnostics.PlaybackDiagnostics
import app.sokyiptv.ui.diagnostics.PlaybackDiagnosticsDialog
import kotlin.math.max
import kotlin.math.min
import java.util.concurrent.atomic.AtomicBoolean

/** Native VLC is the first VOD choice. Media3 remains an explicitly bounded alternative. */
@Composable
fun VlcFallbackScreen(
    streamUrl: String,
    mediaKey: String,
    manualSkips: ManualSeriesSkips,
    markersAvailable: Boolean,
    onSkipsChanged: (ManualSeriesSkips) -> Unit,
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember { AppSettings.get(context) }
    val autoplay by preferences.seriesAutoplay.collectAsState()
    val style by preferences.playerUiStyle.collectAsState()
    val netflix = style == AppSettings.PlayerUiStyle.NETFLIX_LIKE
    var markerPanelVisible by remember(mediaKey) { mutableStateOf(false) }
    var creditsDismissed by remember(mediaKey) { mutableStateOf(false) }
    var trackPanelVisible by remember(streamUrl) { mutableStateOf(false) }
    var bufferEventPercent by remember(streamUrl) { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()
    // Restore exact v0.1.9 LibVLC initialization: fixed 1400ms native cache.
    val libvlc = remember(streamUrl) {
        LibVLC(context.applicationContext, arrayListOf("--network-caching=1400", "--no-video-title-show"))
    }
    val player = remember(streamUrl) { MediaPlayer(libvlc) }
    // Compose disposes the native view and parent effects separately. Never release
    // the same native surface from both callbacks or query a released VLC instance.
    val leaving = remember(streamUrl) { AtomicBoolean(false) }
    val nativeReleased = remember(streamUrl) { AtomicBoolean(false) }
    var startupErrorEpoch by remember(streamUrl) { mutableIntStateOf(0) }
    // Passive alpha diagnostics only: do not affect stable v0.1.9 playback state machine.
    val diagHealth = remember(streamUrl) { VlcPlaybackHealth() }
    // Re-enable v39 selected-target, one-retry seek correction. The v47 renderer did not call it.
    val seekCorrection = remember(streamUrl) { VlcNativeSeekCorrection() }
    var videoLayout by remember(streamUrl) { mutableStateOf<VLCVideoLayout?>(null) }
    val seekFrames = remember(mediaKey) { NetflixSeekFrameCache() }
    var thumbnailPostSeekTargetMs by remember(mediaKey) { mutableLongStateOf(-1L) }
    DisposableEffect(seekFrames) { onDispose { seekFrames.clear() } }
    var surfaceRebindPending by remember(streamUrl) { mutableStateOf(false) }
    var nativePlaybackActive by remember(streamUrl) { mutableStateOf(false) }
    var healthSnapshot by remember(streamUrl) {
        mutableStateOf(VlcPlaybackHealth.Snapshot(VlcPlaybackHealth.State.STARTING, 0, 0))
    }
    var diagnosticText by remember(streamUrl) { mutableStateOf<String?>(null) }
    var diagnosticsState by remember(streamUrl) { mutableStateOf(false) }
    var ready by remember(streamUrl) { mutableStateOf(false) }
    var failed by remember(streamUrl) { mutableStateOf(false) }
    var ended by remember(streamUrl) { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var resumeRequestedAtMs by remember(streamUrl) { mutableLongStateOf(0L) }
    var resumeRetryCount by remember(streamUrl) { mutableIntStateOf(0) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var previewMs by remember { mutableLongStateOf(0L) }
    var seekOriginMs by remember { mutableLongStateOf(0L) }
    var seekHoldStartedAtMs by remember { mutableLongStateOf(0L) }
    var seekHeldDirection by remember { mutableStateOf<SeekDirection?>(null) }
    var timelineFocused by remember { mutableStateOf(false) }
    // Fixed stream-declared seekbar duration: tail probes never expand its endpoint.
    val timelineScale = AdaptiveVodTimeline.scale(durationMs, positionMs)
    var initialSeekPending by remember { mutableStateOf(initialPositionMs > 0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var netflixSeeking by remember(mediaKey) { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    val rootFocus = remember { FocusRequester() }
    var rootOwnsFocus by remember { mutableStateOf(false) }
    val pauseFocus = remember { FocusRequester() }
    val trackFocus = remember { FocusRequester() }
    val autoplayFocus = remember { FocusRequester() }
    var autoplayFocused by remember { mutableStateOf(false) }
    var focusLevel by remember { mutableStateOf(CinematicFocusLevel.CONTROLS) }
    // Only Left/Right while transport controls are hidden selects seekbar.
    // When visible, native Compose focus navigation uses the visible button order.
    var focusSeekOnReveal by remember { mutableStateOf(false) }
    val seekFocus = remember { FocusRequester() }
    val onMarkerLongPress = rememberManualMarkerLongPress(
        enabled = paused && showEpisodeControls && markersAvailable && !trackPanelVisible &&
            !markerPanelVisible && !leaving.get(),
        onLongPress = { markerPanelVisible = true; controlsVisible = true; interaction++ },
    )


    fun showControls() { controlsVisible = true; interaction++ }
    fun leaveVideo() {
        if (leaving.compareAndSet(false, true)) onBack()
    }
    fun setPlayback(shouldPlay: Boolean) {
        if (leaving.get() || nativeReleased.get()) return
        val wasPaused = paused
        // Never treat the UI state as proof of native playback: some TV devices
        // reject/reschedule a resume while the UI already says "playing".
        paused = !shouldPlay
        if (shouldPlay) {
            if (wasPaused || !runCatching { player.isPlaying }.getOrDefault(false)) {
                resumeRequestedAtMs = android.os.SystemClock.elapsedRealtime()
                resumeRetryCount = 0
                runCatching { player.play() }
            }
        } else {
            resumeRequestedAtMs = 0L
            resumeRetryCount = 0
            if (!wasPaused || runCatching { player.isPlaying }.getOrDefault(false)) {
                runCatching { player.pause() }
            }
        }
        showControls()
    }
    fun togglePlayback() = setPlayback(paused)
    fun setHardwarePlayback(shouldPlay: Boolean) = setPlayback(shouldPlay)
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
        if (leaving.get() || nativeReleased.get() || !player.isSeekable) return
        // v0.1.9 direct native seek: only one call; reported duration is the limit.
        val capped = target.coerceIn(0L, durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)
        seekCorrection.requested(capped, player.time.coerceAtLeast(0L),
            android.os.SystemClock.elapsedRealtime(), durationMs)
        player.setTime(capped)
        PlaybackDiagnostics.event(context, "VLC_SEEK", (capped / 1000).toInt())
        positionMs = capped
        thumbnailPostSeekTargetMs = capped
        showControls()
    }

    DisposableEffect(player) {
        PlaybackDiagnostics.begin("VLC", "RESTORED_V019")
        PlaybackDiagnostics.event(context, "VLC_START", persistNow = true)
        player.setEventListener { event ->
            if (leaving.get() || nativeReleased.get()) return@setEventListener
            when (event.type) {
                MediaPlayer.Event.Playing -> scope.launch {
                    if (leaving.get() || nativeReleased.get()) return@launch
                    startupErrorEpoch++
                    diagHealth.onPlaying(android.os.SystemClock.elapsedRealtime())
                    PlaybackDiagnostics.event(context, "VLC_READY", persistNow = true)
                    ready = true; failed = false
                    // Do not overwrite the last requested pause/play state here.
                }
                MediaPlayer.Event.Buffering -> {
                    val percentage = event.getBuffering()
                    scope.launch {
                        bufferEventPercent = percentage.toInt().coerceIn(0, 100)
                        if (leaving.get() || nativeReleased.get()) return@launch
                        diagHealth.onBuffering(
                            percentage, android.os.SystemClock.elapsedRealtime(),
                        )
                    }
                }
                MediaPlayer.Event.EncounteredError -> scope.launch {
                    if (leaving.get() || nativeReleased.get()) return@launch
                    // Provider startup can temporarily fail and recover. Don't flash
                    // an alarming error while it is still opening/buffering.
                    PlaybackDiagnostics.event(context, "VLC_ERROR", persistNow = true)
                    val epoch = ++startupErrorEpoch
                    delay(7_000L)
                    if (epoch == startupErrorEpoch && !leaving.get() && !nativeReleased.get()) {
                        failed = true
                        ready = false
                        showControls()
                    }
                }
                MediaPlayer.Event.EndReached -> scope.launch {
                    if (leaving.get() || nativeReleased.get()) return@launch
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
            leaving.set(true)
            // Stop newly queued native events before the Compose view is destroyed.
            runCatching { player.setEventListener(null) }
            PlaybackDiagnostics.event(context, "VLC_EXIT", persistNow = true)
            onProgress(positionMs, durationMs)
            // Native cleanup belongs ONLY to AndroidView.onRelease below, when its
            // VLCVideoLayout is removed. Releasing here races with view detachment.
        }
    }

    // A stopped Android Activity may destroy its video surface while LibVLC keeps its
    // audio session alive. Rebind the *same* view on return without reopening the URL,
    // restarting the decoder/media, or altering the saved watch position.
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> surfaceRebindPending = true
                Lifecycle.Event.ON_RESUME -> {
                    if (!leaving.get() && !nativeReleased.get() && surfaceRebindPending) {
                        videoLayout?.let { layout ->
                            runCatching {
                                player.detachViews()
                                player.attachViews(layout, null, true, false)
                                player.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
                                layout.requestLayout()
                                layout.invalidate()
                            }.onSuccess {
                                surfaceRebindPending = false
                                showControls()
                            }
                        }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Retain v47 native position polling, adding only selected-target seek verification.
    LaunchedEffect(player) {
        var lastSaved = 0L
        var previousHealth = VlcPlaybackHealth.State.STARTING
        var previousNativeClockMs = -1L
        var lastObservedClockAdvanceAtMs = Long.MIN_VALUE
        while (isActive && !leaving.get() && !nativeReleased.get()) {
            runCatching {
                if (leaving.get() || nativeReleased.get()) return@runCatching
                val length = player.length
                if (length > 0L) durationMs = length
                val nativeClockMs = player.time.coerceAtLeast(0L)
                if (!scrubbing) positionMs = nativeClockMs
                val tick = android.os.SystemClock.elapsedRealtime()
                // isPlaying alone can stay true even while no frames/clock are advancing.
                // A new stable ~500 ms clock sample confirms forward progress; a seek jump
                // or rewind itself never starts the five-second chrome hide timer.
                if (previousNativeClockMs >= 0L &&
                    nativeClockMs - previousNativeClockMs in 100L..1_600L) {
                    lastObservedClockAdvanceAtMs = tick
                }
                previousNativeClockMs = nativeClockMs
                val clockAdvancing = lastObservedClockAdvanceAtMs != Long.MIN_VALUE &&
                    tick - lastObservedClockAdvanceAtMs <= 1_300L
                val nativePlaying = player.isPlaying
                val health = diagHealth.sample(tick, nativeClockMs, nativePlaying, paused)
                healthSnapshot = health
                if (paused || nativePlaying && clockAdvancing) {
                    resumeRequestedAtMs = 0L
                } else if (resumeRequestedAtMs > 0L &&
                    resumeRetryCount < 1 &&
                    tick - resumeRequestedAtMs >= 2_500L &&
                    ready && !failed && !ended && !suspendPlayback &&
                    !diagHealth.networkBuffering && !initialSeekPending &&
                    (!nativePlaying || health.state == VlcPlaybackHealth.State.STALLED)) {
                    // ONE bounded reassertion of the requested Play state;
                    // never restart the media, reopen the IPTV URL or spin retries.
                    resumeRetryCount++
                    resumeRequestedAtMs = tick
                    runCatching { player.play() }
                    PlaybackDiagnostics.event(context, "VLC_RESUME_REASSERT")
                }
                nativePlaybackActive = ready && nativePlaying && clockAdvancing &&
                    !paused && !suspendPlayback && !diagHealth.networkBuffering &&
                    health.state == VlcPlaybackHealth.State.PLAYING
                // Buffering can HOLD an already visible bar but must NEVER pop up
                // controls that the user previously allowed to auto-hide.
                // nativePlaybackActive=false cancels the existing auto-hide timer.
                if (ready && !failed && !ended && player.isSeekable) {
                    when (val decision = seekCorrection.observe(
                        nativeMs = nativeClockMs, nowMs = tick,
                        playing = nativePlaybackActive,
                        buffering = !nativePlaybackActive,
                    )) {
                        is VlcNativeSeekCorrection.Decision.RetryAt -> {
                            player.setTime(decision.commandTimeMs)
                            PlaybackDiagnostics.event(context, "VLC_SEEK_CORRECT",
                                (decision.commandTimeMs / 1000).toInt())
                        }
                        else -> Unit
                    }
                }
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
                    val initialTarget = initialPositionMs.coerceIn(0L, durationMs)
                    seekCorrection.requested(initialTarget, player.time.coerceAtLeast(0L),
                        tick, durationMs)
                    player.setTime(initialTarget)
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

    // Passive Netflix-style trickplay stills from this same VLC video surface.
    // Never seek or allocate another decoder to produce a thumbnail.
    LaunchedEffect(videoLayout, ready, netflix) {
        val layout = videoLayout ?: return@LaunchedEffect
        if (!netflix || !ready) return@LaunchedEffect
        var failures = 0
        while (isActive && !leaving.get() && !nativeReleased.get()) {
            delay(1_650L)
            if (paused || !ready || failed || ended || !player.isPlaying) continue
            val timestamp = player.time.coerceAtLeast(0L)
            val still = NetflixSeekFrameCapture.sample(layout)
            if (still == null) {
                // Surface creation/resize or PixelCopy may fail transiently;
                // keep trying rather than permanently disabling seek previews.
                failures++
                continue
            }
            if (kotlin.math.abs(player.time - timestamp) <= 1_400L) {
                seekFrames.store(timestamp, still)
                failures = 0
            } else still.recycle()
        }
    }

    // A completed seek deserves one timely REAL scene capture. The periodic
    // sampler alone could miss the destination by several seconds.
    LaunchedEffect(thumbnailPostSeekTargetMs, videoLayout, netflix, ready) {
        val target = thumbnailPostSeekTargetMs
        val layout = videoLayout ?: return@LaunchedEffect
        if (target < 0L || !netflix || !ready || failed || ended) return@LaunchedEffect
        delay(620L) // let VLC display the sought decoder frame
        if (leaving.get() || nativeReleased.get() || !player.isPlaying) return@LaunchedEffect
        val atMs = player.time.coerceAtLeast(0L)
        if (kotlin.math.abs(atMs - target) > 2_500L) return@LaunchedEffect
        val snapshot = NetflixSeekFrameCapture.sample(layout) ?: return@LaunchedEffect
        if (kotlin.math.abs(player.time - atMs) <= 1_800L) {
            seekFrames.store(atMs, snapshot)
        } else snapshot.recycle()
    }

    // Preserve modern screensaver pause API, without newer decoder rebind or seek recovery.
    LaunchedEffect(suspendPlayback) {
        if (suspendPlayback) {
            runCatching { player.pause() }
            paused = true
            controlsVisible = true
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

    LaunchedEffect(controlsVisible, interaction, nativePlaybackActive, paused, scrubbing, netflixSeeking, failed, ended) {
        if (controlsVisible && nativePlaybackActive && !paused && !scrubbing && !netflixSeeking && !failed && !ended &&
            !trackPanelVisible && !markerPanelVisible) {
            delay(5_000)
            controlsVisible = false
        }
    }
    val skipIntroPromptVisible = !ended && ready && !paused && !failed &&
        showEpisodeControls && manualSkips.showIntro(positionMs)
    val skipCreditsPromptVisible = !ended && ready && !paused && !failed &&
        showEpisodeControls && !creditsDismissed && manualSkips.showCredits(positionMs)
    val playButtonBuffering = !paused && !ended && !failed &&
        (!ready || healthSnapshot.state == VlcPlaybackHealth.State.BUFFERING ||
            healthSnapshot.state == VlcPlaybackHealth.State.STALLED ||
            (ready && !nativePlaybackActive && !scrubbing && !netflixSeeking))
    // For the original theme too: show native VLC input-buffer progress
    // during a genuine buffering event; 100% but no advancing video means
    // stalled decoding, so use an indeterminate ring rather than a false 100%.
    val playButtonBufferPercent = bufferEventPercent.takeIf {
        // Use LibVLC's actual latest native input-buffer event, not the
        // health model's 0% default before an event has ever been received.
        it in 0..99 && (!ready ||
            healthSnapshot.state == VlcPlaybackHealth.State.BUFFERING ||
            healthSnapshot.state == VlcPlaybackHealth.State.STALLED ||
            !nativePlaybackActive)
    }
    val skipPromptVisible = skipIntroPromptVisible || skipCreditsPromptVisible
    val skipPromptFocus = remember { FocusRequester() }
    fun activateSkipPrompt() {
        if (skipIntroPromptVisible) seekTo(manualSkips.introEndMs)
        else if (skipCreditsPromptVisible) {
            if (nextEpisodeAvailable) onNextEpisodeClick() else leaveVideo()
        }
    }
    // Focus only when overlay visibility changes, never after a D-pad move.
    // Wait until the real focus targets have attached before requesting them.
    LaunchedEffect(controlsVisible, netflix, skipPromptVisible) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching {
            if (skipPromptVisible) {
                skipPromptFocus.requestFocus()
            } else if (controlsVisible) {
                if (focusSeekOnReveal) {
                    seekFocus.requestFocus()
                    focusLevel = CinematicFocusLevel.SEEK
                    focusSeekOnReveal = false
                } else {
                    pauseFocus.requestFocus()
                    focusLevel = CinematicFocusLevel.CONTROLS
                }
            } else rootFocus.requestFocus()
        }
    }

    BackHandler {
        when {
            leaving.get() -> Unit
            markerPanelVisible -> markerPanelVisible = false
            trackPanelVisible -> {
                trackPanelVisible = false
                showControls()
            }
            scrubbing -> {
                // Back cancels a preview, it does not exit the playing episode.
                scrubbing = false
                seekHeldDirection = null
                previewMs = positionMs
                showControls()
            }
            controlsVisible && ready && !failed -> controlsVisible = false
            else -> leaveVideo()
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFocus)
            .onFocusChanged { rootOwnsFocus = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (onMarkerLongPress(event)) return@onPreviewKeyEvent true
                if (leaving.get()) return@onPreviewKeyEvent true
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
                else if (skipPromptVisible && rootOwnsFocus &&
                    event.key in setOf(Key.DirectionCenter, Key.Enter)) {
                    if (event.nativeKeyEvent.repeatCount == 0) activateSkipPrompt()
                    true
                } else if (skipPromptVisible && rootOwnsFocus &&
                    event.key in setOf(Key.DirectionLeft, Key.DirectionRight,
                        Key.DirectionUp, Key.DirectionDown)) {
                    runCatching { skipPromptFocus.requestFocus() }
                    true
                } else if (skipPromptVisible) {
                    // The overlaid Skip Intro/Next Episode action owns D-pad focus.
                    // Do not intercept Up/Down to move the invisible transport row.
                    false
                } else if (controlsVisible && netflix) {
                    // Explicit recovery if the full-screen player root retained focus
                    // after the Netflix controls were composed. Without this, Left
                    // accidentally becomes the only key that escapes a focus trap.
                    if (event.key != Key.Back && event.key != Key.Escape) interaction++
                    if (rootOwnsFocus && event.key in setOf(
                            Key.DirectionLeft, Key.DirectionRight,
                            Key.DirectionUp, Key.DirectionDown,
                        )) {
                        val target = if (event.key == Key.DirectionLeft ||
                            event.key == Key.DirectionRight) seekFocus else pauseFocus
                        runCatching { target.requestFocus() }
                        true
                    } else false
                } else if (controlsVisible) {
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
                            seekTo(TvSeekPolicy.tap(positionMs, durationMs, direction))
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
            onRelease = {
                // Called once when the actual native video view leaves Compose.
                // Stop/flush/detach/release on the UI thread; no stale callbacks
                // or 500 ms health polling may touch the native player afterward.
                leaving.set(true)
                if (nativeReleased.compareAndSet(false, true)) {
                    runCatching { player.setEventListener(null) }
                    runCatching { player.stop() }
                    runCatching { player.detachViews() }
                    runCatching { player.release() }
                    runCatching { libvlc.release() }
                    videoLayout = null
                }
            },
        )
        if (failed) {
            Column(
                Modifier.align(Alignment.Center).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Unable to play this stream.", color = Color.White, textAlign = TextAlign.Center)
                TvVlcButton("Retry playback") { failed = false; ended = false; player.play() }
                TvVlcButton("Try Media3 instead", onClick = onTryMedia3)
                TvVlcButton("Open in VLC app", onClick = ::openExternalVlc)
                TvVlcButton("Diagnostics QR", onClick = ::showReport)
                TvVlcButton("Back to title", onClick = onBack)
            }
        }
        // Display the selected overlay without changing the native playback lifecycle.
        if (controlsVisible) {
            if (netflix) {
                NetflixLikePlayerChrome(
                    title = title, isEpisode = showEpisodeControls,
                    positionMs = positionMs, durationMs = durationMs, paused = paused,
                    buffering = playButtonBuffering,
                    bufferingPercent = playButtonBufferPercent,
                    nextAvailable = nextEpisodeAvailable,
                    manualSkips = manualSkips,
                    markersEnabled = paused && showEpisodeControls && markersAvailable,
                    onMarkers = { markerPanelVisible = true; showControls() },
                    playFocus = pauseFocus,
                    timelineFocus = seekFocus, tracksFocus = trackFocus,
                    frameAt = seekFrames::nearestFrame,
                    onSeekingChanged = { netflixSeeking = it; interaction++ },
                    onActivity = { interaction++ },
                    onBack = ::leaveVideo, onToggle = ::togglePlayback,
                    onSeek = ::seekTo, onEpisodes = ::leaveVideo,
                    onTracks = { trackPanelVisible = true; showControls() },
                    onNext = onNextEpisodeClick,
                )
            } else {
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
                    durationText = if (timelineScale.approximate) "~" + clock(timelineScale.maximumMs)
                        else if (durationMs > 0L) clock(durationMs) else "--:--",
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
                                                    previewMs = TvSeekPolicy.tap(seekOriginMs, durationMs, direction)
                                                } else {
                                                    val heldMs = (
                                                        android.os.SystemClock.uptimeMillis() - seekHoldStartedAtMs
                                                    ).coerceAtLeast(0L)
                                                    val accelerated = TvSeekPolicy.heldPreview(
                                                        seekOriginMs, heldMs, direction, null,
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
                                // The bar stays filled after the declared duration; no growth.
                                val fraction = AdaptiveVodTimeline.fraction(
                                    if (scrubbing) previewMs else positionMs, timelineScale,
                                )
                                val playheadX = startX + (endX - startX) * fraction
                                drawLine(
                                    color = CinemaBlue,
                                    start = androidx.compose.ui.geometry.Offset(startX, centerY),
                                    end = androidx.compose.ui.geometry.Offset(playheadX, centerY),
                                    strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round,
                                )
                                drawManualTimelineBands(
                                    manualSkips, timelineScale.maximumMs,
                                    startX, endX, centerY, 8.dp.toPx()
                                )
                                drawCircle(
                                    color = Color.White,
                                    radius = if (timelineFocused) 8.dp.toPx() else 5.dp.toPx(),
                                    center = androidx.compose.ui.geometry.Offset(playheadX, centerY),
                                )
                            }
                        }
                        Text(
                            if (timelineScale.approximate) "~" + clock(timelineScale.maximumMs)
                            else if (durationMs > 0L) clock(durationMs) else "--:--",
                            color = Color.White,
                        )
                    }
                CinematicTransport(
                    isEpisode = showEpisodeControls,
                    paused = paused,
                    buffering = playButtonBuffering,
                    bufferingPercent = playButtonBufferPercent,
                    previousEnabled = previousEpisodeAvailable,
                    nextEnabled = nextEpisodeAvailable,
                    onPrevious = onPreviousEpisode,
                    onRewind = { seekTo(TvSeekPolicy.tap(positionMs, durationMs, SeekDirection.BACKWARD)) },
                    onTogglePause = ::togglePlayback,
                    onForward = { seekTo(TvSeekPolicy.tap(positionMs, durationMs, SeekDirection.FORWARD)) },
                    onNext = onNextEpisodeClick,
                    onMarkers = if (markersAvailable && showEpisodeControls) {
                        { markerPanelVisible = true; showControls() }
                    } else null,
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
        // When controls are hidden, the same centre Play/Pause face remains
        // visible as the loading ring; never pop the entire transport chrome.
        if (playButtonBuffering && !controlsVisible) {
            PlayerBufferPercentOverlay(
                percent = playButtonBufferPercent,
                label = "Buffering",
                netflix = netflix,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        // Only explicit saved series marks can display these prompts.
        if (skipIntroPromptVisible) {
            NetflixSkipPrompt(
                label = "Skip intro",
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = 44.dp, bottom = if (controlsVisible) 192.dp else 62.dp),
                focusRequester = skipPromptFocus,
            ) { seekTo(manualSkips.introEndMs) }
        } else if (skipCreditsPromptVisible && netflix) {
            NetflixCreditsPrompt(
                isEpisode = showEpisodeControls, nextAvailable = nextEpisodeAvailable,
                onContinue = { creditsDismissed = true },
                onSkip = {
                    if (showEpisodeControls && nextEpisodeAvailable) onNextEpisodeClick()
                    else leaveVideo()
                },
                modifier = Modifier.align(Alignment.Center),
                focusRequester = skipPromptFocus,
            )
        } else if (skipCreditsPromptVisible) {
            NetflixSkipPrompt(
                label = if (showEpisodeControls && nextEpisodeAvailable)
                    "Next episode" else "Back to title",
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = 44.dp, bottom = if (controlsVisible) 192.dp else 62.dp),
                focusRequester = skipPromptFocus,
            ) {
                if (showEpisodeControls && nextEpisodeAvailable) onNextEpisodeClick()
                else leaveVideo()
            }
        }
        if (trackPanelVisible && !leaving.get() && !nativeReleased.get()) {
            val audio = runCatching {
                player.audioTracks?.map { tr ->
                    tr.name.orEmpty().ifBlank { "Audio track" } to {
                        runCatching { player.setAudioTrack(tr.id) }
                        trackPanelVisible = false
                    }
                } ?: emptyList()
            }.getOrDefault(emptyList())
            val subs = runCatching {
                player.spuTracks?.map { tr ->
                    tr.name.orEmpty().ifBlank { "Subtitles" } to {
                        runCatching { player.setSpuTrack(tr.id) }
                        trackPanelVisible = false
                    }
                } ?: emptyList()
            }.getOrDefault(emptyList())
            NetflixTrackOptions(audio, subs) { trackPanelVisible = false }
        }
    }
    if (markerPanelVisible && markersAvailable && showEpisodeControls && !leaving.get()) {
        ManualSeriesSkipDialog(
            atMs = positionMs, marks = manualSkips,
            onMark = { mark ->
                val now = runCatching { player.time.coerceAtLeast(0L) }
                    .getOrDefault(positionMs)
                onSkipsChanged(manualSkips.mark(mark, now))
                markerPanelVisible = false
                showControls()
            },
            onClearIntro = {
                onSkipsChanged(manualSkips.clearIntro())
                markerPanelVisible = false
            },
            onClearCredits = {
                onSkipsChanged(manualSkips.clearCredits())
                markerPanelVisible = false
            },
            onClearAll = {
                onSkipsChanged(ManualSeriesSkips())
                markerPanelVisible = false
            },
            onClose = { markerPanelVisible = false },
        )
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
