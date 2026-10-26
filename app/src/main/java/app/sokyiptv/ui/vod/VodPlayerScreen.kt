/*
 * This file is part of SokyIPTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.sokyiptv.ui.vod

import app.sokyiptv.player.ManualSeriesSkips
import app.sokyiptv.player.ManualSeriesSkipStore
import app.sokyiptv.player.SkipMark
import app.sokyiptv.data.remote.EpisodeDurationDiscovery
import app.sokyiptv.data.remote.EpisodeRuntimeHints

import android.view.ViewGroup
import android.view.WindowManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import app.sokyiptv.R
import app.sokyiptv.core.AppSettings
import app.sokyiptv.core.ServiceLocator
import app.sokyiptv.core.SleepTimer
import app.sokyiptv.core.findActivity
import app.sokyiptv.player.PlayerController
import app.sokyiptv.player.SeekDirection
import app.sokyiptv.player.TvSeekPolicy
import app.sokyiptv.player.TvHardwarePlaybackKeys
import app.sokyiptv.player.WatchProgressCapture
import app.sokyiptv.player.VodProgressStore
import app.sokyiptv.player.VlcSeekClock
import app.sokyiptv.player.AdaptiveVodTimeline
import app.sokyiptv.player.PostSeekPlaybackGate
import app.sokyiptv.diagnostics.PlaybackDiagnostics
import app.sokyiptv.ui.diagnostics.PlaybackDiagnosticsDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays a movie or episode: a single non-live stream, with resume and a proper transport bar.
 *
 * The bar is drawn in Compose rather than left to the Media3 view's own controller, because that
 * controller needs the embedded view to hold d-pad focus to be summoned — which it doesn't, on a
 * TV inside Compose, so it reads as "no controls". This is the same self-drawn, auto-hiding bar as
 * the live player, with a seek bar added since a film needs scrubbing.
 */
