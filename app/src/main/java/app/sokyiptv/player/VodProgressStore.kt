/* GPL-3.0-or-later: reliable VOD viewing progress persistence. */
package app.sokyiptv.player

import android.util.Log
import app.sokyiptv.core.ServiceLocator
import app.sokyiptv.data.model.PlaybackPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Persists captured primitive progress independently of the composable/player scope.
 * Exiting a VOD screen cancels its coroutine scope, so no pending write may depend on it.
 * Unknown media length must NOT discard a valid elapsed-time resume position.
 */
internal object VodProgressStore {
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Retain the position even when VLC or a provider cannot report a valid length. */
    fun snapshot(profileId: Long, key: String, positionMs: Long, reportedDurationMs: Long): PlaybackPosition? {
        if (positionMs <= 5_000L || profileId < 0L || key.isBlank()) return null
        val position = positionMs.coerceAtLeast(0L)
        val duration = reportedDurationMs.takeIf { it > 0L && it >= position } ?: 0L
        return PlaybackPosition(
            profileId = profileId,
            mediaKey = key,
            positionMillis = position,
            durationMillis = duration,
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    suspend fun persist(graph: ServiceLocator.Graph, progress: PlaybackPosition?) {
        if (progress == null) return
        withContext(Dispatchers.IO) {
            // Transactional newest-only merge prevents delayed writes and obsolete
            // tick snapshots from overwriting a newer seek or exit position.
            val changed = graph.playbackPositions.mergeIfNewer(
                progress.profileId, progress.mediaKey,
                progress.positionMillis, progress.durationMillis, progress.updatedAtMillis,
            )
            // Event-driven Drive sync is independent of the player's lifecycle.
            if (changed > 0) {
                app.sokyiptv.sync.cloud.GoogleDriveProgressSync.signalLocalProgressChanged()
            }
        }
    }

    fun enqueue(graph: ServiceLocator.Graph, progress: PlaybackPosition?) {
        if (progress == null) return
        persistenceScope.launch {
            try {
                persist(graph, progress)
            } catch (e: Exception) {
                // No URLs, provider secrets or stream identifiers in diagnostics.
                Log.w("SokyProgress", "Could not persist resume position", e)
            }
        }
    }
}
