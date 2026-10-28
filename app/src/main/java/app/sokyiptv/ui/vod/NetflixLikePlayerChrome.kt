/* GPL-3.0-or-later. Netflix-inspired frameless VOD chrome with explicit TV focus. */
package app.sokyiptv.ui.vod

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.unit.sp
import app.sokyiptv.player.ManualSeriesSkips
import app.sokyiptv.player.SkipMark
import app.sokyiptv.player.SeekDirection
import app.sokyiptv.player.TvSeekPolicy
import kotlin.math.max
import kotlin.math.min

private val netflixRed = Color(0xFFC7D1D8) // quiet neutral progress / seek accent
private val netflixWhite = Color.White

/**
 * Mirrors the reference's positions and scale, not its inaccessible buttons.
 * All click targets are frameless when unfocused, with an obvious white focus cue.
 * Supplied focus requesters are the SAME targets requested by the parent renderer.
 */
@Composable
internal fun NetflixLikePlayerChrome(
    title: String,
    isEpisode: Boolean,
    positionMs: Long,
    durationMs: Long,
    paused: Boolean,
    buffering: Boolean = false,
    bufferingPercent: Int? = null,
    nextAvailable: Boolean,
    manualSkips: ManualSeriesSkips = ManualSeriesSkips(),
    markersEnabled: Boolean = false,
    onMarkers: () -> Unit = {},
    playFocus: FocusRequester,
    timelineFocus: FocusRequester,
    tracksFocus: FocusRequester,
    skipPromptVisible: Boolean = false,
    skipPromptFocus: FocusRequester? = null,
    frameAt: (Long) -> NetflixSeekFrameCache.CapturedFrame? = { null },
    predictiveTrickplay: Boolean = false,
    playbackSettling: Boolean = false,
    previewStatus: String = "Preparing thumbnails…",
    onPreviewRequested: (Long) -> Unit = {},
    onSeekingChanged: (Boolean) -> Unit = {},
    onActivity: () -> Unit,
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onEpisodes: () -> Unit,
    onTracks: () -> Unit,
    onNext: () -> Unit,
) {
    val closeFocus = remember { FocusRequester() }
    val backwardFocus = remember { FocusRequester() }
    val forwardFocus = remember { FocusRequester() }
    val episodesFocus = remember { FocusRequester() }
    val markersFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    var scrubbing by remember { mutableStateOf(false) }
    var seekPreviewMs by remember { mutableLongStateOf(0L) }
    var seekOriginMs by remember { mutableLongStateOf(0L) }
    var seekHeldAtMs by remember { mutableLongStateOf(0L) }
    var heldDirection by remember { mutableStateOf<SeekDirection?>(null) }
    var seekFocused by remember { mutableStateOf(false) }
    var buttonSeekPreviewMs by remember { mutableLongStateOf(-1L) }
    var activeTarget by remember { mutableStateOf(NetflixTarget.PLAY) }
    var selectedSeekTargetMs by remember { mutableLongStateOf(-1L) }
    var seekSubmittedAtMs by remember { mutableLongStateOf(0L) }
    val nowUptimeMs = SystemClock.uptimeMillis()
    val pendingTarget = selectedSeekTargetMs.takeIf { it >= 0L &&
        !SeekbarOkPolicy.caughtUp(it, positionMs, playbackSettling,
            seekSubmittedAtMs, nowUptimeMs) }
    val displayMs = when {
        scrubbing -> seekPreviewMs
        pendingTarget != null -> pendingTarget
        else -> positionMs
    }
    LaunchedEffect(selectedSeekTargetMs, positionMs, playbackSettling, seekSubmittedAtMs) {
        if (selectedSeekTargetMs < 0L) return@LaunchedEffect
        val remain = (seekSubmittedAtMs + SeekbarOkPolicy.MIN_CONFIRMATION_MS -
            SystemClock.uptimeMillis()).coerceAtLeast(0L)
        if (remain > 0L) delay(remain)
        if (SeekbarOkPolicy.caughtUp(selectedSeekTargetMs, positionMs,
                playbackSettling, seekSubmittedAtMs, SystemClock.uptimeMillis())) {
            selectedSeekTargetMs = -1L
        }
    }

    // TV remotes do not always send repeated D-pad DOWN events while held.
    // Progress based on monotonic elapsed time, without issuing ANY player seek
    // until the user releases the key.
    LaunchedEffect(heldDirection, seekHeldAtMs, durationMs) {
        val dir = heldDirection ?: return@LaunchedEffect
        if (durationMs <= 0L) return@LaunchedEffect
        while (isActive) {
            delay(65L)
            val held = SystemClock.uptimeMillis() - seekHeldAtMs
            if (held >= TvSeekPolicy.HOLD_THRESHOLD_MS) {
                val prediction = TvSeekPolicy.heldPreview(seekOriginMs, held, dir, durationMs)
                seekPreviewMs = if (dir == SeekDirection.FORWARD)
                    max(seekPreviewMs, prediction) else min(seekPreviewMs, prediction)
            }
        }
    }
    LaunchedEffect(buttonSeekPreviewMs) {
        if (buttonSeekPreviewMs >= 0L) {
            delay(1_450L)
            buttonSeekPreviewMs = -1L
        }
    }

    // Do not block arrow-key focus traversal with a fullscreen clickable surface.
    BoxWithConstraints(Modifier.fillMaxSize()
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val direction = when (event.key) {
                Key.DirectionUp -> NetflixDirection.UP
                Key.DirectionDown -> NetflixDirection.DOWN
                Key.DirectionLeft -> NetflixDirection.LEFT
                Key.DirectionRight -> NetflixDirection.RIGHT
                else -> null
            } ?: return@onPreviewKeyEvent false
            // Up always returns to a visible skip prompt from ANY Netflix
            // transport control; previously the D-pad focus graph trapped it.
            if (skipPromptVisible && direction == NetflixDirection.UP) {
                skipPromptFocus?.let { runCatching { it.requestFocus() } }
                onActivity()
                return@onPreviewKeyEvent true
            }
            // Timeline owns Left and Right for real +/-10s and hold-to-accelerate.
            if (activeTarget == NetflixTarget.TIMELINE &&
                direction in setOf(NetflixDirection.LEFT, NetflixDirection.RIGHT))
                return@onPreviewKeyEvent false
            val next = NetflixFocusGraph.next(activeTarget, direction, isEpisode, nextAvailable, markersEnabled)
            onActivity()
            if (next != activeTarget) {
                activeTarget = next
                val requester = when (next) {
                    NetflixTarget.CLOSE -> closeFocus
                    NetflixTarget.REWIND -> backwardFocus
                    NetflixTarget.PLAY -> playFocus
                    NetflixTarget.FORWARD -> forwardFocus
                    NetflixTarget.TIMELINE -> timelineFocus
                    NetflixTarget.EPISODES -> episodesFocus
                    NetflixTarget.MARKERS -> markersFocus
                    NetflixTarget.TRACKS -> tracksFocus
                    NetflixTarget.NEXT -> nextFocus
                }
                runCatching { requester.requestFocus() }
            }
            true
        },
    ) {
        val topGap = maxHeight * 0.080f
        val controlsBottom = maxHeight * 0.30f
        val timelineBottom = maxHeight * 0.145f
        val linksBottom = maxHeight * 0.040f
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = .30f),
                    .23f to Color.Transparent,
                    .65f to Color.Transparent,
                    1f to Color.Black.copy(alpha = .56f),
                ),
            ),
        )
        Text(
            text = title,
            modifier = Modifier.align(Alignment.TopCenter)
                .fillMaxWidth(.70f).padding(top = topGap),
            fontSize = 21.sp, color = netflixWhite, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        NIcon(
            icon = Icons.Filled.Close, label = "Close",
            modifier = Modifier.align(Alignment.TopEnd)
                .padding(end = 40.dp, top = topGap - 7.dp)
                .focusRequester(closeFocus)
                .onFocusChanged { if (it.isFocused) { activeTarget = NetflixTarget.CLOSE; onActivity() } }
                .focusProperties { down = playFocus; left = playFocus },
            size = 37, onClick = onBack, onActivity = onActivity,
        )

        Row(
            modifier = Modifier.align(Alignment.Center)
                .padding(bottom = maxHeight * .05f),
            horizontalArrangement = Arrangement.spacedBy(maxWidth * .175f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NIcon(
                Icons.Filled.Replay10, "Rewind 10 seconds",
                Modifier.focusRequester(backwardFocus)
                    .onFocusChanged { if (it.isFocused) { activeTarget = NetflixTarget.REWIND; onActivity() } }
                    .focusProperties {
                        right = playFocus; up = closeFocus; down = timelineFocus
                    },
                58,
                onClick = {
                    val target = TvSeekPolicy.tap(positionMs, durationMs, SeekDirection.BACKWARD)
                    buttonSeekPreviewMs = target
                    onSeek(target)
                },
                onActivity = onActivity,
            )
            NIcon(
                if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                if (paused) "Play" else "Pause",
                Modifier.focusRequester(playFocus)
                    .onFocusChanged { if (it.isFocused) { activeTarget = NetflixTarget.PLAY; onActivity() } }
                    .focusProperties {
                    left = backwardFocus; right = forwardFocus
                    up = closeFocus; down = timelineFocus
                },
                73, onClick = onToggle, onActivity = onActivity,
                // The Play/Pause symbol itself remains visible; its button
                // surface is fully transparent whether focused or buffering.
                opacityUnfocused = .64f,
                buffering = buffering,
                bufferingPercent = bufferingPercent,
            )
            NIcon(
                Icons.Filled.Forward10, "Forward 10 seconds",
                Modifier.focusRequester(forwardFocus)
                    .onFocusChanged { if (it.isFocused) { activeTarget = NetflixTarget.FORWARD; onActivity() } }
                    .focusProperties {
                    left = playFocus; up = closeFocus; down = timelineFocus
                },
                58,
                onClick = {
                    val target = TvSeekPolicy.tap(positionMs, durationMs, SeekDirection.FORWARD)
                    buttonSeekPreviewMs = target
                    onSeek(target)
                },
                onActivity = onActivity,
            )
        }

        Row(
            modifier = Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth().padding(start = 47.dp, end = 53.dp, bottom = timelineBottom),
            horizontalArrangement = Arrangement.spacedBy(17.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.weight(1f).height(42.dp)
                    .focusRequester(timelineFocus)
                    .focusProperties {
                        up = playFocus
                        down = if (isEpisode) episodesFocus else tracksFocus
                    }
                    .onFocusChanged {
                        seekFocused = it.isFocused
                        if (it.isFocused) { activeTarget = NetflixTarget.TIMELINE; onActivity() }
                        else if (heldDirection != null) {
                            heldDirection = null
                            scrubbing = false
                            onSeekingChanged(false)
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        // Remote OK confirms an unsettled selection immediately.
                        // Once playback catches the cursor it means Play/Pause.
                        if (event.key == Key.DirectionCenter || event.key == Key.Enter) {
                            if (event.type == KeyEventType.KeyDown &&
                                event.nativeKeyEvent.repeatCount == 0) {
                                onActivity()
                                val selected = if (scrubbing) seekPreviewMs else selectedSeekTargetMs
                                val action = SeekbarOkPolicy.action(
                                    selectedMs = selected,
                                    reportedPlaybackMs = positionMs,
                                    activelySelecting = scrubbing,
                                    seekStillSettling = playbackSettling,
                                    submittedAtUptimeMs = seekSubmittedAtMs,
                                    nowUptimeMs = SystemClock.uptimeMillis(),
                                )
                                if (action == SeekbarOkPolicy.Action.COMMIT_SEEK) {
                                    heldDirection = null
                                    scrubbing = false
                                    onSeekingChanged(false)
                                    selectedSeekTargetMs = selected
                                    seekSubmittedAtMs = SystemClock.uptimeMillis()
                                    buttonSeekPreviewMs = selected
                                    onSeek(selected)
                                } else {
                                    selectedSeekTargetMs = -1L
                                    onToggle()
                                }
                            }
                            return@onPreviewKeyEvent true
                        }
                        val direction = when (event.key) {
                            Key.DirectionLeft -> SeekDirection.BACKWARD
                            Key.DirectionRight -> SeekDirection.FORWARD
                            else -> null
                        } ?: return@onPreviewKeyEvent false
                        onActivity()
                        when (event.type) {
                            KeyEventType.KeyDown -> {
                                if (durationMs <= 0L) return@onPreviewKeyEvent true
                                if (event.nativeKeyEvent.repeatCount == 0 ||
                                    heldDirection != direction) {
                                    seekOriginMs = if (scrubbing) seekPreviewMs
                                        else pendingTarget ?: positionMs
                                    seekHeldAtMs = SystemClock.uptimeMillis()
                                    heldDirection = direction
                                    seekPreviewMs = TvSeekPolicy.tap(
                                        seekOriginMs, durationMs, direction)
                                    scrubbing = true
                                    onSeekingChanged(true)
                                }
                                // Held-key acceleration is updated by LaunchedEffect.
                                true
                            }
                            KeyEventType.KeyUp -> {
                                val target = seekPreviewMs
                                val shouldCommit = scrubbing && heldDirection == direction
                                heldDirection = null
                                scrubbing = false
                                onSeekingChanged(false)
                                if (shouldCommit && durationMs > 0L) {
                                    selectedSeekTargetMs = target
                                    seekSubmittedAtMs = SystemClock.uptimeMillis()
                                    buttonSeekPreviewMs = target
                                    onSeek(target)
                                }
                                true
                            }
                            else -> false
                        }
                    }
                    .semantics {
                        contentDescription = "Timeline. Left and right move or seek ten seconds. OK confirms a pending seek, otherwise pauses or resumes."
                    }
                    .focusable(),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxWidth().height(23.dp)) {
                    val left = 11.dp.toPx()
                    val right = size.width - left
                    val middle = size.height / 2f
                    drawLine(Color(0xFF68747E), Offset(left, middle),
                        Offset(right, middle), 3.dp.toPx(), cap = StrokeCap.Round)
                    val fraction = if (durationMs > 0L)
                        (displayMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f) else 0f
                    val playhead = left + (right-left) * fraction
                    drawLine(netflixRed, Offset(left, middle), Offset(playhead, middle),
                        3.dp.toPx(), cap = StrokeCap.Round)
                    drawManualTimelineBands(
                        manualSkips, durationMs, left, right, middle, 7.dp.toPx()
                    )
                    // Frameless red dot, no focus halo. Only a modest size
                    // difference signals D-pad focus; keep all decoration off.
                    drawCircle(Color(0xFFE50914),
                        (if (seekFocused) 6.5f else 5f).dp.toPx(),
                        Offset(playhead, middle))
                }
            }
            Text(if (durationMs > 0L) nClock(durationMs) else "--:--",
                color = netflixWhite, fontSize = 18.sp)
        }
        // Netflix-style trickplay card follows the playhead, not the centre of
        // the screen. A snapshot is shown only for a time-matching decoded frame.
        // Without provider-supplied storyboards, unseen positions have no image.
        val previewOn = seekFocused || scrubbing ||
            buttonSeekPreviewMs >= 0L || pendingTarget != null
        if (previewOn && durationMs > 0L) {
            val previewAt = when {
                scrubbing -> seekPreviewMs
                buttonSeekPreviewMs >= 0L -> buttonSeekPreviewMs
                else -> pendingTarget ?: positionMs
            }.coerceIn(0L, durationMs)
            val fraction = (previewAt.toDouble() / durationMs).toFloat()
                .coerceIn(0f, 1f)
            val bubbleWidth = 204.dp
            val travel = (maxWidth - 47.dp - 53.dp - 111.dp).coerceAtLeast(0.dp)
            val start = (58.dp + travel * fraction - bubbleWidth / 2f)
                .coerceIn(8.dp, (maxWidth - bubbleWidth - 8.dp).coerceAtLeast(8.dp))
            // Fast hold requests use a thinner contact-sheet cadence but
            // never alter the actual playback seek target or time label.
            val previewSampleMs = if (predictiveTrickplay && heldDirection != null) {
                val heldMs = SystemClock.uptimeMillis() - seekHeldAtMs
                val granularity = NetflixPreviewPolicy.displayGranularity(heldMs)
                (previewAt / granularity) * granularity
            } else previewAt
            LaunchedEffect(previewSampleMs) { onPreviewRequested(previewSampleMs) }
            val frame = frameAt(previewSampleMs)
            Column(
                modifier = Modifier.align(Alignment.BottomStart)
                    .padding(start = start, bottom = timelineBottom + 47.dp)
                    .width(bubbleWidth),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    Modifier.fillMaxWidth().height(115.dp)
                        .background(Color(0xE5101010), RoundedCornerShape(5.dp))
                        .border(1.dp, Color.White.copy(alpha = .62f), RoundedCornerShape(5.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (frame != null) {
                        Image(
                            bitmap = frame.image,
                            contentDescription = "Previously decoded scene at " +
                                nClock(frame.timestampMs),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                        if (!frame.accurate) {
                            Text(
                                (if (frame.storyboard) "Approx. scene near: " else "Nearby frame: ") +
                                    nClock(frame.timestampMs),
                                modifier = Modifier.align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = .83f))
                                    .padding(3.dp),
                                color = Color.White, fontSize = 11.sp,
                            )
                        }
                    } else {
                        Text(previewStatus, color = Color.White.copy(alpha = .7f),
                            fontSize = 12.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(8.dp))
                    }
                }
                Row(
                    Modifier.background(Color(0xDD101010), RoundedCornerShape(4.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(nClock(previewAt), color = Color.White, fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text("-" + nClock(durationMs - previewAt),
                        color = Color.White.copy(alpha = .70f), fontSize = 14.sp)
                }
            }
        }
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = linksBottom),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            if (isEpisode) NText(
                "Episodes", Icons.Filled.VideoLibrary,
                Modifier.focusRequester(episodesFocus)
                    .onFocusChanged { if (it.isFocused) { activeTarget = NetflixTarget.EPISODES; onActivity() } }
                    .focusProperties {
                    up = timelineFocus; right = if (markersEnabled) markersFocus else tracksFocus
                }, onClick = onEpisodes, onActivity = onActivity,
            )
            if (isEpisode && markersEnabled) NText(
                "Mark skips", Icons.Filled.BookmarkAdd,
                Modifier.focusRequester(markersFocus)
                    .onFocusChanged {
                        if (it.isFocused) { activeTarget = NetflixTarget.MARKERS; onActivity() }
                    }
                    .focusProperties {
                        up = timelineFocus; left = episodesFocus; right = tracksFocus
                    },
                onClick = onMarkers, onActivity = onActivity,
            )
            NText(
                "Audio & Subtitles", Icons.Filled.Subtitles,
                Modifier.focusRequester(tracksFocus)
                    .onFocusChanged { if (it.isFocused) { activeTarget = NetflixTarget.TRACKS; onActivity() } }
                    .focusProperties {
                    up = timelineFocus
                    if (markersEnabled) left = markersFocus
                    else if (isEpisode) left = episodesFocus
                    if (isEpisode && nextAvailable) right = nextFocus
                }, onClick = onTracks, onActivity = onActivity,
            )
            if (isEpisode && nextAvailable) NText(
                "Next Episode", Icons.Filled.SkipNext,
                Modifier.focusRequester(nextFocus)
                    .onFocusChanged { if (it.isFocused) { activeTarget = NetflixTarget.NEXT; onActivity() } }
                    .focusProperties {
                    up = timelineFocus; left = tracksFocus
                }, onClick = onNext, onActivity = onActivity,
            )
        }
    }
}

/** Frameless unless focused; white emphasis belongs only on the credits prompt. */
@Composable
private fun NIcon(
    icon: ImageVector, label: String, modifier: Modifier = Modifier,
    size: Int, onClick: () -> Unit, onActivity: () -> Unit,
    opacityUnfocused: Float = 1f,
    buffering: Boolean = false,
    bufferingPercent: Int? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.onFocusChanged { focused = it.isFocused }
            .background(
                if (buffering) Color.Transparent
                else if (focused) Color(0xFF99A8B5).copy(alpha =
                    if (opacityUnfocused < 1f) .08f else .16f)
                else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .clickable { onActivity(); onClick() }
            .padding(5.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (buffering) {
            BufferingControlFace(
                percent = bufferingPercent,
                netflix = true,
                diameterDp = size,
            )
        } else {
            Icon(icon, label, tint = netflixWhite.copy(alpha =
                opacityUnfocused), modifier = Modifier.size(size.dp))
        }
    }
}

/** Restrained graphite/mint focus palette for menus, controls and skip choices. */
@Composable
private fun NText(
    label: String, icon: ImageVector? = null, modifier: Modifier = Modifier,
    onClick: () -> Unit, onActivity: () -> Unit = {}, emphasized: Boolean = false,
    countdownFraction: Float? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(7.dp)
    val base = when {
        focused -> Color(0xF0343C45)
        emphasized -> Color(0xD920262D)
        else -> Color(0xC5181D23)
    }
    val outline = when {
        focused -> Color(0xFFCFD7DC)
        emphasized -> Color(0x888994A0)
        else -> Color(0x446D7882)
    }
    Row(
        modifier.onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(base)
            .drawBehind {
                countdownFraction?.let { fraction ->
                    drawRect(
                        color = Color(0xB067747F),
                        size = Size(size.width * fraction.coerceIn(0f, 1f), size.height),
                    )
                }
            }
            .border(if (focused) 2.dp else 1.dp, outline, shape)
            .clickable { onActivity(); onClick() }
            .padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = Color(0xFFE8F6F2),
            modifier = Modifier.size(22.dp))
        Text(label, color = Color(0xFFEBEFF2),
            fontSize = 16.sp,
            fontWeight = if (focused || emphasized) FontWeight.SemiBold else FontWeight.Medium)
    }
}

/**
 * Each of intro and credits has a visible cancellation action and a 10s
 * progress fill INSIDE the primary action. No auto-skip after cancellation.
 */
@Composable
internal fun NetflixTimedSkipPrompt(
    label: String,
    cancelLabel: String,
    elapsedMs: Long,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onCancel: () -> Unit,
    onSkip: () -> Unit,
    onLeave: () -> Unit = {},
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val fallbackFocus = remember { FocusRequester() }
    val actionFocus = focusRequester ?: fallbackFocus
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(label, actionFocus) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching { actionFocus.requestFocus() }
    }
    Row(
        modifier.onFocusChanged { onFocusChanged(it.hasFocus) }
            .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false
            else when (event.key) {
                Key.DirectionDown -> { onLeave(); true }
                Key.DirectionLeft -> {
                    runCatching { cancelFocus.requestFocus() }
                    true
                }
                Key.DirectionRight -> {
                    runCatching { actionFocus.requestFocus() }
                    true
                }
                else -> false
            }
        },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NText(cancelLabel, modifier = Modifier.focusRequester(cancelFocus),
            onClick = onCancel)
        NText(
            "$label · ${SkipCountdownPolicy.remainingSeconds(elapsedMs)}s",
            icon = Icons.Filled.SkipNext,
            modifier = Modifier.focusRequester(actionFocus),
            onClick = onSkip, emphasized = true,
            countdownFraction = SkipCountdownPolicy.fraction(elapsedMs),
        )
    }
}

