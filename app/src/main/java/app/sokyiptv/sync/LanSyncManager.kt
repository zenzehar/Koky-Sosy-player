package app.sokyiptv.sync

import android.content.Context
import android.util.Log
import app.sokyiptv.core.ServiceLocator
import app.sokyiptv.sync.cloud.GoogleDriveProgressSync
import java.util.concurrent.TimeUnit
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * This lives at Application scope, NOT in SyncScreen's disposable ViewModel.
 * Opt-in sharing keeps a listener while Android keeps the application process
 * alive. Periodic WorkManager jobs attempt paired pulls if Drive is unavailable.
 */
class LanSyncManager private constructor(context: Context) {
    private val app = context.applicationContext
    private val graph = ServiceLocator.get(app)
    private val prefs = app.getSharedPreferences("soky_lan_background", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val server = SyncServer(app, scope)
    val client = SyncClient(graph.httpClient, app)
    private val syncMutex = Mutex()
    private var polling = false

    fun sharingEnabled() = prefs.getBoolean("enabled", false)

    fun startAtLaunch() {
        if (sharingEnabled()) scope.launch { openListener() }
        schedule()
        val startPolling = synchronized(this) {
            if (polling) false else { polling = true; true }
        }
        if (startPolling) {
            scope.launch {
                while (isActive) {
                    runCatching { pullPaired() }.onFailure {
                        Log.w("SokyLAN", "Background paired sync unavailable", it)
                    }
                    delay(60_000L)
                }
            }
        }
    }

    private suspend fun openListener() {
        if (server.state.value is SyncServer.State.Sharing) return
        val bundle = WatchHistorySync.snapshot(graph)
        val keepCode = server.preferFixedCode
        server.start(
            bundleJson = bundle,
            persistent = keepCode,
            newestBundle = {
                // Never send a stale snapshot captured when sharing was switched on.
                runBlocking(Dispatchers.IO) { WatchHistorySync.snapshot(graph) }
            },
            incomingBundle = { incoming -> WatchHistorySync.apply(graph, incoming) },
        )
    }

    fun enableSharing(fixed: Boolean) {
        server.setPreferFixedCode(fixed)
        prefs.edit().putBoolean("enabled", true).apply()
        scope.launch { openListener() }
        schedule()
    }

    fun disableSharing() {
        prefs.edit().putBoolean("enabled", false).apply()
        server.stop()
        schedule()
    }

    internal suspend fun receive(address: String, code: String, deviceId: String): Result<WatchHistorySync.Transfer> {
        return try {
            val outgoing = WatchHistorySync.snapshot(graph)
            client.pull(address, code, deviceId, outgoing)
                .mapCatching { WatchHistorySync.apply(graph, it) }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (e: Exception) {
            // A catalogue/database snapshot failure must appear as a receive error,
            // not crash the ViewModel coroutine and leave the button stuck on Connecting.
            Result.failure(e)
        }
    }

    suspend fun pullPaired(): Int = syncMutex.withLock {
        val ids = client.pairedIds()
        if (ids.isEmpty()) return@withLock 0
        // Drive is the preferred route. Fall back to paired LAN ONLY after the
        // cloud exchange actually fails, not merely because a login token exists.
        val drive = GoogleDriveProgressSync.get(app)
        if (drive.authorized && drive.syncNow()) return@withLock 0
        val found = runCatching { SyncDiscovery.scan() }.getOrDefault(emptyList())
            .associateBy { it.deviceId }
        var received = 0
        for (id in ids) {
            val address = found[id]?.address ?: client.lastAddress(id) ?: continue
            val result = receive(address, "", id)
            received += result.getOrNull()?.updated ?: 0
        }
        received
    }

    private fun schedule() {
        val wm = WorkManager.getInstance(app)
        if (client.pairedIds().isEmpty() && !sharingEnabled()) {
            wm.cancelUniqueWork("soky-paired-lan-v1")
            return
        }
        val request = PeriodicWorkRequestBuilder<PairedLanWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(
            "soky-paired-lan-v1", ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }

    fun pairedDeviceAdded() = schedule()

    companion object {
        @Volatile private var instance: LanSyncManager? = null
        fun get(context: Context): LanSyncManager = instance ?: synchronized(this) {
            instance ?: LanSyncManager(context).also { instance = it }
        }
    }
}

class PairedLanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val manager = LanSyncManager.get(applicationContext)
        manager.startAtLaunch()
        manager.pullPaired()
        Result.success()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        Result.retry()
    }
}
