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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Lightweight OPTIONAL thumbnails of frames already decoded by the active player.
 * Never seeks, starts a stream/decoder, writes to storage, or captures overlay UI.
 * At most 12 small frames are held in RAM (~1 MiB), and capture runs infrequently.
 * An unvisited target has no thumbnail rather than an invented or stale scene.
 */
internal class SeekFramePreviewCache {
    private data class Frame(val atMs: Long, val bitmap: ImageBitmap)
    private val frames = mutableStateListOf<Frame>()

    fun nearest(atMs: Long): ImageBitmap? {
        val closest = frames.minByOrNull { abs(it.atMs - atMs) } ?: return null
        return closest.bitmap.takeIf { abs(closest.atMs - atMs) <= 6_000L }
    }

    suspend fun record(root: View, playedPositionMs: Long) {
        if (playedPositionMs < 0L || frames.any { abs(it.atMs - playedPositionMs) < 10_000L }) return
        val surface = activeVideoSurface(root) ?: return
        val bitmap = Bitmap.createBitmap(192, 108, Bitmap.Config.ARGB_8888)
        val ok = withTimeoutOrNull(1_200L) {
            when (surface) {
                is TextureView -> surface.isAvailable && runCatching {
                    surface.getBitmap(bitmap) != null
                }.getOrDefault(false)
                is SurfaceView -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                    surface.holder.surface?.isValid == true) {
                    suspendCancellableCoroutine<Boolean> { continuation ->
                        runCatching {
                            PixelCopy.request(surface, bitmap, { result ->
                                if (continuation.isActive) continuation.resume(result == PixelCopy.SUCCESS)
                            }, Handler(Looper.getMainLooper()))
                        }.onFailure {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    }
                } else false
                else -> false
            }
        } == true
        if (!ok) {
            // The bitmap may still be used by an in-flight PixelCopy when timed out.
            // Leave it for garbage collection rather than recycling under that writer.
            return
        }
        frames.add(Frame(playedPositionMs, bitmap.asImageBitmap()))
        while (frames.size > 12) frames.removeAt(0)
    }

    private fun activeVideoSurface(root: View): View? {
        var candidate: View? = null
        var area = 0L
        fun visit(view: View) {
            when (view) {
                is SurfaceView -> {
                    if (view.isAttachedToWindow && view.holder.surface?.isValid == true) {
                        val measure = view.width.toLong() * view.height
                        if (measure > area) { area = measure; candidate = view }
                    }
                }
                is TextureView -> {
                    if (view.isAvailable) {
                        val measure = view.width.toLong() * view.height
                        if (measure > area) { area = measure; candidate = view }
                    }
                }
                is ViewGroup -> for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
        return candidate
    }
}
