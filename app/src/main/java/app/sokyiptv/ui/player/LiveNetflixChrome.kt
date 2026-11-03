/* GPL-3.0-or-later. Netflix-style LIVE chrome (no changes to playback engine). */
package app.sokyiptv.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import app.sokyiptv.ui.isHandheldPortrait

/**
 * Mirrors the Netflix-like VOD control hierarchy and neutral/frameless focus
 * palette. Live-specific controls are additional: channels, quality, recording,
 * favourite, aspect, PiP. A progress/seek timeline exists ONLY for real DVR.
 *
 * No implicit channel tuning, fake seeking, extra video decoders, or EPG refresh.
 */
@Composable
internal fun LiveNetflixChrome(
    title: String,
    paused: Boolean,
    buffering: Boolean,
    seekable: Boolean,
    positionMs: Long,
    durationMs: Long,
    favourite: Boolean,
    recording: Boolean,
    hasQualityVariants: Boolean,
    allowPip: Boolean,
    playFocus: FocusRequester,
    onActivity: () -> Unit,
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onSeek: (Long) -> Unit,
    onGoLive: () -> Unit,
    onChannels: () -> Unit,
    onFavourite: () -> Unit,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    onQuality: () -> Unit,
    onAspect: () -> Unit,
    onRecord: () -> Unit,
    onPip: () -> Unit,
) {
    val portrait = isHandheldPortrait()
    val realDvr = seekable && durationMs > 0L
    var scrub by remember(durationMs) { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val bottomFocus = remember { FocusRequester() }
    val bottomListState = rememberLazyListState()
    val bottomActions = buildList {
        add(LiveAction("channels", "Channels", Icons.Default.Tv, onChannels))
        add(LiveAction("favourite", if (favourite) "Unfavourite" else "Favourite",
            if (favourite) Icons.Default.Star else Icons.Outlined.StarBorder,
            onFavourite, if (favourite) Color(0xFFFFD566) else Color.White))
        add(LiveAction("audio", "Audio", Icons.Default.Audiotrack, onAudio))
        add(LiveAction("subtitles", "Subtitles", Icons.Default.Subtitles, onSubtitles))
        if (hasQualityVariants)
            add(LiveAction("quality", "Quality", Icons.Default.HighQuality, onQuality))
        add(LiveAction("aspect", "Aspect", Icons.Default.AspectRatio, onAspect))
        add(LiveAction("record", if (recording) "Stop recording" else "Record",
            Icons.Default.FiberManualRecord, onRecord,
            if (recording) Color(0xFFE85050) else Color.White))
        if (allowPip)
            add(LiveAction("pip", "Picture in picture",
                Icons.Default.PictureInPictureAlt, onPip))
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
        )
    ) {
        val large = maxHeight > 380.dp
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            Color.Black.copy(alpha = .27f),
            Color.Transparent, Color.Transparent,
            Color.Black.copy(alpha = .67f)
        ))))
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .padding(horizontal = if (portrait) 12.dp else 38.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveNetflixButton(Icons.Default.ArrowBack, "Back", onActivity, onBack)
            Spacer(Modifier.width(16.dp))
            Text(title, modifier = Modifier.weight(1f), color = Color.White,
                fontSize = if (portrait) 17.sp else 22.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text("LIVE", color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = 12.sp, modifier = Modifier.background(
                    Color(0xFFB71221), RoundedCornerShape(4.dp)
                ).padding(horizontal = 9.dp, vertical = 5.dp))
        }
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(if (portrait) 20.dp else 60.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveNetflixButton(
                if (realDvr) Icons.Default.FastRewind else Icons.Default.SkipPrevious,
                if (realDvr) "Rewind" else "Previous channel",
                onActivity,
                if (realDvr) onRewind else onPrevious,
                iconDp = if (large) 47 else 36,
                downFocus = bottomFocus,
            )
            LiveNetflixButton(
                if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                if (paused) "Play" else "Pause",
                onActivity, onToggle,
                iconDp = if (large) 68 else 49,
                focus = playFocus,
                opacity = if (buffering) .45f else .83f,
                downFocus = bottomFocus,
            )
            LiveNetflixButton(
                if (realDvr) Icons.Default.FastForward else Icons.Default.SkipNext,
                if (realDvr) "Forward" else "Next channel",
                onActivity,
                if (realDvr) onForward else onNext,
                iconDp = if (large) 47 else 36,
                downFocus = bottomFocus,
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(horizontal = if (portrait) 12.dp else 32.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (large) 12.dp else 5.dp),
        ) {
            if (realDvr) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("DVR", color = Color(0xFFDAE1E8), fontSize = 12.sp)
                    Spacer(Modifier.width(12.dp))
                    Slider(
                        value = if (dragging) scrub else
                            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f),
                        onValueChange = { scrub = it; dragging = true; onActivity() },
                        onValueChangeFinished = {
                            onSeek((durationMs * scrub.coerceIn(0f, 1f)).toLong())
                            dragging = false
                        },
                        modifier = Modifier.weight(1f),
                    )
                    LiveNetflixButton(Icons.Default.LiveTv, "Go live", onActivity, onGoLive,
                        iconDp = 23)
                }
            } else {
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    drawLine(Color.White.copy(alpha = .55f),
                        Offset(0f, size.height / 2f),
                        Offset(size.width, size.height / 2f),
                        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    drawCircle(Color(0xFFE50914), 4.dp.toPx(),
                        Offset(size.width, size.height / 2f))
                }
            }
            // Compose only visible action buttons. Focus naturally walks through
            // off-screen items and the focused item scrolls into view.
            LazyRow(
                state = bottomListState,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(if (portrait) 10.dp else 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(bottomActions, key = { _, action -> action.id }) { index, action ->
                    LiveNetflixButton(
                        icon = action.icon, label = action.title,
                        onActivity = onActivity, onClick = action.click,
                        iconDp = 26,
                        focus = if (index == 0) bottomFocus else null,
                        upFocus = playFocus,
                        tint = action.tint,
                    )
                }
            }
        }
    }
}

/** Netflix-like frameless icon; only the focused item gets a subtle highlight. */
@Composable
private fun LiveNetflixButton(
    icon: ImageVector,
    label: String,
    onActivity: () -> Unit,
    onClick: () -> Unit,
    iconDp: Int = 32,
    focus: FocusRequester? = null,
    opacity: Float = 1f,
    tint: Color = Color.White,
    downFocus: FocusRequester? = null,
    upFocus: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onActivity()
            }
            .then(Modifier.focusProperties {
                if (downFocus != null) down = downFocus
                if (upFocus != null) up = upFocus
            })
            .background(
                if (focused) Color.White.copy(alpha = .12f) else Color.Transparent,
                RoundedCornerShape(7.dp)
            )
            .clickable { onActivity(); onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint.copy(alpha = opacity),
            modifier = Modifier.size(iconDp.dp))
        if (iconDp < 35)
            Text(label, color = Color.White.copy(alpha = .9f), fontSize = 11.sp,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

private data class LiveAction(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val click: () -> Unit,
    val tint: Color = Color.White,
)
