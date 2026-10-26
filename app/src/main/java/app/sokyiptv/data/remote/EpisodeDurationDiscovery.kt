/* GPL-3.0-or-later: deliberately slow, cancellable, metadata-only episode inspection. */
package app.sokyiptv.data.remote

import app.sokyiptv.data.model.Episode
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * At most one metadata request at once, across all active series screens.
 * No LibVLC/Media3 instances, no media seek or stream download. On playback
 * entry cancel the current OkHttp call immediately to return connection budget
 * to the video player. Do not expose credential-bearing URLs in any diagnostics.
 */
internal object EpisodeDurationDiscovery {
    enum class Result { DISCOVERED, UNAVAILABLE, UNSUPPORTED, PROVIDER_BUSY, RANGE_UNSUPPORTED }
    data class Inspection(
        val status: Result, val seconds: Int? = null,
        val approximate: Boolean = false,
    )

    private const val MAX_RESPONSE = 262_144
    private val serial = Mutex()
    private val playing = AtomicInteger(0)
    @Volatile private var inFlight: Call? = null
    val playbackActive: Boolean get() = playing.get() > 0

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(1, 10L, TimeUnit.SECONDS))
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(false)
        .build()

    fun playbackEntered() {
        playing.incrementAndGet()
        inFlight?.cancel()
    }

    fun playbackExited() {
        // AtomicInteger.updateAndGet() is not present on every older Fire OS
        // runtime. A plain CAS loop works down to our minSdk 23.
        while (true) {
            val now = playing.get()
            if (now <= 0 || playing.compareAndSet(now, now - 1)) return
        }
    }

    suspend fun inspect(episode: Episode, userAgent: String): Inspection =
        withContext(Dispatchers.IO) {
            serial.withLock {
                if (playbackActive) return@withLock Inspection(Result.UNAVAILABLE)
                val url = episode.streamUrl.toHttpUrlOrNull()
                    ?: return@withLock Inspection(Result.UNAVAILABLE)
                // A significant share of IPTV episode URLs end in .mkv or
                // .ts, which v58 silently skipped. Probe both only with bounded
                // bytes, never an additional decoder or complete media transfer.
                val extension = url.encodedPath.substringAfterLast('.', "").lowercase()
                if (extension !in setOf("mp4", "m4v", "mov", "m3u8", "mkv", "ts"))
                    return@withLock Inspection(Result.UNSUPPORTED)

                try {
                    val found = when (extension) {
                        "m3u8" -> requestBytes(url.toString(), userAgent, range = null)
                            ?.let { BoundedVODDurationParser.hlsVodSeconds(it) to false }
                        "mkv" -> {
                            val front = requestBytes(url.toString(), userAgent, "bytes=0-131071")
                            val exact = front?.let(MatroskaDurationReader::seconds)
                            if (exact != null) exact to false
                            else {
                                // Some IPTV servers mislabel transport streams
                                // as .mkv. A PCR estimate requires the tail too.
                                val tail = if (front != null && !playbackActive)
                                    requestBytes(url.toString(), userAgent, "bytes=-262144")
                                    else null
                                if (front != null && tail != null)
                                    TransportStreamDurationReader.approximateSeconds(front, tail) to true
                                else null
                            }
                        }
                        "ts" -> {
                            val front = requestBytes(url.toString(), userAgent, "bytes=0-131071")
                            val back = if (front != null && !playbackActive)
                                requestBytes(url.toString(), userAgent, "bytes=-262144")
                                else null
                            if (front != null && back != null)
                                TransportStreamDurationReader.approximateSeconds(front, back) to true
                                else null
                        }
                        else -> {
                            val front = requestBytes(url.toString(), userAgent, "bytes=0-131071")
                            val headTime = front?.let(BoundedVODDurationParser::mp4Seconds)
                                ?: front?.let(MatroskaDurationReader::seconds)
                            if (headTime != null) headTime to false
                            else {
                                val tail = if (front != null && !playbackActive)
                                    requestBytes(url.toString(), userAgent, "bytes=-262144")
                                    else null
                                val exact = tail?.let(BoundedVODDurationParser::mp4Seconds)
                                if (exact != null) exact to false
                                else if (front != null && tail != null)
                                    TransportStreamDurationReader.approximateSeconds(front, tail) to true
                                else null
                            }
                        }
                    }
                    val seconds = found?.first
                    if (seconds != null && !playbackActive)
                        Inspection(Result.DISCOVERED, seconds, approximate = found.second)
                    else Inspection(Result.UNAVAILABLE)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: RangeUnsupported) {
                    // The server would send full videos when Range is requested.
                    // Stop discovery for this series instead of hammering it.
                    Inspection(Result.RANGE_UNSUPPORTED)
                } catch (_: ProviderDenied) {
                    // Avoid more requests when a provider enforces connection limits
                    // or rate-limits; revisit only on a future series-screen visit.
                    Inspection(Result.PROVIDER_BUSY)
                } catch (_: Exception) {
                    // Unsupported container, offline or invalid header: no crash and
                    // no log of URLs, user names, access tokens or raw responses.
                    Inspection(Result.UNAVAILABLE)
                }
            }
        }

    private class ProviderDenied : IOException()
    private class RangeUnsupported : IOException()

    /** Range MUST return 206; a 200 can be an entire multi-GB video, so do not
     * consume a single byte in that case. A bounded HLS manifest may use 200. */
    private suspend fun requestBytes(url: String, userAgent: String, range: String?): ByteArray? {
        if (playbackActive) return null
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Encoding", "identity")
            .apply { if (range != null) header("Range", range) }
            .get().build()
        val call = client.newCall(request)
        inFlight = call
        try {
            if (playbackActive) {
                call.cancel()
                return null
            }
            val response = suspendCancellableCoroutine<Response> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        if (continuation.isActive) continuation.resume(response)
                        else response.close()
                    }
                })
            }
            return response.use {
                if (it.code == 429 || it.code == 401 || it.code == 403 ||
                    it.code == 503) throw ProviderDenied()
                if (range != null && it.code in listOf(200, 416)) throw RangeUnsupported()
                if (it.code != if (range == null) 200 else 206) return@use null
                if (range != null && it.header("Content-Range").isNullOrBlank())
                    return@use null
                val body = it.body ?: return@use null
                if (body.contentLength() > MAX_RESPONSE) return@use null
                val input = body.byteStream()
                val output = java.io.ByteArrayOutputStream(minOf(MAX_RESPONSE, 8192))
                val buf = ByteArray(8192)
                while (output.size() <= MAX_RESPONSE) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (playbackActive) return@use null
                    val count = input.read(buf, 0,
                        minOf(buf.size, MAX_RESPONSE + 1 - output.size()))
                    if (count == -1) break
                    output.write(buf, 0, count)
                    if (output.size() > MAX_RESPONSE) return@use null
                }
                output.toByteArray()
            }
        } finally {
            if (inFlight === call) inFlight = null
        }
    }
}
