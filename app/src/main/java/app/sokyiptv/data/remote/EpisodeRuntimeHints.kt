/* GPL-3.0-or-later: small, provider-independent local episode runtime hints. */
package app.sokyiptv.data.remote

import android.content.Context
import app.sokyiptv.data.model.Episode
import app.sokyiptv.core.ServiceLocator
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Episode runtime is NOT watch progress. Resetting history must never erase it.
 * Key by source + provider episode ID (not local Room auto-ID) so a catalogue
 * refresh cannot orphan runtime hints. No source URL or password is stored.
 * All stored values are approximate and are display-only.
 */
internal object EpisodeRuntimeHints {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val revision = MutableStateFlow(0L)
    private const val PREFS = "soky_episode_runtime_hints_v1"
    private const val MIN = 120
    private const val MAX = 43_200

    private fun key(episode: Episode): String {
        val bytes = "${episode.sourceId}:${episode.episodeId}".toByteArray(Charsets.UTF_8)
        return "runtime_" + MessageDigest.getInstance("SHA-256")
            .digest(bytes).take(16).joinToString("") { "%02x".format(it) }
    }

    fun lookupMillis(context: Context, episode: Episode): Long? {
        val seconds = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(key(episode), 0)
        return seconds.takeIf { it in MIN..MAX }?.toLong()?.times(1_000L)
    }

    fun save(context: Context, episode: Episode, seconds: Int) {
        if (seconds !in MIN..MAX || episode.episodeId.isBlank()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key(episode)
        if (prefs.getInt(k, 0) == seconds) return
        prefs.edit().putInt(k, seconds).apply()
        revision.value = revision.value + 1
    }

    /**
     * A non-blocking one-time lookup after leaving the player. No native VLC
     * access from this coroutine: all playback timestamps were captured already.
     * A contradictory length is rejected, but credible near-EOF lengths survive
     * Reset Progress as display-only approximate metadata.
     */
    fun savePlaybackLengthAsync(
        context: Context,
        graph: ServiceLocator.Graph,
        mediaKey: String,
        elapsedMs: Long,
        reportedDurationMs: Long,
    ) {
        val episodeId = mediaKey.removePrefix("ep:")
            .takeIf { mediaKey.startsWith("ep:") }?.toLongOrNull() ?: return
        if (elapsedMs < 30_000L || reportedDurationMs !in 120_000L..43_200_000L) return
        if (elapsedMs > reportedDurationMs + 30_000L) return
        val seconds = (reportedDurationMs / 1_000L).toInt()
        io.launch {
            runCatching {
                val episode = graph.catalogRepository.episode(episodeId) ?: return@launch
                save(context, episode, seconds)
            }
        }
    }
}
