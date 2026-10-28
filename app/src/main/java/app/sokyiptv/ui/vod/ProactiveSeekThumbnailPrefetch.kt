package app.sokyiptv.ui.vod

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
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
 * One optional low-priority metadata connection at a time, no second video player.
 * Never seek or stop the active decoder to generate a preview.
 * Providers may refuse simultaneous HTTP connections or range requests.
 */
internal object ProactiveFrameExtractor {
    private val onlyOne = Mutex()

    data class Result(val supported: Boolean, val frames: Int)

    suspend fun extract(
        streamUrl: String,
        userAgent: String,
        cache: NetflixSeekFrameCache,
        disk: SeekThumbnailDiskCache,
        durationMs: Long,
        initialPositionMs: Long,
        canWork: () -> Boolean,
        previewTarget: () -> Long,
        demandOnly: Boolean,
    ): Result = onlyOne.withLock {
        // Start preparing the WHOLE movie once duration is known. Do not wait
        // for playback position, 'isPlaying', or startup/buffering health.
        // Health affects pacing only, never whether the preview queue runs.
        if (!coroutineContext.isActive) return@withLock Result(true, 0)
        cache.previewStatus = if (demandOnly)
            "Preparing selected seek scene…" else "Indexing full-video preview timeline…"
        val retriever = try {
            withContext(Dispatchers.IO) {
                MediaMetadataRetriever().also { media ->
                    try {
                        val headers = if (userAgent.isNotBlank())
                            mapOf("User-Agent" to userAgent) else emptyMap()
                        media.setDataSource(streamUrl, headers)
                    } catch (error: Exception) {
                        media.release()
                        throw error
                    }
                }
            }
        } catch (_: Exception) {
            // Extractor exceptions may contain provider credentials; never log them.
            cache.previewStatus = "Preview blocked or unsupported by this provider"
            return@withLock Result(false, 0)
        }

        // Metadata often reports duration before the playback engine.
        // This allows whole-video indexing even while video buffers.
        val effectiveDuration = if (durationMs >= 60_000L) durationMs else
            withContext(Dispatchers.IO) {
                runCatching {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                }.getOrNull() ?: 0L
            }
        if (effectiveDuration < 60_000L) {
            cache.previewStatus = "Waiting for video duration…"
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { retriever.release() }
            }
            return@withLock Result(true, 0)
        }
        var frames = 0
        var failures = 0
        var slowFrames = 0
        val attempted = HashSet<Long>()
        val targets = if (demandOnly)
            ProactivePreviewPolicy.requestedFrames(initialPositionMs, effectiveDuration)
        else ProactivePreviewPolicy.fiveSecondStoryboard(effectiveDuration, 0L)
        var next = 0
        val limit = if (demandOnly) 3 else targets.size
        var requests = 0
        var idleSince = 0L
        if (!demandOnly) {
            cache.updateFullVideoIndex(disk.preparedCount(), targets.size)
        }
        var lastRequested = -1L
        var lastRequestAt = 0L
        // Never exceed one bounded session. Long-running VOD sources still
        // get continuous playback priority and a strict memory limit.
        val maxLifetimeAt = SystemClock.elapsedRealtime() +
            if (demandOnly) 120_000L else 3_600_000L
        try {
            // Keep the *same* metadata connection alive after initial storyboard
            // completion. Later D-pad selections are handled here instead of
            // paying setDataSource + 3 health ticks for every new timestamp.
            while (coroutineContext.isActive && frames < limit &&
                requests < targets.size + 96 && failures < 4 && slowFrames < 4 &&
                SystemClock.elapsedRealtime() < maxLifetimeAt) {
                // v76 halted extraction on BUFFERING. v77 continues indexing
                // even before frames first arrive and during buffering, but
                // uses a much gentler pace while video is unhealthy.
                val videoHealthy = canWork()

                // Prefer the most recently selected scrub target over the prefetch queue.
                val requested = previewTarget()
                val tick = SystemClock.elapsedRealtime()
                val alignedRequest = ((requested + 2_500L) / 5_000L) * 5_000L
                val priority = !demandOnly && alignedRequest in 5_000L until effectiveDuration &&
                    alignedRequest != lastRequested && tick - lastRequestAt >= 85L &&
                    !disk.contains(alignedRequest) &&
                    !cache.hasFrameNear(alignedRequest, 2_000L) &&
                    alignedRequest !in attempted
                val target = when {
                    priority -> {
                        lastRequested = alignedRequest
                        lastRequestAt = tick
                        alignedRequest
                    }
                    next < targets.size -> targets[next++]
                    else -> {
                        if (idleSince == 0L) idleSince = tick
                        if (tick - idleSince >= 3_000L) break
                        delay(130L); continue
                    }
                }
                idleSince = 0L
                if (!attempted.add(target)) continue
                // Prefer already-prepared disk scenes rather than starting a
                // fresh metadata seek. The read loop independently hydrates
                // these frames into the in-memory LRU during remote scrubbing.
                // A nearby RAM frame is NOT proof that this exact five-second
                // disk bucket is prepared. v76 skipped many grid positions
                // because of a nearby but non-persisted playback screenshot.
                if (disk.contains(target)) continue
                requests++

                val started = SystemClock.elapsedRealtime()
                val image: Bitmap? = try {
                    withContext(Dispatchers.IO) {
                        // API 27+ scales in native code rather than allocating a 4K bitmap.
                        retriever.getScaledFrameAtTime(
                            target * 1_000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            160, 90,
                        )
                    }
                } catch (_: Exception) { null }
                val elapsed = SystemClock.elapsedRealtime() - started
                if (elapsed > 4_000L) slowFrames++ else slowFrames = 0
                if (image == null) failures++
                else if (coroutineContext.isActive) {
                    // These are nearest sync keyframes, not exact-frame guarantees.
                    // Persist before giving the bitmap to the bounded memory
                    // cache, which may recycle duplicates during insertion.
                    val saved = disk.save(target, image)
                    cache.storePrefetched(target, effectiveDuration, image)
                    frames++
                    failures = 0
                    if (!demandOnly && saved && frames % 16 == 0) {
                        cache.updateFullVideoIndex(disk.preparedCount(), targets.size)
                    }
                } else image.recycle()
                // Adaptive low-priority scheduling: quickly build the index
                // on fast range-capable providers; slow down (but never halt)
                // during buffering or slow remote reads.
                val pacingMs = when {
                    !videoHealthy -> 750L
                    elapsed > 1_800L -> 300L
                    elapsed > 650L -> 140L
                    else -> 40L
                }
                delay(pacingMs)
            }
        } finally {
            // Native getScaledFrameAtTime cannot be cancelled mid-call.
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { retriever.release() }
            }
        }
        if (!demandOnly) {
            cache.updateFullVideoIndex(disk.preparedCount(), targets.size)
            if (failures >= 4 || slowFrames >= 4) {
                cache.previewStatus = "Some previews unavailable from this provider · ${cache.indexedScenes}/${targets.size} cached"
            }
        } else if (frames == 0) {
            cache.previewStatus = "Selected scene not available from this provider"
        }
        // A decoder that returned no frames repeatedly is not a viable
        // preview source. Avoid four more provider connections in that case.
        Result(!(frames == 0 && (failures >= 3 || slowFrames >= 2)), frames)
    }
}

