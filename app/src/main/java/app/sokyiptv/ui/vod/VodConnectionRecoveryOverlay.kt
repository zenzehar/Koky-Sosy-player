package app.sokyiptv.ui.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/** Modal focus boundary: the VLC surface cannot steal D-pad input. */
@Composable
internal fun VodConnectionRecoveryOverlay(
    title: String,
    reason: String,
    attempts: Int,
    retryCountdown: Int,
    skipCountdown: Int,
    retrying: Boolean,
    stopped: Boolean,
    canSkipEpisode: Boolean,
    usingVlc: Boolean,
    onRetryNow: () -> Unit,
    onStop: () -> Unit,
    onNextEpisode: () -> Unit,
    onTryMedia3: () -> Unit,
    onAdvancedVlc: () -> Unit,
    onBack: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onStop,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(modifier = Modifier.fillMaxWidth(.90f).heightIn(max = 560.dp),
            shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Playback connection interrupted", style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center)
                Text(title, style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center, maxLines = 2)
                Text(reason, style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center, maxLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    repeat(5) { i ->
                        Text(if (i < attempts) "●" else "○",
                            color = if (i < attempts) Color(0xFF58C7B9)
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.titleLarge)
                    }
                }
                Text(when {
                    stopped -> "Paused — " + attempts + " of 5 retries used. Waiting for you."
                    skipCountdown > 0 -> "Five retries failed. Next episode in " + skipCountdown + "s."
                    retryCountdown > 0 -> "Retry " + (attempts + 1) + " of 5 in " + retryCountdown + "s"
                    retrying -> "Reconnecting — attempt " + attempts + " of 5"
                    attempts >= 5 -> "Five retries failed. Your intervention is required."
                    else -> "Reconnecting — " + attempts + " of 5 attempts"
                }, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                Text("Your playback position is preserved. A connection failure never completes an episode.",
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                Button(onClick = onRetryNow, modifier = Modifier.fillMaxWidth()
                    .focusRequester(firstFocus)) { Text("Retry now", maxLines = 1) }
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text("Stop / wait — let me intervene", maxLines = 2)
                }
                if (canSkipEpisode) OutlinedButton(onClick = onNextEpisode,
                    modifier = Modifier.fillMaxWidth()) { Text("Next episode", maxLines = 1) }
                if (usingVlc) {
                    OutlinedButton(onClick = onTryMedia3,
                        modifier = Modifier.fillMaxWidth()) {
                        Text("Try Media3 instead", maxLines = 1)
                    }
                    OutlinedButton(onClick = onAdvancedVlc,
                        modifier = Modifier.fillMaxWidth()) {
                        Text("More VLC options · External player / diagnostics", maxLines = 2)
                    }
                }
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Back to title", maxLines = 1)
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { firstFocus.requestFocus() }
    }
}
