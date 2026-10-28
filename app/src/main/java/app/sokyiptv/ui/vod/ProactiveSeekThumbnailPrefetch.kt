package app.sokyiptv.ui.vod

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * Independent VOD thumbnail indexer, rebuilt from v76.
 *
 * The playing decoder is never sought, paused, replaced or reconfigured. One
 * low-priority Android metadata retriever builds a whole-video five-second
 * index directly to the private disk cache. It does NOT wait for "isPlaying",
 * the resume time, the first frame, or a healthy network-buffering state.
 */
internal object WholeVideoDiskIndexer {
    private val oneRetrieverAcrossScreens = Mutex()

    suspend fun index(
        url: String,
        userAgent: String,
        cache: NetflixSeekFrameCache,
        disk: SeekThumbnailDiskCache,
        durationNow: () -> Long,
        playbackHealthy: () -> Boolean,
        requestedPosition: () -> Long,
    ) = oneRetrieverAcrossScreens.withLock {
        var retriever: MediaMetadataRetriever? = null
        try {
            val opened = withContext(Dispatchers.IO) {
                MediaMetadataRetriever().also { media ->
                    try {
                        val headers = if (userAgent.isBlank()) emptyMap()
                            else mapOf("User-Agent" to userAgent)
                        media.setDataSource(url, headers)
                    } catch (error: Exception) {
                        runCatching { media.release() }
                        throw error
                    }
                }
            }
            retriever = opened

            var duration = durationNow()
            if (duration < 60_000L) {
                duration = withContext(Dispatchers.IO) {
                    runCatching {
                        opened.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull()
                    }.getOrNull() ?: 0L
                }
            }
            // Duration is essential for a full index. Source metadata is tried
            // immediately, before the player is ready. If unavailable, wait for
            // the real player to learn it; this does not depend on buffering.
            while (coroutineContext.isActive && duration < 60_000L) {
                cache.previewStatus = "Waiting for video duration to index all scenes…"
                delay(180L)
                duration = durationNow()
            }
            if (!coroutineContext.isActive) return@withLock

            val plan = FullVideoThumbnailPlan.orderedTimeline(duration)
            val total = plan.size
            cache.updateDiskProgress(disk.preparedCount(), total)
            var sequenceIndex = 0
            var failures = 0
            var recentPriority = 0
            var lastRequestedBucket = -1L
            var completed = 0

            // No 96 / 192 / 2880-item total cap; completion is disk-based.
            // Coroutine ends when the video screen closes, the plan finishes,
            // or independent extraction is unsupported by the provider.
            while (coroutineContext.isActive && sequenceIndex < plan.size) {
                val selected = requestedPosition()
                val priorityBucket = if (selected >= 0L)
                    FullVideoThumbnailPlan.nearestBucket(selected, duration) else -1L
                val priority = priorityBucket >= 0L &&
                    priorityBucket != lastRequestedBucket &&
                    recentPriority < 3 && !disk.contains(priorityBucket)
                val timestamp = if (priority) {
                    lastRequestedBucket = priorityBucket
                    recentPriority++
                    priorityBucket
                } else {
                    recentPriority = 0
                    plan[sequenceIndex++]
                }
                if (disk.contains(timestamp)) continue

                // A keyframe approximation is acceptable for trickplay, but
                // is never presented as an exact decoded frame.
                val frame: Bitmap? = try {
                    withContext(Dispatchers.IO) {
                        opened.getScaledFrameAtTime(
                            timestamp * 1_000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            160, 90,
                        )
                    }
                } catch (_: Exception) { null }

                if (frame == null) {
                    failures++
                    if (failures >= 5) {
                        cache.previewStatus =
                            "Provider cannot supply more independent scene previews"
                        break
                    }
                } else {
                    failures = 0
                    if (disk.save(timestamp, frame)) completed++
                    // Keep only the requested portion of the timeline in RAM.
                    // All other images are saved to disk and immediately freed.
                    val wanted = requestedPosition()
                    if (wanted >= 0L && abs(timestamp - wanted) <= 7_500L) {
                        cache.storePrefetched(timestamp, duration, frame)
                    } else {
                        frame.recycle()
                    }
                    if (completed % 12 == 0) {
                        cache.updateDiskProgress(disk.preparedCount(), total)
                    }
                }

                // Continue indexing while buffering; simply reduce contention.
                // One retriever, never a second playback decoder.
                delay(if (playbackHealthy()) 85L else 500L)
            }
            cache.updateDiskProgress(disk.preparedCount(), total)
            if (failures >= 5) {
                cache.previewStatus = "Provider limits remote previews · cached ${cache.diskPrepared}/${total}"
            }
        } catch (_: Exception) {
            cache.previewStatus = "Independent preview extraction unavailable"
        } finally {
            // Native retriever calls cannot be cancelled mid-frame. Release
            // safely once the call returns, including during episode changes.
            val toRelease = retriever
            if (toRelease != null) withContext(NonCancellable + Dispatchers.IO) {
                runCatching { toRelease.release() }
            }
        }
    }
}

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
    val context = LocalContext.current
    val disk = remember(streamUrl) {
        SeekThumbnailDiskCache(context.applicationContext, streamUrl)
    }
    val currentDuration by rememberUpdatedState(durationMs)
    val currentHealthy by rememberUpdatedState(playbackHealthy)
    val currentRequest by rememberUpdatedState(requestedPreviewMs)

    LaunchedEffect(disk) { disk.maintain() }

    // Begin indexing at stream entry, regardless of playback position, pause,
    // buffering, or whether the Netflix-like visual style is currently shown.
    LaunchedEffect(streamUrl, userAgent, disk, cache) {
        if (!FullVideoThumbnailPlan.supportedSource(streamUrl, Build.VERSION.SDK_INT)) {
            cache.previewStatus =
                "Only previously played frames can be cached for this source"
        } else {
            WholeVideoDiskIndexer.index(
                streamUrl, userAgent, cache, disk,
                durationNow = { currentDuration },
                playbackHealthy = { currentHealthy },
                requestedPosition = { currentRequest },
            )
        }
    }

    // Lightweight display reader. It never waits for the video player to
    // buffer or seek. Retry briefly for the same selected position because
    // the background indexer may finish its JPEG after the first lookup.
    LaunchedEffect(streamUrl, cache, disk) {
        while (coroutineContext.isActive) {
            val target = currentRequest
            if (target >= 0L && !cache.hasFrameNear(target, 2_800L)) {
                val scene = disk.loadNear(target, 7_500L)
                if (scene != null) {
                    if (currentRequest == target && coroutineContext.isActive) {
                        cache.storePrefetched(
                            scene.timestampMs,
                            currentDuration.coerceAtLeast(60_000L),
                            scene.bitmap,
                        )
                    } else scene.bitmap.recycle()
                }
            }
            delay(if (target >= 0L) 70L else 200L)
        }
    }
}
