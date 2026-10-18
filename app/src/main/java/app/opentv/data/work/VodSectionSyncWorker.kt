/*
 * GPL-3.0-or-later.
 * Manual independent Movies / Series refresh. Small WorkManager job so it survives
 * closing the browsing screen, with no live TV/EPG/network preload side effects.
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
import app.opentv.data.model.SourceKind

class VodSectionSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = ServiceLocator.get(applicationContext)
        val series = inputData.getBoolean(KEY_SERIES, false)
        val requestedSource = inputData.getLong(KEY_SOURCE_ID, ALL_SOURCES)
        val sources = graph.sourceRepository.enabled()
            .filter { it.kind == SourceKind.XTREAM }
            .filter { requestedSource == ALL_SOURCES || it.id == requestedSource }
        if (sources.isEmpty()) return Result.failure()
        if (series && !graph.settings.seriesEnabled.value) return Result.failure()
        if (!series && !graph.settings.moviesEnabled.value) return Result.failure()
        val started = System.currentTimeMillis()
        try {
            sources.forEachIndexed { index, source ->
                val success = if (series) graph.catalogRepository.syncSeriesPhase(source) { progress ->
                    setProgressAsync(Data.Builder().putInt("percent",
                        ((index + progress) / sources.size * 100).toInt()).build())
                } else graph.catalogRepository.syncMoviesPhase(source) { progress ->
                    setProgressAsync(Data.Builder().putInt("percent",
                        ((index + progress) / sources.size * 100).toInt()).build())
                }
                if (!success) {
                    // Keep all prior content and stamps intact for the failed provider.
                    return Result.failure()
                }
                graph.settings.markVodSectionSynced(series, source.id, System.currentTimeMillis())
            }
            Log.i(TAG, "${if (series) "Series" else "Movies"} refreshed in " +
                "${System.currentTimeMillis() - started}ms")
            return Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Independent VOD section update failed", e)
            return Result.failure()
        }
    }

    companion object {
        private const val TAG = "VodSectionSyncWorker"
        private const val KEY_SERIES = "series"
        private const val KEY_SOURCE_ID = "source_id"
        private const val ALL_SOURCES = -1L

        /** Stable name scopes repeat taps to a single job, independently per tab/provider. */
        fun workName(isSeries: Boolean, sourceId: Long?): String =
            "koky-vod-${if (isSeries) "series" else "movies"}-${sourceId ?: "all"}"

        fun refresh(context: Context, isSeries: Boolean, sourceId: Long?) {
            val input = Data.Builder()
                .putBoolean(KEY_SERIES, isSeries)
                .putLong(KEY_SOURCE_ID, sourceId ?: ALL_SOURCES)
                .build()
            val request = OneTimeWorkRequestBuilder<VodSectionSyncWorker>()
                .setInputData(input)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                workName(isSeries, sourceId), ExistingWorkPolicy.KEEP, request,
            )
        }
    }
}
