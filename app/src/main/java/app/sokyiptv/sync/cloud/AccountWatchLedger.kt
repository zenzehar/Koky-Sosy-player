package app.sokyiptv.sync.cloud

/**
 * One Google account has one newest-wins point per media identity.
 * A local profile label is NOT part of the cross-device key.
 */
internal object AccountWatchLedger {
    fun merge(now: Long, vararg batches: List<CloudWatchPoint>): List<CloudWatchPoint> {
        val latest = linkedMapOf<String, CloudWatchPoint>()
        for (batch in batches) for (item in batch) {
            if (!CloudMergeRules.valid(item, now)) continue
            // Do not mark a film as finished merely because its provider declared
            // a shorter duration than the actual playable content.
            val point = item.copy(
                profile = "",
                durationMillis = item.durationMillis.takeIf {
                    it == 0L || it >= item.positionMillis
                } ?: 0L,
            )
            val key = point.kind + ":" + point.mediaHash
            val previous = latest[key]
            if (previous == null || point.updatedAtMillis > previous.updatedAtMillis ||
                (point.updatedAtMillis == previous.updatedAtMillis &&
                    point.positionMillis > previous.positionMillis)) latest[key] = point
        }
        return latest.values.sortedWith(
            compareByDescending<CloudWatchPoint> { it.updatedAtMillis }
                .thenBy { it.kind }.thenBy { it.mediaHash },
        ).take(10_000)
    }

    fun covers(actual: List<CloudWatchPoint>, expected: List<CloudWatchPoint>): Boolean {
        val available = actual.associateBy { it.kind + ":" + it.mediaHash }
        return expected.all { point ->
            val remote = available[point.kind + ":" + point.mediaHash]
            remote != null && (remote.updatedAtMillis > point.updatedAtMillis ||
                remote.updatedAtMillis == point.updatedAtMillis &&
                    remote.positionMillis >= point.positionMillis)
        }
    }
}
