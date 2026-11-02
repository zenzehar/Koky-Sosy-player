package app.sokyiptv.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Streams directly into Android's own staged install session. There is deliberately no
 * rollback APK in app-private cache, external storage, or a persistent backup directory.
 * Android validates the package name, APK versionCode and permanent signing certificate.
 *
 * Normal Android does NOT allow installing an older versionCode. The rollback companion
 * contains PREVIOUS code but a HIGHER versionCode than the currently installed update.
 */
internal class RollbackInstaller(private val http: OkHttpClient) {
    suspend fun install(
        context: Context,
        candidate: RollbackRepository.Asset,
        expiresAtMs: Long,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        check(System.currentTimeMillis() < expiresAtMs) { "The 24-hour rollback window expired." }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        var committed = false
        try {
            installer.openSession(id).use { session ->
                val request = Request.Builder()
                    .url(candidate.url)
                    .header("User-Agent", "SokyIPTV")
                    .build()
                http.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Rollback download failed (HTTP " + response.code + ")." }
                    val input = response.body?.byteStream() ?: error("Rollback file is empty.")
                    session.openWrite("base.apk", 0L, candidate.bytes).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var received = 0L
                        var lastReported = -1
                        input.use { source ->
                            while (true) {
                                val read = source.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                received += read
                                val percent = (received * 100L / candidate.bytes).toInt().coerceIn(0, 100)
                                if (percent != lastReported && percent % 2 == 0) {
                                    lastReported = percent
                                    withContext(Dispatchers.Main) { onProgress(percent / 100f) }
                                }
                            }
                        }
                        check(received == candidate.bytes) { "Incomplete rollback download." }
                        session.fsync(output)
                    }
                }
                check(System.currentTimeMillis() < expiresAtMs) {
                    "The 24-hour rollback window expired before installation."
                }
                val callback = Intent(context, RollbackInstallReceiver::class.java)
                    .setAction(RollbackInstallReceiver.ACTION)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val sender = PendingIntent.getBroadcast(context, id, callback, flags)
                session.commit(sender.intentSender)
                committed = true
            }
        } finally {
            if (!committed) runCatching { installer.abandonSession(id) }
        }
    }
}

/** Dispatches Android's required installation confirmation, and reports failures safely. */
internal class RollbackInstallReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "app.sokyiptv.update.ROLLBACK_INSTALL_STATUS"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                if (confirm == null) {
                    RollbackUi.error.value = "Android did not supply an installation confirmation."
                } else {
                    runCatching {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(confirm)
                    }.onFailure {
                        RollbackUi.error.value = "Could not open Android's installer confirmation."
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // The old code may already be running. No local APK needs deletion.
                RollbackUi.error.value = null
            }
            else -> {
                RollbackUi.error.value =
                    "Android rejected the rollback. Check package identity and signing compatibility."
            }
        }
    }
}
