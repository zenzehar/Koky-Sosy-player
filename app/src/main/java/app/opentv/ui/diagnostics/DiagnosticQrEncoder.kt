/*
 * Diagnostic QR rendering used by the real Android TV UI and Android bitmap tests.
 * Temporary testing feature; GPL-3.0-or-later.
 */
package app.opentv.ui.diagnostics

import android.graphics.Bitmap
import android.graphics.Color
import app.opentv.pairing.QrCodes
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

internal data class DiagnosticQrResult(
    val bitmap: Bitmap?,
    val failureClass: String? = null,
    val compact: Boolean = false,
)

/**
 * Uses the application's existing phone-pairing renderer first, rather than maintaining a
 * second unproven TV QR implementation. Fallback uses a separate, smaller RGB_565 bitmap.
 * In the unlikely case both fail, show a sanitized *exception type only*, never its message:
 * Java exception messages may contain credentials or a provider's URL.
 *
 * Do not upload any report automatically. QR contains only already-allowlisted plain text.
 */
internal object DiagnosticQrEncoder {
    fun render(report: String): DiagnosticQrResult {
        val safe = report.take(MAX_TEXT)
        if (safe.isBlank()) return DiagnosticQrResult(null, "EmptyReport")
        // Pairing QR generation has been used elsewhere in this app; it has a quiet zone,
        // contrast and a phone-camera-tested bitmap conversion.
        QrCodes.render(safe, PIXELS)?.let { return DiagnosticQrResult(it) }

        var errorType = "PrimaryRendererFailure"
        // Reattempt with lower error-correction pressure and without optional hint knobs.
        // Use RGB_565 (half the bitmap memory) to tolerate constrained Android TV devices.
        val compact = safe.take(280)
        val fallback = try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.MARGIN to 3,
            )
            val matrix = QRCodeWriter().encode(compact, BarcodeFormat.QR_CODE, PIXELS, PIXELS, hints)
            val pixels = IntArray(matrix.width * matrix.height)
            for (y in 0 until matrix.height) {
                val offset = y * matrix.width
                for (x in 0 until matrix.width) {
                    pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565).apply {
                setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
            }
        } catch (t: Throwable) {
            // Diagnostics must never leak raw messages (or crash an already broken player).
            errorType = (t.javaClass.simpleName.ifBlank { "QrRenderFailure" })
                .replace(Regex("[^A-Za-z0-9_]"), "").take(42)
            null
        }
        return DiagnosticQrResult(fallback, if (fallback == null) errorType else null, compact = true)
    }

    private const val MAX_TEXT = 950
    private const val PIXELS = 480 // 0.9 MiB ARGB_8888, previously 1.2 MiB at 560px.
}
