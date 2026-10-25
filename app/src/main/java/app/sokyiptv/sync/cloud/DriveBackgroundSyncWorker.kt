package app.sokyiptv.sync.cloud

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Android only guarantees background periodic work at a minimum of 15 minutes.
 * Foreground pulls use the v0.1.11.39 two-minute cadence, with
 * startup/on-background flush. Watch progress saves locally immediately.
 * This class does not modify any player lifecycle or playback code.
 * Workers never initiate Google sign-in: access is scoped to an authorised account.
 */
class DriveBackgroundSyncWorker(
    context: Context, params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // v53 stability isolation: disable cloud I/O without deleting any Drive data.
        return Result.success()
    }

    companion object {
        private const val NAME = "soky-drive-progress-periodic-v1"
        fun schedule(context: Context) {
            // Cancel jobs installed by old builds; do not enqueue new cloud work.
            cancel(context)
        }
        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(NAME)
        }
    }
}
