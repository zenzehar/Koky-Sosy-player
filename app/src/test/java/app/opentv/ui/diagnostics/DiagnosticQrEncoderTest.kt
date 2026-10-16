package app.opentv.ui.diagnostics

import android.app.Application
import android.graphics.Bitmap
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unlike the original BitMatrix-only test, these exercise the very same
 * Android Bitmap creation and QR rendering used by the actual television UI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class DiagnosticQrEncoderTest {
    private fun decoded(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    @Test fun realBitmapFromScreenshotEquivalentReportCanBeScanned() {
        val report = "KOKY-DIAG/1\n" +
            "build=0.1.10.8 alpha;libvlc=3.7.7\n" +
            "sdk=34;device=SDMC-HAKO_Pro;engine=VLC\n" +
            "heap=14/256MiB;freeRAM=530MiB\n" +
            "position=0s;duration=1855s;interruptions=1\n" +
            "events=0s:OPEN NATIVE|0s:VLC_START 0|11s:VLC_READY 0|" +
            "18s:VLC_STALL 0|24s:REPORT 0"
        val result = DiagnosticQrEncoder.render(report)
        assertNotNull("QR bitmap must exist, failure=${result.failureClass}", result.bitmap)
        assertEquals(report, decoded(result.bitmap!!))
        assertFalse(result.fallback)
    }

    @Test fun largerReportRemainsBoundedAndScannable() {
        val report = "KOKY-DIAG/1\nengine=VLC\n" +
            (1..17).joinToString("|") { "${it}s:VLC_BUFFER ${it * 5}" }
        val image = DiagnosticQrEncoder.render(report)
        assertNotNull("QR missing: ${image.failureClass}", image.bitmap)
        assertEquals(report, decoded(image.bitmap!!))
    }

    @Test fun userCanForceEasierScanModeEvenWhenFullQrWouldWork() {
        val source = "KOKY-DIAG/1\\nengine=VLC\\nevents=0s:VLC_READY 0"
        val fallback = DiagnosticQrEncoder.render(source, forceFallback = true)
        assertTrue(fallback.fallback)
        assertEquals(1, fallback.partCount)
        assertNotNull(fallback.bitmap)
        val text = decoded(fallback.bitmap!!)
        assertTrue(text.startsWith("KOKY-PART 1/1"))
        assertTrue(text.contains(source))
    }

    @Test fun paginationFallbackPreservesReportRatherThanSilentlyDiscardingEvents() {
        val report = "KOKY-DIAG/1\n" + (1..29).joinToString("|") {
            "${it}s:VLC_BUFFER ${it % 100}"
        }
        // Index >0 forces the fallback path (normally reached after primary failure).
        val second = DiagnosticQrEncoder.render(report, partIndex = 1)
        assertTrue(second.fallback)
        assertTrue(second.partCount > 1)
        assertNotNull(second.bitmap)
        val body = decoded(second.bitmap!!)
        assertTrue(body.startsWith("KOKY-PART 2/"))
        assertTrue(body.endsWith(report.substring(220, minOf(440, report.length))))
    }
}
