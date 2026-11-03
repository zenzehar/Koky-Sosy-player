package app.sokyiptv.update

import android.Manifest
import android.app.AlarmManager
import android.app.ActivityOptions
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
import android.util.Log
import androidx.core.content.ContextCompat
import app.sokyiptv.BuildConfig

/**
 * Best-effort ONE-SHOT restart after a successful self-update.
 *
 * The app's previous process is killed during package replacement. Android 10+
 * may prevent an app in the background from starting an Activity even if an alarm
 * fires. Alarm scheduling is NOT evidence of successful foreground launch.
 *
 * Never retry in a loop, never crash the receiver, and always retain a user-open
 * notification when the OS allows notifications. No credential-bearing data
 * is logged. No broadcast from another app can trigger a restart.
 */
class UpdateRelaunchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // A vendor-specific PendingIntent/AlarmManager failure must NOT crash Soky.
        runCatching { handleReplacement(context.applicationContext) }
            .onFailure { Log.w(TAG, "Post-update launch request unavailable: " + it.javaClass.simpleName) }
    }

    private fun handleReplacement(context: Context) {
        // This marker is written only by Soky's own updater (never by a sideload).
        // v99 arms it before launching the system installer.
        if (!UpdateRelaunch.consume(context, BuildConfig.VERSION_NAME)) return

        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        // Do not revive stale activities or back stacks from the old installation.
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        val options = if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions.makeBasic().apply {
                setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }.toBundle()
        } else null
        val pending = PendingIntent.getActivity(
            context,
            UpdateRelaunch.NOTIFICATION_ID,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            options,
        )

        // Offer a user-initiated way to open the updated app. Android may
        // disallow this notification if the user has disabled notifications.
        runCatching { UpdateRelaunch.showOpenNotification(context, pending) }
            .onFailure { Log.w(TAG, "Post-update notification unavailable: " + it.javaClass.simpleName) }

        // A single delayed attempt, NOT a startup loop. Delaying also gives
        // PackageManager / vendor TV launchers time to settle after replacement.
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val scheduled = runCatching {
            checkNotNull(alarm)
            alarm.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + AUTO_START_DELAY_MS,
                pending,
            )
        }.isSuccess
        if (!scheduled) {
            Log.w(TAG, "Post-update relaunch could not be scheduled")
            // Android 10+ prohibits general background starts. Never use an
            // uncontrolled direct startActivity fallback on those systems.
            if (Build.VERSION.SDK_INT < 29) {
                runCatching { context.startActivity(launch) }
                    .onFailure { Log.w(TAG, "Legacy relaunch unavailable") }
            }
        }
    }

    private companion object {
        const val TAG = "SokyUpdateRelaunch"
        const val AUTO_START_DELAY_MS = 6_000L
    }
}

internal object UpdateRelaunch {
    private const val PREFERENCES = "soky_post_update"
    private const val EXPECTED_VERSION = "restart_expected_version"
    private const val CHANNEL_ID = "soky_successful_update"
    const val NOTIFICATION_ID = 7921

    /** Commit synchronously: installer replacement can kill our process immediately. */
    fun arm(context: Context, version: String) {
        check(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(EXPECTED_VERSION, version).commit()
        ) { "Could not save requested update version" }
    }

    fun disarm(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().remove(EXPECTED_VERSION).apply()
    }

    /** Consume once before launch: if new startup crashes, there is no relaunch loop. */
    fun consume(context: Context, installedVersion: String): Boolean {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (prefs.getString(EXPECTED_VERSION, null) != installedVersion) return false
        return prefs.edit().remove(EXPECTED_VERSION).commit()
    }

    fun showOpenNotification(context: Context, pending: PendingIntent) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Soky updates", NotificationManager.IMPORTANCE_DEFAULT)
            )
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
