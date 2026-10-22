package app.sokyiptv.sync

import app.sokyiptv.core.ServiceLocator
import app.sokyiptv.sync.cloud.CloudMergeRules
import app.sokyiptv.sync.cloud.ProgressMediaIndex
import kotlinx.serialization.json.Json

/** App-owned, profile-agnostic export/import of the CURRENT active viewing profile. */
internal object WatchHistorySync {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun snapshot(graph: ServiceLocator.Graph): String {
        val index = ProgressMediaIndex.build(graph)
        val active = graph.settings.activeProfileId.value
        val newest = linkedMapOf<String, SyncPosition>()
        for (pos in graph.playbackPositions.forProfile(active).sortedByDescending { it.updatedAtMillis }) {
            val media = index.byLocal[pos.mediaKey] ?: continue
            newest.putIfAbsent(media.logicalHash, SyncPosition(
                profile = "active", streamUrl = "", kind = media.key.substringBefore(':'),
                positionMillis = pos.positionMillis, durationMillis = pos.durationMillis,
                updatedAtMillis = pos.updatedAtMillis, identityHash = media.hash,
                logicalHash = media.logicalHash, identityAliases = media.aliases.toList(),
            ))
        }
        return json.encodeToString(SyncBundle.serializer(), SyncBundle(
            generatedAtMillis = System.currentTimeMillis(), positions = newest.values.toList(),
        ))
    }

    data class Transfer(val received: Int, val matched: Int, val updated: Int)

    suspend fun apply(graph: ServiceLocator.Graph, bundle: SyncBundle): Transfer {
        val now = System.currentTimeMillis()
        val index = ProgressMediaIndex.build(graph)
        val latest = linkedMapOf<String, Pair<SyncPosition, ProgressMediaIndex.Media>>()
        var matched = 0
        for (point in bundle.positions) {
            if (point.kind !in setOf("movie", "ep")) continue
            if (point.updatedAtMillis <= 0L || point.updatedAtMillis > now + CloudMergeRules.MAX_CLOCK_SKEW_MS ||
                point.positionMillis < 0 || point.durationMillis < 0 ||
                (point.durationMillis > 0 && point.positionMillis > point.durationMillis + 120_000L)) continue
            val media = index.findAny(point.kind, listOf(point.identityHash) + point.identityAliases) ?: continue
            if (!ProgressMediaIndex.plausibleDuration(media.durationMillis, point.durationMillis)) continue
            matched++
            val existing = latest[media.logicalHash]
            if (existing == null || point.updatedAtMillis > existing.first.updatedAtMillis) {
                latest[media.logicalHash] = point to media
            }
        }
        val profileId = graph.settings.activeProfileId.value
        val local = graph.playbackPositions.forProfile(profileId)
            .mapNotNull { p -> index.byLocal[p.mediaKey]?.let { it.logicalHash to p.updatedAtMillis } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.maxOrNull() ?: 0L }
        var updated = 0
        for ((logical, pair) in latest) {
            val (point, media) = pair
            if (point.updatedAtMillis <= (local[logical] ?: 0L)) continue
            updated += graph.playbackPositions.mergeIfNewer(
                profileId, media.key, point.positionMillis, point.durationMillis, point.updatedAtMillis,
            )
        }
        return Transfer(bundle.positions.size, matched, updated)
    }
}
