/* GPL-3.0-or-later. Best-effort, strictly bounded range probe for TS VOD only. */
package app.sokyiptv.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Never buffers a second full movie or opens a second native decoder.
 * HTTP suffix byte range jumps directly to EOF if the origin permits it.
 * If the origin ignores Range (200), abort without reading the response.
 *
 * MPEG-TS only: MP4, Matroska, HLS, live, growing recordings and incompatible
 * providers keep the existing adaptive timeline. No redirects/retries.
 */
internal object LowImpactTsDurationProbe {
    private const val PREFIX_BYTES = 65_536
    private const val SUFFIX_BYTES = 98_304
    private const val MIN_FILE_BYTES = 1_048_576L
    private const val MIN_LINK_KBPS = 25_000
    private val rangePattern = Regex("""bytes (\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)

    internal fun eligibleUrl(url: String): Boolean = runCatching {
        val parsed = URL(url)
        (parsed.protocol == "http" || parsed.protocol == "https") &&
            parsed.path.lowercase().endsWith(".ts") &&
            parsed.userInfo == null
    }.getOrDefault(false)

    fun connectionLooksSuitable(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching false
        if (cm.isActiveNetworkMetered) return@runCatching false
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
        (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
            capabilities.linkDownstreamBandwidthKbps >= MIN_LINK_KBPS
    }.getOrDefault(false)

    private data class Part(val bytes: ByteArray, val begin: Long, val end: Long, val total: Long)

    suspend fun investigate(
        url: String,
        userAgent: String,
        playbackStillHealthy: () -> Boolean,
    ): Long? = withContext(Dispatchers.IO) {
        if (!eligibleUrl(url) || !playbackStillHealthy()) return@withContext null
        // Hard budget: ~160 KiB maximum across both requests, no body when
        // range unsupported, no implicit redirects or unlimited GET.
        val beginning = readRange(url, userAgent, "bytes=0-${PREFIX_BYTES - 1}", PREFIX_BYTES)
            ?: return@withContext null
        if (beginning.begin != 0L || beginning.total < MIN_FILE_BYTES ||
            !playbackStillHealthy() || !currentCoroutineContext().isActive) return@withContext null

        val tail = readRange(url, userAgent, "bytes=-$SUFFIX_BYTES", SUFFIX_BYTES)
            ?: return@withContext null
        if (!playbackStillHealthy() || !currentCoroutineContext().isActive ||
            tail.total != beginning.total || tail.end != tail.total - 1 ||
            tail.begin < beginning.end + 1) return@withContext null
        TransportStreamDuration.estimateMillis(beginning.bytes, tail.bytes)
    }

    private suspend fun readRange(
        url: String, agent: String, requestedRange: String, limit: Int,
    ): Part? {
        if (!currentCoroutineContext().isActive) return null
        val connection = (URL(url).openConnection() as? HttpURLConnection) ?: return null
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 1_800
            connection.readTimeout = 1_800
            connection.useCaches = false
            connection.setRequestProperty("Range", requestedRange)
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", agent.take(200))
            if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL) return null
            if (connection.contentEncoding?.equals("identity", ignoreCase = true) == false)
                return null
            val m = rangePattern.matchEntire(
                connection.getHeaderField("Content-Range")?.trim() ?: return null,
            ) ?: return null
            val begin = m.groupValues[1].toLongOrNull() ?: return null
            val end = m.groupValues[2].toLongOrNull() ?: return null
            val total = m.groupValues[3].toLongOrNull() ?: return null
            val size = end - begin + 1L
            if (begin < 0 || size !in 1..limit.toLong() ||
                total < size || end >= total || connection.contentLengthLong > limit) return null
            val bytes = ByteArray(size.toInt())
            connection.inputStream.use { input ->
                var offset = 0
                while (offset < bytes.size && currentCoroutineContext().isActive) {
                    val n = input.read(bytes, offset, bytes.size - offset)
                    if (n <= 0) return null
                    offset += n
                }
                if (offset != bytes.size) return null
            }
            return Part(bytes, begin, end, total)
        } catch (_: Exception) {
            // No raw URL, credentials or provider-specific response logged.
            return null
        } finally {
            connection.disconnect()
        }
    }
}
