/*
 * GPL-3.0-or-later.
 * Independently refresh Live TV channels and the optional guide without fetching
 * Movies or Series. Continues through Home navigation using WorkManager.
 */
package app.opentv.data.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.opentv.core.ServiceLocator
import app.opentv.data.repo.CatalogRepository

class LiveSectionSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = ServiceLocator.get(applicationContext)
        if (!graph.settings.liveEnabled.value) return Result.failure()
        val sources = graph.sourceRepository.enabled()
        if (sources.isEmpty()) return Result.failure()
        val now = System.currentTimeMillis()
        try {
            for ((index, source) in sources.withIndex()) {
                when (val result = graph.catalogRepository.syncLive(source, now) { fraction ->
                    setProgressAsync(
                        Data.Builder().putInt("percent",
                            (((index + fraction.coerceIn(0f, 1f)) / sources.size) * 100f).toInt()
                        ).build()
                    )
                }) {
                    is CatalogRepository.SyncResult.Success -> {
                        // Never stamp failed or unattempted providers. The dashboard displays
                        // the oldest success, or Never updated when any provider lacks one.
                        graph.settings.markLiveSectionSynced(source.id, System.currentTimeMillis())
                    }
                    is CatalogRepository.SyncResult.Failed -> {
                        Log.w(TAG, "Live channel refresh failed for a provider")
                        return Result.failure()
                    }
                }
            }
            // EPG remains best-effort, as in the existing full-catalogue refresh.
            runCatching { graph.epgRepository.syncAll(System.currentTimeMillis()) }
                .onFailure { Log.w(TAG, "Optional guide refresh failed", it) }
            setProgress(Data.Builder().putInt("percent", 100).build())
            return Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Independent Live TV refresh failed", e)
            return Result.failure()
        }
    }

    companion object {
        private const val TAG = "LiveSectionSyncWorker"
        private const val WORK_NAME = "koky-live-all"

        fun workName(): String = WORK_NAME

        /** Backfill/stale-refresh Live TV on launch ONLY when the full catalogue
         * update is not due. Do not race a second sync against SyncWorker.
         * The user's existing 'update on launch' setting also governs this.
         */
        suspend fun refreshAtLaunch(context: Context) {
            val graph = ServiceLocator.get(context.applicationContext)
            val hours = graph.settings.catalogueRefreshHours.value
            if (hours <= 0 || !graph.settings.liveEnabled.value) return
            val sourceIds = graph.sourceRepository.enabled().map { it.id }
            if (sourceIds.isEmpty()) return
            val lastSuccess = graph.settings.lastLiveSectionSynced(sourceIds)
            val now = System.currentTimeMillis()
            if (lastSuccess > 0L && now >= lastSuccess &&
                now - lastSuccess < hours * 3_600_000L) return
            refresh(context)
        }

        fun refresh(context: Context) {
            val request = OneTimeWorkRequestBuilder<LiveSectionSyncWorker>()
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.KEEP, request,
            )
        }
    }
}
