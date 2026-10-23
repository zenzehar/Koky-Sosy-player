package app.sokyiptv.sync.cloud

/**
 * Account-wide cloud ledger policy: no profile matching. Media hashes protect
 * against assigning a saved position to an unrelated title. Timestamps are newest-wins.
 */
object CloudMergeRules {
    const val MAX_CLOCK_SKEW_MS: Long = 5 * 60_000L
    fun valid(point: CloudWatchPoint, now: Long): Boolean =
        point.profile.length <= 120 &&
        point.kind in setOf("movie", "ep") &&
        point.mediaHash.matches(Regex("[a-f0-9]{64}")) &&
        point.updatedAtMillis > 0 &&
        point.updatedAtMillis <= now + MAX_CLOCK_SKEW_MS &&
        point.durationMillis >= 0 &&
        point.positionMillis >= 0

    // Same timestamp is ambiguous, so keep local instead of silently overwriting.
    fun shouldImport(localTimestamp: Long?, remoteTimestamp: Long): Boolean =
        localTimestamp == null || remoteTimestamp > localTimestamp
}
