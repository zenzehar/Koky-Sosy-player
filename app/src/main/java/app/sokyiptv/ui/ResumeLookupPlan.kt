/* GPL-3.0-or-later: bounded local watch-history lookup plan. */
package app.sokyiptv.ui

/**
 * Prepare one bounded query per media type instead of querying Room separately
 * for every Continue Watching card. The original order remains in the saved
 * positions; these lists are only a set of database fetch keys.
 */
internal data class ResumeLookupPlan(val movieIds: List<Long>, val episodeIds: List<Long>) {
    companion object {
        fun parse(mediaKey: String): Pair<String, Long>? {
            val sep = mediaKey.indexOf(':')
            if (sep <= 0) return null
            val kind = mediaKey.substring(0, sep)
            if (kind != "movie" && kind != "ep") return null
            val id = mediaKey.substring(sep + 1).toLongOrNull()?.takeIf { it > 0L } ?: return null
            return kind to id
        }

        fun from(mediaKeys: Iterable<String>): ResumeLookupPlan {
            val movies = LinkedHashSet<Long>()
            val episodes = LinkedHashSet<Long>()
            for (key in mediaKeys) {
                val parsed = parse(key) ?: continue
                if (parsed.first == "movie") movies += parsed.second
                else episodes += parsed.second
            }
            // observeRecent currently caps at 300: safely beneath Android's
            // lowest supported SQLite IN bind-parameter limit (999).
            return ResumeLookupPlan(movies.toList(), episodes.toList())
        }
    }
}
