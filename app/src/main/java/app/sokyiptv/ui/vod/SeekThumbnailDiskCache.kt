package app.sokyiptv.ui.vod

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/**
 * v78 fresh disk-first thumbnail store. Every five-second video bucket can
 * have its own JPEG. There is NO per-video frame-count cap. Memory never needs
 * to contain the full timeline.
 *
 * This v78 folder is independent from the approved v76 cache folder. Its name
 * contains no provider URL or credentials, only a SHA256-derived identifier.
 */
internal class SeekThumbnailDiskCache(context: Context, streamUrl: String) {
    private val root = File(context.applicationContext.cacheDir, "soky_trickplay_v78")
    private val identifier = MessageDigest.getInstance("SHA-256")
        .digest(streamUrl.toByteArray(Charsets.UTF_8)).take(20)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private val folder = File(root, identifier)

    companion object {
        private const val BUCKET_MS = FullVideoThumbnailPlan.SPACING_MS
        private const val MAX_CACHE_BYTES = 256L * 1024L * 1024L
        private const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    }

    data class Scene(val timestampMs: Long, val bitmap: Bitmap)

    private fun bucket(ms: Long): Long = (ms.coerceAtLeast(0L) / BUCKET_MS) * BUCKET_MS
    private fun fileAt(ms: Long) = File(folder, "${bucket(ms) / BUCKET_MS}.jpg")
    fun contains(ms: Long): Boolean = fileAt(ms).isFile

    suspend fun preparedCount(): Int = withContext(Dispatchers.IO) {
        folder.listFiles()?.count { it.isFile && it.extension == "jpg" } ?: 0
    }

    suspend fun save(ms: Long, image: Bitmap): Boolean = withContext(Dispatchers.IO) {
        if (ms < 0L || image.isRecycled) return@withContext false
        try {
            if (!folder.isDirectory && !folder.mkdirs()) return@withContext false
            val file = fileAt(ms)
            if (file.isFile && file.length() > 0L) return@withContext true
            // Unique temporary names prevent concurrent passive-sampler writes
            // from overwriting the indexer's output.
            val temporary = File.createTempFile("scene_", ".tmp", folder)
            try {
                val encoded = temporary.outputStream().buffered().use { out ->
                    image.compress(Bitmap.CompressFormat.JPEG, 58, out)
                }
                if (!encoded) return@withContext false
                if (!file.exists() && !temporary.renameTo(file)) {
                    if (!file.exists()) temporary.copyTo(file, overwrite = false)
                }
                folder.setLastModified(System.currentTimeMillis())
                true
            } finally {
                temporary.delete()
            }
        } catch (_: Exception) {
            // Never log media-source exceptions: URLs may include credentials.
            false
        }
    }

    suspend fun loadNear(requestMs: Long, maxDistanceMs: Long = 7_500L): Scene? =
        withContext(Dispatchers.IO) {
            if (requestMs < 0L) return@withContext null
            val center = (requestMs + BUCKET_MS / 2L) / BUCKET_MS
            val slots = (-2L..2L).map { center + it }
                .filter { it >= 0L }
                .sortedBy { abs(it * BUCKET_MS - requestMs) }
            for (slot in slots) {
                val at = slot * BUCKET_MS
                if (abs(at - requestMs) > maxDistanceMs) continue
                val file = fileAt(at)
                if (!file.isFile) continue
                val image = runCatching {
                    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                    })
                }.getOrNull()
                if (image != null) return@withContext Scene(at, image)
            }
            null
        }

    suspend fun maintain() = withContext(Dispatchers.IO) {
        runCatching {
            if (!root.isDirectory) return@runCatching
            val now = System.currentTimeMillis()
            val folders = root.listFiles()?.filter { it.isDirectory }.orEmpty()
            for (dir in folders) {
                if (now - dir.lastModified() > MAX_AGE_MS &&
                    dir.absolutePath != folder.absolutePath) dir.deleteRecursively()
            }
            val remaining = root.listFiles()?.filter { it.isDirectory }
                ?.sortedBy { it.lastModified() }.orEmpty()
            var total = remaining.sumOf { dir ->
                dir.listFiles()?.sumOf { it.length() } ?: 0L
            }
            for (dir in remaining) {
                if (total <= MAX_CACHE_BYTES) break
                if (dir.absolutePath == folder.absolutePath) continue
                val size = dir.listFiles()?.sumOf { it.length() } ?: 0L
                if (dir.deleteRecursively()) total -= size
            }
            folder.setLastModified(now)
        }
    }
}
