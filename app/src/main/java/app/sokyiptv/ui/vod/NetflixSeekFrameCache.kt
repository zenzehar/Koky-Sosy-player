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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Bounded TV memory: (48 playback + 24 proactively extracted) compact bitmaps,\n * ~7 MB uncompressed. Exact played frames and approximate storyboard\n * frames are stored separately so natural playback doesn't evict a storyboard.
 */
internal class NetflixSeekFrameCache {
    // A strict ~7 MiB budget: 48 recent played frames + 24 future storyboard frames.
    // Prefetched frames survive playback without being evicted by the 1.65s sampler.
    private val liveFrames = LinkedHashMap<Long, Bitmap>(50, .75f, true)
    private val storyboardFrames = LinkedHashMap<Long, Bitmap>(26, .75f, true)
    private val liveCapacity = 48
    private val storyboardCapacity = 24
    private var storyboardDurationMs = 0L

    data class CapturedFrame(
        val timestampMs: Long,
        val image: ImageBitmap,
        val accurate: Boolean,
    )

    var revision by mutableIntStateOf(0)
        private set

    fun nearestFrame(positionMs: Long): CapturedFrame? {
        @Suppress("UNUSED_VARIABLE") val observedRevision = revision
        val live = liveFrames.entries.minByOrNull { abs(it.key - positionMs) }
        val proactive = storyboardFrames.entries.minByOrNull { abs(it.key - positionMs) }
        val nearest = when {
            live == null -> proactive
            proactive == null -> live
            abs(live.key - positionMs) <= abs(proactive.key - positionMs) -> live
            else -> proactive
        } ?: return null
        val distance = abs(nearest.key - positionMs)
        // Only the sparse storyboard can cover unseen portions. The UI explicitly
        // labels a non-exact cached scene with its actual timestamp.
        val tolerance = if (storyboardFrames.containsKey(nearest.key))
            ProactivePreviewPolicy.maxApproximateDistanceMs(storyboardDurationMs)
        else 12_000L
        if (distance > tolerance) return null
        return CapturedFrame(nearest.key, nearest.value.asImageBitmap(),
            NetflixSeekPreviewPolicy.frameMatches(nearest.key, positionMs))
    }

    fun thumbnail(positionMs: Long): ImageBitmap? =
        nearestFrame(positionMs)?.takeIf { it.accurate }?.image

    fun hasFrameNear(targetMs: Long, toleranceMs: Long): Boolean =
        liveFrames.keys.any { abs(it - targetMs) <= toleranceMs } ||
            storyboardFrames.keys.any { abs(it - targetMs) <= toleranceMs }

    fun store(positionMs: Long, image: Bitmap) {
        put(liveFrames, liveCapacity, positionMs, image, 1_250L)
    }

    fun storePrefetched(positionMs: Long, durationMs: Long, image: Bitmap) {
        storyboardDurationMs = durationMs
        put(storyboardFrames, storyboardCapacity, positionMs, image, 4_000L)
    }

    private fun put(
        destination: LinkedHashMap<Long, Bitmap>,
        capacity: Int,
        positionMs: Long,
        image: Bitmap,
        spacingMs: Long,
    ) {
        if (positionMs < 0L || hasFrameNear(positionMs, spacingMs)) {
            image.recycle()
            return
        }
        destination[positionMs] = image
        while (destination.size > capacity) {
            val iterator = destination.entries.iterator()
            val oldest = iterator.next()
            iterator.remove()
            oldest.value.recycle()
        }
        revision++
    }

    fun clear() {
        liveFrames.values.forEach { it.recycle() }
        storyboardFrames.values.forEach { it.recycle() }
        liveFrames.clear()
        storyboardFrames.clear()
        storyboardDurationMs = 0L
        revision++
    }
}

/**
 * PixelCopy reads only pixels ALREADY produced by the running decoder. On
 * Android <= 23 (without a TextureView), or DRM-protected surfaces, return
 * null. Attempts are time limited so the TV UI can never stall for capture.
 */
internal object NetflixSeekFrameCapture {
    // Avoid concurrent PixelCopy requests after quick repeated seeks/remote taps.
    private val captureMutex = Mutex()

    suspend fun sample(root: View): Bitmap? = captureMutex.withLock {
        sampleUnlocked(root)
    }

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

    private suspend fun sampleUnlocked(root: View): Bitmap? {
        val surface = locate(root) ?: return null
        // API 23 SurfaceView cannot be copied. Exit before bitmap allocation.
        if (surface is SurfaceView && Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
            return null
        val bitmap = Bitmap.createBitmap(208, 117, Bitmap.Config.ARGB_8888)
        val copyResult: Boolean? = try {
            withTimeoutOrNull(850L) {
                when (surface) {
                    is TextureView -> surface.getBitmap(bitmap) != null
                    is SurfaceView -> {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) false
                        else suspendCancellableCoroutine { continuation ->
                            runCatching {
                                PixelCopy.request(surface, bitmap, { result ->
                                    if (continuation.isActive) {
                                        continuation.resume(result == PixelCopy.SUCCESS)
                                    } else {
                                        // A timed-out PixelCopy still owns its output;
                                        // only recycle AFTER Android has finished writing.
                                        bitmap.recycle()
                                    }
                                }, Handler(Looper.getMainLooper()))
                            }.onFailure {
                                if (continuation.isActive) continuation.resume(false)
                            }
                        }
                    }
                    else -> false
                }
            }
        } catch (_: Exception) { false }
        if (copyResult != true) {
            // Timed-out SurfaceView output is recycled in its later callback.
            // Completed failure and all TextureView failures can be recycled now.
            if (copyResult == false || surface is TextureView) bitmap.recycle()
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
