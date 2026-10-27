package app.sokyiptv.ui.vod

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The loading state of the PLAY/PAUSE CONTROL itself, not a second, separate
 * warning panel. Uses actual engine-reported progress only; otherwise spins.
 */
@Composable
internal fun BufferingControlFace(
    percent: Int?,
    netflix: Boolean,
    diameterDp: Int = 62,
    modifier: Modifier = Modifier,
) {
    val actual = percent?.takeIf { it in 0..100 }
    val accent = if (netflix) Color(0xFFE50914) else CinemaBlue
    Box(modifier.size(diameterDp.dp), contentAlignment = Alignment.Center) {
        if (actual != null) {
            Canvas(Modifier.size((diameterDp - 8).dp)) {
                val stroke = Stroke(5.dp.toPx(), cap = StrokeCap.Round)
                drawArc(Color.White.copy(alpha = .22f), 0f, 360f, false, style = stroke)
                if (actual > 0) drawArc(
                    accent, -90f, actual * 3.6f, false, style = stroke
                )
            }
            Text(
                "$actual%",
                color = Color.White,
                fontSize = (diameterDp * .27f).sp,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size((diameterDp - 10).dp),
                color = accent, strokeWidth = 5.dp,
            )
        }
    }
}

/**
 * When the playback controls have faded out, keep the same play-button loading
 * face visible at the screen centre WITHOUT reopening transport controls.
 */
@Composable
internal fun PlayerBufferPercentOverlay(
    percent: Int?,
    label: String,
    netflix: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .background(Color(0x90101010), RoundedCornerShape(16.dp))
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BufferingControlFace(percent = percent, netflix = netflix, diameterDp = 74)
    }
}
