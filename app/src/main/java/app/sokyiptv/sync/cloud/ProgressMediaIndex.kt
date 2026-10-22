package app.sokyiptv.sync.cloud

import app.sokyiptv.core.ServiceLocator
import java.security.MessageDigest
import java.util.Locale

/**
 * Identifies the underlying title, not a local SQLite ID or IPTV login.
 * Exact provider IDs remain preferred. Cross-provider fallback is allowed only
 * for a stable TMDb ID or uniquely identified title/year and episode number.
 * Never transfer plaintext names, media URLs or account credentials.
 */
internal class ProgressMediaIndex private constructor(
    val byLocal: Map<String, Media>,
    val byHash: Map<String, Media>,
) {
    data class Media(
        val key: String,
        val hash: String,
        val logicalHash: String,
        val aliases: Set<String>,
        val durationMillis: Long,
    )

    fun find(kind: String, hash: String): Media? =
        if (hash.matches(Regex("[a-f0-9]{64}"))) byHash["$kind:$hash"] else null

    /** Fail closed if independently supplied strong identifiers disagree. */
    fun findAny(kind: String, hashes: Collection<String>): Media? {
        val matches = hashes.asSequence().mapNotNull { find(kind, it) }.distinctBy { it.key }.toList()
        return matches.singleOrNull()
    }

    companion object {
        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private fun name(value: String?): String? = value?.trim()
            ?.lowercase(Locale.ROOT)
            ?.replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            ?.trim()?.takeIf { it.length >= 3 }

        private fun tmdb(value: String?): String? =
            value?.trim()?.takeIf { it.matches(Regex("[0-9]{2,12}")) }

        private fun movieMetadata(title: String, year: Int?, tmdbId: String?): List<String> {
            val out = mutableListOf<String>()
            tmdb(tmdbId)?.let { out += "tmdb:movie:$it" }
            if (year != null && year in 1888..2100) {
                name(title)?.let { out += "movie:$it:$year" }
            }
            return out
        }

        private fun seriesMetadata(
            title: String?, year: Int?, tmdbId: String?,
        ): List<String> {
            val out = mutableListOf<String>()
            tmdb(tmdbId)?.let { out += "tmdb:series:$it" }
            if (year != null && year in 1888..2100) {
                name(title)?.let { out += "series:$it:$year" }
            }
            return out
        }

        suspend fun build(graph: ServiceLocator.Graph): ProgressMediaIndex {
            val byLocal = linkedMapOf<String, Media>()
            val candidates = linkedMapOf<String, MutableList<Media>>()
            fun register(
                kind: String, id: Long, url: String,
                metadata: List<String>, logicalMetadata: List<String>,
                durationSeconds: Int?,
            ) {
                if (url.isBlank()) return
                val key = "$kind:$id"
                val providerHash = CloudMediaKeys.hash(kind, url)
                val aliases = linkedSetOf(
                    providerHash,
                    CloudMediaKeys.previousHash(kind, url),
                    CloudMediaKeys.legacyHash(kind, url),
                )
                metadata.forEach { aliases += digest("media:$it") }
                // Prefer interoperable verified metadata; never use naked title alone.
                val hash = metadata.firstOrNull()?.let { digest("media:$it") } ?: providerHash
                val logical = logicalMetadata.firstOrNull()?.let { digest("logical:$it") }
                    ?: hash
                val media = Media(
                    key = key, hash = hash, logicalHash = logical,
                    aliases = aliases, durationMillis = (durationSeconds ?: 0).coerceAtLeast(0) * 1000L,
                )
                byLocal[key] = media
                for (alias in aliases) candidates.getOrPut("$kind:$alias") { mutableListOf() } += media
            }

            graph.database.movies().progressRefs().forEach { movie ->
                val names = movieMetadata(movie.name, movie.year, movie.tmdbId)
                register("movie", movie.id, movie.streamUrl, names, names, movie.durationSeconds)
            }
            graph.database.episodes().progressRefs().forEach { ep ->
                val series = seriesMetadata(ep.seriesName, ep.seriesYear, ep.seriesTmdbId)
                val metadata = series.map { "$it:S${ep.season}:E${ep.episodeNumber}" }
                register("ep", ep.id, ep.streamUrl, metadata, series, ep.durationSeconds)
            }
            // Collision policy: no arbitrary FIRST match when two local catalogue rows
            // claim the same ID. A provider with duplicate files requires user action.
            val byHash = candidates.mapNotNull { (key, items) ->
                val distinct = items.distinctBy { it.key }
                if (distinct.size == 1) key to distinct.first() else null
            }.toMap()
            return ProgressMediaIndex(byLocal, byHash)
        }

        fun plausibleDuration(localMillis: Long, remoteMillis: Long): Boolean {
            if (localMillis <= 0L || remoteMillis <= 0L) return true
            val diff = kotlin.math.abs(localMillis - remoteMillis)
            return diff <= maxOf(120_000L, maxOf(localMillis, remoteMillis) / 10)
        }
    }
}
