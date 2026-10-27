package app.sokyiptv.ui.vod

import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * One *optional* low-priority metadata connection for compatible progressive VOD.
 *
 * No second ExoPlayer/VLC instance, no repositioning the active decoder, no
 * streaming URL logging, and no background job after playback exits.
 *
 * Some providers disallow a second HTTP connection or do not support ranged
 * progressive metadata retrieval. Their failures immediately fall back to the
 * safe passive PixelCopy sampler.
 */
internal object ProactiveFrameExtractor {
    private val onlyOne = Mutex()

    suspend fun prefetch(
        streamUrl: String,
        userAgent: String,
        cache: NetflixSeekFrameCache,
        durationMs: Long,
        initialPositionMs: Long,
        canWork: () -> Boolean,
        previewTarget: () -> Long,
    ) = onlyOne.withLock {
        // Do not reserve a decoder when the user is still loading/buffering.
        var stableCount = 0
        while (coroutineContext.isActive && stableCount < 3) {
            delay(1_000L)
            stableCount = if (canWork()) stableCount + 1 else 0
        }
        if (!coroutineContext.isActive) return@withLock

        val retriever = try {
            withContext(Dispatchers.IO) {
                MediaMetadataRetriever().also { retriever ->
                    try {
                        val headers = if (userAgent.isNotBlank())
                            mapOf("User-Agent" to userAgent) else emptyMap()
                        retriever.setDataSource(streamUrl, headers)
                    } catch (error: Exception) {
                        retriever.release()
                        throw error
                    }
                }
            }
        } catch (_: Exception) {
            // Unsupported container, provider rejects extra connection, etc.
            return@withLock
        }

        try {
            val targets = ProactivePreviewPolicy.storyboard(durationMs, initialPositionMs)
            val attempted = HashSet<Long>()
            var failures = 0
            var slowFrames = 0
            var shots = 0
            var cursor = 0
            var lastPreviewTarget = -1L
            var consecutiveUnhealthyTicks = 0

            while (coroutineContext.isActive && shots < 24 && failures < 3 &&
                slowFrames < 2 && cursor < targets.size) {
                if (!canWork()) {
                    // If the primary video starts buffering, release the
                    // metadata connection within ~4.5s instead of occupying
                    // a provider session while the main player is starving.
                    if (++consecutiveUnhealthyTicks >= 5) break
                    delay(900L)
                    continue
                }
                consecutiveUnhealthyTicks = 0

                // Real-time seek-preview requests take precedence over the
                // scheduled evenly spaced storyboard. Conflate rapid D-pad
                // moves rather than enqueueing old seek positions.
                val requested = previewTarget()
                val priority = requested in 2_000L until durationMs &&
                    requested != lastPreviewTarget &&
                    !cache.hasFrameNear(requested, 2_400L)
                val target = if (priority) {
                    lastPreviewTarget = requested
                    requested
                } else {
                    val pending = targets.getOrNull(cursor) ?: break
                    cursor++
                    pending
                }
                if (target in attempted || cache.hasFrameNear(target, 2_400L)) continue
                attempted.add(target)

                val start = SystemClock.elapsedRealtime()
                val bitmap = try {
                    withContext(Dispatchers.IO) {
                        // API 27+ decodes/scales at source; unlike getFrameAtTime,
                        // this avoids retaining a 4K full-resolution bitmap.
                        retriever.getScaledFrameAtTime(
                            target * 1_000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            208, 117,
                        )
                    }
                } catch (_: Exception) { null }
                val costMs = SystemClock.elapsedRealtime() - start
                if (costMs > 2_400L) slowFrames++ else slowFrames = 0

                if (bitmap == null) {
                    failures++
                } else if (coroutineContext.isActive) {
                    // All cache changes and Compose revisions happen on Main.
                    cache.storePrefetched(target, durationMs, bitmap)
                    shots++
                    failures = 0
                } else bitmap.recycle()

                // Preserve playback priority and avoid continuous native work.
                delay(if (costMs > 900L) 1_800L else 1_100L)
            }
        } finally {
            // Cancellation can't interrupt a native frame extraction immediately,
            // but release on IO as soon as the call returns.
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { retriever.release() }
            }
        }
    }
}

/**
 * Mounted only for the actively selected renderer (VLC or Media3).
 * Reading latest snapshot-backed callbacks avoids cancelling and reopening
 * the extractor on every playhead tick or transient buffering transition.
 */
@Composable
internal fun ProactiveSeekThumbnailPrefetch(
    streamUrl: String,
    userAgent: String,
    netflix: Boolean,
    durationMs: Long,
    positionMs: Long,
    playbackHealthy: Boolean,
    requestedPreviewMs: Long,
    cache: NetflixSeekFrameCache,
) {
    val latestDuration by rememberUpdatedState(durationMs)
    val latestPosition by rememberUpdatedState(positionMs)
    val latestHealthy by rememberUpdatedState(playbackHealthy)
    val latestRequested by rememberUpdatedState(requestedPreviewMs)

    LaunchedEffect(streamUrl, userAgent, netflix, cache) {
        if (!netflix || !ProactivePreviewPolicy.eligible(streamUrl, Build.VERSION.SDK_INT))
            return@LaunchedEffect
        // Startup playback has the highest priority; do not initiate extra
        // provider traffic until the main stream has settled.
        delay(8_000L)
        while (coroutineContext.isActive && latestDuration < 60_000L) delay(1_000L)
        if (!coroutineContext.isActive) return@LaunchedEffect

        ProactiveFrameExtractor.prefetch(
            streamUrl = streamUrl,
            userAgent = userAgent,
            cache = cache,
            durationMs = latestDuration,
            initialPositionMs = latestPosition,
            canWork = { latestHealthy },
            previewTarget = { latestRequested },
        )
    }
}