@OptIn(UnstableApi::class)
@Composable
fun VodPlayerScreen(
    mediaKey: String,
    streamUrl: String,
    title: String,
    userAgent: String,
    onBack: () -> Unit,
    onNextEpisode: (app.sokyiptv.data.model.Episode) -> Unit = {},
) {
    val context = LocalContext.current
    // Duration previews must never contend with an actual movie/episode stream.
    // Entry cancels their single in-flight metadata HTTP request immediately.
    DisposableEffect(mediaKey) {
        EpisodeDurationDiscovery.playbackEntered()
        onDispose { EpisodeDurationDiscovery.playbackExited() }
    }
    val view = LocalView.current
    val graph = remember { ServiceLocator.get(context) }
    // v53: cloud and LAN sync are temporarily disabled. Resume data continues
    // to be captured locally in Room; no cloud gate/network work on video exit.
    val settings = remember { graph.settings }
    val autoplay by settings.seriesAutoplay.collectAsState()
    val uiStyle by settings.playerUiStyle.collectAsState()
    val netflix = uiStyle == AppSettings.PlayerUiStyle.NETFLIX_LIKE
    var seriesGroup by remember(mediaKey) { mutableStateOf<String?>(null) }
    val markerStore = remember(context) { ManualSeriesSkipStore(context) }
    var manualSkips by remember(mediaKey, seriesGroup) {
        mutableStateOf(markerStore.read(seriesGroup))
    }
    fun storeManualSkips(updated: ManualSeriesSkips) {
        if (seriesGroup.isNullOrBlank()) return
        markerStore.write(seriesGroup, updated)
        manualSkips = updated
    }
    var creditsDismissed by remember(mediaKey) { mutableStateOf(false) }
    var markerDialogVisible by remember(mediaKey) { mutableStateOf(false) }
    val advanced = remember(mediaKey) { java.util.concurrent.atomic.AtomicBoolean(false) }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    // A recording that's still being written plays through the tail-following source. It behaves a
    // little differently in the transport: the "length" is how much has been recorded so far, which
    // keeps growing, and seeking is bounded to that recorded extent.
    val growingRec = remember(streamUrl) { streamUrl.startsWith("optvrec://") }
    // HTTP VOD prefers VLC. Do not allocate an idle Media3 ExoPlayer on every VLC movie/episode.
    // On low-RAM sticks its native renderer, track selector and buffers are unnecessary overhead.
    var usingVlc by remember(streamUrl) {
        mutableStateOf(!growingRec && streamUrl.startsWith("http", true))
    }
    var vlcEverTried by remember(streamUrl) { mutableStateOf(usingVlc) }
    // The SMB and growing recording data sources are initialised only when actually needed.
    val media3Holder = remember {
        lazy(LazyThreadSafetyMode.NONE) {
            PlayerController(
                context, scope, graph.streamingHttpClient, subtitlesEnabled = false,
                smbDataSourceFactory = app.sokyiptv.player.SmbDataSource.Factory(graph.settings),
                growingDataSourceFactory =
                    app.sokyiptv.player.GrowingRecordingDataSource.Factory(context.applicationContext),
                liveRecording = growingRec,
            )
        }
    }
    val controller by media3Holder
    // Skip forward/back within the recorded portion. For a growing recording ExoPlayer won't report
    // the item as seekable (no fixed length), so we seek directly, clamped to what's on disk.
    fun seekRelative(deltaMs: Long) {
        val p = controller.player
        val ceiling = if (growingRec) p.bufferedPosition.coerceAtLeast(0L)
        else (p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0L, ceiling))
    }
    // Subscribe to Media3 only while it is selected; VLC owns its own player state and tracks.
    val state: PlayerController.State = if (usingVlc) PlayerController.State.Idle
        else controller.state.collectAsState().value
    val tracks: Tracks = if (usingVlc) Tracks.EMPTY
        else controller.tracks.collectAsState().value

    // Episode navigation belongs to the catalog, outside the already-approved seek engine.
    var previousEpisode by remember(mediaKey) {
        mutableStateOf<app.sokyiptv.data.model.Episode?>(null)
    }
    var followingEpisode by remember(mediaKey) {
        mutableStateOf<app.sokyiptv.data.model.Episode?>(null)
    }
    LaunchedEffect(mediaKey) {
        val id = mediaKey.takeIf { it.startsWith("ep:") }
            ?.removePrefix("ep:")?.toLongOrNull()
        seriesGroup = id?.let { graph.catalogRepository.episode(it) }
            ?.let { episode -> "${episode.sourceId}:${episode.seriesId}" }
        previousEpisode = id?.let { graph.catalogRepository.previousEpisode(it) }
        followingEpisode = id?.let { graph.catalogRepository.nextEpisode(it) }
    }
    var paused by remember { mutableStateOf(false) }
    var media3Diagnostic by remember { mutableStateOf<String?>(null) }
    fun showMedia3Diagnostic() {
        PlaybackDiagnostics.event(context, "REPORT", persistNow = true)
        media3Diagnostic = PlaybackDiagnostics.capture(context)
    }

    var screenSaverSuspended by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        fun pauseForSaver() {
            if (!app.sokyiptv.core.PipState.inPip.value) {
                screenSaverSuspended = true
                if (media3Holder.isInitialized()) {
                    runCatching { controller.player.pause() }
                }
                paused = true
                app.sokyiptv.core.PipState.isPlaying = false
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF, Intent.ACTION_DREAMING_STARTED -> pauseForSaver()
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_DREAMING_STOPPED -> {
                        screenSaverSuspended = false
                        // Reassert TV keep-awake after dreaming; do not auto-play.
                        context.findActivity()?.window?.addFlags(
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                        )
                        view.keepScreenOn = true
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_DREAMING_STARTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_DREAMING_STOPPED)
        }
        runCatching {
            ContextCompat.registerReceiver(
                context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> pauseForSaver()
                Lifecycle.Event.ON_RESUME -> {
                    screenSaverSuspended = false
                    context.findActivity()?.window?.addFlags(
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    )
                    view.keepScreenOn = true
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
    var resumeLoaded by remember(mediaKey) { mutableStateOf(false) }
    var resumeFromMs by remember(mediaKey) { mutableLongStateOf(0L) }
    var vodPanel by remember { mutableStateOf(VodPanel.NONE) }
    val panelFocus = remember { FocusRequester() }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    // Visual bar follows only the reported duration; never an extrapolated end.
    val timelineScale = AdaptiveVodTimeline.scale(durationMs, positionMs)

    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val barFocus = remember { FocusRequester() }
    val netflixTracksFocus = remember { FocusRequester() }
    val seekFrameCache = remember(mediaKey) { SeekFramePreviewCache() }
    var media3SeekPreviewView by remember(mediaKey) { mutableStateOf<PlayerView?>(null) }
    val rootFocus = remember { FocusRequester() }
    var rootOwnsFocus by remember { mutableStateOf(false) }
    val autoplayFocus = remember { FocusRequester() }
    val timelineFocus = remember { FocusRequester() }
    var timelineFocused by remember { mutableStateOf(false) }
    var autoplayFocused by remember { mutableStateOf(false) }
    var focusLevel by remember { mutableStateOf(CinematicFocusLevel.CONTROLS) }
    // Only hidden-to-visible horizontal input focuses the seek timeline.
    var focusSeekOnReveal by remember { mutableStateOf(false) }
    // Keep rapid absolute-time taps anchored while Media3's native clock settles.
    val media3SeekClock = remember(controller) { VlcSeekClock() }
    val postSeekGate = remember(controller) { PostSeekPlaybackGate() }
    var awaitingSeekPlayback by remember(controller) { mutableStateOf(false) }
    val onMarkerLongPress = rememberManualMarkerLongPress(
        enabled = !usingVlc && paused && !seriesGroup.isNullOrBlank() &&
            mediaKey.startsWith("ep:") &&
            !markerDialogVisible && vodPanel == VodPanel.NONE,
        onLongPress = { markerDialogVisible = true; controlsVisible = true; interaction++ },
    )

    fun reveal() { controlsVisible = true; interaction++ }

    fun setHardwarePlayback(action: TvHardwarePlaybackKeys.Action) {
        val play = when (action) {
            TvHardwarePlaybackKeys.Action.TOGGLE -> !controller.player.playWhenReady
            TvHardwarePlaybackKeys.Action.PLAY -> true
            TvHardwarePlaybackKeys.Action.PAUSE -> false
        }
        controller.player.playWhenReady = play
        paused = !play
        reveal()
    }

    fun seekAt(targetMs: Long) {
        if (!growingRec && !controller.player.isCurrentMediaItemSeekable) return
        val ceiling = if (growingRec) controller.player.bufferedPosition.coerceAtLeast(0L)
            else durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        val target = targetMs.coerceIn(0L, ceiling)
        val now = android.os.SystemClock.elapsedRealtime()
        postSeekGate.requested(now)
        awaitingSeekPlayback = true
        controller.player.seekTo(target)
        positionMs = media3SeekClock.requested(target, now)
        scrubValue = target.toFloat()
        scrubbing = false
        reveal()
    }

    fun seekTenSeconds(direction: SeekDirection) {
        if (!growingRec && !controller.player.isCurrentMediaItemSeekable) return
        val recordedExtent = if (growingRec) controller.player.bufferedPosition.coerceAtLeast(0) else null
        val target = TvSeekPolicy.tap(positionMs, recordedExtent, direction)
        // Seek in absolute milliseconds; only the visual progress bar needs a fraction.
        val now = android.os.SystemClock.elapsedRealtime()
        postSeekGate.requested(now)
        awaitingSeekPlayback = true
        controller.player.seekTo(target)
        positionMs = media3SeekClock.requested(target, now)
        scrubValue = target.toFloat()
        scrubbing = false
        interaction++
    }

    suspend fun savePosition() {
        if (advanced.get()) return
        val pos = if (usingVlc) positionMs else controller.player.currentPosition
        val dur = if (usingVlc) durationMs
            else controller.player.duration.takeIf { it > 0 } ?: 0L
        // LibVLC/Media3 can know elapsed time while reporting an unknown length.
        // Saving must not depend on a positive total duration.
        WatchProgressCapture.snapshot(
            profileId = settings.activeProfileId.value,
            mediaKey = mediaKey,
            elapsedMs = pos,
            reportedDurationMs = dur,
            updatedAtMs = System.currentTimeMillis(),
        )?.let { VodProgressStore.persist(graph, it) }
    }

    fun naturalEnd() {
        // VLC and Media3 may report end more than once. Also suppress stale
        // disposal progress so the completed episode never reappears at 98%.
        if (!advanced.compareAndSet(false, true)) return
        scope.launch {
            val currentEpisode = if (mediaKey.startsWith("ep:"))
                mediaKey.removePrefix("ep:").toLongOrNull()
                    ?.let { graph.catalogRepository.episode(it) }
            else null
            val next = if (autoplay && currentEpisode != null)
                graph.catalogRepository.nextEpisode(currentEpisode.id) else null
            // A real end-of-stream confirms completion even if the length was missing.
            val duration = if (usingVlc) durationMs else controller.player.duration
            val elapsedAtEnd = if (usingVlc) positionMs else controller.player.currentPosition
            val finishAt = maxOf(duration, elapsedAtEnd)
            if (finishAt > 0L) {
                graph.playbackPositions.mergeIfNewer(
                    settings.activeProfileId.value, mediaKey,
                    finishAt, finishAt, System.currentTimeMillis(),
                )
            }
            if (next != null) {
                // Store a single newest resume pointer to the next episode,
                // including when the current episode was the season finale.
                graph.playbackPositions.upsert(
                    app.sokyiptv.data.model.PlaybackPosition(
                        settings.activeProfileId.value, "ep:${next.id}", 1_000L, 0L,
                        System.currentTimeMillis() + 1L,
                    ),
                )
                onNextEpisode(next)
            }
        }
    }

    // Media3 is used for local/recorded content and certain fallback streams.
    LaunchedEffect(usingVlc) {
        if (!usingVlc) {
            PlaybackDiagnostics.begin("Media3", "EXO")
            PlaybackDiagnostics.event(context, "MEDIA3_START", persistNow = true)
        }
    }
    LaunchedEffect(state, usingVlc) {
        if (!usingVlc) {
            when (val current = state) {
                is PlayerController.State.Error ->
                    PlaybackDiagnostics.event(
                        context, "MEDIA3_ERROR", current.errorCode ?: -1, persistNow = true,
                    )
                is PlayerController.State.Buffering ->
                    PlaybackDiagnostics.event(context, "MEDIA3_STATE", 1, persistNow = true)
                is PlayerController.State.Playing ->
                    PlaybackDiagnostics.event(context, "MEDIA3_STATE", 2)
                else -> Unit
            }
        }
    }
    LaunchedEffect(mediaKey, usingVlc) {
        if (usingVlc) return@LaunchedEffect
        while (isActive && !advanced.get()) {
            if (controller.player.playbackState == Player.STATE_ENDED) {
                naturalEnd()
                break
            }
            delay(450L)
        }
    }

    // Keep the screen awake during playback — see the note in PlayerScreen; a film is exactly when
    // the screensaver must not fire. Window flag is the reliable path; keepScreenOn is a backstop.
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view.keepScreenOn = true
        onDispose {
            PlaybackDiagnostics.event(
                context, if (usingVlc) "VLC_EXIT" else "MEDIA3_EXIT",
                persistNow = true,
            )
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            view.keepScreenOn = false
            // IMPORTANT: Media3 player getters must run on their application/main
            // thread and must never be read after release(). Earlier versions launched
            // savePosition() on IO and immediately released the player, which could crash
            // Android on Back from VOD. Snapshot first, persist only captured primitives.
            val endingPos = if (usingVlc) positionMs else
                runCatching { controller.player.currentPosition }.getOrDefault(0L)
            val endingDuration = if (usingVlc) durationMs else
                runCatching { controller.player.duration.takeIf { it > 0L } ?: 0L }.getOrDefault(0L)
            val endingProfile = settings.activeProfileId.value
            val endingIsComplete = advanced.get()
            val finalProgress = if (!endingIsComplete) WatchProgressCapture.snapshot(
                profileId = endingProfile,
                mediaKey = mediaKey,
                elapsedMs = endingPos,
                reportedDurationMs = endingDuration,
                updatedAtMs = System.currentTimeMillis(),
            ) else null
            // The screen scope is cancelled below. Persist independently so
            // Back/episode navigation cannot silently discard the exit position.
            // Crash-sensitive Back path: only one queued Room write on the isolated
            // IO store. No sync gate, callback or network wake-up.
            VodProgressStore.enqueue(graph, finalProgress)
            // Duration hints outlive the user's progress record and its Reset
            // action. This only uses primitive snapshots captured on UI thread.
            EpisodeRuntimeHints.savePlaybackLengthAsync(
                context.applicationContext, graph, mediaKey, endingPos, endingDuration,
            )
            if (media3Holder.isInitialized()) controller.release()
            scope.cancel()
        }
    }

    LaunchedEffect(mediaKey) {
        resumeFromMs = graph.playbackPositions.get(settings.activeProfileId.value, mediaKey)
            ?.takeIf { !it.isFinished }?.positionMillis ?: 0L
        resumeLoaded = true
    }
    LaunchedEffect(mediaKey, usingVlc, resumeLoaded) {
        if (!resumeLoaded) return@LaunchedEffect
        if (!usingVlc) {
            controller.play(
                PlayerController.Request(
                    url = streamUrl, title = title, userAgent = userAgent,
                    startPositionMillis = if (positionMs > 0L) positionMs else resumeFromMs,
                    isLive = false,
                ),
                debounce = false,
            )
        }
        while (isActive) {
            delay(15_000)
            if (!usingVlc) savePosition()
        }
    }

    // Unsupported container is a decoder/parser incompatibility, not a network buffering issue.
    // Stop Media3 before the native VLC view is attached. Never loop back automatically.
    LaunchedEffect(state, usingVlc) {
        val failure = state as? PlayerController.State.Error
        if (!usingVlc && !vlcEverTried && !growingRec &&
            failure?.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        ) {
            controller.stop()
            vlcEverTried = true
            usingVlc = true
        }
    }

    // Sleep timer: leave the film when the armed deadline passes (position is saved on dispose).
    val sleepDeadline by SleepTimer.deadline.collectAsState()
    LaunchedEffect(sleepDeadline) {
        val d = sleepDeadline ?: return@LaunchedEffect
        val wait = d - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        SleepTimer.clear()
        onBack()
    }

    // Poll position/duration for the seek bar while the film plays.
    LaunchedEffect(Unit) {
        while (isActive) {
            if (!scrubbing && !usingVlc) {
                positionMs = media3SeekClock.reconcile(
                    controller.player.currentPosition.coerceAtLeast(0),
                    android.os.SystemClock.elapsedRealtime(),
                )
                // A growing recording has no fixed length; the recorded-so-far extent (how far you
                // can skip ahead) is what's been read into the buffer.
                durationMs = if (growingRec) controller.player.bufferedPosition.coerceAtLeast(0)
                else controller.player.duration.takeIf { it > 0 } ?: 0
                if (postSeekGate.observe(
                    android.os.SystemClock.elapsedRealtime(),
                    controller.player.currentPosition,
                    controller.player.isPlaying && controller.player.playbackState == Player.STATE_READY,
                    controller.player.playbackState == Player.STATE_BUFFERING,
                )) {
                    awaitingSeekPlayback = false
                    // Only playback after this seek starts the hide countdown.
                    interaction++
                }
                PlaybackDiagnostics.sample(positionMs, durationMs, 0)
            }
            delay(500)
        }
    }

    // Sample only video frames that the active Media3 player ALREADY decoded,
    // at low frequency; no new media items, streams, seek calls or decoder.
    LaunchedEffect(netflix, usingVlc, media3SeekPreviewView) {
        if (!netflix || usingVlc) return@LaunchedEffect
        val videoView = media3SeekPreviewView ?: return@LaunchedEffect
        delay(2_500L)
        while (isActive && !usingVlc) {
            if (!paused && controller.player.isPlaying && !awaitingSeekPlayback) {
                runCatching {
                    seekFrameCache.record(videoView,
                        controller.player.currentPosition.coerceAtLeast(0L))
                }
            }
            delay(15_000L)
        }
    }

    // Auto-hide when playing and not paused/scrubbing and no picker open. A growing recording dips in
    // and out of Buffering as it rides the write head, so for that case Buffering counts as "playing"
    // here — otherwise a single hiccup would pin the transport bar on screen for the rest of the watch.
    LaunchedEffect(controlsVisible, interaction, state, paused, scrubbing, vodPanel, awaitingSeekPlayback) {
        val activelyPlaying = state is PlayerController.State.Playing ||
            (growingRec && state is PlayerController.State.Buffering)
        if (controlsVisible && !paused && !scrubbing && vodPanel == VodPanel.NONE &&
            !markerDialogVisible && activelyPlaying && !awaitingSeekPlayback) {
            delay(5_000)
            controlsVisible = false
        }
    }

    // One reliable focus handoff after controls have entered the Compose focus tree.
    // Never re-request focus on navigation events: that would steal it from D-pad moves.
    LaunchedEffect(controlsVisible, vodPanel, netflix) {
        withFrameNanos { }
        withFrameNanos { }
        if (controlsVisible) {
            runCatching {
                if (vodPanel != VodPanel.NONE) {
                    // Independent dialog owns focus in both player styles.
                } else if (netflix) {
                    if (focusSeekOnReveal) timelineFocus.requestFocus()
                    else barFocus.requestFocus()
                    focusSeekOnReveal = false
                } else if (focusSeekOnReveal) {
                    timelineFocus.requestFocus()
                    focusLevel = CinematicFocusLevel.SEEK
                    focusSeekOnReveal = false
                } else {
                    barFocus.requestFocus()
                    focusLevel = CinematicFocusLevel.CONTROLS
                }
            }
        } else runCatching { rootFocus.requestFocus() }
    }

    BackHandler(enabled = !usingVlc) {
        val activelyPlaying = state is PlayerController.State.Playing ||
            (growingRec && state is PlayerController.State.Buffering)
        when {
            markerDialogVisible -> markerDialogVisible = false
            vodPanel != VodPanel.NONE -> vodPanel = VodPanel.NONE
            controlsVisible && activelyPlaying && !paused -> controlsVisible = false
            else -> onBack()
        }
    }

    if (usingVlc && resumeLoaded) {
        VlcFallbackScreen(
            streamUrl = streamUrl,
            mediaKey = mediaKey,
            manualSkips = manualSkips,
            markersAvailable = !seriesGroup.isNullOrBlank(),
            onSkipsChanged = ::storeManualSkips,
            title = title,
            userAgent = userAgent,
            initialPositionMs = (if (positionMs > 0L) positionMs else resumeFromMs),
            onBack = onBack,
            onTryMedia3 = { usingVlc = false },
            showEpisodeControls = mediaKey.startsWith("ep:"),
            previousEpisodeAvailable = previousEpisode != null,
            nextEpisodeAvailable = followingEpisode != null,
            onPreviousEpisode = {
                previousEpisode?.let { target ->
                    scope.launch { savePosition(); onNextEpisode(target) }
                }
            },
            onNextEpisodeClick = {
                followingEpisode?.let { target ->
                    scope.launch { savePosition(); onNextEpisode(target) }
                }
            },
            onEnded = { naturalEnd() },
            suspendPlayback = screenSaverSuspended,
            onProgress = { pos, dur ->
                positionMs = pos
                durationMs = dur
                // Only save a snapshot of primitives, with no native player access on IO.
                // Unknown VOD duration must not suppress elapsed playback history.
                val snapshot = if (!advanced.get()) WatchProgressCapture.snapshot(
                    profileId = settings.activeProfileId.value,
                    mediaKey = mediaKey,
                    elapsedMs = pos,
                    reportedDurationMs = dur,
                    updatedAtMs = System.currentTimeMillis(),
                ) else null
                // VLC teardown can cancel the screen scope before its last write.
                VodProgressStore.enqueue(graph, snapshot)
            },
        )
    } else Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (onMarkerLongPress(event)) return@onPreviewKeyEvent true
                val hardwareKeyCode = event.nativeKeyEvent.keyCode
                when {
                    TvHardwarePlaybackKeys.isTransportKey(hardwareKeyCode) -> {
                        if (event.type == KeyEventType.KeyDown) {
                            TvHardwarePlaybackKeys.actionForInitialPress(
                                hardwareKeyCode, event.nativeKeyEvent.repeatCount,
                            )?.let(::setHardwarePlayback)
                        }
                        // KeyUp/repeat are handled but do not trigger a second action.
                        true
                    }
                    event.key == Key.Back || event.key == Key.Escape -> false
                    event.type == KeyEventType.KeyDown && !controlsVisible &&
                        (event.key == Key.DirectionCenter || event.key == Key.Enter) -> {
                        if (event.nativeKeyEvent.repeatCount == 0) {
                            paused = !paused
                            controller.player.playWhenReady = !paused
                        }
                        reveal()
                        true
                    }
                    event.type == KeyEventType.KeyDown && !controlsVisible &&
                        (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) -> {
                        focusSeekOnReveal = true
                        focusLevel = CinematicFocusLevel.SEEK
                        reveal()
                        seekTenSeconds(if (event.key == Key.DirectionRight)
                            SeekDirection.FORWARD else SeekDirection.BACKWARD)
                        true
                    }
                    event.type == KeyEventType.KeyDown && !controlsVisible -> { reveal(); true }
                    event.type == KeyEventType.KeyDown && controlsVisible && netflix -> {
                        interaction++
                        if (rootOwnsFocus && event.key in setOf(
                                Key.DirectionLeft, Key.DirectionRight,
                                Key.DirectionUp, Key.DirectionDown,
                            )) {
                            val target = if (event.key == Key.DirectionLeft ||
                                event.key == Key.DirectionRight) timelineFocus else barFocus
                            runCatching { target.requestFocus() }
                            true
                        } else false
                    }
                    event.type == KeyEventType.KeyDown && controlsVisible &&
                        (event.key == Key.DirectionUp || event.key == Key.DirectionDown) -> {
                        val next = moveCinematicFocus(
                            focusLevel, mediaKey.startsWith("ep:"),
                            up = event.key == Key.DirectionUp,
                        )
                        if (next == focusLevel) false
                        else {
                            focusLevel = next
                            when (next) {
                                CinematicFocusLevel.AUTOPLAY -> autoplayFocus.requestFocus()
                                CinematicFocusLevel.CONTROLS -> barFocus.requestFocus()
                                CinematicFocusLevel.SEEK -> timelineFocus.requestFocus()
                            }
                            reveal()
                            true
                        }
                    }
                    event.type == KeyEventType.KeyDown -> { interaction++; false }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .onFocusChanged { rootOwnsFocus = it.isFocused }
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures { if (controlsVisible) controlsVisible = false else reveal() }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    media3SeekPreviewView = this
                    player = controller.player
                    useController = false
                    subtitleView?.setUserDefaultStyle()
                    subtitleView?.setUserDefaultTextSize()
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            // Drop Media3's view reference before the native/decoder teardown.
            onRelease = { playerView ->
                if (media3SeekPreviewView === playerView) media3SeekPreviewView = null
                playerView.player = null
            },
        )

        when (val current = state) {
            is PlayerController.State.Buffering ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(current.title, color = Color.White)
                    }
                }

            is PlayerController.State.Error ->
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(48.dp),
                    ) {
                        Text(current.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Text(current.message, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { controller.retry() }) { Text(stringResource(R.string.common_try_again)) }
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = ::showMedia3Diagnostic) { Text("Scan error diagnostics QR") }
                    }
                }

            else -> Unit
        }

        // Netflix controls must be mounted synchronously, without AnimatedVisibility's
        // entering/exiting nodes temporarily invalidating FocusRequesters.
        if (netflix) {
            if (controlsVisible) {
                Box(Modifier.fillMaxSize()) {
                    NetflixLikePlayerChrome(
                        title = title, isEpisode = mediaKey.startsWith("ep:"),
                        positionMs = positionMs, durationMs = durationMs, paused = paused,
                        nextAvailable = followingEpisode != null, playFocus = barFocus,
                        timelineFocus = timelineFocus, tracksFocus = netflixTracksFocus,
                        onActivity = { interaction++ },
                        onBack = onBack,
                        onToggle = {
                            paused = !paused
                            controller.player.playWhenReady = !paused
                            reveal()
                        },
                        onSeek = ::seekAt, onEpisodes = onBack,
                        onTracks = { vodPanel = VodPanel.AUDIO; reveal() },
                        onNext = {
                            followingEpisode?.let { target ->
                                scope.launch { savePosition(); onNextEpisode(target) }
                            }
                        },
                        previewFrames = seekFrameCache,
                    )
                    if (vodPanel != VodPanel.NONE) {
                        val aud: List<Pair<String, () -> Unit>> = tracks.groups
                            .filter { it.type == C.TRACK_TYPE_AUDIO }
                            .flatMap { g ->
                                (0 until g.length).filter { g.isTrackSupported(it) }.map { i ->
                                    val f = g.getTrackFormat(i)
                                    vodTrackLabel(f.label, f.language, i) to {
                                        controller.selectTrack(g, i); vodPanel = VodPanel.NONE
                                    }
                                }
                            }
                        val sub: List<Pair<String, () -> Unit>> =
                            listOf("Off" to { controller.disableText(); vodPanel = VodPanel.NONE }) +
                            tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { g ->
                                (0 until g.length).filter { g.isTrackSupported(it) }.map { i ->
                                    val f = g.getTrackFormat(i)
                                    vodTrackLabel(f.label, f.language, i) to {
                                        controller.selectTrack(g, i); vodPanel = VodPanel.NONE
                                    }
                                }
                            }
                        NetflixTrackOptions(aud, sub) { vodPanel = VodPanel.NONE }
                    }
                }
            }
        } else {
            // Retain the approved cinematic/current-style transitions untouched.
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
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
                    isEpisode = mediaKey.startsWith("ep:"),
                    elapsedText = formatDuration(if (scrubbing) scrubValue.toLong() else positionMs),
                    durationText = if (timelineScale.approximate)
                        "~" + formatDuration(timelineScale.maximumMs) else formatDuration(durationMs),
                    modifier = Modifier.align(Alignment.TopStart)
                        .padding(start = 46.dp, top = 44.dp),
                )
                if (mediaKey.startsWith("ep:")) {
                    CinematicAutoplay(
                        enabled = autoplay,
                        onChange = { settings.setSeriesAutoplay(it); interaction++ },
                        focusRequester = autoplayFocus,
                        onFocusChanged = {
                            autoplayFocused = it
                            if (it) focusLevel = CinematicFocusLevel.AUTOPLAY
                        },
                        modifier = Modifier.align(Alignment.TopEnd)
                            .padding(end = 40.dp, top = 42.dp),
                    )
                }
                // Preserve the approved Media3 relative seek and Slider callbacks unchanged.
                // Seek bar with times either side. Left/right on the remote scrubs.
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 36.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(formatDuration(if (scrubbing) scrubValue.toLong() else positionMs), color = Color.White)
                    Spacer(Modifier.width(12.dp))
                    Slider(
                        value = (if (scrubbing) scrubValue else positionMs.toFloat())
                            .coerceIn(0f, timelineScale.maximumMs.toFloat()),
                        valueRange = 0f..timelineScale.maximumMs.toFloat(),
                        onValueChange = { scrubbing = true; scrubValue = it; interaction++ },
                        onValueChangeFinished = {
                            val now = android.os.SystemClock.elapsedRealtime()
                            postSeekGate.requested(now)
                            awaitingSeekPlayback = true
                            controller.player.seekTo(scrubValue.toLong())
                            positionMs = media3SeekClock.requested(scrubValue.toLong(), now)
                            scrubbing = false
                            interaction++
                        },
                        colors = androidx.compose.material3.SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = CinemaBlue,
                            inactiveTrackColor = CinemaTrack,
                        ),
                        modifier = Modifier.weight(1f)
                            // Override the Slider's fraction-based TV arrow handling.
                            // Pointer dragging remains continuous, but remote arrows seek
                            // exactly 10,000 milliseconds per press, not a % of duration.
                            .onPreviewKeyEvent { event ->
                                val direction = when (event.key) {
                                    Key.DirectionLeft -> SeekDirection.BACKWARD
                                    Key.DirectionRight -> SeekDirection.FORWARD
                                    else -> null
                                }
                                if (direction != null) {
                                    if (event.type == KeyEventType.KeyDown) seekTenSeconds(direction)
                                    true
                                } else false
                            }
                            .focusRequester(timelineFocus)
                            .onFocusChanged {
                                timelineFocused = it.isFocused
                                if (it.isFocused) focusLevel = CinematicFocusLevel.SEEK
                            },
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(if (timelineScale.approximate) "~" + formatDuration(timelineScale.maximumMs)
                         else formatDuration(durationMs), color = Color.White)
                }
                CinematicTransport(
                    isEpisode = mediaKey.startsWith("ep:"),
                    paused = paused,
                    previousEnabled = previousEpisode != null,
                    nextEnabled = followingEpisode != null,
                    onPrevious = {
                        previousEpisode?.let { target ->
                            scope.launch { savePosition(); onNextEpisode(target) }
                        }
                    },
                    onRewind = {
                        seekTenSeconds(SeekDirection.BACKWARD)
                    },
                    onTogglePause = {
                        paused = !paused
                        controller.player.playWhenReady = !paused
                        interaction++
                    },
                    onForward = {
                        seekTenSeconds(SeekDirection.FORWARD)
                    },
                    onNext = {
                        followingEpisode?.let { target ->
                            scope.launch { savePosition(); onNextEpisode(target) }
                        }
                    },
                    pauseRequester = barFocus,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = 74.dp),
                )
                if (vodPanel != VodPanel.NONE) {
                    TrackPanel(
                        panel = vodPanel,
                        controller = controller,
                        tracks = tracks,
                        firstFocus = panelFocus,
                        onDone = { vodPanel = VodPanel.NONE; interaction++ },
                    )
                }
            }
            }
        }
        val introSkip = mediaKey.startsWith("ep:") && manualSkips.showIntro(positionMs)
        val creditsSkip = mediaKey.startsWith("ep:") && manualSkips.showCredits(positionMs)
        if (state !is PlayerController.State.Error && !paused && introSkip) {
            NetflixSkipPrompt(
                label = "Skip Intro",
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = 44.dp, bottom = if (controlsVisible) 192.dp else 62.dp),
            ) { seekAt(manualSkips.introEndMs) }
        } else if (state !is PlayerController.State.Error && !paused &&
            creditsSkip && !creditsDismissed) {
            val nextAction: () -> Unit = {
                val candidate = followingEpisode
                if (mediaKey.startsWith("ep:") && candidate != null) {
                    scope.launch { savePosition(); onNextEpisode(candidate) }
                } else onBack()
            }
            if (netflix) {
                NetflixCreditsPrompt(
                    isEpisode = mediaKey.startsWith("ep:"),
                    nextAvailable = followingEpisode != null,
                    onContinue = { creditsDismissed = true },
                    onSkip = nextAction,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                NetflixSkipPrompt(
                    label = if (mediaKey.startsWith("ep:") && followingEpisode != null)
                        "Next Episode" else "Back to Title",
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .padding(end = 44.dp, bottom = if (controlsVisible) 192.dp else 62.dp),
                    onClick = nextAction,
                )
            }
        }
        media3Diagnostic?.let { report ->
            PlaybackDiagnosticsDialog(
                report = report,
                onDismiss = { media3Diagnostic = null },
            )
        }
    }
    if (!usingVlc && markerDialogVisible && !seriesGroup.isNullOrBlank() &&
        mediaKey.startsWith("ep:")) {
        ManualSeriesSkipDialog(
            atMs = positionMs,
            marks = manualSkips,
            onMark = { mark ->
                val current = controller.player.currentPosition.coerceAtLeast(0L)
                storeManualSkips(manualSkips.mark(mark, current))
                markerDialogVisible = false
                reveal()
            },
            onClearIntro = {
                storeManualSkips(manualSkips.clearIntro())
                markerDialogVisible = false
            },
            onClearCredits = {
                storeManualSkips(manualSkips.clearCredits())
                markerDialogVisible = false
            },
            onClearAll = {
                storeManualSkips(ManualSeriesSkips())
                markerDialogVisible = false
            },
            onClose = { markerDialogVisible = false },
        )
    }
}

