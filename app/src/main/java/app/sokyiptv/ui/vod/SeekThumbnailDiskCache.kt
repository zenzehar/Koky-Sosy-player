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
 * App-private, bounded JPEG contact-sheet storage. This is NOT a media decoder:
 * the existing low-priority retriever generates the scenes in the background.
 *
 * Only a SHA-256 digest of the stream URL becomes a directory name; no provider
 * credentials, raw URLs or headers are written to disk. Android may clear cacheDir
 * at any time, and that is safe: scenes will be regenerated when possible.
 */
internal class SeekThumbnailDiskCache(context: Context, streamUrl: String) {
    // Keep v76's v1 cache intact for exact rollback. v77 migrates its 10s
    // snapshots to the new 5s schema in a background IO pass.
    private val root = File(context.applicationContext.cacheDir, "soky_seek_frames_v2")
    private val digest = MessageDigest.getInstance("SHA-256")
        .digest(streamUrl.toByteArray(Charsets.UTF_8))
        .take(20).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val folder = File(root, digest)
    private val oldFolder = File(context.applicationContext.cacheDir,
        "soky_seek_frames_v1/$digest")

    companion object {
        const val GRANULARITY_MS = 5_000L
        private const val MAX_BYTES = 192L * 1024 * 1024
        private const val TTL_MS = 7L * 24 * 60 * 60 * 1_000
    }

    data class Scene(val timestampMs: Long, val bitmap: Bitmap)

    private fun frameFile(ms: Long) = File(folder, "${ms / GRANULARITY_MS}.jpg")
    fun contains(ms: Long): Boolean = frameFile(ms).isFile

    /** Actual number of stored images (not the 192-frame RAM window). */
    suspend fun preparedCount(): Int = withContext(Dispatchers.IO) {
        folder.listFiles()?.count { it.isFile && it.extension == "jpg" } ?: 0
    }

    suspend fun save(ms: Long, bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
        if (bitmap.isRecycled) return@withContext false
        try {
            if (!folder.isDirectory && !folder.mkdirs()) return@withContext false
            val file = frameFile(ms)
            if (file.isFile && file.length() > 0L) return@withContext true
            // The passive playback sampler and future-scene worker may both
            // hit the same bucket. Unique temporary files prevent corruption.
            val tmp = File.createTempFile("seek_", ".tmp", folder)
            try {
                tmp.outputStream().buffered().use {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 65, it)
                }
                if (!file.exists() && !tmp.renameTo(file)) {
                    file.outputStream().use { output ->
                        tmp.inputStream().use { input -> input.copyTo(output) }
                    }
                }
                folder.setLastModified(System.currentTimeMillis())
                true
            } finally {
                tmp.delete()
            }
        } catch (_: Exception) {
            false // never log potentially identifying provider information
        }
    }

    suspend fun loadNear(requestedMs: Long, maxDistanceMs: Long = 25_000L): Scene? =
        withContext(Dispatchers.IO) {
            if (requestedMs < 0L) return@withContext null
            val bucket = (requestedMs + GRANULARITY_MS / 2L) / GRANULARITY_MS
            val choices = (-5L..5L).map { bucket + it }
                .filter { it >= 0L }
                .sortedBy { abs(it * GRANULARITY_MS - requestedMs) }
            for (id in choices) {
                val stamp = id * GRANULARITY_MS
                if (abs(stamp - requestedMs) > maxDistanceMs) continue
                val file = frameFile(stamp)
                if (!file.isFile) continue
                val image = runCatching {
                    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                    })
                }.getOrNull()
                if (image != null) return@withContext Scene(stamp, image)
            }
            null
        }

    /**
     * Keep v76's already downloaded scenes. Legacy frame numbers represented
     * 10-second positions; v77 frame numbers represent 5-second positions.
     * Copying index n -> 2n is safe, idempotent and never edits the gold cache.
     */
    private fun migrateLegacy() {
        if (!oldFolder.isDirectory) return
        val marker = File(folder, ".migrated-v1")
        if (marker.isFile) return
        if (!folder.isDirectory && !folder.mkdirs()) return
        oldFolder.listFiles()?.asSequence()
            ?.filter { it.isFile && it.extension == "jpg" }
            ?.take(3_000)
            ?.forEach { old ->
                val number = old.nameWithoutExtension.toLongOrNull() ?: return@forEach
                val dest = File(folder, "${number * 2L}.jpg")
                if (!dest.exists()) runCatching { old.copyTo(dest, overwrite = false) }
            }
        runCatching { marker.writeText("v76 indexed scenes reused at 5-second resolution") }
    }

    /** Delete expired items and globally cap all private thumbnail folders. */
    suspend fun maintain() = withContext(Dispatchers.IO) {
        runCatching {
            migrateLegacy()
            if (!root.exists()) return@runCatching
            val now = System.currentTimeMillis()
            val folders = root.listFiles()?.filter { it.isDirectory }.orEmpty()
            for (dir in folders) {
                if (dir.lastModified() < now - TTL_MS) dir.deleteRecursively()
            }
            val remaining = root.listFiles()?.filter { it.isDirectory }
                ?.sortedBy { it.lastModified() }.orEmpty()
            var bytes = remaining.sumOf { dir ->
                dir.listFiles()?.sumOf { it.length() } ?: 0L
            }
            for (dir in remaining) {
                if (bytes <= MAX_BYTES) break
                val size = dir.listFiles()?.sumOf { it.length() } ?: 0L
                if (dir.absolutePath == folder.absolutePath) continue
                if (dir.deleteRecursively()) bytes -= size
            }
            folder.setLastModified(now)
        }
    }
}
