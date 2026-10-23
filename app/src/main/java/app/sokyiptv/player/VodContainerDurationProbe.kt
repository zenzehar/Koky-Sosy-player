/* GPL-3.0-or-later. Seek Solution 2: bounded, optional metadata read. */
package app.sokyiptv.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Small metadata-only requests; no secondary decoder, full-media buffering,
 * recursive playlist traversal, redirects or retries. Never blocks the player.
 */
internal object VodContainerDurationProbe {
    internal enum class Type { HLS_VOD, MP4 }
    internal fun typeOf(uri: String): Type? = runCatching {
        val url = URL(uri)
        if (url.protocol !in listOf("http", "https") || url.userInfo != null) return@runCatching null
        when (url.path.lowercase().substringAfterLast('.')) {
            "m3u8" -> Type.HLS_VOD
            "mp4", "m4v" -> Type.MP4
            else -> null
        }
    }.getOrNull()

    suspend fun investigate(
        uri: String, agent: String, healthy: () -> Boolean,
    ): Long? = withContext(Dispatchers.IO) {
        if (!healthy()) return@withContext null
        when (typeOf(uri)) {
            Type.HLS_VOD -> read(uri, agent, null, 128 * 1024, false, healthy)?.let {
                VodEndMetadata.hlsVodDurationMillis(it)
            }
            Type.MP4 -> {
                val header = read(uri, agent, "bytes=0-65535", 65_536, true, healthy)
                    ?: return@withContext null
                VodEndMetadata.mp4MovieDurationMillis(header) ?: run {
                    if (!healthy() || !currentCoroutineContext().isActive) return@run null
                    val tail = read(uri, agent, "bytes=-262144", 262_144, true, healthy)
                        ?: return@run null
                    VodEndMetadata.mp4MovieDurationMillis(tail)
                }
            }
            null -> null
        }
    }

    private suspend fun read(
        uri: String, agent: String, range: String?, maxBytes: Int, requirePartial: Boolean,
        healthy: () -> Boolean,
    ): ByteArray? {
        if (!healthy() || !currentCoroutineContext().isActive) return null
        val c = (URL(uri).openConnection() as? HttpURLConnection) ?: return null
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 1_400
            c.readTimeout = 1_400
            c.useCaches = false
            c.setRequestProperty("Accept-Encoding", "identity")
            c.setRequestProperty("User-Agent", agent.take(200))
            if (range != null) c.setRequestProperty("Range", range)
            val response = c.responseCode
            if ((requirePartial && response != 206) || (!requirePartial && response != 200) ||
                c.contentLengthLong > maxBytes ||
                c.contentEncoding?.equals("identity", ignoreCase = true) == false) return null
            if (requirePartial) {
                val contentRange = c.getHeaderField("Content-Range") ?: return null
                if (!contentRange.startsWith("bytes ", ignoreCase = true)) return null
                val length = contentRange.substringAfter("bytes ")
                    .substringBefore('/').split('-').mapNotNull { it.toLongOrNull() }
                if (length.size != 2 || length[0] < 0L || length[1] < length[0] ||
                    length[1] - length[0] + 1 > maxBytes) return null
            }
            val buffer = ByteArray(maxBytes)
            var used = 0
            c.inputStream.use { input ->
                while (used < maxBytes && healthy() && currentCoroutineContext().isActive) {
                    val n = input.read(buffer, used, maxBytes - used)
                    if (n <= 0) break
                    used += n
                }
                if (used == maxBytes && input.read() != -1) return null
            }
            return if (used > 0 && healthy()) buffer.copyOf(used) else null
        } catch (_: Exception) {
            return null
        } finally {
            c.disconnect()
        }
    }
}
