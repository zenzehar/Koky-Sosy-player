package app.sokyiptv.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvHardwarePlaybackKeysTest {
    @Test fun commonTvHardwarePlayPauseButtonsAreRecognised() {
        assertEquals(TvHardwarePlaybackKeys.Action.TOGGLE,
            TvHardwarePlaybackKeys.actionForInitialPress(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0))
        assertEquals(TvHardwarePlaybackKeys.Action.PLAY,
            TvHardwarePlaybackKeys.actionForInitialPress(KeyEvent.KEYCODE_MEDIA_PLAY, 0))
        assertEquals(TvHardwarePlaybackKeys.Action.PAUSE,
            TvHardwarePlaybackKeys.actionForInitialPress(KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
        assertEquals(TvHardwarePlaybackKeys.Action.TOGGLE,
        assertTrue(TvHardwarePlaybackKeys.isTransportKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    }

    @Test fun repeatsAndUnrelatedRemoteButtonsNeverTogglePlayback() {
        assertNull(TvHardwarePlaybackKeys.actionForInitialPress(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 1))
        assertNull(TvHardwarePlaybackKeys.actionForInitialPress(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 5))
        assertNull(TvHardwarePlaybackKeys.actionForInitialPress(KeyEvent.KEYCODE_DPAD_CENTER, 0))
        assertFalse(TvHardwarePlaybackKeys.isTransportKey(KeyEvent.KEYCODE_DPAD_LEFT))
    }
}
