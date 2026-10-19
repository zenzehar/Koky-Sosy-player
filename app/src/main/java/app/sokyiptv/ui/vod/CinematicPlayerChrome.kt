/*
 * Soky IPTV Pro cinematic VOD chrome. Design implementation follows user supplied
 * 16:9 reference (18 Oct 2026); this file owns visuals only, never VLC/Media3 seeking.
 * GPL-3.0-or-later.
 */
package app.sokyiptv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val CinemaBlue = Color(0xFF65B6FF)
internal val CinemaPaleBlue = Color(0xFFA9D8FF)
internal val CinemaPanel = Color(0xFF1B202D)
internal val CinemaTrack = Color(0xFF575C68)

/** Pure title parsing: the provider sometimes prefixes and suffixes an episode number. */
internal data class CinematicTitleParts(val title: String, val episode: String?) {
    companion object {
        private val EP = Regex("""(?i)\bS(\d{1,2})\s*E(\d{1,3})\b""")
        private val SPACED = Regex("""(?i)\bSeason\s+(\d+)\s+Episode\s+(\d+)\b""")
        fun parse(raw: String, isEpisode: Boolean): CinematicTitleParts {
            if (!isEpisode) return CinematicTitleParts(raw, null)
            val match = EP.find(raw) ?: SPACED.find(raw)
                ?: return CinematicTitleParts(raw, null)
            val season = match.groupValues[1].toIntOrNull() ?: return CinematicTitleParts(raw, null)
            val number = match.groupValues[2].toIntOrNull() ?: return CinematicTitleParts(raw, null)
            // Remove repeated episode markers, not the series name, even when the provider
            // returns "S01E05 - اللعبة S01 E05" or Arabic punctuation.
            val cleaned = SPACED.replace(EP.replace(raw, ""), "")
                .trim(' ', '-', '–', '—', ':', '·', '/', '،')
                .replace(Regex("""\s{2,}"""), " ")
            return CinematicTitleParts(
                cleaned.ifBlank { raw },
                "S%02d E%02d".format(java.util.Locale.US, season, number),
            )
        }
    }
}

/** Full-screen gradient leaves the video visible and darkens only the control areas. */
@Composable
internal fun CinematicScrim() {
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.42f),
                .23f to Color.Black.copy(alpha = 0.04f),
                .46f to Color.Transparent,
                .68f to Color.Black.copy(alpha = 0.16f),
                1f to Color.Black.copy(alpha = 0.92f),
            ),
        ),
    )
}

/** Reference: title and episode in top-left, with a rounded black progress-time pill. */
@Composable
internal fun CinematicTitle(
    rawTitle: String,
    isEpisode: Boolean,
    elapsedText: String,
    durationText: String,
    modifier: Modifier = Modifier,
) {
    val parts = remember(rawTitle, isEpisode) { CinematicTitleParts.parse(rawTitle, isEpisode) }
    Column(
        modifier = modifier.widthIn(max = 520.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            parts.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontSize = 35.sp,
            lineHeight = 39.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        if (parts.episode != null) {
            Text(parts.episode, color = Color.White, fontSize = 21.sp)
        }
        Spacer(Modifier.size(4.dp))
        Box(
            Modifier.clip(RoundedCornerShape(24.dp))
                .background(Color.Black.copy(alpha = .82f))
                .padding(horizontal = 14.dp, vertical = 5.dp),
        ) {
            Text(
                "$elapsedText / $durationText",
                color = Color(0xFFD3D7E2),
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** Top-right switch is one D-pad focus target, not an unfocusable Row + inner Switch. */
@Composable
internal fun CinematicAutoplay(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(23.dp)
    Row(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.hasFocus
                onFocusChanged(it.hasFocus)
            }
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(Color(0xE60F141F), Color(0xE61C2534))))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) CinemaBlue else Color.White.copy(alpha = .2f),
                shape = shape,
            )
            // toggleable makes the WHOLE pill a keyboard/TV remote focus target.
            // Switch has no independent focus/click target, avoiding remote dead ends.
            .toggleable(value = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(start = 16.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text("Autoplay next episode", color = Color.White, fontSize = 15.sp)
        Switch(
            checked = enabled,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Color(0xFF1786FF),
                checkedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFF444B5C),
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = Color(0xFF616B7C),
            ),
            modifier = Modifier.semantics { contentDescription = "Autoplay status" },
        )
    }
}

/**
 * Exactly five symbols for episodes, three for films. No labels, stems or dots.
 * Buttons align along one baseline and sit immediately above the seek timeline.
 * The visual treatment never changes VLC/Media3 player timing or seek implementation.
 */
@Composable
internal fun CinematicTransport(
    isEpisode: Boolean,
    paused: Boolean,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    onPrevious: () -> Unit,
    onRewind: () -> Unit,
    onTogglePause: () -> Unit,
    onForward: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    pauseRequester: FocusRequester? = null,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(19.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isEpisode) {
            CinematicControl(Icons.Filled.SkipPrevious, "Previous episode",
                enabled = previousEnabled, onClick = onPrevious)
        }
        CinematicControl(Icons.Filled.Replay10, "Rewind 10 seconds", onClick = onRewind)
        CinematicControl(
            if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            if (paused) "Resume" else "Pause",
            focusRequester = pauseRequester,
            onClick = onTogglePause,
        )
        CinematicControl(Icons.Filled.Forward10, "Forward 10 seconds", onClick = onForward)
        if (isEpisode) {
            CinematicControl(Icons.Filled.SkipNext, "Next episode",
                enabled = nextEnabled, onClick = onNext)
        }
    }
}

/** Focus/click target matches the *exact* 74dp rounded-square button surface. */
@Composable
private fun CinematicControl(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    // 0.26 opacity = 74% transparent. Focus never increases fill past 30%.
    val fill = if (focused) Color(0xFF1A2435).copy(alpha = 0.30f)
        else Color(0xFF1A2435).copy(alpha = CinemaButtonFillAlpha)
    Box(
        modifier = Modifier
            .size(74.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(fill)
            .border(
                width = if (focused) 2.5.dp else 1.dp,
                color = if (focused) CinemaBlue else Color.White.copy(alpha = 0.27f),
                shape = shape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = if (enabled) 1f else 0.38f),
            modifier = Modifier.size(35.dp),
        )
    }
}

/** Background fill opacity: 26%, giving at least 70% transparency. */
internal const val CinemaButtonFillAlpha = 0.26f


/** Explicit logical D-pad levels: never infer the next level from asynchronously
 * delivered focus callbacks (which previously caused seek/autoplay bouncing).
 */
internal enum class CinematicFocusLevel { AUTOPLAY, CONTROLS, SEEK }

internal fun moveCinematicFocus(
    current: CinematicFocusLevel,
    isEpisode: Boolean,
    up: Boolean,
): CinematicFocusLevel = if (up) {
    when (current) {
        CinematicFocusLevel.SEEK -> CinematicFocusLevel.CONTROLS
        CinematicFocusLevel.CONTROLS -> if (isEpisode)
            CinematicFocusLevel.AUTOPLAY else CinematicFocusLevel.CONTROLS
        CinematicFocusLevel.AUTOPLAY -> CinematicFocusLevel.AUTOPLAY
    }
} else {
    when (current) {
        CinematicFocusLevel.AUTOPLAY -> CinematicFocusLevel.CONTROLS
        CinematicFocusLevel.CONTROLS -> CinematicFocusLevel.SEEK
        CinematicFocusLevel.SEEK -> CinematicFocusLevel.SEEK
    }
}
