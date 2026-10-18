/*
 * Temporary debug-only TV QR report; remove before a stable release.
 * GPL-3.0-or-later. QR contains allowlisted local diagnostics, never IPTV URLs.
 */
package app.opentv.ui.diagnostics

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun PlaybackDiagnosticsDialog(
    report: String,
    onDismiss: () -> Unit,
    afterCrash: Boolean = false,
) {
    var partIndex by remember(report) { mutableIntStateOf(0) }
    var easierScan by remember(report) { mutableStateOf(false) }
    // Both full report and low-density fallback are tested against Android Bitmaps.
    val qr = remember(report, partIndex, easierScan) {
        DiagnosticQrEncoder.render(report, partIndex, forceFallback = easierScan)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .background(Color(0xFF121923), androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .padding(18.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (afterCrash) "Recovered crash diagnostic" else "Playback diagnostic QR",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "Scan with your phone camera, then paste the resulting text into ChatGPT. " +
                    "No provider URL, credentials or title names are included.",
                color = Color.LightGray,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
            )
            if (qr.bitmap != null) {
                Image(
                    bitmap = qr.bitmap.asImageBitmap(),
                    contentDescription = "Scan to copy the sanitized diagnostic report",
                    modifier = Modifier.size(340.dp).background(Color.White).padding(5.dp),
                )
                if (!qr.fallback) {
                    Button(onClick = { partIndex = 0; easierScan = true }) {
                        Text("Use easier-to-scan QR (smaller parts)")
                    }
                }
                if (qr.fallback) {
                    Text(
                        "Fallback QR part ${qr.partIndex + 1} of ${qr.partCount}. " +
                            "Scan all parts in order for the full report.",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    if (qr.partCount > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Button(
                                enabled = partIndex > 0,
                                onClick = { partIndex-- },
                            ) { Text("Previous part") }
                            Button(
                                enabled = partIndex + 1 < qr.partCount,
                                onClick = { partIndex++ },
                            ) { Text("Next part") }
                        }
                    }
                }
            } else {
                Text(
                    "QR renderer error: ${qr.failureClass ?: "UnknownError"}. " +
                        "The error identifier contains no sensitive text. " +
                        "You can photograph the report below.",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                report.take(350),
                color = Color.LightGray,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(2.dp))
            Button(onClick = onDismiss) { Text("Close diagnostic") }
        }
    }
}