@Composable
private fun VodChip(
    icon: ImageVector,
    label: String,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val container = if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.14f)
    val content = if (focused) MaterialTheme.colorScheme.onPrimary else Color.White
    Row(
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = content)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

private enum class VodPanel { NONE, SUBTITLES, AUDIO }

@OptIn(UnstableApi::class)
@Composable
private fun TrackPanel(
    panel: VodPanel,
    controller: PlayerController,
    tracks: Tracks,
    firstFocus: FocusRequester,
    onDone: () -> Unit,
) {
    val trackType = if (panel == VodPanel.SUBTITLES) C.TRACK_TYPE_TEXT else C.TRACK_TYPE_AUDIO
    val groups = tracks.groups.filter { it.type == trackType }
    val options = mutableListOf<Triple<String, Boolean, () -> Unit>>()

    if (panel == VodPanel.SUBTITLES) {
        val anySelected = groups.any { g -> (0 until g.length).any { g.isTrackSelected(it) } }
        options += Triple(stringResource(R.string.player_subtitles_off), !anySelected) { controller.disableText(); onDone() }
    }
    groups.forEach { group ->
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val format = group.getTrackFormat(i)
            options += Triple(vodTrackLabel(format.label, format.language, options.size), group.isTrackSelected(i)) {
                controller.selectTrack(group, i); onDone()
            }
        }
    }

    TvChoiceDialog(
        heading = if (panel == VodPanel.SUBTITLES) "Subtitles" else "Audio Tracks",
        details = "Use D-pad Up and Down to select a sound or subtitle track.",
        items = options.map { (label, selected, action) ->
            ((if (selected) "[Selected] " else "") + label) to action
        } + listOf("Done" to onDone),
        onClose = onDone,
    )
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit, focusRequester: FocusRequester?) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f)
    val fg = if (focused) MaterialTheme.colorScheme.onPrimary else Color.White
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = fg, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.common_selected), tint = fg)
    }
}

private fun vodTrackLabel(label: String?, language: String?, index: Int): String {
    if (!label.isNullOrBlank()) return label
    if (!language.isNullOrBlank() && language != "und") {
        return runCatching { java.util.Locale(language).displayLanguage.ifBlank { language } }.getOrDefault(language)
    }
    return "Track ${index + 1}"
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
