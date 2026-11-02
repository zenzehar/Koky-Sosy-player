/* GPL-3.0-or-later: pure visibility calculation, independently regression-tested. */
package app.sokyiptv.ui.vod

/** Five full poster tiles constitute a row in every VOD grid. */
internal const val VOD_COLUMNS = 5

internal data class PosterBounds(val index: Int, val top: Int, val bottom: Int)

/**
 * The minimum vertical scroll needed to expose the ENTIRE highlighted poster row.
 * Read layout geometry from a focus callback, never from Compose's composition path.
 * A negative result moves up; a positive result moves down.
 */
internal fun fullPosterRowDelta(
    focusedIndex: Int,
    totalItems: Int,
    columns: Int,
    visible: List<PosterBounds>,
    viewportTop: Int,
    viewportBottom: Int,
): Int {
    if (columns <= 0 || focusedIndex !in 0 until totalItems || viewportBottom <= viewportTop)
        return 0
    val rowStart = (focusedIndex / columns) * columns
    val rowEnd = minOf(rowStart + columns, totalItems)
    val row = visible.filter { it.index in rowStart until rowEnd }
    if (row.isEmpty()) return 0
    val first = row.minOf { it.top }
    val last = row.maxOf { it.bottom }
    // On short TV screens a row may exceed the viewport: prefer its top.
    if (last - first > viewportBottom - viewportTop) return first - viewportTop
    return when {
        first < viewportTop -> first - viewportTop
        last > viewportBottom -> last - viewportBottom
        else -> 0
    }
}
