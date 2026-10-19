/*
 * Diagnostic QR rendering used by the real Android TV UI and Android bitmap tests.
 * Temporary testing feature; GPL-3.0-or-later.
 */
package app.sokyiptv.ui.diagnostics

import android.graphics.Bitmap
import android.graphics.Color
import app.sokyiptv.pairing.QrCodes
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

internal data class DiagnosticQrResult(
    val bitmap: Bitmap?,
    val failureClass: String? = null,
    val fallback: Boolean = false,
    val partIndex: Int = 0,
    val partCount: Int = 1,
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
    fun render(report: String, partIndex: Int = 0, forceFallback: Boolean = false): DiagnosticQrResult {
        val safe = report.take(MAX_TEXT)
        if (safe.isBlank()) return DiagnosticQrResult(null, "EmptyReport")
        // Prefer one full-text QR using our established phone-pairing renderer.
        if (!forceFallback && partIndex == 0) {
            QrCodes.render(safe, PIXELS)?.let { return DiagnosticQrResult(it) }
        }

        // If a dense QR fails, try multiple low-density, numbered QR pages. Never
        // silently discard events: every 220-character slice is represented.
        val count = (safe.length + PART_SIZE - 1) / PART_SIZE
        val index = partIndex.coerceIn(0, count - 1)
        val from = index * PART_SIZE
        val partText = "SOKY-PART ${index + 1}/$count\n" +
            safe.substring(from, minOf(from + PART_SIZE, safe.length))
        var errorType = "PrimaryRendererFailure"
        val fallback = try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.MARGIN to 3,
            )
            val matrix = QRCodeWriter().encode(partText, BarcodeFormat.QR_CODE, PIXELS, PIXELS, hints)
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
        return DiagnosticQrResult(
            bitmap = fallback,
            failureClass = if (fallback == null) errorType else null,
            fallback = true,
            partIndex = index,
            partCount = count,
        )
    }

    private const val PART_SIZE = 220
    private const val MAX_TEXT = 950
    private const val PIXELS = 480 // 0.9 MiB ARGB_8888, previously 1.2 MiB at 560px.
}
