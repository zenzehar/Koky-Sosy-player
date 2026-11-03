package app.sokyiptv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal const val MAX_LIVE_RETRIES = 5

/** Five visible attempts and D-pad-accessible intervention. No recording mutation. */
@Composable
internal fun LiveRecoveryOverlay(
    title: String,
    message: String,
    attempted: Int,
    retryCountdown: Int,
    skipCountdown: Int,
    waiting: Boolean,
    canSkip: Boolean,
    actionFocus: FocusRequester,
    onRetryNow: () -> Unit,
    onWait: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .80f)),
        contentAlignment = Alignment.Center) {
        Card(modifier = Modifier.fillMaxWidth(.86f), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Live TV connection", style = MaterialTheme.typography.titleLarge)
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(message, style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center, maxLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(MAX_LIVE_RETRIES) { i ->
                        Box(Modifier.size(width = 32.dp, height = 7.dp).background(
                            if (i < attempted) Color(0xFF57D8CC)
                            else MaterialTheme.colorScheme.outline.copy(alpha = .4f),
                            RoundedCornerShape(6.dp)
                        ))
                    }
                }
                Text(
                    when {
                        waiting -> "Retries complete ($attempted/$MAX_LIVE_RETRIES) · Waiting for you"
                        skipCountdown > 0 -> "No response after $MAX_LIVE_RETRIES retries · next channel in " + skipCountdown + "s"
                        retryCountdown > 0 -> "Retry " + (attempted + 1) + " of " + MAX_LIVE_RETRIES + " in " + retryCountdown + "s"
                        attempted >= MAX_LIVE_RETRIES -> "All $MAX_LIVE_RETRIES retries failed"
                        else -> "Reconnecting · $attempted of $MAX_LIVE_RETRIES retries completed"
                    },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRetryNow, modifier = Modifier.focusRequester(actionFocus)) {
                        Text("Retry now", maxLines = 1)
                    }
                    OutlinedButton(onClick = onWait) {
                        Text(if (waiting) "Hold" else "Stop / wait", maxLines = 1)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canSkip) OutlinedButton(onClick = onSkip) {
                        Text("Skip channel", maxLines = 1)
                    }
                    OutlinedButton(onClick = onBack) {
                        Text("Back to channels", maxLines = 1)
                    }
                }
            }
        }
    }
}
