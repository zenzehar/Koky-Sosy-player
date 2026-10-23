package app.sokyiptv.sync.cloud

/** One Google account => one history. Profile names and device IDs are not record keys. */
internal object CloudAccountLedger {
    fun merge(now: Long, vararg groups: List<CloudWatchPoint>): List<CloudWatchPoint> {
        val latest = linkedMapOf<String, CloudWatchPoint>()
        for (group in groups) for (original in group) {
            if (!CloudMergeRules.valid(original, now)) continue
            // An IPTV provider can under-report running time. A duration
            // smaller than position would incorrectly mark Continue Watching
            // finished in Room; keep progress but mark duration unknown.
            val point = original.copy(
                profile = "",
                durationMillis = original.durationMillis.takeIf {
                    it == 0L || it >= original.positionMillis
                } ?: 0L,
            )
            val key = point.kind + ":" + point.mediaHash
            val prior = latest[key]
            if (prior == null || point.updatedAtMillis > prior.updatedAtMillis ||
                (point.updatedAtMillis == prior.updatedAtMillis &&
                    point.positionMillis > prior.positionMillis)) {
                latest[key] = point
            }
        }
        return latest.values.sortedWith(
            compareByDescending<CloudWatchPoint> { it.updatedAtMillis }
                .thenBy { it.kind }.thenBy { it.mediaHash },
        ).take(10_000)
    }

    /** Verify all changes survived a readback without disregarding newer valid positions. */
    fun covers(actual: List<CloudWatchPoint>, expected: List<CloudWatchPoint>): Boolean {
        val records = actual.associateBy { it.kind + ":" + it.mediaHash }
        return expected.all { want ->
            val got = records[want.kind + ":" + want.mediaHash]
            got != null && (got.updatedAtMillis > want.updatedAtMillis ||
                got.updatedAtMillis == want.updatedAtMillis &&
                got.positionMillis >= want.positionMillis)
        }
    }
}
