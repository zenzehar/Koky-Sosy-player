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
        val sessionBegan = android.os.SystemClock.elapsedRealtime()
        try {
            // Cold-source startup: opening the primary player and independent
            // metadata reader simultaneously can temporarily fail. Retry a
            // FEW times, without reopening or interfering with the player.
            for (attempt in 1..NetflixColdStartPolicy.MAX_OPEN_ATTEMPTS) {
                if (!coroutineContext.isActive) return@withLock
                val opened = runCatching {
                    withContext(Dispatchers.IO) {
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
                }.getOrNull()
                if (opened != null) {
                    retriever = opened
                    break
                }
                cache.previewStatus = "Preparing first-time video previews…"
                if (attempt < NetflixColdStartPolicy.MAX_OPEN_ATTEMPTS)
                    delay(800L * attempt)
            }
            val media = retriever ?: run {
                cache.previewStatus = "Provider refused independent first-time previews"
                return@withLock
            }

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
                cache.previewStatus = "Preparing video index; awaiting duration…"
                delay(180L)
                duration = durationNow()
                if (android.os.SystemClock.elapsedRealtime() - sessionBegan >
                    120_000L) return@withLock
            }
            if (!coroutineContext.isActive) return@withLock

            val planned = NetflixPreviewPolicy.progressive(duration)
            val expected = planned.size
            var prepared = disk.preparedCount()
            cache.updateDiskProgress(prepared, expected)
            var successes = 0
            var failures = 0
            var nextBackground = 0
            var lastSelection = -1L
            var neighbours = emptyList<Long>()
            var nextNeighbour = 0
            var usedInBurst = 0
            var backgroundSinceCursorRetry = 0
            val failuresAt = HashMap<Long, Int>()
            val attemptedAt = HashMap<Long, Long>()
            val retries = LinkedHashSet<Long>()

            while (coroutineContext.isActive &&
                android.os.SystemClock.elapsedRealtime() - sessionBegan <
                    NetflixColdStartPolicy.MAX_SESSION_MS) {
                val requested = requestedPosition()
                val selection = if (requested >= 0L)
                    NetflixPreviewPolicy.bucket(requested, duration) else -1L
                if (selection != lastSelection) {
                    lastSelection = selection
                    neighbours = if (requested >= 0L)
                        NetflixPreviewPolicy.aroundCursor(requested, duration)
                    else emptyList()
                    nextNeighbour = 0
                    usedInBurst = 0
                    backgroundSinceCursorRetry = 0
                }

                val now = android.os.SystemClock.elapsedRealtime()
                val target = when {
                    NetflixColdStartPolicy.usePriority(
                        nextNeighbour < neighbours.size, usedInBurst) -> {
                        usedInBurst++
                        neighbours[nextNeighbour++]
                    }
                    // A selected *failed* cold thumbnail must be retried
                    // promptly, not only after all 1,439 film frames finish.
                    selection >= 0L && backgroundSinceCursorRetry >= 4 &&
                        !disk.contains(selection) &&
                        NetflixColdStartPolicy.canTryFrame(
                            failuresAt[selection] ?: 0,
                            attemptedAt[selection] ?: 0L, now) -> {
                        backgroundSinceCursorRetry = 0
                        selection
                    }
                    nextBackground < planned.size -> {
                        usedInBurst = 0
                        backgroundSinceCursorRetry++
                        planned[nextBackground++]
                    }
                    retries.isNotEmpty() -> {
                        // Failed cold-start timestamps come back into the
                        // queue. No permanent 'attempted' blacklist on failure.
                        val retry = retries.firstOrNull { time ->
                            NetflixColdStartPolicy.canTryFrame(
                                failuresAt[time] ?: 0, attemptedAt[time] ?: 0L, now)
                        }
                        if (retry == null) { delay(200L); continue }
                        retries.remove(retry)
                        retry
                    }
                    else -> break
                }
                if (disk.contains(target)) continue
                val previouslyFailed = failuresAt[target] ?: 0
                if (previouslyFailed >= NetflixColdStartPolicy.MAX_FRAME_ATTEMPTS) continue
                if (!NetflixColdStartPolicy.canTryFrame(
                        previouslyFailed, attemptedAt[target] ?: 0L, now)) {
                    retries.add(target)
                    continue
                }
                attemptedAt[target] = now
                val operationStarted = android.os.SystemClock.elapsedRealtime()
                // The same pending retry may be selected by foreground cursor.
                retries.remove(target)
                val frame: Bitmap? = try {
                    withContext(Dispatchers.IO) {
                        media.getScaledFrameAtTime(
                            target * 1_000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            176, 99,
                        )
                    }
                } catch (_: Exception) { null }
                val elapsed = android.os.SystemClock.elapsedRealtime() - operationStarted

                if (frame == null) {
                    failures++
                    if (successes == 0 && failures <= 6)
                        cache.previewStatus = "Warming first-time thumbnail extraction…"
                    val attempts = previouslyFailed + 1
                    failuresAt[target] = attempts
                    if (attempts < NetflixColdStartPolicy.MAX_FRAME_ATTEMPTS)
                        retries.add(target)
                    // Previously, only FIVE null frames ended all indexing.
                    // Startup and buffering are transient on many providers.
                    if (NetflixColdStartPolicy.shouldGiveUp(successes, failures, android.os.SystemClock.elapsedRealtime() - sessionBegan)) {
                        cache.previewStatus =
                            "Provider restricts first-time extraction · ${cache.diskPrepared}/${expected} ready"
                        break
                    }
                } else {
                    failuresAt.remove(target)
                    attemptedAt.remove(target)
                    retries.remove(target)
                    if (disk.save(target, frame)) {
                        successes++
                        prepared++
                    }
                    // Show a requested cold scene immediately on decode; do
                    // not wait for disk polling or the active video to buffer.
                    val latest = requestedPosition()
                    if (latest >= 0L && abs(target - latest) <= 10_000L) {
                        cache.storePrefetched(target, duration, frame)
                    } else frame.recycle()
                    if (successes <= 3 || successes % 8 == 0) {
                        cache.updateDiskProgress(prepared, expected)
                    }
                }

                // First scenes receive priority, even while playback buffers.
                // Later work remains low-priority to protect streaming.
                delay(NetflixColdStartPolicy.pacingMs(
                    playbackHealthy(), successes, elapsed))
            }

            cache.updateDiskProgress(disk.preparedCount(), expected)
            if (NetflixColdStartPolicy.shouldGiveUp(successes, failures, android.os.SystemClock.elapsedRealtime() - sessionBegan)) {
                cache.previewStatus =
                    "Provider limits uncached previews · ${cache.diskPrepared}/${expected} scenes available"
            }
        } catch (_: Exception) {
            cache.previewStatus = "Independent first-time preview extraction unavailable"
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
            // Cold thumbnails may arrive immediately after the initial miss.
            // Use a short, bounded disk recheck instead of waiting 350ms.
            if (requested == lastRequested && now - lastCheckedAt < 120L) {
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
