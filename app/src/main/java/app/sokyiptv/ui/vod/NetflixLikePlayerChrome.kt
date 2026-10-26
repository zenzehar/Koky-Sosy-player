/* GPL-3.0-or-later. Netflix-inspired frameless VOD chrome with explicit TV focus. */
package app.sokyiptv.ui.vod

import android.os.SystemClock
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Subtitles
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
import app.sokyiptv.player.ManualSeriesSkips
import app.sokyiptv.player.SkipMark
import app.sokyiptv.player.SeekDirection
import app.sokyiptv.player.TvSeekPolicy
import kotlin.math.max
import kotlin.math.min

private val netflixRed = Color(0xFFE50914)
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
    nextAvailable: Boolean,
    playFocus: FocusRequester,
    timelineFocus: FocusRequester,
    tracksFocus: FocusRequester,
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
    val nextFocus = remember { FocusRequester() }
    var scrubbing by remember { mutableStateOf(false) }
    var seekPreviewMs by remember { mutableLongStateOf(0L) }
    var seekOriginMs by remember { mutableLongStateOf(0L) }
    var seekHeldAtMs by remember { mutableLongStateOf(0L) }
    var heldDirection by remember { mutableStateOf<SeekDirection?>(null) }
    var seekFocused by remember { mutableStateOf(false) }
    var activeTarget by remember { mutableStateOf(NetflixTarget.PLAY) }
    val displayMs = if (scrubbing) seekPreviewMs else positionMs

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
            // Timeline owns Left and Right for real +/-10s and hold-to-accelerate.
            if (activeTarget == NetflixTarget.TIMELINE &&
                direction in setOf(NetflixDirection.LEFT, NetflixDirection.RIGHT))
                return@onPreviewKeyEvent false
            val next = NetflixFocusGraph.next(activeTarget, direction, isEpisode, nextAvailable)
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
                onClick = { onSeek(TvSeekPolicy.tap(positionMs, durationMs, SeekDirection.BACKWARD)) },
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
                opacityUnfocused = .12f,
            )
            NIcon(
                Icons.Filled.Forward10, "Forward 10 seconds",
                Modifier.focusRequester(forwardFocus)
                    .onFocusChanged { if (it.isFocused) { activeTarget = NetflixTarget.FORWARD; onActivity() } }
                    .focusProperties {
                    left = playFocus; up = closeFocus; down = timelineFocus
                },
                58,
                onClick = { onSeek(TvSeekPolicy.tap(positionMs, durationMs, SeekDirection.FORWARD)) },
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
                    .onFocusChanged { seekFocused = it.isFocused
                        if (it.isFocused) { activeTarget = NetflixTarget.TIMELINE; onActivity() }
                    }
                    .onPreviewKeyEvent { event ->
                        val direction = when (event.key) {
                            Key.DirectionLeft -> SeekDirection.BACKWARD
                            Key.DirectionRight -> SeekDirection.FORWARD
                            else -> null
                        }
                        if (direction == null) return@onPreviewKeyEvent false
                        onActivity()
                        if (event.type == KeyEventType.KeyDown) {
                            if (durationMs <= 0L) return@onPreviewKeyEvent true
                            if (!scrubbing || heldDirection != direction) {
                                seekOriginMs = if (scrubbing) seekPreviewMs else positionMs
                                seekHeldAtMs = SystemClock.uptimeMillis()
                                heldDirection = direction
                                seekPreviewMs = TvSeekPolicy.tap(seekOriginMs, durationMs, direction)
                                scrubbing = true
                            } else {
                                val held = SystemClock.uptimeMillis() - seekHeldAtMs
                                seekPreviewMs = if (held > TvSeekPolicy.HOLD_THRESHOLD_MS) {
                                    val target = TvSeekPolicy.heldPreview(
                                        seekOriginMs, held, direction, durationMs,
                                    )
                                    if (direction == SeekDirection.FORWARD) max(seekPreviewMs, target)
                                    else min(seekPreviewMs, target)
                                } else seekPreviewMs
                            }
                            true
                        } else if (event.type == KeyEventType.KeyUp) {
                            if (scrubbing && durationMs > 0L) onSeek(seekPreviewMs)
                            scrubbing = false
                            heldDirection = null
                            true
                        } else false
                    }
                    .semantics {
                        contentDescription = "Timeline. Left and right seek ten seconds; hold to accelerate."
                    }
                    .focusable(),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxWidth().height(23.dp)) {
                    val left = 11.dp.toPx()
                    val right = size.width - left
                    val middle = size.height / 2f
                    drawLine(Color(0xFF646464), Offset(left, middle),
                        Offset(right, middle), 3.dp.toPx(), cap = StrokeCap.Round)
                    val fraction = if (durationMs > 0L)
                        (displayMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f) else 0f
                    val playhead = left + (right-left) * fraction
                    drawLine(netflixRed, Offset(left, middle), Offset(playhead, middle),
                        4.dp.toPx(), cap = StrokeCap.Round)
                    drawCircle(netflixRed,
                        (if (seekFocused) 11 else 9).dp.toPx(),
                        Offset(playhead, middle))
                    if (seekFocused) drawCircle(netflixWhite,
                        12.dp.toPx(), Offset(playhead, middle),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                }
            }
            Text(if (durationMs > 0L) nClock(durationMs) else "--:--",
                color = netflixWhite, fontSize = 18.sp)
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
                    up = timelineFocus; right = tracksFocus
                }, onClick = onEpisodes, onActivity = onActivity,
            )
            NText(
                "Audio & Subtitles", Icons.Filled.Subtitles,
                Modifier.focusRequester(tracksFocus)
                    .onFocusChanged { if (it.isFocused) { activeTarget = NetflixTarget.TRACKS; onActivity() } }
                    .focusProperties {
                    up = timelineFocus
                    if (isEpisode) left = episodesFocus
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
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.onFocusChanged { focused = it.isFocused }
            .background(
                if (focused) netflixWhite.copy(alpha = if (opacityUnfocused < 1f) .06f else .17f)
                else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .clickable { onActivity(); onClick() }
            .padding(5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = netflixWhite.copy(alpha =
            opacityUnfocused), modifier = Modifier.size(size.dp))
    }
}

@Composable
private fun NText(
    label: String, icon: ImageVector? = null, modifier: Modifier = Modifier,
    onClick: () -> Unit, onActivity: () -> Unit = {}, emphasized: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(3.dp)
    val light = focused || emphasized
    Row(
        modifier.onFocusChanged { focused = it.isFocused }
            .background(if (light) Color.White else Color.Transparent, shape)
            .clickable { onActivity(); onClick() }
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (light) Color.Black else netflixWhite,
            modifier = Modifier.size(22.dp))
        Text(label, color = if (light) Color.Black else netflixWhite,
            fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun NetflixSkipPrompt(
    label: String, modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    val promptFocus = remember { FocusRequester() }
    LaunchedEffect(label) {
        withFrameNanos { }
        runCatching { promptFocus.requestFocus() }
    }
    NText(label, modifier = modifier
        .focusRequester(promptFocus)
        .background(Color(0xC11D1D1D), RoundedCornerShape(4.dp)),
        onClick = onClick, emphasized = true)
}

/** Exact two-action credits composition; focus stays reachable without a modal trap. */
@Composable
internal fun NetflixCreditsPrompt(
    isEpisode: Boolean,
    nextAvailable: Boolean,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionFocus = remember { FocusRequester() }
    LaunchedEffect(nextAvailable, isEpisode) {
        withFrameNanos { }
        runCatching { actionFocus.requestFocus() }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        NText("Watch Credits", modifier = Modifier
            .background(Color(0xC2505050), RoundedCornerShape(4.dp)),
            onClick = onContinue)
        NText(
            if (isEpisode && nextAvailable) "Next Episode" else "Back to Title",
            icon = if (isEpisode && nextAvailable) Icons.Filled.PlayArrow else null,
            modifier = Modifier.focusRequester(actionFocus)
                .background(Color.White, RoundedCornerShape(4.dp)),
            onClick = onSkip, emphasized = true,
        )
    }
}

/**
 * A separate modal window keeps focus away from the underlying transport.
 * Explicit D-pad routing avoids Android TV's occasionally wrong spatial search,
 * especially when there are many audio/subtitle tracks.
 */
@Composable
private fun TvChoiceDialog(
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
        details = "Paused at $time. Mark the beginning and ending in this episode." +
            "\n" + marks.display() + "\nApplies to all episodes of this series until cleared.",
        items = listOf(
            "Mark intro START at $time" to { onMark(SkipMark.INTRO_START) },
            "Mark intro END at $time" to { onMark(SkipMark.INTRO_END) },
            "Mark credits START at $time" to { onMark(SkipMark.CREDITS_START) },
            "Mark credits END at $time" to { onMark(SkipMark.CREDITS_END) },
            "Clear intro markers" to onClearIntro,
            "Clear credits markers" to onClearCredits,
            "Clear all markers for this series" to onClearAll,
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
