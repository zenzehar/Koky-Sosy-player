/* GPL-3.0-or-later. Resumable, foreground, persistent APK transfer. */
package app.sokyiptv.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.sokyiptv.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

internal data class UpdateTransferSnapshot(
    val stage: String = "none",
    val version: String = "",
    val url: String = "",
    val expectedBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val error: String = "",
) {
    val fraction: Float get() =
        if (expectedBytes > 0L)
            (downloadedBytes.toDouble() / expectedBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        else -1f
}

/**
 * Only one APK transfer is active. Persist metadata in SharedPreferences for
 * crash recovery; .part remains until aborted or replaced with another version.
 * A completed download is NOT installed in the background: Android's installer
 * UI must be opened after an explicit foreground user interaction.
 */
internal object UpdateTransfer {
    private const val PREFS = "soky_update_transfer_v1"
    private const val UNIQUE = "soky_resumable_apk_transfer"
    const val NOTIFICATION_ID = 7944
    private const val CHANNEL = "soky_apk_downloads"
    private val _state = MutableStateFlow(UpdateTransferSnapshot())
    val state: StateFlow<UpdateTransferSnapshot> = _state

    fun directory(context: Context): File =
        File(context.cacheDir, "updates").apply { mkdirs() }
    fun partial(context: Context): File = File(directory(context), "soky-transfer.part")
    fun ready(context: Context): File = File(directory(context), "sokyiptv-update.apk")

    @Synchronized fun restore(context: Context): UpdateTransferSnapshot {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val snapshot = UpdateTransferSnapshot(
            p.getString("stage", "none") ?: "none",
            p.getString("version", "") ?: "",
            p.getString("url", "") ?: "",
            p.getLong("expected", 0L),
            p.getLong("downloaded", 0L),
            p.getString("error", "") ?: "",
        )
        _state.value = snapshot
        return snapshot
    }

    @Synchronized fun change(context: Context, snapshot: UpdateTransferSnapshot) {
        _state.value = snapshot
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("stage", snapshot.stage)
            .putString("version", snapshot.version)
            .putString("url", snapshot.url)
            .putLong("expected", snapshot.expectedBytes)
            .putLong("downloaded", snapshot.downloadedBytes)
            .putString("error", snapshot.error)
            .apply()
    }

    fun start(context: Context, update: UpdateChecker.Update) {
        val old = restore(context)
        if (old.stage == "downloading" && old.version == update.versionName) return
        if (old.version != update.versionName || old.url != update.apkUrl ||
            old.expectedBytes != update.apkSizeBytes) {
            partial(context).delete()
            ready(context).delete()
            etag(context, null)
        }
        change(context, UpdateTransferSnapshot(
            stage = "downloading", version = update.versionName, url = update.apkUrl,
            expectedBytes = update.apkSizeBytes,
            downloadedBytes = partial(context).length()
        ))
        enqueue(context)
    }

    fun pause(context: Context) {
        val old = restore(context)
        if (old.stage != "downloading") return
        change(context, old.copy(stage = "paused",
            downloadedBytes = partial(context).length()))
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
    }

    fun resume(context: Context) {
        val old = restore(context)
        if (old.stage !in listOf("paused", "failed") || old.url.isBlank()) return
        change(context, old.copy(stage = "downloading",
            error = "", downloadedBytes = partial(context).length()))
        enqueue(context)
    }

    fun abort(context: Context) {
        val old = restore(context)
        change(context, old.copy(stage = "aborted"))
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
        partial(context).delete()
        ready(context).delete()
        etag(context, null)
        change(context, UpdateTransferSnapshot())
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.cancel(NOTIFICATION_ID)
    }

    fun setReady(context: Context) {
        val old = restore(context)
        if (old.stage != "downloading") return
        change(context, old.copy(stage = "ready", downloadedBytes = ready(context).length()))
    }

    fun failed(context: Context, error: String) {
        val old = restore(context)
        if (old.stage != "downloading") return
        change(context, old.copy(stage = "failed",
            downloadedBytes = partial(context).length(), error = error.take(180)))
    }

    private fun enqueue(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val work = OneTimeWorkRequestBuilder<ResumableUpdateWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, work)
    }

    fun etag(context: Context, value: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("etag", value).apply()
    }
    fun etag(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("etag", null)

    fun notification(context: Context, state: UpdateTransferSnapshot): Notification {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, "Soky app updates", NotificationManager.IMPORTANCE_LOW
            ))
        }
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val content = PendingIntent.getActivity(context, 7944, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(context, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(context)
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Soky IPTV Pro update")
            .setContentText(when (state.stage) {
                "ready" -> "Download complete — open Soky to install"
                "paused" -> "Paused — resume when ready"
                "failed" -> "Interrupted — tap Resume"
                else -> "Downloading " + state.version
            })
            .setOngoing(state.stage == "downloading")
            .setContentIntent(content)
            .setOnlyAlertOnce(true)
        if (state.expectedBytes > 0)
            builder.setProgress(100, (state.fraction * 100).toInt(), false)
        else builder.setProgress(0, 0, true)
        if (state.stage in listOf("downloading", "paused", "failed")) {
            val action = if (state.stage == "downloading") "pause" else "resume"
            val label = if (action == "pause") "Pause" else "Resume"
            builder.addAction(android.R.drawable.ic_media_pause, label,
                actionIntent(context, action, 7945))
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel,
                "Abort", actionIntent(context, "abort", 7946))
        }
        return builder.build()
    }

    fun notify(context: Context, snapshot: UpdateTransferSnapshot = restore(context)) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification(context, snapshot))
        }
    }

    private fun actionIntent(context: Context, command: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, UpdateTransferActionReceiver::class.java)
            .setAction("app.sokyiptv.update." + command)
        return PendingIntent.getBroadcast(context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

/** Notification controls are explicit, non-exported broadcasts. */
class UpdateTransferActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            "app.sokyiptv.update.pause" -> UpdateTransfer.pause(context)
            "app.sokyiptv.update.resume" -> UpdateTransfer.resume(context)
            "app.sokyiptv.update.abort" -> UpdateTransfer.abort(context)
        }
        val stage = UpdateTransfer.restore(context)
        if (stage.stage != "none") UpdateTransfer.notify(context, stage)
    }
}

class ResumableUpdateWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val state = UpdateTransfer.restore(applicationContext)
        val type = if (Build.VERSION.SDK_INT >= 29)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        return ForegroundInfo(UpdateTransfer.NOTIFICATION_ID,
            UpdateTransfer.notification(applicationContext, state), type)
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val state = UpdateTransfer.restore(ctx)
        if (state.stage != "downloading" || state.url.isBlank()) return Result.success()
        return try {
            setForeground(getForegroundInfo())
            transfer(ctx, state)
            if (UpdateTransfer.restore(ctx).stage == "ready") Result.success()
            else Result.failure()
        } catch (e: CancellationException) {
            throw e // Pause and abort leave a valid .part for the next attempt.
        } catch (e: Exception) {
            if (runAttemptCount < 3 && UpdateTransfer.restore(ctx).stage == "downloading") {
                Result.retry() // Partial data remains available for a verified range request.
            } else {
                UpdateTransfer.failed(ctx, e.javaClass.simpleName)
                UpdateTransfer.notify(ctx)
                Result.failure()
            }
        }
    }

    private suspend fun transfer(context: Context, snapshot: UpdateTransferSnapshot) =
        withContext(Dispatchers.IO) {
            val part = UpdateTransfer.partial(context)
            val final = UpdateTransfer.ready(context)
            if (final.exists() && final.length() == snapshot.expectedBytes &&
                snapshot.expectedBytes > 0L) {
                UpdateTransfer.setReady(context)
                UpdateTransfer.notify(context)
                return@withContext
            }
            var offset = part.length()
            if (snapshot.expectedBytes > 0L && offset > snapshot.expectedBytes) {
                part.delete()
                offset = 0L
                UpdateTransfer.etag(context, null)
            }
            val client = OkHttpClient.Builder().followRedirects(true)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build()
            fun request(from: Long): Request {
                val b = Request.Builder().url(snapshot.url)
                    .header("User-Agent", "SokyIPTV").header("Accept-Encoding", "identity")
                if (from > 0L) {
                    b.header("Range", "bytes=" + from + "-")
                    UpdateTransfer.etag(context)?.let { b.header("If-Range", it) }
                }
                return b.build()
            }
            client.newCall(request(offset)).execute().use { response ->
                if (response.code !in listOf(200, 206)) {
                    // 416 may mean the full file is present; require exact expected bytes.
                    if (response.code == 416 && snapshot.expectedBytes > 0L &&
                        offset == snapshot.expectedBytes) {
                        if (!part.renameTo(final)) throw IOException("Cannot finish cached update")
                        UpdateTransfer.setReady(context)
                        UpdateTransfer.notify(context)
                        return@withContext
                    }
                    throw IOException("HTTP " + response.code)
                }
                val contentRange = response.header("Content-Range").orEmpty()
                val match = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(contentRange)
                val partialValid = offset > 0L && response.code == 206 &&
                    match?.groupValues?.get(1)?.toLongOrNull() == offset &&
                    (snapshot.expectedBytes <= 0L ||
                        match.groupValues[3].toLongOrNull() == snapshot.expectedBytes)
                // If the origin ignores Range, safely restart from zero, never append.
                // If 206 is malformed, fail rather than silently produce a corrupt APK.
                if (response.code == 206 && !partialValid)
                    throw IOException("Invalid partial content range")
                val append = partialValid
                if (!append) {
                    offset = 0L
                    UpdateTransfer.etag(context, null)
                }
                val storedEtag = UpdateTransfer.etag(context)
                val replyEtag = response.header("ETag") ?: response.header("Last-Modified")
                if (append && storedEtag != null && replyEtag != null &&
                    storedEtag != replyEtag)
                    throw IOException("Remote release asset changed during resume")
                val etag = replyEtag
                if (etag != null) UpdateTransfer.etag(context, etag)
                val stream = response.body?.byteStream() ?: throw IOException("Empty APK response")
                var written = offset
                var lastPublish = 0L
                java.io.FileOutputStream(part, append).use { output ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        if (UpdateTransfer.state.value.stage != "downloading")
                            throw CancellationException("Transfer paused/aborted")
                        val n = stream.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        written += n
                        if (snapshot.expectedBytes > 0L && written > snapshot.expectedBytes)
                            throw IOException("Downloaded more bytes than release asset")
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastPublish > 900L) {
                            UpdateTransfer.change(context, UpdateTransfer.state.value.copy(
                                downloadedBytes = written))
                            UpdateTransfer.notify(context, UpdateTransfer.state.value)
                            lastPublish = now
                        }
                    }
                    output.fd.sync()
                }
            }
            val size = part.length()
            if (snapshot.expectedBytes <= 0L || size != snapshot.expectedBytes)
                throw IOException("Incomplete APK download")
            if (UpdateTransfer.restore(context).stage != "downloading")
                throw CancellationException("Transfer is no longer active")
            final.delete()
            if (!part.renameTo(final)) throw IOException("Could not finish APK")
            UpdateTransfer.setReady(context)
            UpdateTransfer.notify(context)
        }
}
