package app.sokyiptv.update

import android.content.Context
import android.content.pm.PackageManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import app.sokyiptv.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * A 24-hour offer, anchored to Android's actual package replacement timestamp (not
 * to the first time the new version launches). No prior APK or user data is copied.
 * A remote, owner-signed forward-version rollback APK is required before showing Install.
 */
internal object RollbackStore {
    const val WINDOW_MS = 24L * 60L * 60L * 1000L
    private const val PREFS = "soky_rollback"
    private const val LEGACY_PREFS = "sokyiptv"
    private const val CURRENT = "observed_version"
    private const val CURRENT_INSTALLED_AT = "observed_package_updated_at"
    private const val PENDING_FROM = "pending_from"
    private const val PENDING_TO = "pending_to"
    private const val SOURCE = "window_source"
    private const val TARGET = "window_target"
    private const val EXPIRES = "window_expires"
    private const val WORK = "soky_rollback_24h_expiration"

    data class Window(val sourceVersion: String, val previousVersion: String, val expiresAtMs: Long)

    fun markUpdateAttempt(context: Context, target: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(PENDING_FROM, BuildConfig.VERSION_NAME)
            .putString(PENDING_TO, target)
            .apply()
    }

    /**
     * Run once on start. For upgrades from pre-rollback v91, use the last update-check
     * version as a conservative legacy hint. Do NOT guess a prior version from v92's
     * sequence number: users can skip releases, and installing the wrong old code is unsafe.
     */
    fun reconcile(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = BuildConfig.VERSION_NAME
        val packageInfo = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull() ?: return
        val changed = prefs.getString(CURRENT, null) != current ||
            prefs.getLong(CURRENT_INSTALLED_AT, -1L) != packageInfo.lastUpdateTime
        if (changed) {
            val fromPending = prefs.getString(PENDING_FROM, null)
                ?.takeIf { prefs.getString(PENDING_TO, null) == current }
            val lastObserved = prefs.getString(CURRENT, null)
            val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                .getString("last_update_check_version", null)
            val previous = fromPending ?: lastObserved ?: legacy
            val expiry = safeExpiry(packageInfo.lastUpdateTime)
            val edit = prefs.edit()
                .putString(CURRENT, current)
                .putLong(CURRENT_INSTALLED_AT, packageInfo.lastUpdateTime)
                .remove(PENDING_FROM)
                .remove(PENDING_TO)
                .remove(SOURCE)
                .remove(TARGET)
                .remove(EXPIRES)
            if (isEligibleUpgrade(current, previous, packageInfo.firstInstallTime,
                    packageInfo.lastUpdateTime, System.currentTimeMillis())) {
                edit.putString(SOURCE, current)
                    .putString(TARGET, previous)
                    .putLong(EXPIRES, expiry)
            }
            edit.apply()
        }
        prune(context)
        window(context)?.let { scheduleExpiration(context, it.expiresAtMs) }
    }

    fun window(context: Context, now: Long = System.currentTimeMillis()): Window? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val source = p.getString(SOURCE, null) ?: return null
        val target = p.getString(TARGET, null) ?: return null
        val expiry = p.getLong(EXPIRES, 0L)
        if (source != BuildConfig.VERSION_NAME || expiry <= now ||
            !UpdateChecker.isNewer(source, target)) return null
        return Window(source, target, expiry)
    }

    fun prune(context: Context, now: Long = System.currentTimeMillis()) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.contains(EXPIRES) && p.getLong(EXPIRES, 0L) <= now) {
            p.edit().remove(SOURCE).remove(TARGET).remove(EXPIRES).apply()
        }
        // Remove obsolete updater cache from earlier releases; rollback never stores an APK.
        val updates = context.cacheDir.resolve("updates")
        if (updates.isDirectory) {
            updates.listFiles()?.filter { it.name.startsWith("soky-rollback-") }
                ?.forEach { it.delete() }
        }
    }

    private fun scheduleExpiration(context: Context, expiresAt: Long) {
        val delayMs = (expiresAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<ExpireRollbackWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
    }

    internal fun safeExpiry(installTime: Long): Long =
        if (installTime > Long.MAX_VALUE - WINDOW_MS) Long.MAX_VALUE else installTime + WINDOW_MS

    internal fun isEligibleUpgrade(
        current: String, previous: String?, firstInstall: Long, lastUpdate: Long, now: Long,
    ): Boolean =
        !previous.isNullOrBlank() && firstInstall < lastUpdate &&
            lastUpdate <= now && now < safeExpiry(lastUpdate) &&
            UpdateChecker.isNewer(current, previous)
}

internal class ExpireRollbackWorker(context: Context, parameters: WorkerParameters) :
    Worker(context, parameters) {
    override fun doWork(): Result {
        RollbackStore.prune(applicationContext)
        return Result.success()
    }
}
