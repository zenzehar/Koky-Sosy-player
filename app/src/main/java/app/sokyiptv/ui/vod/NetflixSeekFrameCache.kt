/*
 * GPL-3.0-or-later. Compact, local TV seek-preview screenshots from the
 * existing active video surface. NO network probes, second decoder or seek
 * operations. A frame must match its own sampled playback timestamp.
 */
package app.sokyiptv.ui.vod

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Bounded TV memory: 48 * 208 * 117 * 4 bytes, <5 MB uncompressed. Each
 * preview corresponds to a *previously displayed* moment of the current title.
 * Unknown/non-seeked positions must not reuse the wrong scene image.
 */
internal class NetflixSeekFrameCache {
    private val frames = LinkedHashMap<Long, Bitmap>(50, .75f, true)
    private val capacity = 48
    var revision by mutableIntStateOf(0)
        private set

    fun thumbnail(positionMs: Long): ImageBitmap? {
        // Compose tracks changes via revision, while bitmaps remain out of snapshots.
        @Suppress("UNUSED_VARIABLE") val observedRevision = revision
        val match = frames.entries.minByOrNull { abs(it.key - positionMs) }
            ?: return null
        // Never pretend an unrelated scene belongs to the selected timestamp.
        if (!NetflixSeekPreviewPolicy.frameMatches(match.key, positionMs)) return null
        return match.value.asImageBitmap()
    }

    fun store(positionMs: Long, image: Bitmap) {
        // One frame at approximately every five seconds; avoid near-duplicates.
        if (positionMs < 0L || frames.keys.any { abs(it - positionMs) < 4_500L }) {
            image.recycle()
            return
        }
        frames[positionMs] = image
        while (frames.size > capacity) {
            val oldest = frames.entries.iterator().next()
            frames.remove(oldest.key)
            oldest.value.recycle()
        }
        revision++
    }

    fun clear() {
        // Normally disposed with the player; avoid holding stale title frames.
        frames.values.forEach { it.recycle() }
        frames.clear()
        revision++
    }
}

/**
 * PixelCopy reads only pixels ALREADY produced by the running decoder. On
 * Android <= 23 (without a TextureView), or DRM-protected surfaces, return
 * null. Attempts are time limited so the TV UI can never stall for capture.
 */
internal object NetflixSeekFrameCapture {
    private fun locate(root: View): View? {
        var best: View? = null
        var bestArea = 0L
        fun scan(view: View) {
            when (view) {
                is TextureView -> if (view.isAvailable && view.width > 0 && view.height > 0) {
                    val area = view.width.toLong() * view.height
                    if (area > bestArea) { best = view; bestArea = area }
                }
                is SurfaceView -> if (view.isAttachedToWindow && view.width > 0 &&
                    view.height > 0 && view.holder.surface?.isValid == true) {
                    val area = view.width.toLong() * view.height
                    if (area > bestArea) { best = view; bestArea = area }
                }
                is ViewGroup -> for (i in 0 until view.childCount) scan(view.getChildAt(i))
            }
        }
        scan(root)
        return best
    }

    suspend fun sample(root: View): Bitmap? {
        val surface = locate(root) ?: return null
        // API 23 SurfaceView cannot be copied. Exit before bitmap allocation.
        if (surface is SurfaceView && Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
            return null
        val bitmap = Bitmap.createBitmap(208, 117, Bitmap.Config.ARGB_8888)
        val success = try {
            withTimeoutOrNull(850L) {
                when (surface) {
                    is TextureView -> surface.getBitmap(bitmap) != null
                    is SurfaceView -> {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) false
                        else suspendCancellableCoroutine { continuation ->
                            runCatching {
                                PixelCopy.request(surface, bitmap, { result ->
                                    if (continuation.isActive)
                                        continuation.resume(result == PixelCopy.SUCCESS)
                                }, Handler(Looper.getMainLooper()))
                            }.onFailure {
                                if (continuation.isActive) continuation.resume(false)
                            }
                        }
                    }
                    else -> false
                }
            } == true
        } catch (_: Exception) { false }
        if (!success) {
            // Timed-out asynchronous PixelCopy can still complete into bitmap;
            // do not recycle its output until the framework has released it.
            return null
        }
        // Black/secure surfaces do not give a meaningful visual scene preview.
        val pix = IntArray(20)
        for (i in pix.indices) {
            pix[i] = bitmap.getPixel((i % 5) * 39 + 20, (i / 5) * 25 + 10)
        }
        if (pix.all {
                val r = (it shr 16) and 255
                val g = (it shr 8) and 255
                val b = it and 255
                r < 9 && g < 9 && b < 9
            }) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }
}
