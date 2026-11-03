/*
 * Soky IPTV Pro — durable browse preferences, no DB schema change.
 * GPL-3.0-or-later.
 */
package app.sokyiptv.core

import android.content.Context
import app.sokyiptv.data.model.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

enum class BrowseSort(val title: String) {
    DEFAULT("Default"),
    NAME_ASC("Name A–Z"),
    NAME_DESC("Name Z–A"),
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    RATING("Highest rated"),
    NUMBER_ASC("Channel number ↑"),
    NUMBER_DESC("Channel number ↓"),
    CUSTOM("Custom order");

    companion object {
        fun options(kind: String, favourites: Boolean): List<BrowseSort> {
            val common = listOf(DEFAULT, NAME_ASC, NAME_DESC)
            val extras = if (kind == "live") listOf(NUMBER_ASC, NUMBER_DESC)
                         else listOf(NEWEST, OLDEST, RATING)
            return common + extras + if (favourites) listOf(CUSTOM) else emptyList()
        }
    }
}

/** No provider passwords, URLs or stream content are stored here. */
class BrowseCustomizations(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "soky_browse_customizations_v1", Context.MODE_PRIVATE)
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    private fun sortKey(kind: String, favourites: Boolean): String =
        "sort:" + kind + ":" + if (favourites) "fav" else "all"

    fun sort(kind: String, favourites: Boolean): BrowseSort {
        val raw = prefs.getString(sortKey(kind, favourites), null)
        val selected = BrowseSort.entries.firstOrNull { it.name == raw } ?: BrowseSort.DEFAULT
        return if (selected == BrowseSort.CUSTOM && !favourites) BrowseSort.DEFAULT else selected
    }

    fun setSort(kind: String, favourites: Boolean, sort: BrowseSort) {
        if (sort == BrowseSort.CUSTOM && !favourites) return
        prefs.edit().putString(sortKey(kind, favourites), sort.name).apply()
        _revision.value++
    }

    private fun readList(key: String): List<String> = runCatching {
        val arr = JSONArray(prefs.getString(key, "[]") ?: "[]")
        (0 until arr.length()).map { arr.getString(it) }.distinct()
    }.getOrDefault(emptyList())

    private fun writeList(key: String, values: List<String>) {
        val data = JSONArray()
        values.forEach { data.put(it) }
        prefs.edit().putString(key, data.toString()).apply()
        _revision.value++
    }

    /** Stable over provider updates and channel quality switches. */
    fun orderKey(channel: Channel): String =
        channel.sourceId.toString() + ":" + channel.groupKey.ifBlank { channel.streamId }

    fun favouriteOrder(kind: String): List<String> = readList("fav-order:" + kind)

    /** Existing user order is retained even when switching to another sort mode. */
    fun <T> customSort(kind: String, values: List<T>, stableKey: (T) -> String): List<T> {
        val priority = favouriteOrder(kind).withIndex().associate { it.value to it.index }
        return values.withIndex().sortedWith(
            compareBy<IndexedValue<T>> { priority[stableKey(it.value)] ?: Int.MAX_VALUE }
                .thenBy { it.index }
        ).map { it.value }
    }

    /** Move among the full visible favourites set; preserve temporarily missing keys. */
    fun moveFavourite(kind: String, key: String, delta: Int, visibleKeys: List<String>): Boolean {
        val visible = visibleKeys.distinct()
        val current = visible.indexOf(key)
        if (current < 0 || current + delta !in visible.indices || delta == 0) return false
        val stored = favouriteOrder(kind)
        val all = (stored + visible).distinct()
        val orderedVisible = all.filter { it in visible }.toMutableList()
        val a = orderedVisible.indexOf(key)
        val b = a + delta
        if (a < 0 || b !in orderedVisible.indices) return false
        java.util.Collections.swap(orderedVisible, a, b)
        val iter = orderedVisible.iterator()
        writeList("fav-order:" + kind, all.map { if (it in visible) iter.next() else it })
        return true
    }

    /** Per-profile Live TV history; never saves credentials or resume positions. */
    fun recentLive(profileId: Long): List<String> = readList("live-recent:" + profileId)

    fun recordLive(profileId: Long, channel: Channel) {
        val key = orderKey(channel)
        val previous = recentLive(profileId)
        if (previous.firstOrNull() == key) return
        writeList("live-recent:" + profileId, (listOf(key) + previous.filterNot { it == key }).take(100))
    }
}
