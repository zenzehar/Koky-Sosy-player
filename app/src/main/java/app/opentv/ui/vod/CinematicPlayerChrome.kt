/*
 * Koky & Sosy Player cinematic VOD chrome. Design implementation follows user supplied
 * 16:9 reference (18 Oct 2026); this file owns visuals only, never VLC/Media3 seeking.
 * GPL-3.0-or-later.
 */
package app.opentv.ui.vod

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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
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

/** Reference: single pill at the top right, no extra autoplay button in transport. */
@Composable
internal fun CinematicAutoplay(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .shadow(8.dp, RoundedCornerShape(23.dp))
            .clip(RoundedCornerShape(23.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF0F141F), Color(0xFF1C2534))))
            .onFocusChanged { focused = it.isFocused }
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) CinemaBlue else Color.White.copy(alpha = .2f),
                RoundedCornerShape(23.dp),
            )
            .padding(start = 16.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text("Autoplay next episode", color = Color.White, fontSize = 15.sp)
        Switch(
            checked = enabled,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Color(0xFF1786FF),
                checkedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFF444B5C),
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = Color(0xFF616B7C),
            ),
            modifier = Modifier.semantics { contentDescription = "Autoplay next episode" },
        )
    }
}

/** Exactly five buttons for episodes, three for films. No additional standard-view buttons. */
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
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (isEpisode) {
            CinematicControl(
                icon = Icons.Filled.SkipPrevious, caption = "Previous\nepisode",
                enabled = previousEnabled, onClick = onPrevious,
            )
        }
        CinematicControl(
            icon = Icons.Filled.Replay10, caption = "Rewind\n10 seconds",
            enabled = true, onClick = onRewind,
        )
        CinematicControl(
            icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            caption = if (paused) "Resume\n(D-pad click)" else "Pause / Resume\n(D-pad click)",
            main = true, focusRequester = pauseRequester, onClick = onTogglePause,
        )
        CinematicControl(
            icon = Icons.Filled.Forward10, caption = "Forward\n10 seconds",
            enabled = true, onClick = onForward,
        )
        if (isEpisode) {
            CinematicControl(
                icon = Icons.Filled.SkipNext, caption = "Next\nepisode",
                enabled = nextEnabled, onClick = onNext,
            )
        }
    }
}

@Composable
private fun CinematicControl(
    icon: ImageVector,
    caption: String,
    enabled: Boolean = true,
    main: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)
    val emphasized = main || focused
    val ring = if (focused) Color.White else CinemaPaleBlue
    Column(
        modifier = Modifier
            .width(if (main) 94.dp else 84.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = caption.replace("\n", " ") },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(if (main) 77.dp else 69.dp)
                .shadow(
                    elevation = if (emphasized) 15.dp else 7.dp,
                    shape = shape,
                    ambientColor = if (emphasized) CinemaBlue else Color.Black,
                    spotColor = if (emphasized) CinemaBlue else Color.Black,
                )
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF303643), CinemaPanel, Color(0xFF151B26)),
                    ),
                )
                .border(
                    width = if (emphasized) 2.5.dp else 1.dp,
                    color = if (emphasized) ring else Color.White.copy(alpha = .19f),
                    shape = shape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(if (main) 39.dp else 33.dp),
            )
        }
        Spacer(Modifier.size(6.dp))
        Box(
            Modifier.size(width = 1.dp, height = 10.dp)
                .background(Color.White.copy(alpha = .85f)),
        )
        Box(
            Modifier.size(4.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White),
        )
        Spacer(Modifier.size(4.dp))
        Text(
            caption,
            color = Color.White,
            textAlign = TextAlign.Center,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            maxLines = 2,
        )
    }
}
