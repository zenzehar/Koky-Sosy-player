package app.sokyiptv.ui.vod

/** Deterministic 2D TV navigation. No geometry/focus-search heuristics. */
internal enum class NetflixTarget { CLOSE, REWIND, PLAY, FORWARD, TIMELINE, EPISODES, MARKERS, TRACKS, NEXT }
internal enum class NetflixDirection { UP, DOWN, LEFT, RIGHT }

internal object NetflixFocusGraph {
    fun next(current: NetflixTarget, direction: NetflixDirection, isEpisode: Boolean, hasNext: Boolean, markersEnabled: Boolean = false): NetflixTarget {
        val last = if (isEpisode && hasNext) NetflixTarget.NEXT else NetflixTarget.TRACKS
        val first = if (isEpisode) NetflixTarget.EPISODES else NetflixTarget.TRACKS
        return when (current) {
            NetflixTarget.CLOSE -> when (direction) {
                NetflixDirection.DOWN, NetflixDirection.LEFT -> NetflixTarget.PLAY
                else -> NetflixTarget.CLOSE
            }
            NetflixTarget.REWIND -> when (direction) {
                NetflixDirection.RIGHT -> NetflixTarget.PLAY
                NetflixDirection.DOWN -> NetflixTarget.TIMELINE
                NetflixDirection.UP -> NetflixTarget.CLOSE
                else -> NetflixTarget.REWIND
            }
            NetflixTarget.PLAY -> when (direction) {
                NetflixDirection.LEFT -> NetflixTarget.REWIND
                NetflixDirection.RIGHT -> NetflixTarget.FORWARD
                NetflixDirection.UP -> NetflixTarget.CLOSE
                NetflixDirection.DOWN -> NetflixTarget.TIMELINE
            }
            NetflixTarget.FORWARD -> when (direction) {
                NetflixDirection.LEFT -> NetflixTarget.PLAY
                NetflixDirection.DOWN -> NetflixTarget.TIMELINE
                NetflixDirection.UP -> NetflixTarget.CLOSE
                else -> NetflixTarget.FORWARD
            }
            NetflixTarget.TIMELINE -> when (direction) {
                NetflixDirection.UP -> NetflixTarget.PLAY
                NetflixDirection.DOWN -> first
                else -> NetflixTarget.TIMELINE // seek independently owns L/R
            }
            NetflixTarget.EPISODES -> when (direction) {
                NetflixDirection.UP -> NetflixTarget.TIMELINE
                NetflixDirection.RIGHT -> if (markersEnabled) NetflixTarget.MARKERS else NetflixTarget.TRACKS
                else -> NetflixTarget.EPISODES
            }
            NetflixTarget.MARKERS -> when (direction) {
                NetflixDirection.UP -> NetflixTarget.TIMELINE
                NetflixDirection.LEFT -> if (isEpisode) NetflixTarget.EPISODES else NetflixTarget.MARKERS
                NetflixDirection.RIGHT -> NetflixTarget.TRACKS
                else -> NetflixTarget.MARKERS
            }
            NetflixTarget.TRACKS -> when (direction) {
                NetflixDirection.UP -> NetflixTarget.TIMELINE
                NetflixDirection.LEFT -> if (markersEnabled) NetflixTarget.MARKERS else first
                NetflixDirection.RIGHT -> last
                else -> NetflixTarget.TRACKS
            }
            NetflixTarget.NEXT -> when (direction) {
                NetflixDirection.UP -> NetflixTarget.TIMELINE
                NetflixDirection.LEFT -> NetflixTarget.TRACKS
                else -> NetflixTarget.NEXT
            }
        }
    }
}