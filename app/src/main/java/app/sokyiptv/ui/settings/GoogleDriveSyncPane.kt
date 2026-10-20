/*
 * Soky IPTV Pro experimental Google Drive Cloud Sync settings.
 * The existing LAN and NAS sync panes remain unchanged.
 */
package app.sokyiptv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.sokyiptv.sync.cloud.GoogleDriveProgressSync
import app.sokyiptv.ui.onboarding.TvNativeTextField
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
fun GoogleDriveSyncPane() {
    val context = LocalContext.current
    val sync = remember(context) { GoogleDriveProgressSync.get(context) }
    val state by sync.state.collectAsState()
    val scope = rememberCoroutineScope()
    var clientSecret by remember { mutableStateOf("") }
    var secretMessage by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Google Drive watch-progress sync (TV test)",
            style = MaterialTheme.typography.titleLarge)
        Text(
            "Use the same Google account on your TVs to share Continue Watching and " +
                "episode positions. This never uploads IPTV passwords, server addresses " +
                "or streaming URLs. The app keeps working offline.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (!state.hasTestClientSecret) {
            Card(Modifier.widthIn(max = 680.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("First-time test setup", style = MaterialTheme.typography.titleMedium)
                    Text("Enter your temporary Google TV OAuth Client Secret ON THIS DEVICE. " +
                         "It will be stored encrypted using the Android Keystore, not uploaded " +
                         "to GitHub. This field is for experimental testing only.")
                    TvNativeTextField(
                        value = clientSecret,
                        onValueChange = { clientSecret = it },
                        label = "TV OAuth Client Secret (test)",
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Button(
                        enabled = clientSecret.isNotBlank(),
                        onClick = {
                            runCatching { sync.saveTestSecret(clientSecret) }
                                .onSuccess { secretMessage = "Client settings saved"; clientSecret = "" }
                                .onFailure { secretMessage = "Could not save the test settings" }
                        }
                    ) { Text("Save test configuration") }
                    if (secretMessage.isNotEmpty()) Text(secretMessage)
                }
            }
        }

        Card(Modifier.widthIn(max = 720.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (state.connected) "Google Drive connected" else "Google account not connected",
                    style = MaterialTheme.typography.titleMedium)
                if (!state.connected && !state.busy) {
                    Button(
                        enabled = state.hasTestClientSecret,
                        onClick = { scope.launch { sync.connect() } },
                    ) { Text("Connect Google account") }
                }
                state.pendingCode?.let { code ->
                    Text("On your phone, open:", style = MaterialTheme.typography.bodyMedium)
                    Text(code.verificationUrl, style = MaterialTheme.typography.titleMedium)
                    Text("Enter this code:", style = MaterialTheme.typography.bodyMedium)
                    Text(code.userCode, style = MaterialTheme.typography.headlineMedium)
                    Text("The app will automatically finish signing in after you approve access.")
                }
                if (state.connected) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(enabled = !state.busy, onClick = { scope.launch { sync.syncNow() } }) {
                            Text("Sync Now")
                        }
                        OutlinedButton(onClick = { sync.disconnect() }) { Text("Disconnect") }
                    }
                }
                val last = if (state.lastSuccessMillis <= 0L) "Never" else
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(state.lastSuccessMillis))
                Text("Last successful sync: $last")
                if (state.message.isNotBlank()) {
                    Text(state.message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "When connected: sync at app startup, every two minutes while open, and " +
                "when leaving the app. Multiple devices keep separate cloud snapshots; " +
                "the newest position wins. Titles must exist in each device's local catalogue. " +
                "Removal/reset actions are not yet shared between devices in this experiment.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
