package app.opentv.diagnostics

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33]) // Robolectric 4.13 SDK 33 simulator; app still compiles against 36.
class PlaybackDiagnosticsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun reportHasUsefulSessionEventsAndStaysQrSized() {
        PlaybackDiagnostics.begin("VLC", "NATIVE")
        PlaybackDiagnostics.event(context, "VLC_START", persistNow = true)
        PlaybackDiagnostics.sample(74_000, 999_000, 3)
        repeat(80) {
            PlaybackDiagnostics.event(context, "VLC_BUFFER", it, persistNow = false)
        }
        val report = PlaybackDiagnostics.capture(context)
        assertTrue(report.startsWith("KOKY-DIAG/1"))
        assertTrue(report.contains("engine=VLC"))
        assertTrue(report.contains("interruptions=3"))
        assertTrue(report.contains("position=74s"))
        assertTrue(report.length <= 950)
        assertTrue(report.contains("VLC_BUFFER"))
        PlaybackDiagnostics.event(context, "VLC_EXIT", persistNow = true)
    }

    @Test fun unknownEventNamesCannotInjectCredentialsOrUrls() {
        PlaybackDiagnostics.begin("VLC", "NATIVE")
        PlaybackDiagnostics.event(context, "VLC_START", persistNow = true)
        PlaybackDiagnostics.event(context, "https://provider.example/username/password?token=secret")
        PlaybackDiagnostics.event(context, "VLC_ERROR", -100)
        val report = PlaybackDiagnostics.capture(context)
        assertFalse(report.contains("secret"))
        assertFalse(report.contains("provider.example"))
        assertTrue(report.contains("VLC_ERROR"))
        PlaybackDiagnostics.event(context, "VLC_EXIT", persistNow = true)
    }

    @Test fun generatedQrIsDecodableAsPlainTextOnPhone() {
        val input = "KOKY-DIAG/1\nengine=VLC\ninterruptions=4\nevents=10s:VLC_BUFFER 8|13s:VLC_STALL 0"
        val image = QRCodeWriter().encode(input, BarcodeFormat.QR_CODE, 400, 400)
        val pixels = IntArray(image.width * image.height) { index ->
            if (image[index % image.width, index / image.width]) 0xFF000000.toInt()
            else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(
            RGBLuminanceSource(image.width, image.height, pixels)
        ))
        val decoded = MultiFormatReader().decode(bitmap)
        assertTrue(decoded.text == input)
    }

    @Test fun interruptedSessionIsDescribedAsPossibleNotProvenCrash() {
        PlaybackDiagnostics.acknowledgeCrash(context)
        PlaybackDiagnostics.begin("VLC", "NATIVE")
        PlaybackDiagnostics.event(context, "VLC_START", persistNow = true)
        val recovered = PlaybackDiagnostics.recoveredCrash(context)
        assertTrue(recovered?.contains("POSSIBLE_UNCLEAN_EXIT=1") == true)
        PlaybackDiagnostics.acknowledgeCrash(context)
        assertTrue(PlaybackDiagnostics.recoveredCrash(context) == null)
    }
}
