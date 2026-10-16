/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.core.SleepTimer
import app.opentv.core.findActivity
import app.opentv.player.PlayerController
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
    onNextEpisode: (app.opentv.data.model.Episode) -> Unit = {},
) {
    val context = LocalContext.current
    val view = LocalView.current
    val graph = remember { ServiceLocator.get(context) }
    val settings = remember { graph.settings }
    val autoplay by settings.seriesAutoplay.collectAsState()
    val advanced = remember(mediaKey) { java.util.concurrent.atomic.AtomicBoolean(false) }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    // A recording that's still being written plays through the tail-following source. It behaves a
    // little differently in the transport: the "length" is how much has been recorded so far, which
    // keeps growing, and seeking is bounded to that recorded extent.
    val growingRec = remember(streamUrl) { streamUrl.startsWith("optvrec://") }
    // The SMB source lets a recording stored on a NAS play and seek in-app; harmless for the
    // http/file URLs of ordinary VOD.
    val controller = remember {
        PlayerController(
            context, scope, graph.streamingHttpClient, subtitlesEnabled = false,
            smbDataSourceFactory = app.opentv.player.SmbDataSource.Factory(graph.settings),
            // Lets an `optvrec://<id>` recording play while it's still being written.
            growingDataSourceFactory =
                app.opentv.player.GrowingRecordingDataSource.Factory(context.applicationContext),
            liveRecording = growingRec,
        )
    }
    // Skip forward/back within the recorded portion. For a growing recording ExoPlayer won't report
    // the item as seekable (no fixed length), so we seek directly, clamped to what's on disk.
    fun seekRelative(deltaMs: Long) {
        val p = controller.player
        val ceiling = if (growingRec) p.bufferedPosition.coerceAtLeast(0L)
        else (p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0L, ceiling))
    }
    val state by controller.state.collectAsState()
    val tracks by controller.tracks.collectAsState()

    var paused by remember { mutableStateOf(false) }

    var screenSaverSuspended by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context, controller) {
        fun pauseForSaver() {
            if (!app.opentv.core.PipState.inPip.value) {
                screenSaverSuspended = true
                controller.player.pause()
                paused = true
                app.opentv.core.PipState.isPlaying = false
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF, Intent.ACTION_DREAMING_STARTED -> pauseForSaver()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_DREAMING_STARTED)
        }
        runCatching {
            ContextCompat.registerReceiver(
                context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) pauseForSaver()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
    // For HTTP IPTV VOD, use VLC first: Media3 frequently rejects provider containers.
    // Growing/internal/SMB recordings retain Media3's specialised data sources.
    var usingVlc by remember(streamUrl) { mutableStateOf(!growingRec && streamUrl.startsWith("http", true)) }
    var vlcEverTried by remember(streamUrl) { mutableStateOf(usingVlc) }
    var resumeLoaded by remember(mediaKey) { mutableStateOf(false) }
    var resumeFromMs by remember(mediaKey) { mutableLongStateOf(0L) }
    var vodPanel by remember { mutableStateOf(VodPanel.NONE) }
    val panelFocus = remember { FocusRequester() }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }

    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val barFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }

    fun reveal() { controlsVisible = true; interaction++ }

    suspend fun savePosition() {
        if (advanced.get()) return
        val player = controller.player
        val pos = if (usingVlc) positionMs else player.currentPosition
        val dur = if (usingVlc) durationMs else player.duration.takeIf { it > 0 } ?: 0L
        if (dur <= 0L) return
        if (pos > 5_000) {
            graph.playbackPositions.upsert(
                app.opentv.data.model.PlaybackPosition(
                    profileId = settings.activeProfileId.value,
                    mediaKey = mediaKey,
                    positionMillis = pos,
                    durationMillis = dur,
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
        }
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
            val duration = if (usingVlc) durationMs else controller.player.duration
            if (duration > 0L) {
                graph.playbackPositions.upsert(
                    app.opentv.data.model.PlaybackPosition(
                        settings.activeProfileId.value, mediaKey, duration, duration,
                        System.currentTimeMillis(),
                    ),
                )
            }
            if (next != null) {
                // Store a single newest resume pointer to the next episode,
                // including when the current episode was the season finale.
                graph.playbackPositions.upsert(
                    app.opentv.data.model.PlaybackPosition(
                        settings.activeProfileId.value, "ep:${next.id}", 1_000L, 0L,
                        System.currentTimeMillis() + 1L,
                    ),
                )
                onNextEpisode(next)
            }
        }
    }

    // Media3 is used for local/recorded content and certain fallback streams.
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
            if (!endingIsComplete && endingPos > 5_000L && endingDuration > 0L) {
                val snapshot = app.opentv.data.model.PlaybackPosition(
                    profileId = endingProfile,
                    mediaKey = mediaKey,
                    positionMillis = endingPos,
                    durationMillis = endingDuration,
                    updatedAtMillis = System.currentTimeMillis(),
                )
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    runCatching { graph.playbackPositions.upsert(snapshot) }
                        .onFailure { android.util.Log.w("VodPlayerScreen", "Could not save final progress", it) }
                }
            }
            controller.release()
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
                positionMs = controller.player.currentPosition.coerceAtLeast(0)
                // A growing recording has no fixed length; the recorded-so-far extent (how far you
                // can skip ahead) is what's been read into the buffer.
                durationMs = if (growingRec) controller.player.bufferedPosition.coerceAtLeast(0)
                else controller.player.duration.takeIf { it > 0 } ?: 0
            }
            delay(500)
        }
    }

    // Auto-hide when playing and not paused/scrubbing and no picker open. A growing recording dips in
    // and out of Buffering as it rides the write head, so for that case Buffering counts as "playing"
    // here — otherwise a single hiccup would pin the transport bar on screen for the rest of the watch.
    LaunchedEffect(controlsVisible, interaction, state, paused, scrubbing, vodPanel) {
        val activelyPlaying = state is PlayerController.State.Playing ||
            (growingRec && state is PlayerController.State.Buffering)
        if (controlsVisible && !paused && !scrubbing && vodPanel == VodPanel.NONE && activelyPlaying) {
            delay(5_000)
            controlsVisible = false
        }
    }

    LaunchedEffect(controlsVisible, vodPanel) {
        if (controlsVisible) {
            delay(40)
            runCatching { if (vodPanel != VodPanel.NONE) panelFocus.requestFocus() else barFocus.requestFocus() }
        } else {
            runCatching { rootFocus.requestFocus() }
        }
    }

    BackHandler(enabled = !usingVlc) {
        val activelyPlaying = state is PlayerController.State.Playing ||
            (growingRec && state is PlayerController.State.Buffering)
        when {
            vodPanel != VodPanel.NONE -> vodPanel = VodPanel.NONE
            controlsVisible && activelyPlaying && !paused -> controlsVisible = false
            else -> onBack()
        }
    }

    if (usingVlc && resumeLoaded) {
        VlcFallbackScreen(
            streamUrl = streamUrl,
            title = title,
            userAgent = userAgent,
            initialPositionMs = (if (positionMs > 0L) positionMs else resumeFromMs),
            onBack = onBack,
            onTryMedia3 = { usingVlc = false },
            onEnded = { naturalEnd() },
            suspendPlayback = screenSaverSuspended,
            onProgress = { pos, dur ->
                positionMs = pos
                durationMs = dur
                if (!advanced.get() && pos > 5_000L && dur > 0L) {
                    // Capture active profile and position on the UI thread, then persist only
                    // immutable values on IO, never touch player or settings after teardown.
                    val snapshot = app.opentv.data.model.PlaybackPosition(
                        profileId = settings.activeProfileId.value,
                        mediaKey = mediaKey,
                        positionMillis = pos,
                        durationMillis = dur,
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        runCatching { graph.playbackPositions.upsert(snapshot) }
                            .onFailure { android.util.Log.w("VodPlayerScreen", "Progress save failed", it) }
                    }
                }
            },
        )
    } else Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                when {
                    event.key == Key.Back || event.key == Key.Escape -> false
                    event.type == KeyEventType.KeyDown && !controlsVisible -> { reveal(); true }
                    event.type == KeyEventType.KeyDown -> { interaction++; false }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures { if (controlsVisible) controlsVisible = false else reveal() }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
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
            onRelease = { playerView -> playerView.player = null },
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
                    }
                }

            else -> Unit
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
                    // Keep the controls clear of the phone/tablet system nav bar (and the side nav
                    // bar / display cutout in landscape). Zero on a TV, so the 10-foot layout is
                    // unchanged; the gradient above stays full-bleed to the screen edge.
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    )
                    .padding(horizontal = 28.dp, vertical = 20.dp),
            ) {
                if (vodPanel != VodPanel.NONE) {
                    TrackPanel(
                        panel = vodPanel,
                        controller = controller,
                        tracks = tracks,
                        firstFocus = panelFocus,
                        onDone = { vodPanel = VodPanel.NONE; interaction++ },
                    )
                    Spacer(Modifier.height(16.dp))
                }

                if (growingRec) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.FiberManualRecord,
                            contentDescription = null,
                            tint = Color(0xFFE53935),
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.rec_watching_live_badge),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color(0xFFE53935),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Spacer(Modifier.height(8.dp))

                // Seek bar with times either side. Left/right on the remote scrubs.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatDuration(if (scrubbing) scrubValue.toLong() else positionMs), color = Color.White)
                    Spacer(Modifier.width(12.dp))
                    Slider(
                        value = if (scrubbing) scrubValue else positionMs.toFloat(),
                        valueRange = 0f..(durationMs.takeIf { it > 0 }?.toFloat() ?: 1f),
                        onValueChange = { scrubbing = true; scrubValue = it; interaction++ },
                        onValueChangeFinished = {
                            controller.player.seekTo(scrubValue.toLong())
                            positionMs = scrubValue.toLong()
                            scrubbing = false
                            interaction++
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(formatDuration(durationMs), color = Color.White)
                }

                Spacer(Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    VodChip(Icons.Filled.FastRewind, stringResource(R.string.player_rewind)) {
                        if (growingRec) seekRelative(-15_000) else controller.seekBackward(); interaction++
                    }
                    Spacer(Modifier.width(10.dp))
                    VodChip(
                        icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        label = if (paused) stringResource(R.string.common_play) else stringResource(R.string.player_pause),
                        focusRequester = barFocus,
                    ) {
                        paused = !paused
                        controller.player.playWhenReady = !paused
                        interaction++
                    }
                    Spacer(Modifier.width(10.dp))
                    VodChip(Icons.Filled.FastForward, stringResource(R.string.player_forward)) {
                        if (growingRec) seekRelative(15_000) else controller.seekForward(); interaction++
                    }
                    Spacer(Modifier.width(20.dp))
                    VodChip(Icons.Filled.ClosedCaption, stringResource(R.string.player_subtitles)) {
                        vodPanel = if (vodPanel == VodPanel.SUBTITLES) VodPanel.NONE else VodPanel.SUBTITLES
                        interaction++
                    }
                    Spacer(Modifier.width(10.dp))
                    VodChip(Icons.Filled.Audiotrack, stringResource(R.string.player_audio)) {
                        vodPanel = if (vodPanel == VodPanel.AUDIO) VodPanel.NONE else VodPanel.AUDIO
                        interaction++
                    }
                }
            }
        }
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

    Column(
        Modifier
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(16.dp),
    ) {
        Text(
            if (panel == VodPanel.SUBTITLES) stringResource(R.string.player_subtitles) else stringResource(R.string.player_audio),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(8.dp))
        Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            if (options.isEmpty()) {
                Text(stringResource(R.string.player_none_available), color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(8.dp))
            }
            options.forEachIndexed { index, (label, selected, onClick) ->
                TrackRow(label, selected, onClick, if (index == 0) firstFocus else null)
            }
        }
    }
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