/**
 * Keep a single screen-scoped worker AFTER the initial storyboard is complete.
 * Unlike v71, a later D-pad seek can request fresh thumbnails with a capped
 * on-demand session; debouncing prevents a new HTTP session on every keypress.
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
    val context = LocalContext.current
    val disk = remember(cache, streamUrl) {
        SeekThumbnailDiskCache(context.applicationContext, streamUrl)
    }
    val latestDuration by rememberUpdatedState(durationMs)
    val latestPosition by rememberUpdatedState(positionMs)
    val latestHealthy by rememberUpdatedState(playbackHealthy)
    val latestRequest by rememberUpdatedState(requestedPreviewMs)

    // Reuse cached scenes from previous viewings immediately. This never opens
    // a stream or touches the active player; disk decoding stays on IO.
    LaunchedEffect(disk) { disk.maintain() }
    LaunchedEffect(cache, disk, netflix) {
        if (!netflix) return@LaunchedEffect
        var lastLoaded = -1L
        var lastCheckAt = 0L
        while (coroutineContext.isActive) {
            delay(55L)
            val requested = latestRequest
            if (requested < 0L) continue
            val now = SystemClock.elapsedRealtime()
            // When the scene was not yet cached, retry while the D-pad holds
            // steady. v76 made only one attempt per selection, so a frame
            // generated a moment later never appeared until another keypress.
            if (requested == lastLoaded && now - lastCheckAt < 400L) continue
            lastLoaded = requested
            lastCheckAt = now
            if (cache.hasFrameNear(requested, 3_500L)) continue
            disk.loadNear(requested, 10_000L)?.let { scene ->
                if (coroutineContext.isActive) {
                    cache.storePrefetched(scene.timestampMs,
                        latestDuration.coerceAtLeast(60_000L), scene.bitmap)
                } else scene.bitmap.recycle()
            }
        }
    }

    LaunchedEffect(streamUrl, userAgent, netflix, cache) {
        if (!netflix) return@LaunchedEffect
        if (!ProactivePreviewPolicy.eligible(streamUrl, Build.VERSION.SDK_INT)) {
            cache.previewStatus = "This stream supports only already-played snapshots"
            return@LaunchedEffect
        }
        // Start immediately, BEFORE playback necessarily has a duration or
        // renders the first frame. If metadata lacks duration, retry once
        // VLC/Media3 reports it; never wait for healthy playback.
        cache.previewStatus = "Preparing full-video previews…"
        var initial = ProactiveFrameExtractor.extract(
            streamUrl, userAgent, cache, disk, latestDuration, latestPosition,
            canWork = { latestHealthy },
            previewTarget = { latestRequest }, demandOnly = false,
        )
        if (!initial.supported) return@LaunchedEffect
        if (initial.frames == 0 && latestDuration < 60_000L) {
            while (coroutineContext.isActive && latestDuration < 60_000L) delay(150L)
            if (!coroutineContext.isActive) return@LaunchedEffect
            initial = ProactiveFrameExtractor.extract(
                streamUrl, userAgent, cache, disk, latestDuration, latestPosition,
                canWork = { latestHealthy },
                previewTarget = { latestRequest }, demandOnly = false,
            )
            if (!initial.supported) return@LaunchedEffect
        }

        var observed = -1L
        var changedAt = SystemClock.elapsedRealtime()
        var handled = -1L
        var sessions = 0
        while (coroutineContext.isActive) {
            delay(250L)
            val wanted = latestRequest
            val now = SystemClock.elapsedRealtime()
            if (wanted != observed) { observed = wanted; changedAt = now }
            if (wanted < 2_000L || wanted >= latestDuration ||
                now - changedAt < 350L || wanted == handled) continue
            if (cache.hasFrameNear(wanted, 4_000L)) {
                handled = wanted
                continue
            }
            if (sessions >= 4) {
                cache.previewStatus = "Further scenes require seeking into the video"
                handled = wanted
                continue
            }
            handled = wanted
            sessions++
            val result = ProactiveFrameExtractor.extract(
                streamUrl, userAgent, cache, disk, latestDuration, wanted,
                canWork = { latestHealthy },
                previewTarget = { latestRequest }, demandOnly = true,
            )
            if (!result.supported) return@LaunchedEffect
            delay(600L) // provider cooldown before any new metadata connection
        }
    }
}
