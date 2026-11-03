package app.sokyiptv.update

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.sokyiptv.BuildConfig

/**
 * Android terminates an installed application's process during APK replacement.
 * The old Activity therefore cannot reliably restart itself after the installer returns.
 * React ONLY to successful package replacement for an update started within Soky.
 *
 * The system may prohibit background activity launches on some Android versions.
 * A notification with an explicit Open action remains available where permitted.
 */
class UpdateRelaunchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val requested = UpdateRelaunch.consume(context, BuildConfig.VERSION_NAME)
        // v98's updater predates the durable restart flag. Permit this initial
        // v98→v99 upgrade to restart too, after a genuine package replacement.
        val migratingFromLegacyUpdater = BuildConfig.VERSION_NAME ==
            "0.1.11.99-live-catalog-system-ime-relaunch" && runCatching {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                info.firstInstallTime < info.lastUpdateTime
            }.getOrDefault(false)
        if (!requested && !migratingFromLegacyUpdater) return

        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP)

        val pending = PendingIntent.getActivity(
            context, 7921, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        UpdateRelaunch.showOpenNotification(context, pending)
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val launchedByAlarm = runCatching {
            // Restart only AFTER the PackageManager has completed the APK replacement.
            // A one-shot system-held intent survives the old process being killed.
            checkNotNull(alarm)
            alarm.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1_800L,
                pending,
            )
        }.isSuccess
        if (!launchedByAlarm) {
            // Some devices block non-exact alarms or background activity starts.
            runCatching { context.startActivity(launch) }
        }
    }
}

internal object UpdateRelaunch {
    private const val PREFERENCES = "soky_post_update"
    private const val EXPECTED_VERSION = "restart_expected_version"
    private const val CHANNEL_ID = "soky_successful_update"
    const val NOTIFICATION_ID = 7921

    fun arm(context: Context, version: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(EXPECTED_VERSION, version).apply()
    }

    fun disarm(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().remove(EXPECTED_VERSION).apply()
    }

    /** One shot; a cancelled install or a manual sideload never triggers auto-launch. */
    fun consume(context: Context, installedVersion: String): Boolean {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (prefs.getString(EXPECTED_VERSION, null) != installedVersion) return false
        prefs.edit().remove(EXPECTED_VERSION).apply()
        return true
    }

    fun showOpenNotification(context: Context, pending: PendingIntent) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Soky updates", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(context, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(context)
        manager.notify(NOTIFICATION_ID, builder
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Soky IPTV Pro updated")
            .setContentText("Open the updated application")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build())
    }
}