/** Compatibility wrapper for old player prompt call-sites. */
@Composable
internal fun NetflixSkipPrompt(
    label: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val fallbackFocus = remember { FocusRequester() }
    NText(label, modifier = modifier.focusRequester(focusRequester ?: fallbackFocus),
        onClick = onClick, emphasized = true)
}

/** Credits prompt and intro use the same accessible, progressive style. */
@Composable
internal fun NetflixCreditsPrompt(
    isEpisode: Boolean,
    nextAvailable: Boolean,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    elapsedMs: Long = 0L,
    onLeave: () -> Unit = {},
    onFocusChanged: (Boolean) -> Unit = {},
) {
    NetflixTimedSkipPrompt(
        label = if (isEpisode && nextAvailable) "Next episode" else "Back to title",
        cancelLabel = "Watch credits",
        elapsedMs = elapsedMs,
        modifier = modifier,
        focusRequester = focusRequester,
        onCancel = onContinue,
        onSkip = onSkip,
        onLeave = onLeave,
        onFocusChanged = onFocusChanged,
    )
}

/**
 * A separate modal window keeps focus away from the underlying transport.
 * Explicit D-pad routing avoids Android TV's occasionally wrong spatial search,
 * especially when there are many audio/subtitle tracks.
 */
@Composable
internal fun TvChoiceDialog(
    heading: String, details: String,
    items: List<Pair<String, () -> Unit>>,
    onClose: () -> Unit,
) {
    val focusTargets = remember(items.size) { List(items.size) { FocusRequester() } }
    var selected by remember(items.size) { mutableStateOf(0) }
    val scroll = rememberScrollState()
    val pixels = with(LocalDensity.current) { 49.dp.toPx() }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        LaunchedEffect(items.size) {
            withFrameNanos { }
            withFrameNanos { }
            if (focusTargets.isNotEmpty()) runCatching { focusTargets[0].requestFocus() }
        }
        LaunchedEffect(selected) {
            val target = (selected * pixels - 100f).toInt().coerceAtLeast(0)
            scroll.animateScrollTo(target.coerceAtMost(scroll.maxValue))
        }
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = .55f))
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp, Key.DirectionLeft -> {
                            selected = (selected - 1).coerceAtLeast(0)
                            focusTargets.getOrNull(selected)?.let { runCatching { it.requestFocus() } }
                            true
                        }
                        Key.DirectionDown, Key.DirectionRight -> {
                            selected = (selected + 1).coerceAtMost((items.size - 1).coerceAtLeast(0))
                            focusTargets.getOrNull(selected)?.let { runCatching { it.requestFocus() } }
                            true
                        }
                        Key.Back, Key.Escape -> { onClose(); true }
                        else -> false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.fillMaxWidth(.62f).heightIn(max = 500.dp)
                    .background(Color(0xFA141414), RoundedCornerShape(10.dp))
                    .padding(21.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(heading, color = Color.White, fontWeight = FontWeight.Bold,
                    fontSize = 22.sp)
                Text(details, color = Color.White.copy(alpha = .74f), fontSize = 14.sp)
                Column(Modifier.weight(1f, fill = false).verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items.forEachIndexed { index, (label, action) ->
                        NText(
                            label,
                            modifier = Modifier.fillMaxWidth().focusRequester(focusTargets[index])
                                .onFocusChanged { if (it.isFocused) selected = index },
                            onClick = action,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ManualSeriesSkipDialog(
    atMs: Long, marks: ManualSeriesSkips, onMark: (SkipMark) -> Unit,
    onClearIntro: () -> Unit, onClearCredits: () -> Unit,
    onClearAll: () -> Unit, onClose: () -> Unit,
) {
    val time = ManualSeriesSkips.clock(atMs)
    TvChoiceDialog(
        heading = "Series skip markers",
        details = "Paused at $time. Set just two episode-relative points." +
            "\n" + marks.display() +
            "\nSkip Intro appears at 0:00 for up to 10 seconds and jumps to its end mark." +
            "\nSkip Credits appears from the outro mark and opens the next episode." +
            "\nBoth marks apply to this series until cleared.",
        items = listOf(
            "Skip intro marker · set endpoint ($time)" to { onMark(SkipMark.INTRO_END) },
            "Skip end credits marker · set start ($time)" to { onMark(SkipMark.CREDITS_START) },
            "Clear Skip intro marker" to onClearIntro,
            "Clear Skip end credits marker" to onClearCredits,
            "Clear both marks for this series" to onClearAll,
            "Cancel" to onClose,
        ),
        onClose = onClose,
    )
}

@Composable
internal fun NetflixTrackOptions(
    audio: List<Pair<String, () -> Unit>>,
    subtitles: List<Pair<String, () -> Unit>>,
    onClose: () -> Unit,
) {
    TvChoiceDialog(
        heading = "Audio & Subtitles",
        details = "Select a track using the D-pad. Back closes this menu.",
        items = audio.map { (name, select) -> ("Audio  •  " + name) to select } +
            subtitles.map { (name, select) -> ("Subtitles  •  " + name) to select } +
            listOf("Done" to onClose),
        onClose = onClose,
    )
}

private fun nClock(ms: Long): String {
    val sec = ms.coerceAtLeast(0L) / 1000L
    val h = sec / 3600L
    val m = (sec % 3600L) / 60L
    val s = sec % 60L
    return if (h > 0L) "%d:%02d:%02d".format(h,m,s) else "%d:%02d".format(m,s)
}
