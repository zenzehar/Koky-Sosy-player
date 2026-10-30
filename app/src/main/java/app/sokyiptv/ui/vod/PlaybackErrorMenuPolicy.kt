package app.sokyiptv.ui.vod

/**
 * Stable TV remote navigation for the full-screen VLC playback failure menu.
 * Independent of whether the native video surface, root or a stale seekbar
 * currently reports focus. The menu is modal until Retry, fallback or Back.
 */
internal object PlaybackErrorMenuPolicy {
    const val BUTTONS = 5
    enum class Action { RETRY, MEDIA3, EXTERNAL_VLC, DIAGNOSTICS, BACK }

    fun moved(current: Int, step: Int): Int =
        (current + step).coerceIn(0, BUTTONS - 1)

    fun action(current: Int): Action = when (current.coerceIn(0, BUTTONS - 1)) {
        0 -> Action.RETRY
        1 -> Action.MEDIA3
        2 -> Action.EXTERNAL_VLC
        3 -> Action.DIAGNOSTICS
        else -> Action.BACK
    }

    fun shouldActivate(isKeyDown: Boolean, repeatCount: Int): Boolean =
        isKeyDown && repeatCount == 0
}
