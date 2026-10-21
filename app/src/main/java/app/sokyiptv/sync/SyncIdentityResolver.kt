package app.sokyiptv.sync

import app.sokyiptv.core.ServiceLocator
import app.sokyiptv.sync.cloud.CloudMediaKeys

/**
 * Strict cross-device lookup. Only canonical URLs for a known Xtream endpoint
 * are normalised; never match by title or episode name. Ambiguous hashes are
 * removed entirely to prevent unrelated media from being merged.
 */
internal object SyncIdentityResolver {
    suspend fun build(graph: ServiceLocator.Graph): Map<String, Long> {
        val found = hashMapOf<String, Long>()
        val ambiguous = hashSetOf<String>()
        fun add(kind: String, id: Long, url: String) {
            if (url.isBlank()) return
            val hashes = setOf(
                CloudMediaKeys.hash(kind, url),
                CloudMediaKeys.previousHash(kind, url),
                CloudMediaKeys.legacyHash(kind, url),
            )
            for (hash in hashes) {
                val key = "$kind:$hash"
                val previous = found.putIfAbsent(key, id)
                if (previous != null && previous != id) ambiguous += key
            }
        }
        graph.database.movies().cloudRefs().forEach { add("movie", it.id, it.streamUrl) }
        graph.database.episodes().cloudRefs().forEach { add("ep", it.id, it.streamUrl) }
        ambiguous.forEach { found.remove(it) }
        return found
    }
}
