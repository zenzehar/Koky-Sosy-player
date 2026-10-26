/* GPL-3.0-or-later. Passive on-screen fingerprinting. No second HTTP stream/player. */
package app.sokyiptv.player

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Samples the *already decoding* SurfaceView, not the entire window. Subtitles,
 * controls and overlaid skip prompts therefore cannot poison the signature.
 * On Fire OS/API <= 25, Surface PixelCopy is unavailable: return null silently.
 */
object PassiveVideoCapture {
    private fun findSurface(view: View): View? = when (view) {
        is SurfaceView -> view
        is TextureView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull {
            findSurface(view.getChildAt(it))
        }
        else -> null
    }

    suspend fun sample(root: View, mediaTimeMs: Long): SceneSample? {
        if (mediaTimeMs < 0L) return null
        val surface = findSurface(root) ?: return null
        val bitmap = Bitmap.createBitmap(128, 72, Bitmap.Config.ARGB_8888)
        val ok = if (surface is TextureView) {
            if (!surface.isAvailable) false
            else runCatching { surface.getBitmap(bitmap) != null }.getOrDefault(false)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && surface is SurfaceView &&
            surface.holder.surface?.isValid == true && surface.isAttachedToWindow) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                runCatching {
                    PixelCopy.request(surface, bitmap, { result ->
                        if (continuation.isActive)
                            continuation.resume(result == PixelCopy.SUCCESS)
                    }, Handler(Looper.getMainLooper()))
                }.onFailure {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
        } else false
        if (!ok) { bitmap.recycle(); return null }
        return try { signature(bitmap, mediaTimeMs) } finally { bitmap.recycle() }
    }

    internal fun signature(bitmap: Bitmap, atMs: Long): SceneSample? {
        // dHash of video luminance: robust to resolution and mild encoding changes.
        val grid = IntArray(9 * 8)
        var index = 0
        for (y in 0 until 8) for (x in 0 until 9) {
            val color = bitmap.getPixel((x * bitmap.width / 9 + bitmap.width / 18)
                .coerceAtMost(bitmap.width - 1),
                (y * bitmap.height / 8 + bitmap.height / 16)
                    .coerceAtMost(bitmap.height - 1))
            val r = (color shr 16) and 255
            val g = (color shr 8) and 255
            val b = color and 255
            grid[index++] = (r * 77 + g * 150 + b * 29) shr 8
        }
        var fingerprint = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (grid[y * 9 + x] > grid[y * 9 + x + 1]) {
                fingerprint = fingerprint or (1L shl bit)
            }
            bit++
        }
        val density = java.lang.Long.bitCount(fingerprint)
        // Flat black screens, black bars, loading circles, static logos etc.
        // must not create artificial identical patterns across episodes.
        // Keep low-texture frames until after the separate rolling-credits test.
        // The old early return silently dropped nearly black credit backgrounds.

        // Conservative scrolling-credit appearance: near-black background with
        // several short horizontal white strokes in the central 70% of the frame.
        // Only treated as a cue after persistent samples near the true VOD end.
        var dark = 0
        var bright = 0
        var lineRows = 0
        val xMin = bitmap.width / 6
        val xMax = bitmap.width * 5 / 6
        for (y in 6 until bitmap.height - 13 step 2) {
            var rowBright = 0
            for (x in xMin until xMax step 2) {
                val c = bitmap.getPixel(x, y)
                val lum = (((c shr 16) and 255) * 77 +
                    ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8
                if (lum < 38) dark++
                if (lum > 180) { bright++; rowBright++ }
            }
            if (rowBright in 3..22) lineRows++
        }
        val total = ((bitmap.height - 19 + 1) / 2) * ((xMax - xMin + 1) / 2)
        val creditText = total > 0 && dark > total * 7 / 10 &&
            bright in (total * 2 / 100)..(total * 22 / 100) &&
            lineRows >= 5
        if (density !in 8..56 && !creditText) return null
        return SceneSample(atMs, fingerprint, creditText)
    }
}
