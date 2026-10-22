/* GPL-3.0-or-later: hardware transport key mapping shared by both VOD players. */
package app.sokyiptv.player

import android.view.KeyEvent

/** Hardware media keys operate playback independently of which control has TV focus. */
internal object TvHardwarePlaybackKeys {
    enum class Action { TOGGLE, PLAY, PAUSE }

    fun isTransportKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_HEADSETOOK -> true
        else -> false
    }

    /** Consume KeyUp and repeats elsewhere, but trigger just one action on initial KeyDown. */
    fun actionForInitialPress(keyCode: Int, repeatCount: Int): Action? {
        if (repeatCount != 0) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETOOK -> Action.TOGGLE
            KeyEvent.KEYCODE_MEDIA_PLAY -> Action.PLAY
            KeyEvent.KEYCODE_MEDIA_PAUSE -> Action.PAUSE
            else -> null
        }
    }
}
