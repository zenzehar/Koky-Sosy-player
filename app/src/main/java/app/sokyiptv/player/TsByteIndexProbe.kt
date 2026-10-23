/* GPL-3.0-or-later. Fast, sequential MPEG-TS byte index sampling on IO. */
package app.sokyiptv.player

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Indexing never consumes the main media decoder or downloads the file.
 * Total requested payload is <= 576 KiB, sequential single requests only.
 * Abandon immediately on playback degradation, unsupported HTTP Range,
 * MIME mismatch (not TS), mixed PID, changed file, or timestamp discontinuity.
 */
internal object TsByteIndexProbe {
    private const val WINDOW = 65_536L
    private const val CONNECT_MS = 1_200
    private const val READ_MS = 1_200
    private val range = Regex("""bytes (\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
    private val offsets = listOf(0L, 12L, 28L, 45L, 62L, 78L, 89L, 97L)

    fun eligible(context: Context, url: String): Boolean =
        LowImpactTsDurationProbe.eligibleUrl(url) &&
            LowImpactTsDurationProbe.connectionLooksSuitable(context)

    private data class Sample(val start: Long, val total: Long, val bytes: ByteArray)

    suspend fun build(
        url: String, agent: String, healthy: () -> Boolean,
    ): TsByteSeekIndex? = withContext(Dispatchers.IO) {
        if (!LowImpactTsDurationProbe.eligibleUrl(url) || !healthy()) return@withContext null
        val first = fetch(url, agent, 0L, WINDOW - 1, healthy)
            ?: return@withContext null
        val size = first.total
        if (size < 2L * 1024 * 1024 || first.start != 0L) return@withContext null
        val firstClock = TransportStreamDuration.firstVideoClock(first.bytes)
            ?: return@withContext null
        val probes = mutableListOf(TsByteSeekIndex.Probe(0, size, firstClock))
        for (percent in offsets.drop(1)) {
            if (!healthy() || !currentCoroutineContext().isActive) return@withContext null
            // TS packet alignment is recovered from the sample bytes.
            val start = (size * percent / 100L).coerceIn(0L, size - WINDOW)
            val sample = fetch(url, agent, start, (start + WINDOW - 1).coerceAtMost(size - 1), healthy)
                ?: return@withContext null
            if (sample.total != size || sample.start != start) return@withContext null
            val clock = TransportStreamDuration.firstVideoClock(sample.bytes)
                ?: return@withContext null
            probes.add(TsByteSeekIndex.Probe(start, size, clock))
        }
        // Include a sample close to the file's actual end. The timeline label
        // is still approximate because the last decoded frame is not verified.
        val lastAt = size - WINDOW
        val tail = fetch(url, agent, lastAt, size - 1, healthy)
            ?: return@withContext null
        if (tail.total != size || tail.start != lastAt) return@withContext null
        val lastClock = TransportStreamDuration.firstVideoClock(tail.bytes)
            ?: return@withContext null
        probes.add(TsByteSeekIndex.Probe(lastAt, size, lastClock))
        TsByteSeekIndex.fromProbes(probes)
    }

    private suspend fun fetch(
        uri: String, agent: String, start: Long, end: Long, healthy: () -> Boolean,
    ): Sample? {
        if (!healthy() || !currentCoroutineContext().isActive) return null
        val c = (URL(uri).openConnection() as? HttpURLConnection) ?: return null
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = CONNECT_MS
            c.readTimeout = READ_MS
            c.useCaches = false
            c.setRequestProperty("Range", "bytes=$start-$end")
            c.setRequestProperty("Accept-Encoding", "identity")
            c.setRequestProperty("User-Agent", agent.take(200))
            if (c.responseCode != 206 || c.contentEncoding?.equals("identity", true) == false)
                return null
            val m = range.matchEntire(c.getHeaderField("Content-Range")?.trim() ?: return null)
                ?: return null
            val from = m.groupValues[1].toLongOrNull() ?: return null
            val through = m.groupValues[2].toLongOrNull() ?: return null
            val total = m.groupValues[3].toLongOrNull() ?: return null
            val count = through - from + 1
            if (from != start || through != end || count != WINDOW ||
                total <= through || c.contentLengthLong > count) return null
            val b = ByteArray(WINDOW.toInt())
            c.inputStream.use { stream ->
                var pos = 0
                while (pos < b.size && healthy() && currentCoroutineContext().isActive) {
                    val n = stream.read(b, pos, b.size - pos)
                    if (n <= 0) return null
                    pos += n
                }
                if (pos != b.size) return null
            }
            return Sample(start, total, b)
        } catch (_: Exception) {
            // URLs often contain IPTV credentials. Never log network exceptions.
            return null
        } finally {
            c.disconnect()
        }
    }
}
