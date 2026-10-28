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
 * A separate predictive trickplay pipeline. It never seeks the playing
 * LibVLC / Media3 decoder, opens a second player or changes its buffer.
 *
 * A provider-supplied storyboard/sprite track would be ideal; standard
 * Xtream VOD URLs do not advertise one. For those URLs, a single background
 * MediaMetadataRetriever prepares a contact sheet to disk in stages.
 */
internal object NetflixTrickplayIndexer {
    private val oneExtraMetadataSession = Mutex()

    suspend fun run(
        streamUrl: String,
        userAgent: String,
        cache: NetflixSeekFrameCache,
        disk: SeekThumbnailDiskCache,
        durationNow: () -> Long,
        playbackHealthy: () -> Boolean,
        requestedPosition: () -> Long,
    ) = oneExtraMetadataSession.withLock {
        var retriever: MediaMetadataRetriever? = null
        try {
            val media = withContext(Dispatchers.IO) {
                MediaMetadataRetriever().also { item ->
                    try {
                        val headers = if (userAgent.isBlank()) emptyMap()
                            else mapOf("User-Agent" to userAgent)
                        item.setDataSource(streamUrl, headers)
                    } catch (error: Exception) {
                        runCatching { item.release() }
                        throw error
                    }
                }
            }
            retriever = media
            var duration = durationNow()
            if (duration < 60_000L) {
                duration = withContext(Dispatchers.IO) {
                    runCatching {
                        media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull()
                    }.getOrNull() ?: 0L
                }
            }
            while (coroutineContext.isActive && duration < 60_000L) {
                cache.previewStatus = "Waiting for video duration to prepare storyboard…"
                delay(200L)
                duration = durationNow()
            }
            if (!coroutineContext.isActive) return@withLock

            val planned = NetflixPreviewPolicy.progressive(duration)
            val expected = planned.size
            cache.updateDiskProgress(disk.preparedCount(), expected)
            var progress = 0
            var consecutiveFailures = 0
            var scanPosition = 0
            val attempted = HashSet<Long>()
            var latestSelection = -1L
            var pendingNeighbour = emptyList<Long>()
            var nextNeighbour = 0
            var priorityUsed = 0

            while (coroutineContext.isActive && scanPosition < planned.size) {
                val request = requestedPosition()
                val requestedBucket = if (request >= 0L)
                    NetflixPreviewPolicy.bucket(request, duration) else -1L
                if (requestedBucket != latestSelection) {
                    latestSelection = requestedBucket
                    pendingNeighbour = if (request >= 0L)
                        NetflixPreviewPolicy.aroundCursor(request, duration) else emptyList()
                    nextNeighbour = 0
                    priorityUsed = 0
                }

                // A new scrub destination is handled promptly. Do not waste
                // hundreds of decode operations on intermediate cursor positions.
                val target = if (priorityUsed < 3 && nextNeighbour < pendingNeighbour.size) {
                    priorityUsed++
                    pendingNeighbour[nextNeighbour++]
                } else {
                    priorityUsed = 0
                    planned[scanPosition++]
                }
                if (!attempted.add(target) || disk.contains(target)) continue

                val frame: Bitmap? = try {
                    withContext(Dispatchers.IO) {
                        media.getScaledFrameAtTime(
                            target * 1_000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            176, 99,
                        )
                    }
                } catch (_: Exception) { null }

                if (frame == null) {
                    consecutiveFailures++
                    if (consecutiveFailures >= 5) {
                        cache.previewStatus =
                            "Provider cannot independently decode more preview scenes"
                        break
                    }
                } else {
                    consecutiveFailures = 0
                    if (disk.save(target, frame)) progress++
                    val nowRequested = requestedPosition()
                    if (nowRequested >= 0L && abs(target - nowRequested) <= 7_500L) {
                        cache.storePrefetched(target, duration, frame)
                    } else frame.recycle()

                    if (progress > 0 && progress % 24 == 0) {
                        cache.updateDiskProgress(disk.preparedCount(), expected)
                    }
                }
                // Index even when playback is buffering, with gentler pacing
                // to protect the main video stream and slower providers.
                delay(if (playbackHealthy()) 100L else 600L)
            }
            cache.updateDiskProgress(disk.preparedCount(), expected)
            if (consecutiveFailures >= 5) {
                cache.previewStatus =
                    "Preview extraction limited by provider · ${cache.diskPrepared}/${expected} prepared"
            }
        } catch (_: Exception) {
            cache.previewStatus = "Independent preview thumbnails unavailable from provider"
        } finally {
            val item = retriever
            if (item != null) withContext(NonCancellable + Dispatchers.IO) {
                runCatching { item.release() }
            }
        }
    }
}

@Composable
internal fun NetflixTrickplayPrefetch(
    streamUrl: String,
    userAgent: String,
    enabled: Boolean,
    durationMs: Long,
    playbackHealthy: Boolean,
    requestedPreviewMs: Long,
    cache: NetflixSeekFrameCache,
) {
    val context = LocalContext.current
    val disk = remember(streamUrl) {
        SeekThumbnailDiskCache(context.applicationContext, streamUrl,
            namespace = "soky_netflix_trickplay_v1")
    }
    // Read-only fallback lets the new default immediately reuse already
    // generated v78 frames without changing the existing mode's disk cache.
    val existing = remember(streamUrl) {
        SeekThumbnailDiskCache(context.applicationContext, streamUrl)
    }
    val latestDuration by rememberUpdatedState(durationMs)
    val latestHealthy by rememberUpdatedState(playbackHealthy)
    val latestTarget by rememberUpdatedState(requestedPreviewMs)

    LaunchedEffect(enabled, disk) {
        if (enabled) disk.maintain()
    }
    LaunchedEffect(enabled, streamUrl, userAgent, disk, cache) {
        if (!enabled) return@LaunchedEffect
        if (!FullVideoThumbnailPlan.supportedSource(streamUrl, Build.VERSION.SDK_INT)) {
            cache.previewStatus = "Previously played thumbnails only for this stream"
            return@LaunchedEffect
        }
        NetflixTrickplayIndexer.run(
            streamUrl, userAgent, cache, disk,
            durationNow = { latestDuration },
            playbackHealthy = { latestHealthy },
            requestedPosition = { latestTarget },
        )
    }
    LaunchedEffect(enabled, disk, cache, existing) {
        if (!enabled) return@LaunchedEffect
        var lastRequested = -1L
        var lastCheckedAt = 0L
        while (coroutineContext.isActive) {
            val requested = latestTarget
            if (requested < 0L) { delay(130L); continue }
            // Conflate fast D-pad movement: if the cursor moved during disk
            // decoding, don't display an obsolete result as a new scene.
            val now = android.os.SystemClock.elapsedRealtime()
            if (requested == lastRequested && now - lastCheckedAt < 350L) {
                delay(55L)
                continue
            }
            lastRequested = requested
            lastCheckedAt = now
            if (!cache.hasFrameNear(requested, 2_700L)) {
                val scene = disk.loadNear(requested, 7_500L)
                    ?: existing.loadNear(requested, 7_500L)
                if (scene != null) {
                    if (latestTarget == requested && coroutineContext.isActive) {
                        cache.storePrefetched(scene.timestampMs,
                            latestDuration.coerceAtLeast(60_000L), scene.bitmap)
                    } else scene.bitmap.recycle()
                }
            }
            delay(55L)
        }
    }
}
