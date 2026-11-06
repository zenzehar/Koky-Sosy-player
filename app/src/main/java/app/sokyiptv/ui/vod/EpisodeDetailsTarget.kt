package app.sokyiptv.ui.vod

/** Only an actual episode may open its parent series detail from Episodes.
 * Movie URLs and malformed keys must not be routed to another title.
 */
internal object EpisodeDetailsTarget {
    fun episodeId(mediaKey: String): Long? = mediaKey
        .takeIf { it.startsWith("ep:") }
        ?.removePrefix("ep:")
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
}
