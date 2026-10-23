package app.sokyiptv.sync.cloud

/**
 * Account-wide points require a real portable media identity but do not require
 * local profile-name agreement. A Google account is the sync namespace.
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
