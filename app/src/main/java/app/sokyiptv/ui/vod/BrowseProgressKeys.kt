package app.sokyiptv.ui.vod

/** Shared logical identity with Continue Watching's grouping rules.
 * One saved movie quality variant or series episode can decorate every copy
 * of the same title in All, genre categories and Favourites.
 */
internal object BrowseProgressKeys {
    fun movie(sourceId: Long, displayTitle: String, year: Int?): String =
        "movie:" + sourceId + ":" + displayTitle.trim().lowercase() + ":" + (year ?: 0)

    fun series(sourceId: Long, providerSeriesId: String): String =
        "series:" + sourceId + ":" + providerSeriesId
}
