package app.sokyiptv.ui.vod

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The IPTV-Smarters-style VOD connection indicator is passive: no Dialog,
 * Card, buttons, focusRequester, clickable, or pointerInput. It cannot steal
 * a D-pad direction, touch gesture, accessibility focus or player control.
 * A null status animates out completely after playback is stable.
 */
@Composable
internal fun VodConnectionRecoveryOverlay(
    reason: String?,
    attempts: Int,
    maxRetries: Int,
    retryCountdown: Int,
    skipCountdown: Int,
    retrying: Boolean,
    stopped: Boolean,
    showExhaustedHint: Boolean = true,
) {
    val visible = reason != null && (!stopped || showExhaustedHint)
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { -it / 8 },
        exit = fadeOut(tween(240)),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.widthIn(max = 440.dp)
                    .background(Color.Black.copy(alpha = .63f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                val count = maxRetries.coerceIn(1, 10)
                val primary = when {
                    stopped -> "Unable to reconnect · $attempts/$count"
                    skipCountdown > 0 -> "Reconnection failed · $attempts/$count"
                    retryCountdown > 0 -> "Attempting to reconnect · ${(attempts + 1).coerceAtMost(count)}/$count"
                    retrying -> "Attempting to reconnect · $attempts/$count"
                    else -> "Reconnecting · ${(attempts + 1).coerceAtMost(count)}/$count"
                }
                Text(primary, color = Color.White, fontSize = 16.sp,
                    fontWeight = FontWeight.Medium, maxLines = 2,
                    textAlign = TextAlign.Center)
                val secondary = when {
                    stopped -> "OK to retry  ·  Back to exit"
                    skipCountdown > 0 -> "Next episode in ${skipCountdown}s · Back to cancel"
                    retryCountdown > 0 -> "Next attempt in ${retryCountdown}s"
                    retrying -> "Checking stream…"
                    else -> "Restoring playback…"
                }
                Text(secondary, color = Color(0xFFCED9E3), fontSize = 12.sp,
                    maxLines = 2, textAlign = TextAlign.Center)
            }
        }
    }
}
