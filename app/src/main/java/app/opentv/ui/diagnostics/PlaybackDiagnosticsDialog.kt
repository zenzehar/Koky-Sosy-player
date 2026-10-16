/*
 * Temporary debug-only TV QR report; remove before a stable release.
 * GPL-3.0-or-later. QR contains allowlisted local diagnostics, never IPTV URLs.
 */
package app.opentv.ui.diagnostics

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

@Composable
fun PlaybackDiagnosticsDialog(
    report: String,
    onDismiss: () -> Unit,
    afterCrash: Boolean = false,
) {
    val code = remember(report) {
        runCatching {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "ISO-8859-1",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.MARGIN to 2,
            )
            val bits = QRCodeWriter().encode(
                report.take(950), BarcodeFormat.QR_CODE, 560, 560, hints,
            )
            val width = bits.width
            val height = bits.height
            val pixels = IntArray(width * height) { n ->
                if (bits[n % width, n / width]) android.graphics.Color.BLACK
                else android.graphics.Color.WHITE
            }
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        }.getOrNull()
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .background(Color(0xFF121923), androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .padding(22.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (afterCrash) "Recovered crash diagnostic" else "Playback diagnostic QR",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "Scan this with your phone camera, copy the plain text and paste it into ChatGPT. " +
                    "Report contains no playlist URL, provider credentials or title names.",
                color = Color.LightGray,
                textAlign = TextAlign.Center,
            )
            if (code != null) {
                Image(
                    bitmap = code.asImageBitmap(),
                    contentDescription = "Scan to copy the redacted diagnostic report as plain text",
                    modifier = Modifier.size(390.dp)
                        .background(Color.White).padding(5.dp),
                )
            } else {
                Text(
                    "QR generation unavailable. Read the diagnostic summary below.",
                    color = Color.White,
                )
            }
            Text(
                report.take(350),
                color = Color.LightGray,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(3.dp))
            Button(onClick = onDismiss) { Text("Close diagnostic") }
        }
    }
}
