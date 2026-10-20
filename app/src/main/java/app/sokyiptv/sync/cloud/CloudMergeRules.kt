package app.sokyiptv.sync.cloud

/**
 * Conservative import policy. Cloud media identifiers must match the exact URL
 * hash and the explicit profile name. Never guess a match by title/episode text.
 */
object CloudMergeRules {
    const val MAX_CLOCK_SKEW_MS: Long = 5 * 60_000L
    fun valid(point: CloudWatchPoint, now: Long): Boolean =
        point.profile.isNotBlank() &&
        point.profile.length <= 120 &&
        point.kind in setOf("movie", "ep") &&
        point.mediaHash.matches(Regex("[a-f0-9]{64}")) &&
        point.updatedAtMillis > 0 &&
        point.updatedAtMillis <= now + MAX_CLOCK_SKEW_MS &&
        point.durationMillis >= 0 &&
        point.positionMillis >= 0 &&
        (point.durationMillis == 0L || point.positionMillis <= point.durationMillis + 120_000L)

    // Same timestamp is ambiguous, so keep local instead of silently overwriting.
    fun shouldImport(localTimestamp: Long?, remoteTimestamp: Long): Boolean =
        localTimestamp == null || remoteTimestamp > localTimestamp
}
