package app.opentv.data.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.opentv.sync.GoogleDriveCloudSync
import java.util.concurrent.TimeUnit

/** Bounded background flush so an exit does not lose the last minute of history. */
class GoogleHistorySyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val cloud = GoogleDriveCloudSync.get(applicationContext)
        if (!cloud.configured || !cloud.status.value.connected) return Result.success()
        return if (cloud.sync()) Result.success() else Result.retry()
    }

    companion object {
        fun enqueue(context: Context) {
            val task = OneTimeWorkRequestBuilder<GoogleHistorySyncWorker>()
                .setInitialDelay(20, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "koky-google-history-flush", ExistingWorkPolicy.KEEP, task,
            )
        }
    }
}
