/*
 * This file is part of SokyIPTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.sokyiptv.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Downloads an update APK and hands it to the system installer.
 *
 * The download lands in the app's own cache (no storage permission, cleaned up by the OS
 * under pressure) and is handed to `ACTION_INSTALL_PACKAGE` through a [FileProvider] uri —
 * a raw `file://` uri throws `FileUriExposedException` on modern Android. The system then
 * shows its own install screen; if the user has not yet allowed SokyIPTV to install unknown
 * apps, Android walks them through that first. We never install silently.
 */
class ApkInstaller(private val http: OkHttpClient) {

    /** Progress as a 0f..1f fraction, or -1f when the total size is unknown. */
    fun interface Progress {
        fun onProgress(fraction: Float)
    }

    /**
     * Downloads [url] and launches the installer. Returns true once the installer intent has
     * been fired; throws on a download failure so the caller can show a retry.
     */
    suspend fun downloadAndInstall(
        context: Context,
        url: String,
        expectedBytes: Long,
        targetVersion: String,
        progress: Progress,
    ): Boolean {
        val apk = download(context, url, expectedBytes, progress)
        // Persist across Android killing this process during APK replacement.
        UpdateRelaunch.arm(context, targetVersion)
        try {
            launchInstaller(context, apk)
        } catch (e: Exception) {
            UpdateRelaunch.disarm(context)
            throw e
        }
        return true
    }

    private suspend fun download(
        context: Context,
        url: String,
        expectedBytes: Long,
        progress: Progress,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // A fixed name means each download overwrites the last rather than piling up copies.
        val out = File(dir, "sokyiptv-update.apk")

        val request = Request.Builder().url(url).header("User-Agent", "SokyIPTV").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Download failed: HTTP ${response.code}")
            val bodyStream = response.body?.byteStream() ?: error("Empty download")
            val total = if (expectedBytes > 0) expectedBytes else (response.body?.contentLength() ?: -1L)

            out.outputStream().use { sink ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                var written = 0L
                while (bodyStream.read(buffer).also { read = it } != -1) {
                    sink.write(buffer, 0, read)
                    written += read
                    progress.onProgress(if (total > 0) (written.toFloat() / total) else -1f)
                }
            }
        }
        out
    }

    /**
     * Install only a complete, self-signed APK matching the expected app ID and
     * release version. Never hand an unchecked .part file to PackageInstaller.
     * The actual installation still needs Android's user confirmation.
     */
    fun installDownloaded(context: Context, apk: File, expectedBytes: Long, targetVersion: String) {
        require(apk.isFile && expectedBytes > 0L && apk.length() == expectedBytes) {
            "Update file is missing or incomplete"
        }
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (android.os.Build.VERSION.SDK_INT >= 28)
            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        else android.content.pm.PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw IllegalArgumentException("Update is not a valid APK")
        require(archive.packageName == context.packageName) { "Update package identity differs" }
        require(archive.versionName == targetVersion) { "Update version does not match release" }
        @Suppress("DEPRECATION")
        val current = pm.getPackageInfo(context.packageName, flags)
        fun signatures(info: android.content.pm.PackageInfo): List<String> {
            @Suppress("DEPRECATION")
            val certs = if (android.os.Build.VERSION.SDK_INT >= 28)
                info.signingInfo?.apkContentsSigners?.toList().orEmpty()
            else info.signatures?.toList().orEmpty()
            return certs.map {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(it.toByteArray())
                digest.joinToString("") { b -> "%02x".format(b) }
            }.sorted()
        }
        val existing = signatures(current)
        val candidate = signatures(archive)
        require(existing.isNotEmpty() && candidate == existing) {
            "Update certificate does not match installed app"
        }
        RollbackStore.markUpdateAttempt(context, targetVersion)
        UpdateRelaunch.arm(context, targetVersion)
        try {
            launchInstaller(context, apk)
        } catch (e: Exception) {
            UpdateRelaunch.disarm(context)
            throw e
        }
    }

    private fun launchInstaller(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            // The old process is killed when the new APK replaces this package.
            // Do NOT ask the installer to return a result to that dead Activity:
            // leave Android's normal Install complete / Open action available.
        }
        context.startActivity(intent)
    }
}
