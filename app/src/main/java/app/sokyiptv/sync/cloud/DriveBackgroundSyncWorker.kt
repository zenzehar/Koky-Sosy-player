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
        val sync = GoogleDriveProgressSync.get(applicationContext)
        if (!sync.authorized) return Result.success()
        return try {
            if (sync.syncNow()) Result.success() else Result.retry()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "soky-drive-progress-periodic-v1"
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DriveBackgroundSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }
        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(NAME)
        }
    }
}
