package app.sokyiptv.ui.vod

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
 * Shared, centre-screen buffer feedback for native VLC and Media3 in BOTH themes.
 * The fill is determinate only when the active engine supplies a real percentage.
 * Media3 reports whole-item video buffered (not the percentage of a rebuffer event).
 * Never invent a count or run a fake progress animation.
 */
@Composable
internal fun PlayerBufferPercentOverlay(
    percent: Int?,
    label: String,
    netflix: Boolean,
    modifier: Modifier = Modifier,
) {
    val actual = percent?.takeIf { it in 0..100 }
    val accent = if (netflix) Color(0xFFE50914) else CinemaBlue
    Column(
        modifier = modifier
            .background(Color(0xCB101114), RoundedCornerShape(18.dp))
            .padding(horizontal = 28.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(98.dp), contentAlignment = Alignment.Center) {
            if (actual != null) {
                Canvas(Modifier.size(88.dp)) {
                    val stroke = Stroke(8.dp.toPx(), cap = StrokeCap.Round)
                    drawArc(Color.White.copy(alpha = .19f), 0f, 360f, false, style = stroke)
                    if (actual > 0) drawArc(
                        accent, -90f, actual * 3.6f, false, style = stroke
                    )
                }
                Text(
                    "$actual%",
                    color = Color.White,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(74.dp),
                    color = accent,
                    strokeWidth = 6.dp,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(label, color = Color.White, fontSize = 16.sp)
    }
}
