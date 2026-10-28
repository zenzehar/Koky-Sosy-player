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
        // Do not compete with startup or a buffering main stream.
        var good = 0
        var waited = 0
        while (coroutineContext.isActive && good < 2 && waited++ < 36) {
            delay(500L)
            good = if (canWork()) good + 1 else 0
        }
        if (!coroutineContext.isActive || good < 2) return@withLock Result(true, 0)

        cache.previewStatus = if (demandOnly) "Finding selected scene…" else "Preparing future scenes…"
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

        var frames = 0
        var failures = 0
        var slowFrames = 0
        var unhealthy = 0
        val attempted = HashSet<Long>()
        val targets = if (demandOnly)
            ProactivePreviewPolicy.requestedFrames(initialPositionMs, durationMs)
        else ProactivePreviewPolicy.tenSecondStoryboard(durationMs, initialPositionMs)
        var next = 0
        val limit = if (demandOnly) 3 else targets.size
        var requests = 0
        var lastRequested = -1L
        var lastRequestAt = 0L
        // Never exceed one bounded session. Long-running VOD sources still
        // get continuous playback priority and a strict memory limit.
        val maxLifetimeAt = SystemClock.elapsedRealtime() +
            if (demandOnly) 90_000L else 900_000L
        try {
            // Keep the *same* metadata connection alive after initial storyboard
            // completion. Later D-pad selections are handled here instead of
            // paying setDataSource + 3 health ticks for every new timestamp.
            while (coroutineContext.isActive && frames < limit &&
                requests < targets.size + 40 && failures < 4 && slowFrames < 4 &&
                SystemClock.elapsedRealtime() < maxLifetimeAt) {
                if (!canWork()) {
                    // Pause all remote preview work while the primary decoder
                    // is buffering. Allow it to recover rather than discarding
                    // a partially completed whole-video contact sheet.
                    if (++unhealthy >= 60) break
                    delay(500L)
                    continue
                }
                unhealthy = 0

                // Prefer the most recently selected scrub target over the prefetch queue.
                val requested = previewTarget()
                val tick = SystemClock.elapsedRealtime()
                val priority = !demandOnly && requested in 2_000L until durationMs &&
                    requested != lastRequested && tick - lastRequestAt >= 110L &&
                    !cache.hasFrameNear(requested, 4_000L) && requested !in attempted
                val target = when {
                    priority -> {
                        lastRequested = requested
                        lastRequestAt = tick
                        requested
                    }
                    next < targets.size -> targets[next++]
                    else -> { delay(130L); continue }
                }
                if (!attempted.add(target)) continue
                // Prefer already-prepared disk scenes rather than starting a
                // fresh metadata seek. The read loop independently hydrates
                // these frames into the in-memory LRU during remote scrubbing.
                if (disk.contains(target) || cache.hasFrameNear(target, 4_000L)) continue
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
                    disk.save(target, image)
                    cache.storePrefetched(target, durationMs, image)
                    frames++
                    failures = 0
                } else image.recycle()
                // Avoid competing with video decode but keep the contact sheet
                // moving promptly on sources that support independent frames.
                delay(if (elapsed > 900L) 650L else 180L)
            }
        } finally {
            // Native getScaledFrameAtTime cannot be cancelled mid-call.
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { retriever.release() }
            }
        }
        cache.previewStatus = when {
            frames > 0 -> "Cached ${frames} new 10-second scene previews"
            failures >= 3 || slowFrames >= 2 -> "Provider could not supply more seek thumbnails"
            else -> "Cached scenes available; other times may need loading"
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
        while (coroutineContext.isActive) {
            delay(55L)
            val requested = latestRequest
            if (requested < 0L || requested == lastLoaded) continue
            lastLoaded = requested
            if (cache.hasFrameNear(requested, 4_000L)) continue
            disk.loadNear(requested)?.let { scene ->
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
        delay(700L)
        while (coroutineContext.isActive && latestDuration < 60_000L) delay(750L)
        if (!coroutineContext.isActive) return@LaunchedEffect

        val initial = ProactiveFrameExtractor.extract(
            streamUrl, userAgent, cache, disk, latestDuration, latestPosition,
            canWork = { latestHealthy },
            previewTarget = { latestRequest }, demandOnly = false,
        )
        if (!initial.supported) return@LaunchedEffect

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
                now - changedAt < 650L || wanted == handled || !latestHealthy) continue
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
            delay(1_500L) // provider cooldown before any new metadata connection
        }
    }
}
