package app.sokyiptv.ui.vod

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * IPTV-style unobtrusive text on video DURING retries. No popup or focus node
 * while video is still reconnecting. On exhaustion only, two small inline
 * actions are mounted, focusable by both TV remote and touch. They never cover
 * the seekbar or category/episode navigation.
 */
@Composable
internal fun VodConnectionRecoveryOverlay(
    reason: String?,
    attempts: Int,
    maxRetries: Int,
    retryCountdown: Int,
    checkCountdown: Int,
    skipCountdown: Int,
    retrying: Boolean,
    stopped: Boolean,
    showExhaustedHint: Boolean = true,
    onRetry: () -> Unit,
    onExit: () -> Unit,
) {
    // A success callback makes reason null and removes all text/actions.
    val visible = reason != null
    val showActions = visible && (stopped || skipCountdown > 0)
    val retryFocus = remember { FocusRequester() }
    var retryFocused by remember { mutableStateOf(false) }
    var exitFocused by remember { mutableStateOf(false) }

    LaunchedEffect(showActions) {
        if (showActions) {
            delay(90L)
            runCatching { retryFocus.requestFocus() }
        }
    }

    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(220)),
    ) {
        Box(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 450.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val limit = maxRetries.coerceIn(1, 10)
                val primary = when {
                    stopped -> "Unable to reconnect · $attempts/$limit"
                    skipCountdown > 0 -> "Reconnection failed · $attempts/$limit"
                    retryCountdown > 0 ->
                        "Attempting to reconnect · ${(attempts + 1).coerceAtMost(limit)}/$limit"
                    retrying -> "Attempting to reconnect · $attempts/$limit"
                    else -> "Reconnecting · ${(attempts + 1).coerceAtMost(limit)}/$limit"
                }
                val shadow = TextStyle(shadow = Shadow(
                    color = Color.Black, offset = Offset(1.5f, 2.5f), blurRadius = 7f))
                Text(
                    primary,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    style = shadow,
                )
                val details = when {
                    skipCountdown > 0 -> "Next episode in ${skipCountdown}s"
                    stopped -> "Choose Retry or Exit"
                    retryCountdown > 0 -> "Next attempt in ${retryCountdown}s"
                    retrying && checkCountdown > 0 ->
                        "Checking stream · up to ${checkCountdown}s remaining"
                    retrying -> "Checking stream…"
                    else -> "Restoring playback…"
                }
                Text(
                    details,
                    color = Color(0xFFE7EDF4),
                    fontSize = 12.sp,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    style = shadow,
                )
                if (showActions) {
                    // Real focus targets exist ONLY after automatic attempts fail.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val retryShape = RoundedCornerShape(9.dp)
                        Button(
                            onClick = onRetry,
                            shape = retryShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (retryFocused)
                                    Color(0xFF4B6FAC) else Color(0xE52A4169)),
                            modifier = Modifier
                                .focusRequester(retryFocus)
                                .onFocusChanged { retryFocused = it.isFocused }
                                .border(if (retryFocused) 2.dp else 0.dp,
                                    Color(0xFFE7F3FF), retryShape),
                        ) { Text("Retry", maxLines = 1) }
                        OutlinedButton(
                            onClick = onExit,
                            shape = retryShape,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.White),
                            modifier = Modifier
                                .onFocusChanged { exitFocused = it.isFocused }
                                .border(if (exitFocused) 2.dp else 0.dp,
                                    Color(0xFFE7F3FF), retryShape),
                        ) { Text("Exit", maxLines = 1) }
                    }
                }
            }
        }
    }
}
