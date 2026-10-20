/*
 * Soky IPTV Pro per-user Google Drive sign-in. GPL-3.0-or-later.
 * Application OAuth credentials are injected by the build; users only authorise their own account.
 */
package app.sokyiptv.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sokyiptv.sync.cloud.GoogleDriveProgressSync
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

private fun googleDeviceQr(url: String): Bitmap? = runCatching {
    val matrix = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 224, 224)
    Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until 224) for (x in 0 until 224) {
            setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
}.getOrNull()

@Composable
fun GoogleDriveSyncPane() {
    val context = LocalContext.current
    val sync = remember(context) { GoogleDriveProgressSync.get(context) }
    val state by sync.state.collectAsState()
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Google Drive Cloud Sync", style = MaterialTheme.typography.titleLarge)
        Text(
            "Connect your own Google account. Your viewing progress stays in your own Google Drive. " +
                "If Google's hidden app storage is unavailable for TV sign-in, Soky creates a " +
                "normal private-to-you folder in My Drive called Soky IPTV Pro. " +
                "Use the same account on another TV to sync. IPTV credentials and stream URLs are not uploaded.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Card(Modifier.widthIn(max = 720.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (state.connected) "Google account connected" else "Google account not connected",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (state.connected && state.accountLabel.isNotBlank()) {
                    Text("Account: ${state.accountLabel}")
                }
                if (!state.configured) {
                    Text(
                        "Google sign-in is unavailable in this test build because the developer " +
                            "has not configured the application's TV OAuth credentials. " +
                            "Users do not need to enter any Client ID or Client Secret.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (!state.connected && !state.busy) {
                    Button(enabled = state.configured, onClick = { scope.launch { sync.connect() } }) {
                        Text("Connect Google account")
                    }
                }
                state.pendingCode?.let { code ->
                    Text("Scan this code with your phone, or open the URL:", style = MaterialTheme.typography.bodyMedium)
                    val qr = remember(code.verificationUrl) { googleDeviceQr(code.verificationUrl) }
                    if (qr != null) {
                        Image(
                            bitmap = qr.asImageBitmap(),
                            contentDescription = "QR code to open Google's device authorisation page",
                            modifier = Modifier.size(196.dp),
                        )
                    }
                    Text(code.verificationUrl, style = MaterialTheme.typography.titleMedium)
                    Text("Enter this code on your phone (case-sensitive):", style = MaterialTheme.typography.bodyMedium)
                    Text(code.userCode, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (code.requestedScope.endsWith("/drive.file"))
                            "Google will let Soky manage its own files in the Soky IPTV Pro folder in My Drive."
                        else "Approve access to your Google Drive app data. The TV will finish connecting automatically."
                    )
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
                    Text(
                        state.message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "When connected: sync at startup, every two minutes while the app is in use, " +
                "and when it goes into the background. Each device has its own cloud snapshot; " +
                "newer watched positions win. Playback continues offline. Removing history " +
                "across devices is not yet synchronised. Switching Google accounts does not " +
                "erase the local watch history on this TV.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
