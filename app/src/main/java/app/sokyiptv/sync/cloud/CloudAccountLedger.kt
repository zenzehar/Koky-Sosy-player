package app.sokyiptv.sync.cloud

/** One Google account, newest saved position per media identity, no profile matching. */
internal object CloudAccountLedger {
    private val aliasPattern = Regex("[a-f0-9]{64}")

    fun merge(now: Long, vararg groups: List<CloudWatchPoint>): List<CloudWatchPoint> {
        val entries = groups.asSequence().flatMap { it.asSequence() }
            .filter { CloudMergeRules.valid(it, now) }
            .map { original ->
                // Never mark Continue Watching finished from understated provider metadata.
                original.copy(
                    profile = "",
                    durationMillis = original.durationMillis.takeIf {
                        it == 0L || it >= original.positionMillis
                    } ?: 0L,
                    aliases = original.aliases.filter { aliasPattern.matches(it) }.distinct().take(6),
                )
            }.toList()
        if (entries.isEmpty()) return emptyList()

        // Union matching canonical hashes AND known aliases; this upgrades v2 entries
        // without duplicating a title when a second TV reports a different URL hash.
        val parent = IntArray(entries.size) { it }
        fun root(i: Int): Int {
            var j = i
            while (parent[j] != j) {
                parent[j] = parent[parent[j]]
                j = parent[j]
            }
            return j
        }
        fun join(a: Int, b: Int) {
            val x = root(a)
            val y = root(b)
            if (x != y) parent[y] = x
        }
        val seen = hashMapOf<String, Int>()
        entries.forEachIndexed { i, p ->
            for (identity in listOf(p.mediaHash) + p.aliases) {
                val key = p.kind + ":" + identity
                seen.putIfAbsent(key, i)?.let { join(i, it) }
            }
        }
        val grouped = linkedMapOf<Int, MutableList<CloudWatchPoint>>()
        entries.indices.forEach { index ->
            grouped.getOrPut(root(index)) { mutableListOf() }.add(entries[index])
        }
        return grouped.values.map { variants ->
            val newest = variants.maxWithOrNull(
                compareBy<CloudWatchPoint> { it.updatedAtMillis }
                    .thenBy { it.positionMillis },
            )!!
            newest.copy(aliases = variants.flatMap { it.aliases }.distinct().take(6))
        }.sortedWith(
            compareByDescending<CloudWatchPoint> { it.updatedAtMillis }
                .thenBy { it.kind }.thenBy { it.mediaHash },
        ).take(10_000)
    }

    /** Read-back verification for concurrent cloud updates; allow alternate media hash matches. */
    fun covers(actual: List<CloudWatchPoint>, expected: List<CloudWatchPoint>): Boolean {
        for (want in expected) {
            val tokens = (listOf(want.mediaHash) + want.aliases).toSet()
            val matching = actual.filter { p ->
                p.kind == want.kind &&
                    (p.mediaHash in tokens || p.aliases.any { it in tokens })
            }
            if (matching.none { got ->
                    got.updatedAtMillis > want.updatedAtMillis ||
                        got.updatedAtMillis == want.updatedAtMillis &&
                        got.positionMillis >= want.positionMillis
                }) return false
        }
        return true
    }
}
