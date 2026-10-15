/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.opentv.core.ServiceLocator
import app.opentv.core.AppSettings
import app.opentv.core.StatusBus
import app.opentv.data.repo.CatalogRepository
import java.util.concurrent.TimeUnit

/**
 * Background refresh of the catalogue and the guide.
 *
 * Runs on WorkManager rather than a foreground timer so it survives the app being killed,
 * which on a TV box happens constantly. Failures return [Result.retry] with WorkManager's
 * exponential backoff — the app never hammers a provider that is already struggling.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = ServiceLocator.get(applicationContext)
        val now = System.currentTimeMillis()
        val sources = graph.sourceRepository.enabled()
        if (sources.isEmpty()) return Result.success()

        CatalogueGate.started()
        StatusBus.set("Updating Live TV…", 0f)
        try {
            for (source in sources) {
                when (val result = graph.catalogRepository.syncLive(source, now)) {
                    is CatalogRepository.SyncResult.Success ->
                        Log.i(TAG, "Live: ${result.channelCount} channels")
                    is CatalogRepository.SyncResult.Failed ->
                        throw IllegalStateException("Live TV: ${result.reason}")
                }
            }
            CatalogueGate.liveDone()
            StatusBus.set("Updating Movies…", 1f / 3f)
            sources.forEachIndexed { index, source ->
                if (!graph.catalogRepository.syncMoviesPhase(source) { progress ->
                    CatalogueGate.moviesImportProgress((index + progress) / sources.size)
                }) {
                    throw IllegalStateException("Movie catalogue unavailable; previous library retained")
                }
            }
            CatalogueGate.moviesDone()
            StatusBus.set("Updating Series…", 2f / 3f)
            sources.forEachIndexed { index, source ->
                if (!graph.catalogRepository.syncSeriesPhase(source) { progress ->
                    CatalogueGate.seriesImportProgress((index + progress) / sources.size)
                }) {
                    throw IllegalStateException("Series catalogue unavailable; previous library retained")
                }
            }
            // Catalogue is complete. Guide refresh is ancillary and cannot prevent
            // access to an otherwise valid finished VOD library.
            graph.settings.catalogueSyncedAtMillis = System.currentTimeMillis()
            graph.settings.vodSyncedAtMillis = now
            CatalogueGate.done()
            StatusBus.set(null)
            runCatching { graph.epgRepository.syncAll(now) }
                .onFailure { Log.w(TAG, "Optional EPG update failed", it) }
            runCatching { graph.recordingEngine.rescanSeriesRules() }
            return Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Update failed", e)
            CatalogueGate.failed(e.message ?: "Update failed; retry")
            StatusBus.set(null)
            return Result.failure()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val WORK_NAME = "opentv-periodic-sync"
        private const val ONE_SHOT_WORK_NAME = "opentv-manual-sync"

        /**
         * Kick a single catalogue + guide refresh now, off the UI. Runs the same [doWork] as the
         * periodic job — so it honours the content-type toggles — but as one-time work, which means
         * it survives the user navigating away from the screen that triggered it (unlike a refresh
         * tied to a screen's ViewModel scope). REPLACE so repeated taps coalesce into one run.
         */
        fun refreshNow(context: Context) {
            CatalogueGate.pending()
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        /** Network sync only at startup when the selected interval has elapsed. */
        fun refreshAtLaunch(context: Context) {
            val settings = AppSettings.get(context)
            val hours = settings.catalogueRefreshHours.value
            if (hours == 0) return
            val now = System.currentTimeMillis()
            if (!CatalogueGate.due(settings, now)) return
            CatalogueGate.pending()
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "koky-launch-catalogue", ExistingWorkPolicy.KEEP, request,
            )
        }

        fun schedule(context: Context) {
            // Cancel the previous version's unattended six-hour periodic downloader.
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
